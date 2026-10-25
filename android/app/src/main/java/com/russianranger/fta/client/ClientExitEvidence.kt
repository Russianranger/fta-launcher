package com.russianranger.fta.client

/** Never correlate an older runtime exit or a reused PID with this client operation. */
object ClientExitEvidence {
    fun token(attempt: String): String {
        require(attempt.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        return "fta-client:$attempt"
    }
    fun matches(attempt: String, owner: Int, started: Long, end: Long,
        pid: Int, timestamp: Long, summary: String?): Boolean = runCatching {
        owner > 0 && owner == pid && started > 0 && timestamp in started..end && summary == token(attempt)
    }.getOrDefault(false)
}
