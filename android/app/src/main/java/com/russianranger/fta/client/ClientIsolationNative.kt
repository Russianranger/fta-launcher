package com.russianranger.fta.client

import android.content.Context
import android.os.SystemClock
import android.system.Os
import com.russianranger.fta.runtime.NativeProcessBridge
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Executes only the pinned small native fixture. The Wine runtime and imports are never opened. */
object ClientIsolationNative {
    fun packaged(context: Context) = ClientIsolationPins.payloads.keys.all { File(context.applicationInfo.nativeLibraryDir, it).isFile }
    private fun text(file: File): String {
        runtimeRequire(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 16384, "probe-failed")
        return file.inputStream().use { input ->
            val bytes = ByteArray(16385); var used = 0
            while (used < bytes.size) { val n = input.read(bytes, used, bytes.size - used); if (n < 0) break; runtimeRequire(n > 0, "probe-failed"); used += n }
            runtimeRequire(used <= 16384, "probe-failed"); String(bytes, 0, used, Charsets.UTF_8)
        }
    }
    fun run(context: Context, attempt: String, active: () -> Unit, record: (String, Any) -> Unit) {
        val native = File(context.applicationInfo.nativeLibraryDir)
        for ((name, expected) in ClientIsolationPins.payloads) {
            val file = File(native, name)
            runtimeRequire(file.isFile && file.length() in 1..1024 * 1024 && runtimeHex(runtimeHash().digest(file.readBytes())) == expected, "probe-unavailable")
        }
        record("payloadSha256", JSONObject(ClientIsolationPins.payloads))
        val run = ClientIsolationFiles.create(context.filesDir, attempt)
        val immutable = File(run, "immutable"); val tmp = File(run, "tmp")
        Os.chmod(File(immutable, "read-only.txt").absolutePath, 256) // 0400: fake-root must not chmod on read.
        var started = 0; var reaped = 0
        fun execute(option: String?, name: String): Pair<Int, String> {
            active()
            val args = mutableListOf("--kill-on-exit")
            option?.let { args.add("--fta-readonly=$it") }
            args.addAll(listOf("-0", "-r", "/", "-w", File(run, "writable").absolutePath,
                File(native, "libfta_storage_probe.so").absolutePath, run.absolutePath))
            val output = File(run, "$name.out"); val error = File(run, "$name.err")
            val handle = NativeProcessBridge.start(File(native, "libproot_ro.so").absolutePath, args.toTypedArray(),
                arrayOf("PROOT_LOADER=${File(native, "libproot-loader.so").absolutePath}", "PROOT_TMP_DIR=${tmp.absolutePath}",
                    "PROOT_NO_SECCOMP=1", "PATH=/system/bin", "TMPDIR=${tmp.absolutePath}"),
                run.absolutePath, null, output.absolutePath, error.absolutePath)
            started++
            try {
                val deadline = SystemClock.elapsedRealtime() + 30_000
                var exit = Int.MIN_VALUE
                while (exit == Int.MIN_VALUE) {
                    active(); runtimeRequire(SystemClock.elapsedRealtime() < deadline, "probe-timeout")
                    runtimeRequire(output.length() <= 16384 && error.length() <= 16384, "probe-failed")
                    exit = NativeProcessBridge.waitFor(handle, 100)
                }
                return exit to text(output)
            } finally {
                if (NativeProcessBridge.stop(handle, 2000) != Int.MIN_VALUE) reaped++
                record("ownedHandlesReaped", started == reaped)
            }
        }
        val rejected = JSONObject()
        fun reject(name: String, root: String?) {
            val result = execute(root, name)
            runtimeRequire(result.first != 0 && result.second.isEmpty(), "probe-failed")
            rejected.put(name, true); record("rootRejections", rejected)
        }
        reject("missing", null)
        reject("noncanonical", immutable.absolutePath + "/.")
        val invalid = File(immutable, "invalid").toPath()
        Files.createSymbolicLink(invalid, File(immutable, "canary.bin").toPath())
        try { reject("symlink", immutable.absolutePath) } finally { Files.delete(invalid) }
        Files.createLink(invalid, File(immutable, "canary.bin").toPath())
        try { reject("hardlink", immutable.absolutePath) } finally { Files.delete(invalid) }
        fun stamp(file: File): List<Long> = Os.lstat(file.absolutePath).let {
            listOf(it.st_dev, it.st_ino, it.st_mode.toLong(), it.st_nlink, it.st_uid.toLong(), it.st_gid.toLong(),
                it.st_size, it.st_mtime, it.st_ctime)
        }
        val before = ClientIsolationFiles.snapshot(run, ::stamp)
        record("state", "testing")
        val (exit, output) = execute(immutable.absolutePath, "fixture")
        record("fixtureExit", exit)
        record("checks", JSONObject(ClientIsolationProtocol.completed(output).associateWith { 0 }))
        val unchanged = ClientIsolationFiles.snapshot(run, ::stamp) == before
        record("protectedTreeUnchanged", unchanged)
        runtimeRequire(unchanged && ClientIsolationProtocol.passed(output, exit), "probe-failed")
        runtimeRequire(started == 5 && reaped == started, "ownership-failed")
        record("fixtureBytes", 65545)
        record("fixtureChecksPassed", ClientIsolationProtocol.checks.size)
    }
}
