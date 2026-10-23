import com.russianranger.fta.runtime.*;
import java.nio.file.*;
import java.util.*;

/** Original host authserver with production Kotlin wire probe/protocol and JNI process ownership. */
public class LocalAuthHost {
    static String query(Path root, Properties p, String sql) {
        long handle=0;
        try {
            Path script=root.resolve("probe.sql"); Files.writeString(script,sql);
            String[] args=Files.readAllLines(root.resolve("client.args")).toArray(new String[0]);
            handle=NativeProcessBridge.INSTANCE.start(p.getProperty("client"),args,new String[]{"PATH=/usr/bin:/bin"},root.toString(),
                script.toString(),root.resolve("query.out").toString(),root.resolve("query.err").toString());
            if(NativeProcessBridge.INSTANCE.waitFor(handle,5000)!=0) throw new IllegalStateException("Host SQL query failed");
            return Files.readString(root.resolve("query.out")).trim();
        } catch(Exception e) { throw new RuntimeException(e); }
        finally { if(handle!=0) NativeProcessBridge.INSTANCE.stop(handle,1000); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Properties p = new Properties();
        try (var input = Files.newInputStream(root.resolve("host-session.properties"))) { p.load(input); }
        boolean staleFlagCleared=LocalRealmAuthState.INSTANCE.prepare(sql -> query(root,p,sql));
        int port = LocalAuthProbe.INSTANCE.unusedPort();
        String name = "FTA0123456789ABC";
        Path temp = Files.createDirectory(root.resolve("auth-temp"));
        String config = LocalAuthProbe.INSTANCE.config(new java.io.File("/host-socket"), new java.io.File(p.getProperty("client")),
            root.resolve("seed/Data").toFile(), temp.toFile(), "fta_local_host", p.getProperty("password"), port);
        // This host runner cannot bind Unix sockets. Only its database transport differs from Android.
        config = config.replace(".;/host-socket;fta_local_host;", "127.0.0.1;" + p.getProperty("databasePort") + ";fta_local_host;");
        Path conf = root.resolve("auth.conf"); Files.writeString(conf, config);
        var flags = new LinkedHashMap<String,Boolean>();
        LocalAuthProtocol.INSTANCE.run(new LocalAuthProtocol.Driver() {
            long handle; int pass; int query;
            public void checkActive() { }
            public void start(int value) {
                pass = value;
                if (!LocalAuthProbe.INSTANCE.closed(port)) throw new IllegalStateException("Port already occupied");
                handle = NativeProcessBridge.INSTANCE.start(p.getProperty("auth"), new String[]{"--config", conf.toString()},
                    new String[]{"PATH=/usr/bin:/bin", "LD_LIBRARY_PATH=" + p.getProperty("libraries"), "MYSQL_HISTFILE=/dev/null"},
                    root.toString(), null, root.resolve("auth"+pass+".out").toString(), root.resolve("auth"+pass+".err").toString());
                long end = System.nanoTime() + 45_000_000_000L;
                while (System.nanoTime() < end) {
                    if (NativeProcessBridge.INSTANCE.waitFor(handle, 0) != Integer.MIN_VALUE) throw new IllegalStateException("Auth exited before readiness");
                    try { LocalAuthProbe.INSTANCE.exchange(port,name); return; } catch (Exception e) { }
                    try { Thread.sleep(150); } catch (InterruptedException e) { throw new RuntimeException(e); }
                }
                throw new IllegalStateException("Auth readiness deadline");
            }
            public void probe() {
                if (NativeProcessBridge.INSTANCE.waitFor(handle,0) != Integer.MIN_VALUE) throw new IllegalStateException("Auth died");
                LocalAuthProbe.INSTANCE.exchange(port,name);
            }
            public void hold() {
                try {
                    for (int n=0;n<10;n++) {
                        Thread.sleep(2000); probe(); query++;
                        Path sql = root.resolve("probe.sql"); Files.writeString(sql,"SELECT 1;\n");
                        String[] clientArgs = Files.readAllLines(root.resolve("client.args")).toArray(new String[0]);
                        long client = NativeProcessBridge.INSTANCE.start(p.getProperty("client"), clientArgs, new String[]{"PATH=/usr/bin:/bin"},
                            root.toString(),sql.toString(),root.resolve("query.out").toString(),root.resolve("query.err").toString());
                        try {
                            if (NativeProcessBridge.INSTANCE.waitFor(client,5000)!=0 || !Files.readString(root.resolve("query.out")).trim().equals("1"))
                                throw new IllegalStateException("Database health failed");
                        } finally { NativeProcessBridge.INSTANCE.stop(client,1000); }
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            public int stop() {
                if (handle==0) return 0;
                int exit = NativeProcessBridge.INSTANCE.stop(handle,10000); handle=0; return exit;
            }
            public boolean listenerClosed() { return LocalAuthProbe.INSTANCE.closed(port); }
        }, (key,value) -> { flags.put(key,value); return kotlin.Unit.INSTANCE; });
        if (flags.size()!=8 || flags.containsValue(false)) throw new IllegalStateException("Incomplete protocol");
        Files.delete(conf);
        System.out.println("originalAuthProtocolAndRestartsPassed=true");
        System.out.println("authCleanStops=2"); System.out.println("databaseHealthChecks=20");
        System.out.println("staleStartupFlagCleared="+staleFlagCleared);
    }
}
