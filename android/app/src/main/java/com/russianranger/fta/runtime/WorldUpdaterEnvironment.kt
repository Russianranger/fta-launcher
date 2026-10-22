package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

/**
 * The original enabled World Inspector bridge requires an existing absolute queue
 * during its pre-map config hook. Its supported AC_ variable takes precedence over
 * the unchanged module config's relative default. This queue contains no user jobs.
 */
internal object WorldUpdaterEnvironment {
    const val QUEUE_KEY = "AC_WORLD_INSPECTOR_RUNTIME_QUEUE_DIR"
    const val QUEUE_NAME = "world-inspector-queue"
    private val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    private val roles = setOf("database-server", "database-client", "auth-server", "world-server")

    fun createQueue(root: File): File {
        require(root.isAbsolute && Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS))
        val queue = File(root, QUEUE_NAME)
        require(!Files.exists(queue.toPath(), LinkOption.NOFOLLOW_LINKS)) { "World diagnostic queue must be fresh" }
        check(queue.mkdir())
        try {
            Files.setPosixFilePermissions(queue.toPath(), permissions)
            validateQueue(root, queue)
            return queue
        } catch (failure: Exception) {
            // Never delete contents or follow a link if an unexpected filesystem change occurred.
            queue.delete()
            throw failure
        }
    }

    fun forRole(base: Array<String>, role: String, root: File, queue: File?): Array<String> {
        require(role in roles && base.none { it.substringBefore('=') == QUEUE_KEY }) { "World queue environment must be scoped to its process" }
        if (role != "world-server") return base.copyOf()
        require(queue != null) { "World diagnostic queue is unavailable" }
        validateQueue(root, queue)
        return base + "$QUEUE_KEY=${queue.absolutePath}"
    }

    private fun validateQueue(root: File, queue: File) {
        require(root.isAbsolute && queue.isAbsolute && queue.name == QUEUE_NAME &&
            queue.absolutePath.none { it in "\";=\r\n\u0000" } &&
            Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.isDirectory(queue.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            queue.canonicalFile.parentFile == root.canonicalFile &&
            queue.absoluteFile.parentFile == root.absoluteFile &&
            Files.getPosixFilePermissions(queue.toPath(), LinkOption.NOFOLLOW_LINKS) == permissions &&
            queue.list()?.isEmpty() == true) { "World diagnostic queue must be an empty private directory beneath this attempt" }
    }
}
