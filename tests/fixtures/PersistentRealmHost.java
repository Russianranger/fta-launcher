import com.russianranger.fta.runtime.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Production generation/activation protocol and SQL with real host database restarts. */
public class PersistentRealmHost {
    static void copyTemplate(Path template, Path root) throws Exception {
        try (var paths = Files.walk(template.resolve("data"))) {
            for (Path source : paths.toList()) {
                Path target = root.resolve(template.relativize(source));
                if (Files.isDirectory(source)) Files.createDirectory(target); else Files.copy(source, target);
            }
        }
        for (String name : List.of("server.args", "client.args", "client.cnf", "init.sql"))
            Files.writeString(root.resolve(name), Files.readString(template.resolve(name)).replace(template.toString(), root.toString()));
    }
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]), template = Path.of(args[1]);
        Map<String,String> identity = new HashMap<>();
        for (String field : PersistentRealmStore.Companion.getIDENTITY_FIELDS()) identity.put(field, "a".repeat(64));
        var store = new PersistentRealmStore(base.toFile(), identity, dir -> {
            try (var channel = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) { channel.force(true); }
            catch (Exception e) { throw new RuntimeException(e); }
            return kotlin.Unit.INSTANCE;
        });
        var owner = RuntimeRecoveryHost.self();
        var observed = new LinkedHashMap<String, Boolean>();
        int[] counts = {0,0};
        RealmStorageProtocol.INSTANCE.run(store, new RealmStorageProtocol.Driver() {
            void database(RealmGeneration realm, boolean initialize) {
                DatabaseLifecycleHost driver = null;
                boolean recorded = false;
                try {
                    Path root = realm.getDirectory().toPath();
                    if (initialize) copyTemplate(template, root);
                    driver = new DatabaseLifecycleHost(new String[]{root.toString(), args[2], args[3]});
                    store.serverLaunching(owner);
                    driver.restartDatabase();
                    store.serverStarted(owner, RuntimeRecoveryHost.child(driver.database, owner)); recorded = true;
                    if (!driver.query("SELECT VERSION();").trim().equals("12.3.2-MariaDB")) throw new IllegalStateException("Wrong provider");
                    if (initialize) {
                        RealmStorageCanary.INSTANCE.create(realm.getId(), driver::query);
                        driver.query("CREATE DATABASE host_retained; CREATE TABLE host_retained.progress(id INT PRIMARY KEY, value INT) ENGINE=InnoDB; INSERT INTO host_retained.progress VALUES(1,42);");
                    } else {
                        RealmStorageCanary.INSTANCE.verify(realm.getId(), driver::query);
                        if (!driver.query("SELECT value FROM host_retained.progress WHERE id=1;").trim().equals("42")) throw new IllegalStateException("Retained row changed");
                    }
                    if (driver.stopDatabase(false) != 0) throw new IllegalStateException("Unclean persistent stop");
                    store.serverStopped(owner); recorded = false;
                } catch (Exception e) { throw new RuntimeException(e); }
                finally {
                    if (driver != null) driver.cleanup();
                    if (recorded) store.serverStopped(owner);
                }
            }
            public void prepare(RealmGeneration realm) {
                if (args[4].equals("existing")) throw new IllegalStateException("Existing realm tried to import again");
                counts[0]++; database(realm, true);
            }
            public void verify(RealmGeneration realm, int pass) { counts[1]++; database(realm, false); }
            public void checkActive() { }
        }, (key, value) -> { observed.put(key,value); return kotlin.Unit.INSTANCE; });
        int expected = args[4].equals("existing") ? 0 : 1;
        if (counts[0] != expected || counts[1] != 2 || observed.get("seedImportNeeded") != (expected == 1)) throw new IllegalStateException("Unexpected import/reopen counts");
        for (String flag : RealmStorageProtocol.INSTANCE.getFields()) if (!observed.getOrDefault(flag, false)) throw new IllegalStateException("Incomplete storage proof");
        System.out.println("prepared="+counts[0]); System.out.println("reopened="+counts[1]); System.out.println("retainedRow=true");
    }
}
