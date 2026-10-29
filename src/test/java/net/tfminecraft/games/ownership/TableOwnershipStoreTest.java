package net.tfminecraft.games.ownership;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

class TableOwnershipStoreTest {
    @TempDir Path data;
    UUID id = UUID.randomUUID(), account = UUID.randomUUID(), character = UUID.randomUUID();

    JsonObject row() throws Exception {
        var row =
                RawJson.parse(
                        "{\"unknown\":{\"nullable\":null},\"ledger\":[],\"world\":\"missing\",\"ownerIdentityVersion\":2}");
        row.addProperty("id", id.toString());
        row.addProperty("ownerPlayer", account.toString());
        row.addProperty("ownerCharacterId", character.toString());
        return row;
    }

    Path write(JsonObject row) throws IOException {
        Path path = data.resolve("Data/tables/imported.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, RawJson.JSON.toJson(row));
        return path;
    }

    @Test
    void failedIntegrityReadRemainsHeldInMemoryAfterReadAccessReturns() throws Exception {
        Path path = write(row());
        var store = new TableOwnershipStore(data);
        store.observe(path);
        try (var files = org.mockito.Mockito.mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            files.when(() -> Files.readAllBytes(path)).thenThrow(new IOException("unreadable source"));
            assertThrows(IOException.class, () -> store.check(id));
        }
        assertTrue(store.held(id));
        assertThrows(TableOwnershipStore.Conflict.class, () -> store.save(id, row()));
    }

    @Test
    void ordinarySaveRestartRetainsNullsUnknownLedgerMetadataAndInertWagerEntries()
            throws Exception {
        JsonObject raw = row(),
                stake =
                        RawJson.parse(
                                "{\"owner\":\""
                                        + account
                                        + "\",\"item\":\"bytes\",\"typeKey\":\"coin\",\"unit\":1,\"count\":2,\"streetId\":1,\"unknown\":{\"nullable\":null}}");
        stake.addProperty("anchorX", 1);
        raw.getAsJsonArray("ledger").add(stake);
        JsonObject refs = new JsonObject();
        refs.addProperty(account.toString(), character.toString());
        refs.add("future", JsonNull.INSTANCE);
        raw.add("wagerCharacters", refs);
        Path path = write(raw);
        var store = new TableOwnershipStore(data);
        assertTrue(store.prepare(path));
        JsonObject known = row();
        var nextStake = stake.deepCopy();
        nextStake.remove("unknown");
        nextStake.addProperty("anchorX", 1.0);
        nextStake.addProperty("count", 3);
        known.getAsJsonArray("ledger").add(nextStake);
        known.add("wagerCharacters", new JsonObject());
        store.save(id, known);
        var saved = RawJson.read(path);
        assertTrue(saved.getAsJsonObject("unknown").has("nullable"));
        assertTrue(
                saved.getAsJsonArray("ledger")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("unknown")
                        .has("nullable"));
        assertEquals(
                3, saved.getAsJsonArray("ledger").get(0).getAsJsonObject().get("count").getAsInt());
        assertFalse(saved.getAsJsonObject("wagerCharacters").has(account.toString()));
        assertTrue(saved.getAsJsonObject("wagerCharacters").get("future").isJsonNull());
        var restart = new TableOwnershipStore(data);
        assertTrue(restart.prepare(path));
        restart.save(id, known);
        assertEquals(saved, RawJson.read(path));
        assertEquals(1, restart.inventory().size());
    }

    @Test
    void externalContentAndPresenceConflictsStayHeldWithoutRecreatingLostFiles() throws Exception {
        Path path = write(row());
        var store = new TableOwnershipStore(data);
        assertTrue(store.prepare(path));
        String original = Files.readString(path);
        Files.writeString(path, original + " ");
        assertThrows(IOException.class, () -> store.save(id, row()));
        assertTrue(store.held(id));
        Files.writeString(path, original);
        assertThrows(IOException.class, () -> store.save(id, row()));
        assertEquals(original, Files.readString(path));
        var restart = new TableOwnershipStore(data);
        assertTrue(restart.prepare(path));
        Files.delete(path);
        assertThrows(IOException.class, () -> restart.save(id, row()));
        assertFalse(Files.exists(path));
    }

    @Test
    void rawInventoryIncludesUnloadableRowsAndRejectsDuplicateTableIds() throws Exception {
        Path path = write(row());
        var store = new TableOwnershipStore(data);
        assertEquals("missing", store.inventory().getFirst().raw().get("world").getAsString());
        Files.copy(path, path.resolveSibling(id + ".json"));
        assertThrows(IOException.class, store::inventory);
        assertTrue(store.prepare(path));
        assertThrows(IOException.class, () -> store.prepare(path.resolveSibling(id + ".json")));
        assertTrue(store.held(id));
    }

    private static org.mockito.MockedStatic<net.tfminecraft.games.persistence.DurableFiles> writes() {
        return org.mockito.Mockito.mockStatic(
                net.tfminecraft.games.persistence.DurableFiles.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
    }

    private static void verifyWrites(
            org.mockito.MockedStatic<net.tfminecraft.games.persistence.DurableFiles> writes,
            int times) {
        writes.verify(
                () ->
                        net.tfminecraft.games.persistence.DurableFiles.write(
                                org.mockito.ArgumentMatchers.any(Path.class),
                                org.mockito.ArgumentMatchers.anyString()),
                org.mockito.Mockito.times(times));
    }

    @Test
    void identicalBytesAreNotWrittenAgainButAnyChangeIs() throws Exception {
        var store = new TableOwnershipStore(data);
        Path path = data.resolve("Data/tables/" + id + ".json");
        try (var writes = writes()) {
            store.save(id, row());
            verifyWrites(writes, 1);
            String first = Files.readString(path);
            store.save(id, row());
            store.save(id, row());
            verifyWrites(writes, 1);
            assertEquals(first, Files.readString(path));
            JsonObject changed = row();
            changed.addProperty("maxBet", 9);
            store.save(id, changed);
            verifyWrites(writes, 2);
            assertEquals(9, RawJson.read(path).get("maxBet").getAsInt());
            store.save(id, changed);
            verifyWrites(writes, 2);
        }
    }

    @Test
    void bytesFoundOnDiskAreWrittenOnceBeforeTheyCountAsWritten() throws Exception {
        JsonObject row = row();
        row.addProperty("staffMint", false);
        new TableOwnershipStore(data).save(id, row);
        Path path = data.resolve("Data/tables/" + id + ".json");
        String before = Files.readString(path);
        var restart = new TableOwnershipStore(data);
        assertTrue(restart.prepare(path));
        try (var writes = writes()) {
            restart.save(id, row);
            // Found, not written by this store: their directory entry may never have been flushed.
            verifyWrites(writes, 1);
            assertEquals(before, Files.readString(path));
            restart.save(id, row);
            verifyWrites(writes, 1);
            // Reading back the same bytes keeps them known as written.
            restart.observe(path);
            restart.save(id, row);
            verifyWrites(writes, 1);
        }
    }

    @Test
    void aFailedWriteIsTriedAgainAndKeepsThrowingUntilItSucceeds() throws Exception {
        var store = new TableOwnershipStore(data);
        Path path = data.resolve("Data/tables/" + id + ".json");
        store.save(id, row());
        String written = Files.readString(path);
        JsonObject changed = row();
        changed.addProperty("maxBet", 9);
        try (var writes = writes()) {
            writes.when(
                            () ->
                                    net.tfminecraft.games.persistence.DurableFiles.write(
                                            org.mockito.ArgumentMatchers.any(Path.class),
                                            org.mockito.ArgumentMatchers.anyString()))
                    .thenThrow(new IOException("disk full"));
            assertThrows(IOException.class, () -> store.save(id, changed));
            // The same bytes again are not mistaken for written ones: a money save still fails.
            assertThrows(IOException.class, () -> store.save(id, changed));
            verifyWrites(writes, 2);
            assertEquals(written, Files.readString(path));
        }
        store.save(id, changed);
        assertEquals(9, RawJson.read(path).get("maxBet").getAsInt());
        assertFalse(store.held(id));
    }

    @Test
    void aWriteThatFailsAfterReplacingTheFileHoldsTheTableRatherThanSkipping() throws Exception {
        var store = new TableOwnershipStore(data);
        Path path = data.resolve("Data/tables/" + id + ".json");
        store.save(id, row());
        JsonObject changed = row();
        changed.addProperty("maxBet", 9);
        try (var writes = writes()) {
            writes.when(
                            () ->
                                    net.tfminecraft.games.persistence.DurableFiles.write(
                                            org.mockito.ArgumentMatchers.any(Path.class),
                                            org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(
                            call -> {
                                Files.writeString(path, call.<String>getArgument(1));
                                throw new IOException("directory flush failed");
                            });
            assertThrows(IOException.class, () -> store.save(id, changed));
        }
        assertThrows(TableOwnershipStore.Conflict.class, () -> store.save(id, changed));
        assertTrue(store.held(id));
    }

    @Test
    void sourceReportsTheLastWrittenRowWithoutReadingTheFile() throws Exception {
        var store = new TableOwnershipStore(data);
        assertNull(store.source(id));
        store.save(id, row());
        var source = store.source(id);
        assertEquals(data.resolve("Data/tables/" + id + ".json"), source.path());
        assertEquals(account, source.account());
        assertEquals(character, source.character());
        source.raw().addProperty("maxBet", 1);
        assertFalse(store.source(id).raw().has("maxBet"), "callers get a copy");
    }
}
