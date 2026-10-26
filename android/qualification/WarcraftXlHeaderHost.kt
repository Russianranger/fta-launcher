package com.russianranger.fta.client

/** Synthetic snapshots only; no process, file mapping or game startup. */
object WarcraftXlHeaderHost {
    @JvmStatic fun main(args: Array<String>) {
        check(args.isEmpty())
        generateSequence(::readLine).forEach { line ->
            check(line.length == 128)
            val header = line.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            println(WarcraftXlHostHeader.inspect(header).originalCode)
        }
    }
}
