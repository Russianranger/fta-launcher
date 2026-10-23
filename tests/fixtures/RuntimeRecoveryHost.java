import com.russianranger.fta.runtime.*;
import java.nio.file.*;
import java.util.*;

/** Real host processes/SQL behind the production recovery protocol. Android intent delivery is separate. */
public class RuntimeRecoveryHost {
    static final String ATTEMPT = "00000000-0000-0000-0000-000000000012";
    static final String CANCEL_ROOT = "g-00000000-0000-0000-0000-000000000013";
    static final String CRASH_ROOT = "g-00000000-0000-0000-0000-000000000014";
    static ProcessIdentity self() throws Exception {
        int visiblePid = Integer.parseInt(Files.readString(Path.of("/proc/self/stat")).split(" ")[0]);
        return ProcessObservation.INSTANCE.self(visiblePid);
    }
    static ProcessIdentity child(long handle, ProcessIdentity owner) throws Exception {
        int innerPid = NativeProcessBridge.INSTANCE.processId(handle);
        // This host mounts an outer procfs over a nested PID namespace. Translation
        // is host-harness-only; production Android refuses an inconsistent self view.
        List<ProcessIdentity> matches = new ArrayList<>();
        try (var processes = Files.list(Path.of("/proc"))) {
          for (Path process : processes.toList()) {
            if (!process.getFileName().toString().matches("[0-9]+")) continue;
            int pid = Integer.parseInt(process.getFileName().toString());
            try {
                var sample = ProcessObservation.INSTANCE.read(pid);
                if (sample == null || !sample.getAlive() || sample.getIdentity().getParentPid() != owner.getPid()) continue;
                String line = Files.readAllLines(Path.of("/proc/"+pid+"/status")).stream()
                    .filter(s -> s.startsWith("NSpid:")).findFirst().orElseThrow();
                String[] tokens = line.trim().split("\\s+");
                if (Integer.parseInt(tokens[tokens.length-1]) == innerPid) {
                    var identity = ProcessObservation.INSTANCE.live(pid);
                    if (identity.getParentPid() == owner.getPid()) matches.add(identity);
                }
            } catch (NoSuchFileException ignored) { }
          }
        }
        if (matches.size() != 1) throw new IllegalStateException("Host PID translation is ambiguous");
        return matches.get(0);
    }
    static void worker(Path base, String server, String client, RecoveryPhase phase) throws Exception {
        var store = new RecoveryStore(base.resolve("control").toFile(), ATTEMPT);
        var previous = phase == RecoveryPhase.RESUME ? store.read(RecoveryPhase.CRASH) : null;
        String rootName = phase == RecoveryPhase.CANCEL ? CANCEL_ROOT : CRASH_ROOT;
        if (previous != null && (!ProcessObservation.INSTANCE.ended(previous.getOwner()) ||
            previous.getChildren().stream().anyMatch(p -> !ProcessObservation.INSTANCE.ended(p))))
            throw new IllegalStateException("Prior owner or child still alive");
        Path root = base.resolve("database-diagnostics").resolve(rootName);
        var driver = new DatabaseLifecycleHost(new String[]{root.toString(), server, client});
        var owner = self();
        try {
            driver.restartDatabase();
            if (!driver.query("SELECT VERSION();").trim().equals("12.3.2-MariaDB"))
                throw new IllegalStateException("Wrong host provider");
            if (phase == RecoveryPhase.RESUME) {
                RecoveryCanary.INSTANCE.verify(ATTEMPT, driver::query);
                if (driver.stopDatabase(false) != 0) throw new IllegalStateException("Final shutdown failed");
                store.write(new RecoveryWorkerRecord(phase, "RECOVERED", rootName, owner, List.of(), true));
                return;
            }
            RecoveryCanary.INSTANCE.create(ATTEMPT, driver::query);
            long transaction = driver.beginClient(DatabaseLifecycleProbe.TRANSACTION);
            long end = driver.monotonicMs()+10000;
            while (!driver.query(DatabaseLifecycleProbe.ACTIVE).trim().equals("1")) {
                if (!driver.clientRunning(transaction) || driver.monotonicMs() > end) throw new IllegalStateException("Transaction absent");
                driver.pause(100);
            }
            var children = List.of(child(driver.database, owner), child(transaction, owner));
            store.write(new RecoveryWorkerRecord(phase, "READY", rootName, owner, children, false));
            while (true) {
                if (phase == RecoveryPhase.CANCEL && Files.exists(base.resolve("request-stop"))) {
                    if (driver.cancelClient(transaction) != 143) throw new IllegalStateException("Client cancellation not observed");
                    int exit = driver.bridge.stop(driver.database, 2000);
                    if (exit == Integer.MIN_VALUE) throw new IllegalStateException("Database remains alive");
                    driver.owned.remove(driver.database); driver.database = 0;
                    store.write(new RecoveryWorkerRecord(phase, "STOPPED", rootName, owner, children, true));
                    return;
                }
                driver.pause(100);
            }
        } finally { driver.cleanup(); }
    }
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        if (args.length == 4) { worker(base, args[1], args[2], RecoveryPhase.valueOf(args[3])); return; }
        var store = new RecoveryStore(base.resolve("control").toFile(), ATTEMPT);
        var workers = new EnumMap<RecoveryPhase, Process>(RecoveryPhase.class);
        var observed = new LinkedHashSet<String>();
        try {
            RuntimeRecoveryProtocol.INSTANCE.run(new RuntimeRecoveryProtocol.Driver() {
                public void start(RecoveryPhase phase) {
                    try {
                        List<String> command = List.of("java", "-Djava.library.path="+System.getProperty("java.library.path"),
                            "-cp", System.getProperty("java.class.path"), "RuntimeRecoveryHost", base.toString(), args[1], args[2], phase.name());
                        workers.put(phase, new ProcessBuilder(command).redirectErrorStream(true)
                            .redirectOutput(base.resolve(phase.name()+".log").toFile()).start());
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
                public RecoveryWorkerRecord checkpoint(RecoveryPhase phase) {
                    var record = store.read(phase);
                    if (!workers.get(phase).isAlive() && (record == null || !List.of("STOPPED", "RECOVERED").contains(record.getState())))
                        throw new IllegalStateException("Host worker exited before its checkpoint");
                    return record;
                }
                public void stopAction() {
                    try { Files.writeString(base.resolve("request-stop"), "stop"); } catch (Exception e) { throw new RuntimeException(e); }
                }
                public void killOwner() { workers.get(RecoveryPhase.CRASH).destroyForcibly(); }
                public boolean ended(ProcessIdentity p) { return ProcessObservation.INSTANCE.ended(p); }
                public void checkActive() { }
                public void pause() { try { Thread.sleep(100); } catch (Exception e) { throw new RuntimeException(e); } }
                public long nowMs() { return System.nanoTime()/1000000; }
            }, flag -> { observed.add(flag); return kotlin.Unit.INSTANCE; });
            if (!observed.equals(new LinkedHashSet<>(RuntimeRecoveryProtocol.INSTANCE.getFields()))) throw new IllegalStateException("Incomplete host protocol");
            for (String flag : observed) System.out.println(flag+"=true");
        } finally {
            for (Process worker : workers.values()) {
                if (worker.isAlive()) worker.destroyForcibly();
                worker.waitFor();
            }
        }
    }
}
