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
}
