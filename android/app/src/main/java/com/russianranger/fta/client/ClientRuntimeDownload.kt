package com.russianranger.fta.client

import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Fixed public source, HTTPS-only redirects, bounded connections; no ambient credentials. */
class ClientRuntimeDownload(private val active: () -> Unit,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    @Volatile private var current: HttpURLConnection? = null
    fun cancel() { current?.disconnect() }
    fun open(bytes: Long): InputStream {
        var url = URL(ARCHIVE_URL)
        try {
            for (redirect in 0..5) {
                active(); validate(url)
                val request = connect(url).also { current = it }
                request.connectTimeout = 15000; request.readTimeout = 15000; request.instanceFollowRedirects = false
                request.setRequestProperty("Accept-Encoding", "identity")
                active(); val status = request.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val target = request.getHeaderField("Location") ?: throw RuntimeInstallFailure("download-failed")
                    url = URL(url, target); request.disconnect(); continue
                }
                runtimeRequire(status == 200 && request.contentLengthLong in setOf(-1L, bytes), "download-failed")
                active()
                return object : FilterInputStream(request.inputStream) {
                    override fun close() { try { super.close() } finally { request.disconnect(); current = null } }
                }
            }
            throw RuntimeInstallFailure("download-failed")
        } catch (e: Exception) {
            current?.disconnect(); current = null
            if (e is RuntimeInstallFailure) throw e
            throw RuntimeInstallFailure("download-failed")
        }
    }
    companion object {
        const val ARCHIVE_URL = "https://github.com/Russianranger/uo-android-launcher/releases/download/v0.2.0/client-runtime-arm64.tar.gz"
        internal fun validate(url: URL) = runtimeRequire(url.protocol == "https" && url.userInfo == null &&
            url.port in setOf(-1, 443) && url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"), "download-failed")
    }
}
