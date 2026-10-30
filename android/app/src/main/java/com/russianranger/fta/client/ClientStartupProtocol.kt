package com.russianranger.fta.client

/** Bounded observations; acceptance proves startup/header/ownership only. */
object ClientStartupProtocol {
    val stages = listOf("cpu-topology", "display", "prefix", "original-startup", "wine-stop")
    val prefixStages = listOf("cpu-topology", "display", "prefix", "wine-stop")
    val events = setOf("client-started", "client-create-error", "client-early-exit", "host-header-valid", "host-owned",
        "host-channels", "host-not-observed", "startup-observed", "client-forced-stop", "client-stopped", "host-stopped",
        "observer-failure-phase", "observation-elapsed-ms", "client-state", "host-state", "client-exit-code",
        "host-exit-code", "client-wait-error", "host-wait-error", "client-exit-read-error", "host-exit-read-error")
    val required = setOf("client-started", "host-header-valid", "host-owned", "startup-observed", "client-stopped", "host-stopped")
    val completionFlags = setOf("runtimePreRehashValidated", "runtimePostRehashValidated", "clientFullRehashValidated",
        "sourceMetadataUnchanged", "independentLaunchSettingsPrepared", "startupProtocolValidated", "ownedCleanupComplete", "prefixPreparationValidated", "prefixPreparationCleanupComplete")
    private fun integer(value: Any?): Long? = if (value is Int || value is Long) (value as Number).toLong() else null
    fun qualified(report: Map<String, Any?>): Boolean {
        val events = report["startupEvents"] as? Map<*, *> ?: return false
        val observed = report["probeStages"] as? Map<*, *> ?: return false
        val prefix = report["prefixPreparationStages"] as? Map<*, *> ?: return false
        return report["state"] == "complete" && report["operation"] == "original" &&
            report["clientCatalogSha256"] == ClientManagedView.CATALOG &&
            completionFlags.all { report[it] == true } && integer(report["sessionExit"]) == 0L &&
            integer(report["prefixPreparationExit"]) == 0L && prefix.keys == prefixStages.toSet() && prefix.values.all { integer(it) == 0L } &&
            observed.keys == stages.toSet() && observed.values.all { integer(it) == 0L } &&
            required.all { integer(events[it]) == 1L } && (integer(events["host-channels"])?.let { it in 2L..16L } == true) &&
            events.keys.all { it in required + setOf("host-channels", "client-forced-stop") } &&
            ("client-forced-stop" !in events || integer(events["client-forced-stop"]) == 1L)
    }
    val cacheFields = listOf("prefixCacheSyncedFiles", "prefixCacheAcceptedFiles", "prefixCacheErrors", "prefixCacheBytes")
    fun cacheValues(text: String): Map<String, Long> {
        runtimeRequire(text.length <= 65536, "probe-failed")
        val parts = text.lineSequence().lastOrNull { it.startsWith("FTA_PREFIX_CACHE ") }?.split(' ') ?: return emptyMap()
        if (parts.size != 5) return emptyMap()
        val values = parts.drop(1).map { it.toLongOrNull()?.takeIf { value -> value in 0..ClientPlan.MAX_BYTES } ?: return emptyMap() }
        if (values[1] > values[0]) return emptyMap()
        return cacheFields.zip(values).toMap()
    }
    fun validatePrefix(text: String, exit: Int) {
        runtimeRequire(text.length <= 65536, "probe-failed")
        val lines = text.lineSequence().toList()
        runtimeRequire(exit == 0 && lines.count { it == "FTA_CLIENT_PREFIX_PASS" } == 1 &&
            lines.none { it in setOf("FTA_CLIENT_PREFIX_FAILED", "FTA_CLIENT_STOP_FAILED", "FTA_CLIENT_STARTUP_PASS", "FTA_CLIENT_STARTUP_FAILED") || it.startsWith("FTA_CLIENT_EVENT ") } &&
            lines.filter { it.startsWith("FTA_ANDROID_STAGE ") } == prefixStages.map { "FTA_ANDROID_STAGE $it 0" }, "client-startup-failed")
    }
    fun values(text: String): Map<String, Long> {
        runtimeRequire(text.length <= 65536, "probe-failed")
        return text.lineSequence().mapNotNull { line ->
            val f = line.split(' ')
            if (f.size != 3 || f[0] != "FTA_CLIENT_EVENT" || f[1] !in events) null
            else f[2].toLongOrNull()?.takeIf { it in 0..0xffffffffL }?.let { f[1] to it }
        }.toMap()
    }
    fun validate(text: String, exit: Int): Map<String, Long> {
        val values = values(text); val lines = text.lineSequence().toList()
        runtimeRequire(exit == 0 && lines.count { it == "FTA_CLIENT_STARTUP_PASS" } == 1 &&
            lines.none { it in setOf("FTA_CLIENT_STARTUP_FAILED", "FTA_CLIENT_STOP_FAILED", "FTA_CLIENT_TRACE_OVERFLOW") } &&
            lines.filter { it.startsWith("FTA_ANDROID_STAGE ") } == stages.map { "FTA_ANDROID_STAGE $it 0" } &&
            lines.count { it.startsWith("FTA_CLIENT_EVENT ") } == values.size &&
            required.all { values[it] == 1L } && values["host-channels"]?.let { it in 2L..16L } == true &&
            values.keys.all { it in required + setOf("host-channels", "client-forced-stop") } &&
            ("client-forced-stop" !in values || values["client-forced-stop"] == 1L), "client-startup-failed")
        return values
    }
}
