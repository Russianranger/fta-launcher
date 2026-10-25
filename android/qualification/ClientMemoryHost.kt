package com.russianranger.fta.client

import java.io.File
import java.io.InputStream

/** Synthetic bytes only. Run with -Xmx64m. 'legacy' uses the unchanged code-36 store. */
object ClientMemoryHost {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[1]); val mode = args[0]
        val large = 5L * 1024 * 1024 * 1024 + 29
        val source = object : ClientSource {
            override val identity = "synthetic-code36-resume"
            override fun inventory(): List<ClientEntry> = buildList {
                add(ClientEntry("Data", "Data", 0, 1, true))
                repeat(1174) { add(ClientEntry("directory-$it", "dir-$it", 0, 1, true)) }
                add(ClientEntry("Data/large.mpq", "large", large, 1, false))
                for (name in listOf("Wow.exe", "d3d9.dll", "WarcraftXL.dll", "WarcraftXL.cfg"))
                    add(ClientEntry(name, name, 1, 1, false))
                repeat(10471) { add(ClientEntry("file-$it", "file-$it", 1, 1, false)) }
            }
            override fun open(entry: ClientEntry): InputStream = object : InputStream() {
                var position = 0L
                override fun read(): Int = if (position == entry.bytes) -1 else (position++ % 251).toInt()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position == entry.bytes) return -1
                    val count = minOf(length.toLong(), entry.bytes - position).toInt()
                    for (i in 0 until count) buffer[offset + i] = ((position + i) % 251).toByte()
                    position += count; return count
                }
            }
        }
        if (mode == "legacy") {
            ClientStore(root).import(source, progress = {
                if (it.bytes >= 4_865_320_527L) { println("legacyVerifiedBytes=${it.bytes}"); System.out.flush(); Runtime.getRuntime().halt(79) }
            }, validate = {})
            error("Legacy interruption was not reached")
        }
        // A virtual clock tests the production governor's accounting without a multi-hour host run.
        var clock = 0L; var pressureInjected = false; var readbackBytes = 0L
        val guard = ClientIoGuard({ clock }, { clock += it }, {
            val pressured = !pressureInjected && readbackBytes > 1024 * 1024
            if (pressured) pressureInjected = true
            ClientIoGuard.Memory(8L shl 30, 16L shl 30, 256L shl 20, pressured)
        }, {})
        val store = ClientStore(root, releaseCache = { _, _, count ->
            check(count <= ClientStore.IO_WINDOW); readbackBytes += count
        }, beforeIo = guard::beforeIo)
        val copied = store.import(source, active = guard::check, validate = {})
        check(copied.files == 10476 && copied.resumedBytes >= 4_865_320_527L)
        check(pressureInjected && guard.pressurePauses == 1L)
        val verified = store.verify(active = guard::check, validate = {})
        check(verified.catalogSha256 == copied.catalogSha256 && verified.bytes == large + 10475)
        check(guard.ioBytes * 1000 / ClientIoGuard.IO_BYTES_PER_SECOND <= clock)
        println("files=${copied.files} bytes=${copied.bytes} resumedBytes=${copied.resumedBytes} catalog=${copied.catalogSha256}")
        println("governedIoBytes=${guard.ioBytes} virtualElapsedMs=$clock pressurePauses=${guard.pressurePauses} pressureWaitMs=${guard.pressureWaitMs}")
        println("maxHeapBytes=${Runtime.getRuntime().maxMemory()} readbackBytes=$readbackBytes PASS")
    }
}
