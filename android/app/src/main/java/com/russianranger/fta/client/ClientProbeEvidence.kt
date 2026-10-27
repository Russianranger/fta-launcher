package com.russianranger.fta.client

/** Fixed diagnostic vocabulary only: never export guest paths or arbitrary output. */
object ClientProbeEvidence {
    val categories = setOf("cpu-features-missing", "translator-assertion", "prefix-owner-mismatch",
        "display-listen-failed", "permission-denied", "socket-path-too-long", "invalid-argument")
    val displayEvents = setOf("ready", "exited", "timeout", "socket-check-failed")
    data class Observation(val stages: Map<String, Int>, val diagnostics: Set<String>,
        val displayEvent: String?, val displayExit: Int?, val socketStatus: Int?)
    fun parse(text: String): Observation {
        runtimeRequire(text.length <= 64 * 1024, "probe-failed")
        val stages = linkedMapOf<String, Int>(); val diagnostics = linkedSetOf<String>()
        var event: String? = null; var exit: Int? = null; var socket: Int? = null
        for (line in text.lineSequence()) {
            val fields = line.split(' ')
            if (fields.size == 3 && fields[0] == "FTA_ANDROID_STAGE" && fields[1] in ClientProbeProtocol.stages)
                fields[2].toIntOrNull()?.takeIf { it in -255..255 }?.let { stages[fields[1]] = it }
            if (fields.size != 2) continue
            when (fields[0]) {
                "FTA_ANDROID_DIAGNOSTIC" -> if (fields[1] in categories) diagnostics.add(fields[1])
                "FTA_ANDROID_DISPLAY_EVENT" -> if (fields[1] in displayEvents) event = fields[1]
                "FTA_ANDROID_DISPLAY_EXIT" -> fields[1].toIntOrNull()?.takeIf { it in -255..255 }?.let { exit = it }
                "FTA_ANDROID_SOCKET_STATUS" -> fields[1].toIntOrNull()?.takeIf { it in 0..255 }?.let { socket = it }
            }
        }
        return Observation(stages, diagnostics, event, exit, socket)
    }
}
