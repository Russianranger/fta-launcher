package com.russianranger.fta.client

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.Locale

data class ClientEntry(val path: String, val identity: String, val bytes: Long, val modified: Long, val directory: Boolean)
interface ClientSource {
    val identity: String
    fun inventory(): List<ClientEntry>
    fun open(entry: ClientEntry): InputStream
}
class ClientImportFailure(val reason: String) : java.io.IOException(reason)
internal fun demand(ok: Boolean, reason: String) { if (!ok) throw ClientImportFailure(reason) }
internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

/** Private source identities/paths stay here; diagnostic exports contain only totals and hashes. */
class ClientPlan(val identity: String, entries: List<ClientEntry>) {
    val entries = entries.sortedBy { it.path }
    val files get() = entries.filter { !it.directory }
    val bytes: Long
    val encoded: ByteArray
    val sha256: String
    init {
        demand(identity.length in 1..8192 && entries.size in 1..100000, "invalid-plan")
        val inventory = ClientInventory()
        val identities = HashSet<String>()
        var identityCharacters = identity.length.toLong()
        var total = 0L
        val byName = this.entries.associateBy { it.path.lowercase(Locale.ROOT) }
        for (entry in this.entries) {
            demand(entry.identity.length in 1..8192 && identities.add(entry.identity), "duplicate-identity")
            identityCharacters += entry.identity.length
            demand(identityCharacters <= 4 * 1024 * 1024, "inventory-limit")
            demand(entry.bytes >= 0 && entry.modified >= 0, "unknown-size")
            try { inventory.add(entry.path, entry.directory, entry.bytes) }
            catch (_: IllegalArgumentException) { throw ClientImportFailure("invalid-path") }
            val parent = entry.path.substringBeforeLast('/', "")
            demand(parent.isEmpty() || byName[parent.lowercase(Locale.ROOT)]?.let { it.directory && it.path == parent } == true, "invalid-parent")
            if (!entry.directory) {
                demand(entry.bytes <= MAX_BYTES - total, "inventory-limit"); total += entry.bytes
            }
        }
        demand(inventory.presentRoles.containsAll(listOf("wow", "proxy", "warcraftxl", "warcraftxl-config")) && inventory.mpqFiles > 0, "client-files-missing")
        bytes = total
        encoded = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1); out.writeUTF(identity); out.writeInt(this.entries.size)
                for (entry in this.entries) {
                    out.writeUTF(entry.path); out.writeUTF(entry.identity); out.writeLong(entry.bytes)
                    out.writeLong(entry.modified); out.writeBoolean(entry.directory)
                }
            }
            buffer.toByteArray()
        }
        demand(encoded.size <= 16 * 1024 * 1024, "inventory-limit")
        sha256 = hex(digest(encoded))
    }
    companion object {
        const val MAX_BYTES = 1024L * 1024 * 1024 * 1024
        fun decode(encoded: ByteArray): ClientPlan = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            demand(encoded.size <= 16 * 1024 * 1024 && input.readInt() == 1, "invalid-checkpoint")
            val identity = input.readUTF(); val count = input.readInt()
            demand(count in 1..100000, "invalid-checkpoint")
            val entries = List(count) { ClientEntry(input.readUTF(), input.readUTF(), input.readLong(), input.readLong(), input.readBoolean()) }
            demand(input.read() == -1, "invalid-checkpoint")
            ClientPlan(identity, entries).also { demand(it.encoded.contentEquals(encoded), "invalid-checkpoint") }
        }
    }
}
