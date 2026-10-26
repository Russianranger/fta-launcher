package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class ClientRuntimeDownloadTest {
    private class Connection(url: URL, val status: Int = 200, val next: String? = null, val bytes: Long = 3) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() { }
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = if (name == "Location") next else null
        override fun getContentLengthLong() = bytes
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    }
    private fun failure(block: () -> Unit) {
        try { block(); fail() } catch (e: RuntimeInstallFailure) { assertEquals("download-failed", e.reason) }
    }
    @Test fun validatesEveryRedirectBeforeConnectingAndClosesPreviousConnections() {
        val connections = mutableListOf<Connection>()
        val source = ClientRuntimeDownload({ }, { url ->
            Connection(url, if (connections.isEmpty()) 302 else 200,
                "https://release-assets.githubusercontent.com/asset?temporary=opaque").also { connections.add(it) }
        })
        source.open(3).use { assertEquals(1, it.read()); assertTrue(connections[0].disconnected) }
        assertEquals(2, connections.size); assertTrue(connections.all { it.disconnected })
        assertTrue(connections.all { !it.instanceFollowRedirects && it.connectTimeout == 15000 && it.readTimeout == 15000 })
    }
    @Test fun redirectCannotDowngradeUseCredentialsOrReachOtherHostsPorts() {
        for (target in listOf("http://github.com/asset", "https://evil.invalid/asset", "https://user:secret@github.com/asset",
            "https://github.com:8443/asset", "https://github.com.evil.invalid/asset", "file:///private", "https://127.0.0.1/asset")) {
            var connects = 0
            failure { ClientRuntimeDownload({ }, { url -> connects++; Connection(url, 302, target) }).open(3) }
            assertEquals(1, connects)
        }
    }
    @Test fun loopsAndAbsentRedirectTargetsAreBoundedAndDisconnected() {
        for (target in listOf(null, ClientRuntimeDownload.ARCHIVE_URL)) {
            val connections = mutableListOf<Connection>()
            failure { ClientRuntimeDownload({ }, { url -> Connection(url, 302, target).also { connections.add(it) } }).open(3) }
            assertTrue(connections.size in 1..6); assertTrue(connections.all { it.disconnected })
        }
    }
    @Test fun errorsAndWrongDeclaredLengthsCannotReturnPayload() {
        for ((status, size) in listOf(404 to 3L, 206 to 3L, 200 to 4L, 200 to 0L)) {
            var connection: Connection? = null
            failure { ClientRuntimeDownload({ }, { url -> Connection(url, status, bytes = size).also { connection = it } }).open(3) }
            assertTrue(connection!!.disconnected)
        }
    }
    @Test fun unknownLengthStillReturnsStreamForStoreHashAndSizeEnforcement() {
        ClientRuntimeDownload({ }, { Connection(it, bytes = -1) }).open(3).use { assertEquals(3, it.readBytes().size) }
    }
    @Test fun cancellationDisconnectsOwnedConnectionAndPreservesCancellationReason() {
        var cancelled = false; var connection: Connection? = null
        val source = ClientRuntimeDownload({ if (cancelled) throw RuntimeInstallFailure("cancelled") },
            { Connection(it).also { c -> connection = c } })
        source.open(3).use { source.cancel(); assertTrue(connection!!.disconnected) }
        cancelled = true
        try { source.open(3); fail() } catch (e: RuntimeInstallFailure) { assertEquals("cancelled", e.reason) }
    }
}
