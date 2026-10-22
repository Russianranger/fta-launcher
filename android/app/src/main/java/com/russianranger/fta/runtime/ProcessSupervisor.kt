package com.russianranger.fta.runtime

/** JNI ownership helper sets parent-death signals before exec and owns process groups. */
object NativeProcessBridge {
    init { System.loadLibrary("fta_process") }
    external fun start(
        executable: String,
        args: Array<String>,
        environment: Array<String>,
        cwd: String,
        stdinPath: String?,
        stdoutPath: String,
        stderrPath: String
    ): Long
    /** Int.MIN_VALUE means still running; other values are exit status or 128 + signal. */
    external fun waitFor(handle: Long, timeoutMs: Long): Int
    external fun stop(handle: Long, graceMs: Long): Int
}
