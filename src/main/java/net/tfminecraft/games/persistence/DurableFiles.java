package net.tfminecraft.games.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Publish whole, flushed files before money or inventory changes. */
public final class DurableFiles {
    private DurableFiles() {}

    public static void write(Path target, String text) throws IOException {
        write(target, text.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            sync(target.getParent());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void delete(Path target) throws IOException {
        Files.deleteIfExists(target);
        sync(target.getParent());
    }

    private static void sync(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
