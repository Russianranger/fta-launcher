import com.russianranger.fta.runtime.LocalWorldProbe;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real production probe against a host fixture, never a game-login claim. */
public final class LocalWorldProbeHost {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("packet")) {
            LocalWorldProbe.INSTANCE.validateChallenge(Files.readAllBytes(Path.of(args[1])));
        } else if (args.length == 2 && args[0].equals("socket")) {
            LocalWorldProbe.INSTANCE.exchange(Integer.parseInt(args[1]));
        } else throw new IllegalArgumentException("Unknown probe mode");
        System.out.println("worldChallengeProbePassed=true");
    }
}
