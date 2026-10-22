package com.russianranger.fta.runtime

import android.content.Context
import org.json.JSONObject

/** This APK asset contains public file identities only, never SQL or account rows. */
object AuthSeedMetadata {
    fun load(context: Context): AuthSeedLock {
        val raw = context.assets.open("runtime/data/metadata/auth-seed-lock.json").use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= 64 * 1024)
                output.write(buffer, 0, read)
            }
            output.toString("UTF-8")
        }
        val value = JSONObject(raw)
        require(value.getInt("formatVersion") == 1)
        val files = value.getJSONArray("files")
        val entries = (0 until files.length()).map { index ->
            val entry = files.getJSONObject(index)
            AuthSeedEntry(entry.getString("path"), entry.getString("sha256"), entry.getLong("size"),
                entry.getString("role"), entry.getString("targetPath"))
        }
        val directories = value.getJSONArray("directories")
        return AuthSeedLock(value.getString("archiveSha256"), value.getLong("archiveSize"), value.getString("database"),
            entries, (0 until directories.length()).map { directories.getString(it) }, value.getString("sourceDirectory"))
    }
}
