package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import net.tfminecraft.games.persistence.DurableFiles;

/**
 * Card changes move no money, so they wait for the next flush; money saves still write the
 * whole table at once, and deferred loading does not read loaded tables' files again.
 */
class TableManagerSaveBatchingTest extends TableManagerHandFixture {

    private Path file(Table table) {
        return data.resolve("Data/tables/" + table.getId() + ".json");
    }

    private JsonObject read(Table table) throws Exception {
        return JsonParser.parseString(Files.readString(file(table))).getAsJsonObject();
    }

    private int remainingOnDisk(Table table) throws Exception {
        return read(table).getAsJsonArray("remaining").size();
    }

    private MockedStatic<DurableFiles> countWrites() {
        return mockStatic(DurableFiles.class, CALLS_REAL_METHODS);
    }

    private static void verifyWrites(MockedStatic<DurableFiles> writes, Table table, int times) {
        writes.verify(() -> DurableFiles.write(
                argThat((Path path) -> path != null && path.getFileName().toString().equals(table.getId() + ".json")),
                anyString()), times(times));
    }

    @Test void aDealtCardWaitsForTheFlushAndIsThenWrittenOnce() throws Exception {
        Table table = placeAndClearMessage();
        assertEquals(2, remainingOnDisk(table));
        try (var writes = countWrites()) {
            manager.dealToPlayer(table, player, 1);
            tick(5);
            assertEquals(1, table.handOf(player.getUniqueId()).size());
            assertEquals(2, remainingOnDisk(table), "the draw is not written straight away");
            verifyWrites(writes, table, 0);
            manager.flushUnsaved();
            assertEquals(1, remainingOnDisk(table));
            verifyWrites(writes, table, 1);
            manager.flushUnsaved();
            verifyWrites(writes, table, 1);
        }
    }

    @Test void theHandClockWritesWaitingChangesWithinASecond() throws Exception {
        Table table = placeAndClearMessage();
        manager.dealToPlayer(table, player, 1);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(2, remainingOnDisk(table));
        manager.startClock();
        try {
            // Any 20 ticks of the clock include one flush.
            tick(20);
            assertEquals(1, remainingOnDisk(table));
        } finally {
            manager.stopClock();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"moneyMoved", "checkpoint"})
    void aMoneySaveWritesWaitingCardChangesAndClearsTheMark(String save) throws Exception {
        Table table = placeAndClearMessage();
        try (var writes = countWrites()) {
            manager.dealToPlayer(table, player, 1);
            tick(5);
            if (save.equals("moneyMoved")) manager.moneyMoved(table, List.of());
            else manager.checkpoint(table);
            assertEquals(1, remainingOnDisk(table), "a money save writes the whole table");
            verifyWrites(writes, table, 1);
            manager.flushUnsaved();
            verifyWrites(writes, table, 1);
        }
    }

    @Test void aFullReshuffleIsOnlyOnDiskAfterTheFlush() throws Exception {
        Table table = placeAndClearMessage();
        table.getDeck().discard(table.getDeck().draw().orElseThrow());
        manager.checkpoint(table);
        assertEquals(1, read(table).getAsJsonArray("discarded").size());
        try (var writes = countWrites()) {
            manager.reshuffleFull(table);
            assertEquals(1, read(table).getAsJsonArray("discarded").size());
            verifyWrites(writes, table, 0);
            manager.flushUnsaved();
            verifyWrites(writes, table, 1);
        }
        assertEquals(0, read(table).getAsJsonArray("discarded").size());
        assertEquals(2, remainingOnDisk(table));
    }

    @Test void wipingHandsIsOnlyOnDiskAfterTheFlush() throws Exception {
        Table table = dealt(1);
        manager.flushUnsaved();
        assertEquals(1, remainingOnDisk(table));
        try (var writes = countWrites()) {
            manager.wipeHands();
            assertTrue(table.getHands().isEmpty());
            assertEquals(1, remainingOnDisk(table));
            verifyWrites(writes, table, 0);
            manager.flushUnsaved();
            verifyWrites(writes, table, 1);
        }
        assertEquals(2, remainingOnDisk(table));
    }

    private static void failWrites(MockedStatic<DurableFiles> writes, Table table) {
        writes.when(() -> DurableFiles.write(
                argThat((Path path) -> path != null && path.getFileName().toString().equals(table.getId() + ".json")),
                anyString())).thenThrow(new java.io.IOException("disk full"));
    }

