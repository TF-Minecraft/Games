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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Publish whole, flushed files before money or inventory changes. */
public final class DurableFiles {
    /** Flushes one directory; tests swap it to count or fail flushes. */
    interface Syncer {
        void sync(Path directory) throws IOException;
    }

    static Syncer syncer = DurableFiles::force;

    // Directories whose whole ancestor chain this process has flushed. Until then every write
    // flushes all ancestors, which covers folders made by mkdirs elsewhere or left behind by a
    // write whose ancestor flush failed. A folder this class or Games creates again is
    // forgotten (see forget), so a recreated folder is flushed in full; data folders are not
    // deleted by anyone else while the server runs.
    private static final Set<Path> durableDirectories = ConcurrentHashMap.newKeySet();

    private DurableFiles() {}

    public static void write(Path target, String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path target, byte[] bytes) throws IOException {
        write(target, bytes, true);
    }

    /**
     * Like {@link #write(Path, String)}, but replacing a file that already exists does not flush
     * its directory. The new bytes are still flushed before the rename, so the file is always
     * whole: until the rename reaches disk (the filesystem's next journal commit, which any fsync
     * on it or ext4's five-second commit forces), an operating-system crash or power loss brings
     * back the previous complete file. A server crash loses nothing. A file that does not exist
     * yet is flushed in full, as {@code write} does.
     *
     * <p>For files rewritten on every money movement whose loss to the previous version is safe;
     * anything that acknowledges a transfer elsewhere (estates, migrations, journals) keeps
     * {@code write}.
     */
    public static void writeReplacing(Path target, String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8), false);
    }

    /**
     * Flush a directory now, so every rename and delete made in it so far is on disk before
     * anything that depends on them. Pairs with {@link #writeReplacing} when a later step (such
     * as deleting a journal) must not reach disk before the replacement does.
     */
    public static void flushDirectory(Path directory) throws IOException {
        syncer.sync(directory.toAbsolutePath().normalize());
    }

    private static void write(Path target, byte[] bytes, boolean flushReplace) throws IOException {
        target = target.toAbsolutePath();
        boolean replacing = !flushReplace && Files.isRegularFile(target);
        Path parent = target.getParent();
        Path created = firstMissing(parent);
        if (created != null) forget(created);
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".pending-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!replacing) flushEntry(parent);
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
            flushEntry(target.toAbsolutePath().getParent());
            return false;
        }
        write(target, bytes);
        return true;
    }

    public static void delete(Path target) throws IOException {
        Files.deleteIfExists(target);
        syncer.sync(target.toAbsolutePath().getParent());
    }

    /**
     * Delete without flushing the directory: the removal reaches disk with the filesystem's next
     * journal commit, and until then an operating-system crash or power loss can bring the file
     * back. Only for files whose return is safe, such as a finished wager intent, which then
     * freezes its table for review.
     */
    public static void deleteUnflushed(Path target) throws IOException {
        Files.deleteIfExists(target);
    }

    /** Flush a file's directory entry, and its ancestors the first time this process sees it. */
    private static void flushEntry(Path parent) throws IOException {
        parent = parent.normalize();
        syncer.sync(parent);
        if (durableDirectories.contains(parent)) return;
        for (Path directory = parent.getParent(); directory != null; directory = directory.getParent())
            syncer.sync(directory);
        durableDirectories.add(parent);
    }

    /**
     * Forget a folder that was just created, and every folder under it, so the next write
     * there flushes its whole ancestor chain again. Call after mkdirs on a folder that may
     * have existed before.
     */
    public static void forget(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        durableDirectories.removeIf(known -> known.startsWith(root));
    }

    /** The highest ancestor of {@code directory} (or itself) that does not exist yet, or null. */
    private static Path firstMissing(Path directory) {
        Path missing = null;
        for (Path path = directory; path != null && !Files.exists(path); path = path.getParent()) missing = path;
        return missing;
    }

    /** Test hook: forget which directories were flushed. */
    static void forgetDurableDirectories() {
        durableDirectories.clear();
    }

    private static void force(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
