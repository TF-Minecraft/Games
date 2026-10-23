package net.tfminecraft.games.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

class DurableFilesTest {
    @TempDir Path data;
    private final List<Path> flushed = new ArrayList<>();
    private Path failOnce;
    private DurableFiles.Syncer original;

    @BeforeEach
    void countFlushes() {
        original = DurableFiles.syncer;
        DurableFiles.forgetDurableDirectories();
        DurableFiles.syncer = directory -> {
            if (directory.equals(failOnce)) {
                failOnce = null;
                throw new IOException("flush failed");
            }
            flushed.add(directory);
        };
    }

    @AfterEach
    void restoreFlushes() {
        DurableFiles.syncer = original;
        DurableFiles.forgetDurableDirectories();
    }

    private Path tables() {
        return data.toAbsolutePath().resolve("estates/tables");
    }

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
    void firstWriteFlushesEveryAncestorAndLaterWritesOnlyTheParent() throws Exception {
        DurableFiles.write(tables().resolve("a.json"), "a");
        assertEquals(tables(), flushed.get(0));
        assertTrue(flushed.contains(data.toAbsolutePath()));
        assertTrue(flushed.contains(tables().getRoot()));
        flushed.clear();
        DurableFiles.write(tables().resolve("b.json"), "b");
        assertEquals(List.of(tables()), flushed);
    }

    @Test
    void folderMadeElsewhereStillGetsItsAncestorsFlushed() throws Exception {
        // Games.createFolders() and TableManager use mkdirs before their first durable write.
        Files.createDirectories(tables());
        DurableFiles.write(tables().resolve("a.json"), "a");
        assertTrue(flushed.contains(tables().getParent()));
        assertTrue(flushed.contains(data.toAbsolutePath()));
    }

    @Test
    void failedAncestorFlushIsRetriedEvenForAnUnchangedFile() throws Exception {
        Path target = tables().resolve("plan.json");
        failOnce = tables().getParent();
        assertThrows(IOException.class, () -> DurableFiles.writeIfChanged(target, "receipt"));
        assertEquals("receipt", Files.readString(target));
        flushed.clear();
        assertFalse(DurableFiles.writeIfChanged(target, "receipt"));
        assertTrue(flushed.contains(tables()));
        assertTrue(flushed.contains(tables().getParent()));
        flushed.clear();
        assertFalse(DurableFiles.writeIfChanged(target, "receipt"));
        assertEquals(List.of(tables()), flushed);
    }

    @Test
    void folderRecreatedByAWriteIsFlushedInFull() throws Exception {
        Path target = tables().resolve("plan.json");
        DurableFiles.write(target, "a");
        Files.delete(target);
        Files.delete(tables());
        flushed.clear();
        DurableFiles.write(target, "b");
        assertTrue(flushed.contains(tables().getParent()));
        assertTrue(flushed.contains(data.toAbsolutePath()));
    }

    @Test
    void folderRecreatedByMkdirsIsFlushedInFullOnceForgotten() throws Exception {
        Path target = tables().resolve("plan.json");
        DurableFiles.write(target, "a");
        Files.delete(target);
        Files.delete(tables());
        Files.createDirectories(tables());
        DurableFiles.forget(tables().getParent());
        flushed.clear();
        DurableFiles.write(target, "b");
        assertTrue(flushed.contains(tables().getParent()));
        flushed.clear();
        DurableFiles.write(target, "c");
        assertEquals(List.of(tables()), flushed);
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
    void deleteRemovesTheFileAndFlushesItsFolder() throws Exception {
        Path target = data.resolve("ownership-active");
        DurableFiles.write(target, "Games is running\n");
        flushed.clear();
        DurableFiles.delete(target);
        assertFalse(Files.exists(target));
        assertEquals(List.of(data.toAbsolutePath()), flushed);
    }
}
