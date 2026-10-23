import com.russianranger.fta.runtime.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Production archive/restore/protocol with real synthetic host MariaDB files and JNI ownership. */
public class RealmBackupHost {
    static Path template;
    static String server, client;
    static ProcessIdentity owner;
    static int starts;
    static final Map<String,String> identity = new HashMap<>();
    static void sync(java.io.File dir) {
        try (var channel = FileChannel.open(dir.toPath(), StandardOpenOption.READ)) { channel.force(true); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    static PersistentRealmStore store(Path base) {
        return new PersistentRealmStore(base.toFile(), identity, dir -> { sync(dir); return kotlin.Unit.INSTANCE; });
    }
    static void configure(RealmGeneration realm, boolean initial) throws Exception {
        Path root = realm.getDirectory().toPath();
        if (initial) {
            try (var paths = Files.walk(template.resolve("data"))) {
                for (Path source : paths.toList()) {
                    Path target = root.resolve("database").resolve(template.resolve("data").relativize(source));
                    if (Files.isDirectory(source)) Files.createDirectory(target); else Files.copy(source, target);
                }
            }
            Files.copy(template.resolve("client.cnf"), root.resolve("client.cnf"));
            Files.copy(template.resolve("init.sql"), root.resolve("init.sql"));
        }
        for (String name : List.of("server.args", "client.args")) {
            String text = Files.readString(template.resolve(name)).replace(template.resolve("data").toString(), root.resolve("database").toString())
                .replace(template.toString(), root.toString());
            if (!initial && name.equals("server.args")) text = text.replaceAll("(?m)^--init-file=.*\\n", "");
            Files.writeString(root.resolve(name), text);
        }
    }
    static void database(PersistentRealmStore store, RealmGeneration realm, boolean initial) {
        DatabaseLifecycleHost driver = null; boolean recorded = false;
        try {
            configure(realm, initial);
            driver = new DatabaseLifecycleHost(new String[]{realm.getDirectory().toString(), server, client});
            store.serverLaunching(owner); driver.restartDatabase();
            store.serverStarted(owner, RuntimeRecoveryHost.child(driver.database, owner)); recorded = true; starts++;
            if (!driver.query("SELECT VERSION();").trim().equals("12.3.2-MariaDB")) throw new IllegalStateException("Wrong provider");
            if (initial) {
                RealmStorageCanary.INSTANCE.create(realm.getId(), driver::query);
                driver.query("CREATE DATABASE host_retained; CREATE TABLE host_retained.progress(id INT PRIMARY KEY, value INT, payload BLOB) ENGINE=InnoDB; INSERT INTO host_retained.progress VALUES(1,42,UNHEX('00ff010203'));");
            }
            RealmStorageCanary.INSTANCE.verify(realm.getId(), driver::query);
            if (!driver.query("SELECT CONCAT(id,':',value,':',HEX(payload)) FROM host_retained.progress;").trim().equals("1:42:00FF010203"))
                throw new IllegalStateException("Restored data differs");
            if (driver.stopDatabase(false) != 0) throw new IllegalStateException("Unclean shutdown");
            store.serverStopped(owner); recorded = false;
        } catch (Exception e) { throw new RuntimeException(e); }
        finally { if (driver != null) driver.cleanup(); if (recorded) store.serverStopped(owner); }
    }
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]); template = Path.of(args[1]); server = args[2]; client = args[3];
        owner = RuntimeRecoveryHost.self();
        for (String field : PersistentRealmStore.Companion.getIDENTITY_FIELDS()) identity.put(field,"a".repeat(64));
        var active = store(base.resolve("active"));
        if (args[4].equals("existing")) {
            active.exclusive(() -> { database(active, active.active(), false); return kotlin.Unit.INSTANCE; });
            System.out.println("activeReopenedAfterBackup=true"); return;
        }
        Files.createDirectories(base);
        RealmStorageProtocol.INSTANCE.run(active, new RealmStorageProtocol.Driver() {
            public void checkActive() { }
            public void prepare(RealmGeneration realm) { database(active, realm, true); }
            public void verify(RealmGeneration realm, int pass) { database(active, realm, false); }
        }, (k,v) -> kotlin.Unit.INSTANCE);
        var flags = new HashMap<String,Boolean>();
        var result = RealmBackupProtocol.INSTANCE.run(active, identity, base.resolve("attempt").toFile(), new RealmBackupProtocol.Driver() {
            public void checkActive() { }
            public void syncDirectory(java.io.File directory) { sync(directory); }
            public void verifyOriginal(RealmGeneration realm) { database(active, realm, false); }
            public PersistentRealmStore restoredStore(java.io.File directory) { return store(directory.toPath()); }
            public void stageRestored(RealmGeneration realm) { }
            public void verifyRestored(PersistentRealmStore restored, RealmGeneration realm, int pass) { database(restored, realm, false); }
        }, (k,v) -> { flags.put(k,v); return kotlin.Unit.INSTANCE; });
        for (String flag : RealmBackupProtocol.INSTANCE.getFields()) if (!flags.getOrDefault(flag,false)) throw new IllegalStateException("Missing proof "+flag);
        if (starts != 6 || !result.getArchive().isFile() || Files.exists(base.resolve("attempt/restore"))) throw new IllegalStateException("Incomplete backup lifecycle");
        System.out.println("cleanSqlShutdowns=6"); System.out.println("restoredReopens=2");
        System.out.println("binaryRowsRetained=true"); System.out.println("activeFilesAndPointerUnchanged=true");
    }
}
