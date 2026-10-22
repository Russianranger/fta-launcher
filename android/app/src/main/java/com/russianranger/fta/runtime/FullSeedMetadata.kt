package com.russianranger.fta.runtime

import android.content.Context
import org.json.JSONObject

/** Public metadata only. SQL and populated records are supplied privately by the user. */
internal object FullSeedMetadata {
    const val ASSET = "metadata/full-seed-lock.json"
    fun load(context: Context): FullSeedLock {
        val bytes = context.assets.open(ASSET).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= 256 * 1024) { "Full seed metadata exceeds its limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        require(value.getInt("formatVersion") == 1 && value.getString("requiredProviderVersion") == "12.3.2" &&
            value.getInt("receiptConcatLimitBytes") == 1048576 && value.getString("purpose") == "fresh-disposable-full-seed-only")
        val files = value.getJSONArray("files")
        val schemas = value.getJSONArray("schemas")
        return FullSeedLock(value.getString("archiveSha256"), value.getLong("archiveSize"),
            (0 until files.length()).map { index -> files.getJSONObject(index).let { entry ->
                FullSeedEntry(entry.getString("path"), entry.getString("schema"), entry.getString("kind"),
                    entry.getString("name"), entry.getLong("size"), entry.getString("sha256"))
            } }, (0 until schemas.length()).map { index -> schemas.getJSONObject(index).let { schema ->
                val triggers = schema.getJSONArray("triggerNames")
                FullSeedSchema(schema.getString("name"), schema.getInt("tableCount"),
                    (0 until triggers.length()).map { triggers.getString(it) }, schema.getInt("routineCount"),
                    schema.getInt("eventCount"), schema.getInt("receiptCount"), schema.getInt("receiptBytes"), schema.getString("receiptSha256"))
            } })
    }
}
