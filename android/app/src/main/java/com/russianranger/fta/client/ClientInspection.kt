package com.russianranger.fta.client

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Documents
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** User-selected SAF tree. Only read grants, queries and "r" descriptors are used. */
class ClientInspection(private val context: Context) {
    private val signal = CancellationSignal()
    private val opened = AtomicReference<ParcelFileDescriptor?>()
    fun cancel() { signal.cancel(); runCatching { opened.getAndSet(null)?.close() } }

    fun run(tree: Uri, progress: (Int) -> Unit): String {
        val timer = Executors.newSingleThreadScheduledExecutor()
        timer.schedule({ cancel() }, 180, TimeUnit.SECONDS)
        val report = JSONObject().put("state", "running").put("startedAtEpochMs", System.currentTimeMillis())
        val inventory = ClientInventory()
        val components = JSONArray()
        var totalHashed = 0L
        var result = "failed"
        try {
            save(context, report)
            require(Documents.isTreeUri(tree))
            val seenIds = HashSet<String>()
            var idCharacters = 0L
            fun binary(id: String, role: String, expectedSize: Long?): JSONObject {
                val item = JSONObject().put("role", role).put("state", "unreadable")
                try {
                    signal.throwIfCanceled()
                    val uri = Documents.buildDocumentUriUsingTree(tree, id)
                    val descriptor = context.contentResolver.openFileDescriptor(uri, "r", signal) ?: error("Unavailable")
                    opened.set(descriptor)
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        val channel = input.channel
                        val size = channel.size() // seekable local files only; cloud pipes fail closed
                        require(size in 64..(128L * 1024 * 1024))
                        require(expectedSize == null || expectedSize < 0 || expectedSize == size)
                        totalHashed += size; require(totalHashed <= 512L * 1024 * 1024)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (read < size) {
                            signal.throwIfCanceled()
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - read).toInt())
                            require(count > 0); digest.update(buffer, 0, count); read += count
                        }
                        require(input.read() == -1 && channel.size() == size)
                        item.put("bytes", size).put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                        val metadata = runCatching {
                            PeMetadata.inspect(size) { offset, count ->
                                val bytes = ByteArray(count); val target = ByteBuffer.wrap(bytes)
                                while (target.hasRemaining()) {
                                    signal.throwIfCanceled()
                                    require(channel.read(target, offset + target.position()) > 0)
                                }
                                bytes
                            }
                        }.getOrNull()
                        item.put("state", if (metadata == null) "invalid-pe" else "observed")
                        if (metadata != null) {
                            item.put("architecture", metadata.architecture).put("dll", metadata.dll)
                            metadata.fileVersion?.let { item.put("fileVersion", it) }
                            if (role == "wow") item.put("build12340", ClientInventory.matchesWow(metadata))
                        }
                    }
                } catch (_: Exception) {
                    signal.throwIfCanceled()
                } finally { runCatching { opened.getAndSet(null)?.close() } }
                return item
            }
            fun visit(id: String, parent: String, depth: Int) {
                signal.throwIfCanceled(); require(depth < 32 && seenIds.size < 100000 && seenIds.add(id))
                idCharacters += id.length; require(idCharacters <= 4 * 1024 * 1024)
                val uri = Documents.buildChildDocumentsUriUsingTree(tree, id)
                val projection = arrayOf(Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME,
                    Documents.Document.COLUMN_MIME_TYPE, Documents.Document.COLUMN_SIZE)
                val cursor = context.contentResolver.query(uri, projection, null, null, null, signal) ?: error("Unavailable")
                cursor.use {
                    while (it.moveToNext()) {
                        signal.throwIfCanceled()
                        val child = it.getString(0) ?: error("Missing identity")
                        val name = it.getString(1) ?: error("Missing name")
                        require(name.isNotEmpty() && '/' !in name && '\\' !in name)
                        val directory = it.getString(2) == Documents.Document.MIME_TYPE_DIR
                        val size = if (it.isNull(3)) null else it.getLong(3)
                        val path = if (parent.isEmpty()) name else "$parent/$name"
                        val role = inventory.add(path, directory, size)
                        if (directory) visit(child, path, depth + 1)
                        else if (role != null && ClientInventory.isBinary(role)) components.put(binary(child, role, size))
                        if (inventory.files % 250 == 0) progress(inventory.files)
                    }
                }
            }
            visit(Documents.getTreeDocumentId(tree), "", 0)
            signal.throwIfCanceled()
            result = "complete"
        } catch (_: Exception) {
            result = if (signal.isCanceled) "cancelled" else "failed"
        } finally {
            timer.shutdownNow(); cancel()
            report.put("state", result).put("completedAtEpochMs", System.currentTimeMillis())
                .put("files", inventory.files).put("directories", inventory.directories)
                .put("knownBytes", inventory.knownBytes).put("unknownSizes", inventory.unknownSizes)
                .put("mpqFiles", inventory.mpqFiles).put("components", components)
                .put("presentRoles", JSONArray(inventory.presentRoles.toList()))
            save(context, report)
        }
        return result
    }

    companion object {
        private fun file(context: Context) = AtomicFile(File(context.filesDir, "client-inspection/report.json"))
        private fun save(context: Context, report: JSONObject) {
            val file = file(context); file.baseFile.parentFile?.mkdirs()
            val output = file.startWrite()
            try { output.write(report.toString().toByteArray()); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }

        fun export(context: Context): JSONObject {
            val result = JSONObject().put("available", false).put("readOnly", true)
                .put("clientImported", false).put("fullClientIntegrityValidated", false)
                .put("warcraftXLCompatible", false).put("gameLoginQualified", false)
            val raw = runCatching {
                file(context).openRead().use { input ->
                    val buffer = ByteArray(32769); var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        require(read > 0); count += read
                    }
                    require(count <= 32768)
                    JSONObject(String(buffer, 0, count, Charsets.UTF_8))
                }
            }.getOrNull() ?: return result
            result.put("available", true)
            val state = raw.optString("state")
            result.put("state", state.takeIf { it in setOf("running", "complete", "failed", "cancelled") } ?: "invalid")
            for (key in listOf("startedAtEpochMs", "completedAtEpochMs", "files", "directories", "knownBytes", "unknownSizes", "mpqFiles")) {
                val value = raw.opt(key)
                if ((value is Int || value is Long) && (value as Number).toLong() >= 0) result.put(key, value)
            }
            fun safeRole(role: String) = role in ClientInventory.roles.values || role.matches(Regex("auxiliary-executable-([1-9]|[12][0-9]|3[0-2])"))
            val present = raw.optJSONArray("presentRoles") ?: JSONArray()
            result.put("presentRoles", JSONArray((0 until minOf(present.length(), ClientInventory.reportRoleLimit)).mapNotNull { present.optString(it).takeIf(::safeRole) }))
            val safe = JSONArray(); val components = raw.optJSONArray("components") ?: JSONArray()
            for (index in 0 until minOf(components.length(), ClientInventory.reportComponentLimit)) {
                val item = components.optJSONObject(index) ?: continue
                val role = item.optString("role"); if (!safeRole(role)) continue
                val value = JSONObject().put("role", role)
                for ((key, values) in mapOf("state" to setOf("unreadable", "invalid-pe", "observed"), "architecture" to setOf("x86", "x64", "other"))) {
                    item.optString(key).takeIf { it in values }?.let { value.put(key, it) }
                }
                item.optString("sha256").takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { value.put("sha256", it) }
                item.optString("fileVersion").takeIf { it.matches(Regex("[0-9]{1,5}(\\.[0-9]{1,5}){3}")) }?.let { value.put("fileVersion", it) }
                val bytes = item.optLong("bytes", -1); if (bytes in 64..(128L * 1024 * 1024)) value.put("bytes", bytes)
                for (key in listOf("dll", "build12340")) if (item.opt(key) is Boolean) value.put(key, item.getBoolean(key))
                safe.put(value)
            }
            return result.put("components", safe)
        }
    }
}
