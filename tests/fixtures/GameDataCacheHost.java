import com.russianranger.fta.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exercises the actual JNI entry points on Linux; does not qualify Android execution. */
public final class GameDataCacheHost {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        File file = root.resolve("bytes").toFile(); byte[] bytes = new byte[65537];
        new Random(42).nextBytes(bytes);
        try (var output = new FileOutputStream(file)) { output.write(bytes); output.getFD().sync(); }
        var nativeIo = GameDataCacheNative.INSTANCE;
        if (nativeIo.releasePath(file, 0, bytes.length) != 0) throw new AssertionError("JNI path advice rejected");
        try (var input = new FileInputStream(file)) {
            var field = FileDescriptor.class.getDeclaredField("fd"); field.setAccessible(true);
            int fd = field.getInt(input.getFD());
            if (input.read() != Byte.toUnsignedInt(bytes[0])) throw new AssertionError();
            if (nativeIo.releaseDescriptor(fd, 0, bytes.length) != 0) throw new AssertionError("JNI descriptor advice rejected");
            if (input.read() != Byte.toUnsignedInt(bytes[1])) throw new AssertionError("Descriptor position changed or closed");
        }
        if (!Arrays.equals(bytes, Files.readAllBytes(file.toPath()))) throw new AssertionError("Bytes changed");
        if (nativeIo.releaseDescriptor(-1, 0, 1) == 0 || nativeIo.releasePath(file, 0, 0) == 0 ||
            nativeIo.releasePath(file, -1, 1) == 0 || nativeIo.releasePath(file, Long.MAX_VALUE, 1) == 0 ||
            nativeIo.releasePath(root.toFile(), 0, 1) == 0 || nativeIo.releasePath(root.resolve("missing").toFile(), 0, 1) == 0)
            throw new AssertionError("Unsafe/non-file request accepted");
        Path link = root.resolve("link"); Files.createSymbolicLink(link, file.toPath());
        if (nativeIo.releasePath(link.toFile(), 0, 1) == 0) throw new AssertionError("Followed symlink");
        try (var sparse = new RandomAccessFile(root.resolve("large-offset").toFile(), "rw")) {
            long at = 3L * 1024 * 1024 * 1024;
            sparse.seek(at); sparse.write(73); sparse.getFD().sync();
            if (nativeIo.releasePath(root.resolve("large-offset").toFile(), at, 1) != 0) throw new AssertionError("Offset truncated");
            sparse.seek(at); if (sparse.read() != 73) throw new AssertionError("Sparse bytes changed");
        }
        System.out.println("JNI path/descriptor, >3GiB offset, unchanged bytes/position, invalid range and no-follow checks passed");
    }
}