    @Test void aFailedFlushKeepsTheChangeWaitingAndRetriesItWithOneWarning() throws Exception {
        Table table = placeAndClearMessage();
        manager.dealToPlayer(table, player, 1);
        tick(5);
        var logger = net.tfminecraft.games.Games.plugin.getLogger();
        clearInvocations(logger);
        try (var writes = countWrites()) {
            failWrites(writes, table);
            manager.flushUnsaved();
            manager.flushUnsaved();
            verifyWrites(writes, table, 2);
        }
        assertTrue(table.ownershipHeld(), "a failed write still holds the table");
        assertEquals(2, remainingOnDisk(table), "nothing reached the disk");
        verify(logger, times(1)).warning(contains("Failed to save table " + table.getId()));
        manager.flushUnsaved();
        assertEquals(1, remainingOnDisk(table), "the waiting change is written once the disk works again");
        verify(logger).info(contains("saved again"));
        try (var writes = countWrites()) {
            manager.flushUnsaved();
            verifyWrites(writes, table, 0);
        }
    }

    @Test void aFailedWagerSaveLeavesTheWaitingChangeForTheNextFlush() throws Exception {
        Table table = placeAndClearMessage();
        manager.dealToPlayer(table, player, 1);
        tick(5);
        try (var writes = countWrites()) {
            failWrites(writes, table);
            assertThrows(java.io.IOException.class, () -> manager.saveWager(table));
        }
        assertEquals(2, remainingOnDisk(table));
        manager.flushUnsaved();
        assertEquals(1, remainingOnDisk(table));
    }

    @Test void aFlushSkipsATableThatWasRemovedMeanwhile() throws Exception {
        Table table = placeAndClearMessage();
        manager.dealToPlayer(table, player, 1);
        tick(5);
        var tables = TableManager.class.getDeclaredField("tables");
        tables.setAccessible(true);
        ((java.util.Map<?, ?>) tables.get(manager)).remove(table.getId());
        Files.delete(file(table));
        manager.flushUnsaved();
        assertFalse(Files.exists(file(table)), "a removed table is never written back");
    }

    @Test void aMuckIsOnlyOnDiskAfterTheFlush() throws Exception {
        Table table = dealt(2);
        manager.flushUnsaved();
        try (var writes = countWrites()) {
            manager.muckPlayer(table, player);
            assertEquals(0, read(table).getAsJsonArray("discarded").size() + remainingOnDisk(table));
            verifyWrites(writes, table, 0);
            manager.flushUnsaved();
            verifyWrites(writes, table, 1);
        }
        assertEquals(2, read(table).getAsJsonArray("discarded").size() + remainingOnDisk(table));
    }

    @Test void deferredPassesDoNotReadAnUnchangedLoadedTableAgain() throws Exception {
        Table table = placeAndClearMessage();
        String name = table.getId() + ".json";
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            manager.loadDeferred();
            files.verify(() -> Files.readString(argThat((Path path) -> path.getFileName().toString().equals(name))),
                    never());
            // check() still compares the whole file with what was last written.
            files.verify(() -> Files.readAllBytes(argThat((Path path) -> path.getFileName().toString().equals(name))),
                    times(1));
        }
        assertSame(table, manager.table(table.getId()));
        assertFalse(manager.ownershipStore().held(table.getId()));
    }

    @Test void deferredPassesStillHoldALoadedTableEditedOutsideTheWriter() throws Exception {
        Table table = placeAndClearMessage();
        JsonObject edited = read(table);
        edited.addProperty("maxBet", 999);
        Files.writeString(file(table), edited.toString());
        manager.loadDeferred();
        assertTrue(manager.ownershipStore().held(table.getId()));
        assertSame(table, manager.table(table.getId()));
    }

    @Test void deferredPassesStillHoldALoadedTableWhoseIdAppearsInAnotherFile() throws Exception {
        Table table = placeAndClearMessage();
        Files.writeString(data.resolve("Data/tables/copy.json"), Files.readString(file(table)));
        manager.loadDeferred();
        assertTrue(manager.ownershipStore().held(table.getId()));
        assertSame(table, manager.table(table.getId()));
        verify(net.tfminecraft.games.Games.plugin.getLogger(), atLeastOnce())
                .warning(contains("Duplicate table ID held: " + table.getId()));
    }

    @Test void deferredPassesTakeTheOwnerCharacterFromTheCheckedFile() throws Exception {
        Table table = placeAndClearMessage();
        UUID written = table.ownerCharacterId();
        assertNotNull(written);
        table.setOwnerCharacterId(UUID.randomUUID());
        String name = table.getId() + ".json";
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            manager.loadDeferred();
            files.verify(() -> Files.readString(argThat((Path path) -> path.getFileName().toString().equals(name))),
                    never());
        }
        assertEquals(written, table.ownerCharacterId(), "as before, the file's owner character wins");
    }
}
