package com.russianranger.fta.client

import android.content.Context
import android.os.SystemClock
import android.system.Os
import com.russianranger.fta.runtime.NativeProcessBridge
import com.russianranger.fta.runtime.ProcessObservation
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Only packaged synthetic fixtures run here, with a new prefix and owned tracer. */
object ClientNativeProbe {
    private val baseNames = setOf("probe32.exe", "probe64.exe", "android_probe.py", "NOTICE.txt", "proot-COPYING.txt", "talloc-COPYING.txt", "GNU-GPL-3.txt")
    private val bridges = mapOf("libproot.so" to "46dcafabc77b386a7127f6cb7c71bdac03c372ff6bb4db0e1e9493ee29332adb",
        "libproot-loader.so" to "614591192bb34c98cc6cef528fb74ecfc1c97d0fabf4e352df1f16873c71d447")
    fun packaged(context: Context, guarded: Boolean = false) = (if (guarded) ClientGuardedWine.bridges else bridges).keys.all { File(context.applicationInfo.nativeLibraryDir, it).isFile }
    private fun bounded(file: File, max: Int): String {
        runtimeRequire(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= max, "probe-failed")
        return file.inputStream().use { input ->
            val data = ByteArray(max + 1); var size = 0
            while (size < data.size) { val n = input.read(data, size, data.size - size); if (n < 0) break; runtimeRequire(n > 0, "probe-failed"); size += n }
            runtimeRequire(size <= max, "probe-failed"); String(data, 0, size, Charsets.UTF_8)
        }
    }
    fun run(context: Context, runtime: File, attempt: String, active: () -> Unit,
        observe: (String, String) -> Unit,
        evidence: (String, Any) -> Unit = { _, _ -> }, guarded: Boolean = false,
        beforeIo: (Int) -> Unit = {}): ClientProbeProtocol.Result {
        val selectedBridges = if (guarded) ClientGuardedWine.bridges else bridges
        val names = if (guarded) ClientGuardedWine.assets else baseNames
        val assets = if (guarded) "client-guarded-wine" else "client-probe"
        runtimeRequire(attempt.matches(Regex("[0-9a-f-]{36}")) && packaged(context, guarded), "probe-unavailable")
        val native = File(context.applicationInfo.nativeLibraryDir)
        for ((name, expected) in selectedBridges) {
            val file = File(native, name)
            runtimeRequire(file.length() in 1..1024 * 1024 && runtimeHex(runtimeHash().digest(file.readBytes())) == expected, "probe-unavailable")
        }
        if (guarded) evidence("guardedPayloadSha256", JSONObject(selectedBridges))
        val run = if (guarded) ClientGuardedWine.createAttempt(context.filesDir, attempt)
            else ClientProbeAttempts.create(context.filesDir, attempt)
        fun dir(name: String) = File(run, name).also { runtimeRequire(it.mkdir(), "storage-unavailable") }
        val probe = dir("probe"); val prefix = dir("prefix"); val session = dir("session"); val tmp = dir("tmp"); val fonts = dir("font-cache")
        evidence("socketHostPathBytes", ClientProbePaths.validateTemporaryPath(tmp.canonicalPath))
        val metadata = context.assets.open("$assets/manifest.json").use { input ->
            val data = ByteArray(16385); var count = 0
            while (count < data.size) { val n = input.read(data, count, data.size - count); if (n < 0) break; runtimeRequire(n > 0, "probe-unavailable"); count += n }
            runtimeRequire(count <= 16384, "probe-unavailable"); JSONObject(String(data, 0, count, Charsets.UTF_8))
        }
        runtimeRequire(metadata.getInt("formatVersion") == 1, "probe-unavailable")
        val files = metadata.getJSONObject("files")
        runtimeRequire(files.keys().asSequence().toSet() == names, "probe-unavailable")
        for (name in names) {
            active(); val pin = files.getJSONObject(name); val length = pin.getInt("bytes")
            runtimeRequire(length in 1..2 * 1024 * 1024, "probe-unavailable")
            val output = File(probe, name); val hash = runtimeHash(); var total = 0
            context.assets.open("$assets/$name").use { input -> FileOutputStream(output).use { destination ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    active(); val n = input.read(buffer); if (n < 0) break
                    runtimeRequire(n > 0 && total + n <= length, "probe-unavailable")
                    hash.update(buffer, 0, n); destination.write(buffer, 0, n); total += n
                }
                destination.fd.sync()
            } }
            runtimeRequire(total == length && runtimeHex(hash.digest()) == pin.getString("sha256") &&
                runtimeHex(runtimeHash().digest(output.readBytes())) == pin.getString("sha256"), "probe-unavailable")
        }
        val logs = mutableMapOf<Long, File>(); val modes = mutableMapOf<Long, String>()
        fun stamp(file: File): List<Long> = Os.lstat(file.absolutePath).let {
            listOf(it.st_dev, it.st_ino, it.st_mode.toLong(), it.st_nlink, it.st_uid.toLong(), it.st_gid.toLong(),
                it.st_size, it.st_mtime, it.st_ctime)
        }
        val immutable = File(run, "immutable"); val view = File(run, "view")
        if (guarded) {
            runtimeRequire(run.usableSpace >= 3L * 1024 * 1024 * 1024, "insufficient-space")
            val built = ClientGuardedWine.create(run, probe, active, beforeIo)
            evidence("viewCopiedBytes", built.copiedBytes); evidence("viewSharedBytes", built.sharedBytes)
            evidence("viewCopiedFiles", built.copiedFiles); evidence("viewSharedFiles", built.sharedFiles)
            evidence("viewPlanSha256", built.planSha256)
        }
        val before = if (guarded) ClientGuardedWine.snapshot(immutable, ::stamp) else emptyMap()
        var nextGrowth = 0L
        val checkActive = {
            active()
            if (guarded && SystemClock.elapsedRealtime() >= nextGrowth) {
                nextGrowth = SystemClock.elapsedRealtime() + 5000
                evidence("viewBytesObserved", ClientWorkingView.checkGrowth(view, ClientWorkingView.GROWTH_LIMIT))
                evidence("prefixBytesObserved", ClientWorkingView.checkGrowth(prefix, 2L * 1024 * 1024 * 1024))
            }
        }
        val live = mutableSetOf<Long>()
        var ownershipValidated = false
        val driver = object : ClientProbeProtocol.Driver {
            override fun recordOwnership(key: String, value: Int) {
                if (key == "killedTracees" && value == 3) ownershipValidated = true
                evidence(key, value)
            }
            override fun now() = SystemClock.elapsedRealtime()
            override fun pause(ms: Long) { Thread.sleep(ms) }
            override fun active() = checkActive()
            override fun start(mode: String): Long {
                active()
                runtimeRequire(mode in setOf("--ownership-normal", "--ownership-hold", "--wine"), "probe-failed")
                val control = File(session, "ownership-control")
                if (Files.exists(control.toPath(), NOFOLLOW_LINKS)) {
                    runtimeRequire(Files.isRegularFile(control.toPath(), NOFOLLOW_LINKS), "ownership-failed"); Files.delete(control.toPath())
                }
                val args = listOf("--kill-on-exit") +
                    (if (guarded) listOf("--fta-readonly=${immutable.canonicalPath}", "-b", "${immutable.absolutePath}:/immutable", "-b", "${view.absolutePath}:/view") else emptyList()) +
                    listOf("--sysvipc", "-0", "-r", runtime.absolutePath,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${probe.absolutePath}:/client",
                    "-b", "${prefix.absolutePath}:/prefix", "-b", "${session.absolutePath}:/session",
                    "-b", "${tmp.absolutePath}:/tmp", "-b", "${fonts.absolutePath}:/var/cache",
                    "-w", if (guarded) "/view" else "/session", "/usr/bin/env", "-i", "PYTHONDONTWRITEBYTECODE=1", "/usr/bin/python3", "-B", if (guarded) "/client/guarded_wine.py" else "/client/android_probe.py", mode)
                val env = arrayOf("PROOT_LOADER=${File(native, "libproot-loader.so").absolutePath}",
                    "PROOT_TMP_DIR=${tmp.absolutePath}", "PROOT_NO_SECCOMP=1", "PROOT_DONT_POLLUTE_ROOTFS=1", "PATH=/system/bin", "TMPDIR=${tmp.absolutePath}")
                val output = File(run, "step-${logs.size}.out"); val error = File(run, "step-${logs.size}.err")
                val handle = NativeProcessBridge.start(File(native, if (guarded) "libproot_ro.so" else "libproot.so").absolutePath, args.toTypedArray(), env,
                    run.absolutePath, null, output.absolutePath, error.absolutePath)
                live.add(handle)
                logs[handle] = output; modes[handle] = mode
                observe(if (mode == "--wine") "probe-wine" else "probe-ownership", "")
                return handle
            }
            override fun tracer(handle: Long) = NativeProcessBridge.processId(handle)
            override fun output(handle: Long): String = bounded(logs.getValue(handle), 64 * 1024).also {
                observe(if (modes[handle] == "--wine") "probe-wine" else "probe-ownership", it)
            }
            override fun identity(pid: Int): ClientProbeProtocol.Identity? {
                val text = bounded(File("/proc/$pid/stat"), 4096)
                val sample = ProcessObservation.parse(text, pid)
                if (!sample.alive) return null
                val fields = text.substringAfterLast(')').trim().split(Regex("\\s+"))
                return ClientProbeProtocol.Identity(pid, sample.identity.parentPid, fields[3].toInt(), sample.identity.startTicks)
            }
            override fun ended(identity: ClientProbeProtocol.Identity): Boolean {
                val current = ProcessObservation.read(identity.pid)
                return current == null || !current.alive || current.identity.startTicks != identity.ticks
            }
            override fun waitFor(handle: Long, ms: Long): Int {
                val status = NativeProcessBridge.waitFor(handle, ms)
                if (status != ClientProbeProtocol.RUNNING) output(handle)
                return status
            }
            override fun stop(handle: Long, graceMs: Long) = NativeProcessBridge.stop(handle, graceMs).also {
                if (it != ClientProbeProtocol.RUNNING) live.remove(handle)
            }
            override fun requestTracerDeath(handle: Long) {
                runtimeRequire(modes[handle] == "--ownership-hold", "ownership-failed")
                FileOutputStream(File(session, "ownership-control")).use { it.write("kill ${tracer(handle)}\n".toByteArray()); it.fd.sync() }
            }
        }
        try {
            val result = ClientProbeProtocol.run(driver, if (guarded) ClientGuardedWine::parse else ClientProbeProtocol::parseStages)
            if (guarded) {
                observe("probe-postconditions", "")
                runtimeRequire(ClientGuardedWine.snapshot(immutable, ::stamp) == before, "probe-failed")
                runtimeRequire(bounded(File(view, "WTF/Config.wtf"), 64) == "private-original-settings\n", "probe-failed")
                runtimeRequire(File(view, "Logs").list()?.isEmpty() == true, "probe-failed")
                checkActive()
            }
            return result
        }
        finally {
            // Also covers exceptions inside start() after the native handle was acquired.
            for (handle in live.toList()) runCatching { driver.stop(handle, 2000) }
            if (guarded && live.isEmpty()) {
                val unchanged = runCatching { ClientGuardedWine.snapshot(immutable, ::stamp) == before }.getOrDefault(false)
                evidence("protectedFixtureUnchanged", unchanged)
            }
            if (ownershipValidated && live.isEmpty()) {
                runCatching {
                    ClientProbeAttempts.retire(run)
                    evidence("probeCleanupComplete", 1)
                }
            }
        }
    }
}
