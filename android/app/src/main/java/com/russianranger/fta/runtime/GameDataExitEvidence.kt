package com.russianranger.fta.runtime

object GameDataExitEvidence {
    data class Sample(val pid: Int, val timestamp: Long, val reason: Int, val status: Int,
        val pssKiB: Long, val rssKiB: Long, val token: String?)
    fun export(sample: Sample, record: GameDataOperation.Record?, now: Long): Map<String, Any> = mapOf(
        "timestampEpochMs" to sample.timestamp, "reasonCode" to sample.reason,
        "reason" to (reasons[sample.reason] ?: "unknown"), "status" to sample.status,
        "lastSampledPssKiB" to sample.pssKiB, "lastSampledRssKiB" to sample.rssKiB,
        "attemptMatched" to (record != null && sample.pid == record.owner.pid &&
            sample.token == GameDataOperation.token(record.attempt) && sample.timestamp in record.started..now))
    // Android ApplicationExitInfo constants. Unknown future values remain explicitly unknown.
    private val reasons = mapOf(0 to "unknown", 1 to "exit_self", 2 to "signaled", 3 to "low_memory",
        4 to "crash", 5 to "crash_native", 6 to "anr", 7 to "initialization_failure",
        8 to "permission_change", 9 to "excessive_resource_usage", 10 to "user_requested",
        11 to "user_stopped", 12 to "dependency_died", 13 to "other", 14 to "freezer",
        15 to "package_state_change", 16 to "package_updated")
}
