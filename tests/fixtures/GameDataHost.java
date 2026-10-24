import com.russianranger.fta.runtime.*;
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
        GameDataCache cache = Boolean.getBoolean("fta.cacheAdvice") ? new GameDataCache() : null;
        GameDataStore store = new GameDataStore(root, policy, directory -> {
            try (FileChannel c = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { c.force(true); }
            catch (Exception e) { throw new RuntimeException(e); }
            return Unit.INSTANCE;
        }, cache);
        if (args[0].equals("import") || args[0].equals("crash")) {
            try (var input = Files.newInputStream(new File(args[3]).toPath())) {
                // Kotlin's legal method name is a Java keyword; reflection crosses that syntax boundary.
                store.getClass().getMethod("import", java.io.InputStream.class, kotlin.jvm.functions.Function0.class,
                    kotlin.jvm.functions.Function3.class, kotlin.jvm.functions.Function2.class).invoke(store, cache == null ? input : cache.input(input, (at, length) ->
                    GameDataCacheNative.INSTANCE.releasePath(new File(args[3]), at, length)),
                    (kotlin.jvm.functions.Function0<Unit>) () -> Unit.INSTANCE,
                    (kotlin.jvm.functions.Function3<String, Long, Long, Unit>) (stage, done, total) -> {
                    if (args[0].equals("crash") && stage.equals("extract")) Runtime.getRuntime().halt(73);
                    return Unit.INSTANCE;
                }, (kotlin.jvm.functions.Function2<String, String, Unit>) (stage, archiveSha) -> Unit.INSTANCE);
            }
        } else if (args[0].equals("verify")) {
            store.verify(() -> Unit.INSTANCE, (done, total) -> Unit.INSTANCE);
        } else throw new IllegalArgumentException("Unknown test mode");
        var report = store.export();
        if (!Boolean.TRUE.equals(report.get("preflightPassed")) || !Boolean.TRUE.equals(report.get("activeCatalogMatchesReport"))
                || !Boolean.FALSE.equals(report.get("worldReady"))) throw new AssertionError(report);
        if (args.length > 4 && !Long.valueOf(args[4]).equals(report.get("db2CompanionFiles"))) throw new AssertionError(report);
        if (args.length > 5 && !Long.valueOf(args[5]).equals(report.get("editorCompanionFiles"))) throw new AssertionError(report);
        if (args.length > 6) {
            if (!Boolean.TRUE.equals(report.get("databaseFallbackRequired"))
                    || !Boolean.FALSE.equals(report.get("databaseFallbackValidated"))
                    || !Boolean.FALSE.equals(report.get("nativeSemanticValidationPassed"))) throw new AssertionError(report);
            var uses = (java.util.List<?>) report.get("dbcFileCompatibility");
            if (uses.size() != Integer.parseInt(args[6])) throw new AssertionError(report);
            long sql = uses.stream().filter(use -> "original_database_fallback".equals(((java.util.Map<?,?>)use).get("handling"))).count();
            if (sql != 10) throw new AssertionError(report);
        }
        if (cache != null) {
            if (cache.stats().getAccepted() == 0 || cache.stats().getRejected() != 0) throw new AssertionError(cache.stats());
            System.out.println("Cache advice: " + cache.stats());
        }
        System.out.println("Production game-data store " + args[0] + " passed");
    }
}
