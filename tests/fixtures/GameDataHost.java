import com.russianranger.fta.runtime.GameDataStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import kotlin.Unit;

/** Separate JVMs exercise the production store; fixtures contain no game content. */
public final class GameDataHost {
    public static void main(String[] args) throws Exception {
        File root = new File(args[1]);
        String policy = Files.readString(new File(args[2]).toPath());
        GameDataStore store = new GameDataStore(root, policy, directory -> {
            try (FileChannel c = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { c.force(true); }
            catch (Exception e) { throw new RuntimeException(e); }
            return Unit.INSTANCE;
        });
        if (args[0].equals("import") || args[0].equals("crash")) {
            try (var input = Files.newInputStream(new File(args[3]).toPath())) {
                // Kotlin's legal method name is a Java keyword; reflection crosses that syntax boundary.
                store.getClass().getMethod("import", java.io.InputStream.class, kotlin.jvm.functions.Function0.class,
                    kotlin.jvm.functions.Function3.class).invoke(store, input,
                    (kotlin.jvm.functions.Function0<Unit>) () -> Unit.INSTANCE,
                    (kotlin.jvm.functions.Function3<String, Long, Long, Unit>) (stage, done, total) -> {
                    if (args[0].equals("crash") && stage.equals("extract")) Runtime.getRuntime().halt(73);
                    return Unit.INSTANCE;
                });
            }
        } else if (args[0].equals("verify")) {
            store.verify(() -> Unit.INSTANCE, (done, total) -> Unit.INSTANCE);
        } else throw new IllegalArgumentException("Unknown test mode");
        var report = store.export();
        if (!Boolean.TRUE.equals(report.get("preflightPassed")) || !Boolean.TRUE.equals(report.get("activeCatalogMatchesReport"))
                || !Boolean.FALSE.equals(report.get("worldReady"))) throw new AssertionError(report);
        System.out.println("Production game-data store " + args[0] + " passed");
    }
}
