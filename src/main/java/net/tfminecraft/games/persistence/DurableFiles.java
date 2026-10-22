package net.tfminecraft.games.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Publish whole, flushed files before money or inventory changes. */
public final class DurableFiles {
    private DurableFiles() {}

    public static void write(Path target, String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path target, byte[] bytes) throws IOException {
        target = target.toAbsolutePath();
        Path parent = target.getParent();
        // Only directories this write creates need their entries flushed in their own parents.
        Path created = firstMissing(parent);
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".pending-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            sync(parent);
            if (created != null)
                for (Path directory = parent; !directory.equals(created.getParent()); directory = directory.getParent())
                    sync(directory.getParent());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Publish only when the file does not already hold exactly these bytes. Identical content
     * still gets its directory entry flushed, in case an earlier write moved it into place but
     * failed before that flush; on a clean directory this costs next to nothing.
     */
    public static boolean writeIfChanged(Path target, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
            sync(target.toAbsolutePath().getParent());
            return false;
        }
        write(target, bytes);
        return true;
    }

    public static void delete(Path target) throws IOException {
        Files.deleteIfExists(target);
        sync(target.getParent());
    }

    /** The highest ancestor of {@code directory} (or itself) that does not exist yet, or null. */
    private static Path firstMissing(Path directory) {
        Path missing = null;
        for (Path path = directory; path != null && !Files.exists(path); path = path.getParent()) missing = path;
        return missing;
    }

    private static void sync(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
