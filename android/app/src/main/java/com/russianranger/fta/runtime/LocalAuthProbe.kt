package com.russianranger.fta.runtime

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Source-derived build-12340 challenge. No password, account creation or login proof is sent. */
object LocalAuthProbe {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    fun unusedPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }
    fun packet(name: String): ByteArray {
        require(name.matches(Regex("FTA[A-F0-9]{13}")))
        val username = name.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(34 + username.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0); put(0); putShort((30 + username.size).toShort())
            put(byteArrayOf(0x57, 0x6f, 0x57, 0)); put(3); put(3); put(5); putShort(12340)
            put(byteArrayOf(0x36, 0x38, 0x78, 0)); put(byteArrayOf(0x6e, 0x69, 0x57, 0))
            put("SUne".toByteArray(Charsets.US_ASCII)); putInt(0); putInt(0x0100007f)
            put(username.size.toByte()); put(username)
        }.array()
    }
    fun validateReply(reply: ByteArray) { require(reply.contentEquals(byteArrayOf(0, 0, 4))) { "Unexpected original auth protocol response" } }
    fun exchange(port: Int, name: String) {
        require(port in 1024..65535)
        Socket().use { socket ->
            socket.soTimeout = 2000
            socket.connect(InetSocketAddress(loopback, port), 2000)
            socket.getOutputStream().write(packet(name)); socket.getOutputStream().flush()
            val response = ByteArray(3); var size = 0
            while (size < response.size) { val n = socket.getInputStream().read(response, size, response.size - size); check(n > 0); size += n }
            validateReply(response)
        }
    }
    fun closed(port: Int): Boolean {
        require(port in 1024..65535)
        return try { Socket().use { it.connect(InetSocketAddress(loopback, port), 500) }; false }
        catch (_: java.net.ConnectException) { true }
    }
    fun config(socket: File, client: File, source: File, temp: File, user: String, password: String, port: Int): String {
        require(port in 1024..65535)
        return AuthDiagnosticConfig.render(socket, client, source, temp, user, password)
            .replace("RealmServerPort = -1\n", "RealmServerPort = $port\n")
    }
}

/** Two measured listener launches; each must stop before the retained database can shut down. */
object LocalAuthProtocol {
    val fields = listOf("firstProtocolReply", "firstHealthyHold", "firstCleanStop", "firstListenerClosed",
        "secondProtocolReply", "secondHealthyHold", "secondCleanStop", "secondListenerClosed")
    interface Driver {
        fun checkActive()
        fun start(pass: Int)
        fun probe()
        fun hold()
        fun stop(): Int
        fun listenerClosed(): Boolean
    }
    fun run(driver: Driver, observe: (String, Boolean) -> Unit) {
        for (pass in 1..2) {
            val prefix = if (pass == 1) "first" else "second"
            driver.checkActive()
            try {
                driver.start(pass); driver.checkActive(); driver.probe(); observe(prefix + "ProtocolReply", true)
                driver.hold(); driver.checkActive(); driver.probe(); observe(prefix + "HealthyHold", true)
                check(driver.stop() == 0) { "Original authserver did not stop cleanly" }; observe(prefix + "CleanStop", true)
                check(driver.listenerClosed()) { "Auth listener remained open" }; observe(prefix + "ListenerClosed", true)
            } catch (failure: Exception) {
                runCatching { driver.stop() }; throw failure
            }
        }
    }
}
