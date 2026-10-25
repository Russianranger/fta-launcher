package com.russianranger.fta.client

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Documents
import com.russianranger.fta.runtime.GameDataCache
import com.russianranger.fta.runtime.GameDataCacheNative
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/** Read-only persisted SAF grant. Never opens a source descriptor for writing. */
class ClientTreeSource(private val context: Context, private val tree: Uri, private val active: () -> Unit = {}) : ClientSource {
    private val signal = CancellationSignal()
    private val current = AtomicReference<ParcelFileDescriptor?>()
    override val identity = tree.toString()
    val cache = GameDataCache()
    fun cancel() { signal.cancel(); runCatching { current.getAndSet(null)?.close() } }
    fun preflight() {
        val plan = ClientPlan(identity, inventory())
        for (name in listOf("wow.exe", "d3d9.dll", "warcraftxl.dll")) {
            active(); signal.throwIfCanceled()
            val entry = plan.files.singleOrNull { it.path.equals(name, true) } ?: throw ClientImportFailure("client-files-missing")
            val descriptor = context.contentResolver.openFileDescriptor(Documents.buildDocumentUriUsingTree(tree, entry.identity), "r", signal)
                ?: throw ClientImportFailure("provider-error")
            current.set(descriptor)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val channel = input.channel
                    demand(channel.size() == entry.bytes, "source-changed")
                    val info = PeMetadata.inspect(entry.bytes) { offset, count ->
                        val bytes = ByteArray(count); val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) { active(); signal.throwIfCanceled(); demand(channel.read(buffer, offset + buffer.position()) > 0, "client-build-mismatch") }
                        bytes
                    }
                    demand(if (name == "wow.exe") ClientInventory.matchesWow(info) else info.architecture == "x86" && info.dll, "client-build-mismatch")
                }
            } finally { current.compareAndSet(descriptor, null); runCatching { descriptor.close() } }
        }
    }
    override fun inventory(): List<ClientEntry> {
        demand(Documents.isTreeUri(tree), "invalid-source")
        val entries = ArrayList<ClientEntry>(); val seen = HashSet<String>(); val bounds = ClientInventory()
        var idCharacters = 0L
        fun visit(id: String, parent: String, depth: Int) {
            active(); signal.throwIfCanceled(); demand(depth < 32 && seen.add(id), "invalid-source")
            val columns = arrayOf(Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME,
                Documents.Document.COLUMN_MIME_TYPE, Documents.Document.COLUMN_SIZE, Documents.Document.COLUMN_LAST_MODIFIED)
            val uri = Documents.buildChildDocumentsUriUsingTree(tree, id)
            val cursor = context.contentResolver.query(uri, columns, null, null, null, signal) ?: throw ClientImportFailure("provider-error")
            cursor.use {
                while (it.moveToNext()) {
                    active(); signal.throwIfCanceled()
                    val child = it.getString(0) ?: throw ClientImportFailure("invalid-source")
                    val name = it.getString(1) ?: throw ClientImportFailure("invalid-source")
                    demand(name.isNotEmpty() && '/' !in name && '\\' !in name && child.length in 1..8192, "invalid-path")
                    idCharacters += child.length; demand(idCharacters <= 4 * 1024 * 1024, "inventory-limit")
                    val directory = it.getString(2) == Documents.Document.MIME_TYPE_DIR
                    val bytes = if (directory) 0 else if (it.isNull(3)) -1 else it.getLong(3)
                    demand(bytes >= 0, "unknown-size")
                    val path = if (parent.isEmpty()) name else "$parent/$name"
                    try { bounds.add(path, directory, bytes) } catch (_: IllegalArgumentException) { throw ClientImportFailure("invalid-path") }
                    entries += ClientEntry(path, child, bytes, if (it.isNull(4)) 0 else it.getLong(4).coerceAtLeast(0), directory)
                    if (directory) visit(child, path, depth + 1)
                }
            }
        }
        visit(Documents.getTreeDocumentId(tree), "", 0)
        return entries
    }
    override fun open(entry: ClientEntry): InputStream {
        active(); signal.throwIfCanceled()
        val descriptor = context.contentResolver.openFileDescriptor(Documents.buildDocumentUriUsingTree(tree, entry.identity), "r", signal)
            ?: throw ClientImportFailure("provider-error")
        current.set(descriptor)
        val fd = descriptor.fd
        val stream = cache.input(ParcelFileDescriptor.AutoCloseInputStream(descriptor), ClientStore.IO_WINDOW.toLong()) { offset, length ->
            GameDataCacheNative.releaseDescriptor(fd, offset, length)
        }
        return object : FilterInputStream(stream) {
            override fun close() { try { super.close() } finally { current.compareAndSet(descriptor, null) } }
        }
    }
}
