import com.russianranger.fta.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import kotlin.Unit;

/** Real process exits at asset-import boundaries; contains only synthetic data. */
public final class GameDataCheckpointHost {
    private static final String ATTEMPT = "12345678-1234-4234-8234-123456789abc";
    public static void main(String[] args) throws Exception {
        String mode = args[0]; File root = new File(args[1]);
        GameDataOperation journal = new GameDataOperation(new File(root, "operation.properties"));
        if (mode.equals("inspect") || mode.equals("inspect-copy-bytes")) {
            var evidence = journal.export(ATTEMPT, false);
            String expectedStage = mode.equals("inspect") ? args[4] : "copy";
            if (!"INTERRUPTED".equals(evidence.get("state")) || !expectedStage.equals(evidence.get("stage"))) throw new AssertionError(evidence);
            if (!Set.of("copy", "copy_sync").contains(expectedStage) && !evidence.containsKey("archiveSha256")) throw new AssertionError(evidence);
            if (mode.equals("inspect-copy-bytes") && !Long.valueOf(args[4]).equals(evidence.get("copiedBytes"))) throw new AssertionError(evidence);
            System.out.println("Interrupted checkpoint " + args[4] + " preserved"); return;
        }
        // Some hosted test environments expose /proc through a different PID namespace.
        // Read both identity fields from that same observation; never signal a supplied PID.
        String stat = Files.readString(Path.of("/proc/self/stat"));
        var owner = ProcessObservation.INSTANCE.parse(stat, Integer.parseInt(stat.substring(0, stat.indexOf(' ')))).getIdentity();
        long started = System.currentTimeMillis(); long[] copied = {0}; String[] hash = {null};
        GameDataStore store = new GameDataStore(root, Files.readString(Path.of(args[2])), directory -> {
            try (var c = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { c.force(true); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
            return Unit.INSTANCE;
        });
        try (var input = Files.newInputStream(Path.of(args[3]))) {
            store.getClass().getMethod("import", InputStream.class, kotlin.jvm.functions.Function0.class,
                kotlin.jvm.functions.Function3.class, kotlin.jvm.functions.Function2.class).invoke(store, input,
                (kotlin.jvm.functions.Function0<Unit>) () -> Unit.INSTANCE,
                (kotlin.jvm.functions.Function3<String, Long, Long, Unit>) (stage, done, total) -> {
                    if (stage.equals("copy")) copied[0] = done;
                    if (mode.equals("halt-copy-bytes") && stage.equals("copy") && done >= Long.parseLong(args[4])) {
                        var vm = Runtime.getRuntime();
                        journal.write(new GameDataOperation.Record(ATTEMPT, owner, "import", started, System.currentTimeMillis(),
                            "RUNNING", "copy", done, 0, done, null, root.getUsableSpace(),
                            vm.totalMemory() - vm.freeMemory(), vm.maxMemory(), null, List.of(), null));
                        Runtime.getRuntime().halt(73);
                    }
                    return Unit.INSTANCE;
                }, (kotlin.jvm.functions.Function2<String, String, Unit>) (stage, archive) -> {
                    hash[0] = archive;
                    var vm = Runtime.getRuntime();
                    journal.write(new GameDataOperation.Record(ATTEMPT, owner, "import", started, System.currentTimeMillis(),
                        "RUNNING", stage, 0, 0, copied[0], archive, root.getUsableSpace(),
                        vm.totalMemory() - vm.freeMemory(), vm.maxMemory(), null, List.of(), null));
                    if (mode.equals("halt-" + stage)) Runtime.getRuntime().halt(73);
                    return Unit.INSTANCE;
                });
        }
        if (mode.equals("halt-after-publication")) Runtime.getRuntime().halt(73);
        throw new AssertionError("Requested halt boundary was not reached");
    }
}
