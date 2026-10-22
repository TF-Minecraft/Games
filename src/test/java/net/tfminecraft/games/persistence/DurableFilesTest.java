package net.tfminecraft.games.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

class DurableFilesTest {
    @TempDir Path data;

    @Test
    void writeCreatesMissingDirectoriesAndLeavesNoPendingFiles() throws Exception {
        Path target = data.resolve("estates/tables/plan.json");
        DurableFiles.write(target, "first");
        assertEquals("first", Files.readString(target));
        DurableFiles.write(target, "second");
        assertEquals("second", Files.readString(target));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void writeIfChangedSkipsIdenticalContentOnly() throws Exception {
        Path target = data.resolve("estates/tables/plan.json");
        assertTrue(DurableFiles.writeIfChanged(target, "receipt"));
        FileTime old = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(target, old);
        assertFalse(DurableFiles.writeIfChanged(target, "receipt"));
        assertEquals(old, Files.getLastModifiedTime(target));
        assertTrue(DurableFiles.writeIfChanged(target, "receipt "));
        assertEquals("receipt ", Files.readString(target));
        assertNotEquals(old, Files.getLastModifiedTime(target));
    }

    @Test
    void deleteRemovesTheFile() throws Exception {
        Path target = data.resolve("ownership-active");
        DurableFiles.write(target, "Games is running\n");
        DurableFiles.delete(target);
        assertFalse(Files.exists(target));
    }
}
