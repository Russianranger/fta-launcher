package com.russianranger.fta.client

/** Host-only bridge for comparison with the unchanged private launcher function. */
object ClientWineOverridesHost {
    @JvmStatic fun main(args: Array<String>) {
        check(args.isEmpty())
        generateSequence(::readLine).forEach { line ->
            check(line.length <= 2 * ClientWineOverrides.MAX_CHARACTERS && line.length % 2 == 0)
            val text = line.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
            println(ClientWineOverrides.nativeProxy(text).toByteArray(Charsets.UTF_8)
                .joinToString("") { "%02x".format(it) })
        }
    }
}
