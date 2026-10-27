package com.russianranger.fta.client

import android.content.Context
import android.os.SystemClock
import com.russianranger.fta.runtime.NativeProcessBridge
import com.russianranger.fta.runtime.ProcessObservation
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Only packaged synthetic fixtures run here, with a new prefix and owned tracer. */
object ClientNativeProbe {
    private val names = setOf("probe32.exe", "probe64.exe", "android_probe.py", "NOTICE.txt", "proot-COPYING.txt", "talloc-COPYING.txt", "GNU-GPL-3.txt")
    private val bridges = mapOf("libproot.so" to "46dcafabc77b386a7127f6cb7c71bdac03c372ff6bb4db0e1e9493ee29332adb",
        "libproot-loader.so" to "614591192bb34c98cc6cef528fb74ecfc1c97d0fabf4e352df1f16873c71d447")
    fun packaged(context: Context) = bridges.keys.all { File(context.applicationInfo.nativeLibraryDir, it).isFile }
    private fun bounded(file: File, max: Int): String {
        runtimeRequire(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= max, "probe-failed")
        return file.inputStream().use { input ->
            val data = ByteArray(max + 1); var size = 0
            while (size < data.size) { val n = input.read(data, size, data.size - size); if (n < 0) break; runtimeRequire(n > 0, "probe-failed"); size += n }
            runtimeRequire(size <= max, "probe-failed"); String(data, 0, size, Charsets.UTF_8)
        }
    }
    fun run(context: Context, runtime: File, attempt: String, active: () -> Unit,
        observe: (String, String) -> Unit): ClientProbeProtocol.Result {
        runtimeRequire(attempt.matches(Regex("[0-9a-f-]{36}")) && packaged(context), "probe-unavailable")
        val native = File(context.applicationInfo.nativeLibraryDir)
        for ((name, expected) in bridges) {
            val file = File(native, name)
            runtimeRequire(file.length() in 1..1024 * 1024 && runtimeHex(runtimeHash().digest(file.readBytes())) == expected, "probe-unavailable")
        }
        val attempts = File(context.filesDir, "client-runtime-attempts")
        runtimeRequire(!Files.isSymbolicLink(attempts.toPath()) && (attempts.mkdir() || attempts.isDirectory), "unsafe-runtime-store")
        // Preserve interrupted attempts for evidence; no deletion while ownership is uncertain.
        runtimeRequire(attempts.list()?.size?.let { it < 3 } == true && attempts.usableSpace >= 2 * ClientRuntimeStore.RESERVE, "insufficient-space")
        val run = File(attempts, attempt); runtimeRequire(run.mkdir(), "unsafe-runtime-store")
        fun dir(name: String) = File(run, name).also { runtimeRequire(it.mkdir(), "storage-unavailable") }
        val probe = dir("probe"); val prefix = dir("prefix"); val session = dir("session"); val tmp = dir("tmp"); val fonts = dir("font-cache")
        val metadata = context.assets.open("client-probe/manifest.json").use { input ->
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
            context.assets.open("client-probe/$name").use { input -> FileOutputStream(output).use { destination ->
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
        val checkActive = active
        val driver = object : ClientProbeProtocol.Driver {
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
                val args = listOf("--kill-on-exit", "--sysvipc", "-0", "-r", runtime.absolutePath,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${probe.absolutePath}:/client",
                    "-b", "${prefix.absolutePath}:/prefix", "-b", "${session.absolutePath}:/session",
                    "-b", "${tmp.absolutePath}:/tmp", "-b", "${fonts.absolutePath}:/var/cache",
                    "-w", "/session", "/usr/bin/env", "-i", "PYTHONDONTWRITEBYTECODE=1", "/usr/bin/python3", "-B", "/client/android_probe.py", mode)
                val env = arrayOf("PROOT_LOADER=${File(native, "libproot-loader.so").absolutePath}",
                    "PROOT_TMP_DIR=${tmp.absolutePath}", "PROOT_NO_SECCOMP=1", "PATH=/system/bin", "TMPDIR=${tmp.absolutePath}")
                val output = File(run, "step-${logs.size}.out"); val error = File(run, "step-${logs.size}.err")
                val handle = NativeProcessBridge.start(File(native, "libproot.so").absolutePath, args.toTypedArray(), env,
                    run.absolutePath, null, output.absolutePath, error.absolutePath)
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
            override fun stop(handle: Long, graceMs: Long) = NativeProcessBridge.stop(handle, graceMs)
            override fun requestTracerDeath(handle: Long) {
                runtimeRequire(modes[handle] == "--ownership-hold", "ownership-failed")
                FileOutputStream(File(session, "ownership-control")).use { it.write("kill ${tracer(handle)}\n".toByteArray()); it.fd.sync() }
            }
        }
        return ClientProbeProtocol.run(driver)
    }
}
