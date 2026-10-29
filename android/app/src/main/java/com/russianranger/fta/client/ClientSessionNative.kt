package com.russianranger.fta.client

import android.content.Context
import android.os.SystemClock
import com.russianranger.fta.runtime.NativeProcessBridge
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Runtime lease spans both calls; client lease covers run. Each tracer is fully reaped before return. */
object ClientSessionNative {
    private fun bounded(file: File): String {
        runtimeRequire(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 65536, "probe-failed")
        file.inputStream().use { input ->
            val bytes = ByteArray(65537); var count = 0
            while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; runtimeRequire(n > 0, "probe-failed"); count += n }
            runtimeRequire(count <= 65536, "probe-failed"); return String(bytes, 0, count, Charsets.UTF_8)
        }
    }
    class Prepared internal constructor(internal val attempt: File)
    fun prepare(context: Context, runtime: File, run: File, active: () -> Unit,
        observe: (String) -> Unit, evidence: (String, Any) -> Unit): Prepared {
        runtimeRequire(run.usableSpace >= 4L * ClientRuntimeStore.RESERVE, "insufficient-space")
        val source = File(run, "prefix-source"); val view = File(run, "prefix-view")
        runtimeRequire(source.mkdir() && view.mkdir(), "storage-unavailable")
        execute(context, runtime, run, source, view, true, active, observe, evidence)
        evidence("prefixPreparationValidated", true)
        return Prepared(run)
    }
    fun run(context: Context, runtime: File, run: File, source: File, view: File, prepared: Prepared,
        active: () -> Unit, observe: (String) -> Unit, evidence: (String, Any) -> Unit) {
        runtimeRequire(prepared.attempt == run, "probe-failed")
        execute(context, runtime, run, source, view, false, active, observe, evidence)
    }
    private fun execute(context: Context, runtime: File, run: File, source: File, view: File, preparing: Boolean,
        active: () -> Unit, observe: (String) -> Unit, evidence: (String, Any) -> Unit) {
        var owned: Long? = null
        try {
            val budget = ClientWorkingView.sessionBudget(view)
            if (!preparing) {
                evidence("viewInitialBytes", budget.initialBytes)
                evidence("viewLimitBytes", budget.limitBytes)
                evidence("viewGrowthAllowanceBytes", ClientWorkingView.GROWTH_LIMIT)
            }
            val native = File(context.applicationInfo.nativeLibraryDir)
            for ((name, pin) in ClientGuardedWine.bridges) {
                val file = File(native, name)
                runtimeRequire(file.length() in 1..1024 * 1024 && runtimeHex(runtimeHash().digest(file.readBytes())) == pin, "probe-unavailable")
            }
            fun dir(name: String) = File(run, name).also {
                runtimeRequire(if (preparing) it.mkdir() else it.absoluteFile == it.canonicalFile &&
                    Files.isDirectory(it.toPath(), NOFOLLOW_LINKS), "storage-unavailable")
            }
            val probe = dir("probe"); val prefix = dir("prefix"); val session = dir("session"); val tmp = dir("tmp"); val cache = dir("font-cache")
            evidence("socketHostPathBytes", ClientProbePaths.validateTemporaryPath(tmp.canonicalPath))
            val metadata = context.assets.open("client-guarded-wine/manifest.json").use { input ->
                val data = input.readBytes(); runtimeRequire(data.size <= 16384, "probe-unavailable"); JSONObject(data.toString(Charsets.UTF_8))
            }.getJSONObject("files")
            for (name in listOf("android_probe.py", "original_startup.py", "startup64.exe")) {
                val pin = metadata.getJSONObject(name); val size = pin.getInt("bytes")
                runtimeRequire(size in 1..2 * 1024 * 1024, "probe-unavailable")
                val bytes = context.assets.open("client-guarded-wine/$name").use { it.readBytes() }
                runtimeRequire(bytes.size == size && runtimeHex(runtimeHash().digest(bytes)) == pin.getString("sha256"), "probe-unavailable")
                val target = File(probe, name)
                if (preparing) FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
                else runtimeRequire(Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) && target.length() == size.toLong() &&
                    target.readBytes().contentEquals(bytes), "probe-unavailable")
            }
            val args = listOf("--kill-on-exit", "--fta-readonly=${source.canonicalPath}", "--sysvipc", "-0", "-r", runtime.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${source.canonicalPath}:/immutable", "-b", "${view.absolutePath}:/view",
                "-b", "${probe.absolutePath}:/client", "-b", "${prefix.absolutePath}:/prefix", "-b", "${session.absolutePath}:/session",
                "-b", "${tmp.absolutePath}:/tmp", "-b", "${cache.absolutePath}:/var/cache", "-w", "/view",
                "/usr/bin/env", "-i", "PYTHONDONTWRITEBYTECODE=1", "/usr/bin/python3", "-B", "/client/original_startup.py", if (preparing) "--prepare" else "--original")
            val env = arrayOf("PROOT_LOADER=${File(native, "libproot-loader.so").absolutePath}",
                "PROOT_TMP_DIR=${tmp.absolutePath}", "PROOT_NO_SECCOMP=1", "PROOT_DONT_POLLUTE_ROOTFS=1", "PATH=/system/bin", "TMPDIR=${tmp.absolutePath}")
            val output = File(run, if (preparing) "prefix.out" else "startup.out")
            val error = File(run, if (preparing) "prefix.err" else "startup.err")
            active()
            val handle = NativeProcessBridge.start(File(native, "libproot_ro.so").absolutePath, args.toTypedArray(), env,
                run.absolutePath, null, output.absolutePath, error.absolutePath)
            owned = handle
            evidence("guardedPayloadSha256", JSONObject(ClientGuardedWine.bridges))
            val deadline = SystemClock.elapsedRealtime() + 16 * 60 * 1000
            var next = 0L; var nextGrowth = 0L
            while (true) {
                active(); val now = SystemClock.elapsedRealtime()
                runtimeRequire(now < deadline, "probe-timeout")
                if (now >= next) {
                    next = now + 1000; observe(bounded(output))
                    runtimeRequire(run.usableSpace >= ClientRuntimeStore.RESERVE, "insufficient-space")
                }
                if (now >= nextGrowth) {
                    nextGrowth = now + 5000
                    val viewBytes = ClientWorkingView.checkGrowth(view, budget.limitBytes)
                    if (!preparing) evidence("viewBytesObserved", viewBytes)
                    evidence("prefixBytesObserved", ClientWorkingView.checkGrowth(prefix, 3L * ClientRuntimeStore.RESERVE))
                }
                val status = NativeProcessBridge.waitFor(handle, 100)
                if (status != Int.MIN_VALUE) {
                    val text = bounded(output); observe(text)
                    evidence(if (preparing) "prefixPreparationExit" else "sessionExit", status)
                    // Also enforce the budget on a fast terminal result between polls.
                    val viewBytes = ClientWorkingView.checkGrowth(view, budget.limitBytes)
                    if (!preparing) evidence("viewBytesObserved", viewBytes)
                    evidence("prefixBytesObserved", ClientWorkingView.checkGrowth(prefix, 3L * ClientRuntimeStore.RESERVE))
                    if (preparing) ClientStartupProtocol.validatePrefix(text, status)
                    else { ClientStartupProtocol.validate(text, status); evidence("startupProtocolValidated", true) }
                    break
                }
            }
        } finally {
            // Cancellation must not release either lease while a tracer is alive.
            // Repeated stop is restricted to this acquired native ownership handle.
            owned?.let { handle -> while (NativeProcessBridge.stop(handle, 2000) == Int.MIN_VALUE) Thread.sleep(100) }
            evidence(if (preparing) "prefixPreparationCleanupComplete" else "ownedCleanupComplete", true)
        }
    }
}
