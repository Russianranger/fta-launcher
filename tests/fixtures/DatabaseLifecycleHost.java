import com.russianranger.fta.runtime.DatabaseLifecycleProbe;
import com.russianranger.fta.runtime.NativeProcessBridge;
import java.nio.file.*;
import java.util.*;

/** Host-only driver for the production Kotlin probe and production JNI owner. */
public class DatabaseLifecycleHost implements DatabaseLifecycleProbe.Driver {
    final Path root;
    final String server, client;
    final String[] serverArgs, clientArgs;
    final Map<Long, Path> logs = new HashMap<>();
    final Set<Long> owned = new HashSet<>();
    final NativeProcessBridge bridge = NativeProcessBridge.INSTANCE;
    long database;
    int serial;
    DatabaseLifecycleHost(String[] args) throws Exception {
        root = Path.of(args[0]); server = args[1]; client = args[2];
        serverArgs = Files.readAllLines(root.resolve("server.args")).toArray(String[]::new);
        clientArgs = Files.readAllLines(root.resolve("client.args")).toArray(String[]::new);
    }
    long launch(String executable, String[] args, String sql) {
        try {
            String label = "command-" + (++serial);
            Path out = root.resolve(label + ".out");
            Path input = root.resolve(label + ".sql");
            if (sql != null) Files.writeString(input, sql);
            long h = bridge.start(executable, args, new String[]{"PATH=/usr/bin:/bin", "HOME="+root, "TMPDIR="+root},
                root.toString(), sql == null ? null : input.toString(), out.toString(), root.resolve(label+".err").toString());
            logs.put(h, out); owned.add(h); return h;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public String query(String sql) {
        long h = launch(client, clientArgs, sql);
        try {
            if (bridge.waitFor(h, 10000) != 0) throw new IllegalStateException("Host SQL did not complete");
            return Files.readString(logs.get(h));
        } catch (Exception e) { throw new RuntimeException(e); }
        finally { if (bridge.stop(h, 1000) != Integer.MIN_VALUE) owned.remove(h); }
    }
    public long beginClient(String sql) { return launch(client, clientArgs, sql); }
    public boolean clientRunning(long h) { return bridge.waitFor(h, 0) == Integer.MIN_VALUE; }
    public int cancelClient(long h) {
        int result = bridge.stop(h, 1000);
        if (result != Integer.MIN_VALUE) owned.remove(h);
        return result;
    }
    public int stopDatabase(boolean force) {
        int exit;
        if (force) exit = bridge.stop(database, 0);
        else { query("SHUTDOWN;"); exit = bridge.waitFor(database, 15000); }
        if (exit != Integer.MIN_VALUE) { owned.remove(database); database = 0; }
        return exit;
    }
    public void restartDatabase() {
        if (database != 0) throw new IllegalStateException("Database still owned");
        database = launch(server, serverArgs, null);
        long end = monotonicMs()+45000;
        while (true) {
            if (!clientRunning(database)) throw new IllegalStateException("Host database exited before readiness");
            try { if (query("SELECT 1;").trim().equals("1")) return; } catch (Exception ignored) { }
            if (monotonicMs() >= end) throw new IllegalStateException("Host readiness timed out");
            pause(150);
        }
    }
    public void stage(String value) { }
    public void checkActive() { if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Interrupted"); }
    public void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }
    public long monotonicMs() { return System.nanoTime()/1000000; }
    public boolean screenOff() { return false; } // No Android observation may be claimed on the host.
    void cleanup() { for (long h : List.copyOf(owned)) bridge.stop(h, 1000); owned.clear(); }
    public static void main(String[] args) throws Exception {
        var driver = new DatabaseLifecycleHost(args);
        try {
            driver.restartDatabase();
            if (!driver.query("SELECT VERSION();").trim().equals("12.3.2-MariaDB")) throw new IllegalStateException("Wrong host provider");
            if (args[3].equals("owner-victim")) {
                long h = driver.beginClient(DatabaseLifecycleProbe.TRANSACTION);
                long end = driver.monotonicMs()+10000;
                while (!driver.query(DatabaseLifecycleProbe.ACTIVE).trim().equals("1")) {
                    if (!driver.clientRunning(h) || driver.monotonicMs() > end) throw new IllegalStateException("Victim transaction absent");
                    driver.pause(100);
                }
                Files.writeString(driver.root.resolve("victim-ready"), Files.readString(driver.root.resolve("db.pid")));
                while (true) driver.pause(1000); // Python kills this JVM with SIGKILL; no finally runs.
            } else if (args[3].equals("recover-owner")) {
                if (!driver.query(DatabaseLifecycleProbe.CHECK).trim().equals("1") ||
                    !driver.query(DatabaseLifecycleProbe.ACTIVE).trim().equals("0") || driver.stopDatabase(false) != 0)
                    throw new IllegalStateException("Owner-death recovery failed");
                System.out.println("ownerDeathRecovery=true");
            } else {
                var observed = new LinkedHashSet<String>();
                DatabaseLifecycleProbe.INSTANCE.run(driver, flag -> { observed.add(flag); return kotlin.Unit.INSTANCE; });
                if (!observed.equals(new LinkedHashSet<>(DatabaseLifecycleProbe.INSTANCE.getRequiredFields())))
                    throw new IllegalStateException("Host lifecycle evidence differs");
                for (String flag : observed) System.out.println(flag+"=true");
            }
        } finally { driver.cleanup(); }
    }
}
