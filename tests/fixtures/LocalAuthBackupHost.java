import com.russianranger.fta.runtime.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Production archive restoration and unchanged metadata SQL/parser on a private host copy. */
public class LocalAuthBackupHost {
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[1]);
        var lock = WorldUpdaterMetadata.INSTANCE.parse(Files.readString(Path.of(args[2])));
        if (args[0].equals("restore")) {
            var archive = Path.of(args[3]).toFile();
            var manifest = ColdRealmBackup.INSTANCE.inspect(archive);
            if (!manifest.getIdentity().get("sourceTreeSha256").equals(lock.getSourceTreeSha256()) ||
                !manifest.getIdentity().get("seedSha256").equals(lock.getArchiveSha256())) throw new IllegalStateException("Backup provenance differs");
            var destination = Files.createDirectory(work.resolve("restored"));
            ColdRealmBackup.INSTANCE.restore(archive,args[4],manifest,destination.toFile(), dir -> {
                try (var channel = FileChannel.open(dir.toPath(),StandardOpenOption.READ)) { channel.force(true); }
                catch (Exception e) { throw new RuntimeException(e); }
                return kotlin.Unit.INSTANCE;
            }, () -> kotlin.Unit.INSTANCE);
            for (var schema : lock.getSchemas()) Files.writeString(work.resolve(schema.getName()+".sql"),
                WorldUpdaterEvidence.INSTANCE.schemaSql(schema,lock.getIncludeDirectories()));
            System.out.println("restoredFilesVerified=true");
        } else {
            int checks=0;
            for (var schema : lock.getSchemas()) {
                var flags=WorldUpdaterEvidence.INSTANCE.parseValidation(Files.readString(work.resolve(schema.getName()+".out")),schema);
                if (flags.containsValue(false)) throw new IllegalStateException("Strict metadata differs");
                checks+=flags.size();
            }
            if (checks!=24) throw new IllegalStateException("Incomplete metadata checks");
            System.out.println("strictMetadataChecks=24");
        }
    }
}
