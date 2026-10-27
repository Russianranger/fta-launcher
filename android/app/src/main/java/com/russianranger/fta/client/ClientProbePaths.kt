package com.russianranger.fta.client

import java.io.File

/** Linux sockaddr_un.sun_path has 108 bytes, including the terminating NUL. */
object ClientProbePaths {
    fun attempt(files: File, id: String): File {
        runtimeRequire(id.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")), "probe-unavailable")
        return File(File(files, "cr"), id.replace("-", ""))
    }
    fun validateTemporaryPath(canonical: String): Int {
        // PRoot's original socket fallback also lives in PROOT_TMP_DIR. Budget its
        // exact create_temp_name pattern with the largest possible signed PID.
        val socket = "$canonical/.X11-unix/X8".toByteArray(Charsets.UTF_8).size
        val fallback = "$canonical/proot-2147483647-XXXXXX".toByteArray(Charsets.UTF_8).size
        runtimeRequire(socket < 108 && fallback < 108, "socket-path-too-long")
        return socket
    }
}
