package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.wager.WagerEngine;

/** Table files written by older versions, damaged by hand or holding items that will not save. */
@SuppressWarnings("deprecation")
class TableManagerStoredDataTest extends TableManagerFixture {
    private final Gson json = new Gson();

    private Path folder() {
        return data.resolve("Data/tables");
    }

    private JsonObject document(UUID id) {
        JsonObject document = new JsonObject();
        document.addProperty("id", id.toString());
        document.addProperty("gameId", "freeplay");
        document.addProperty("world", world.getName());
        document.addProperty("x", 0);
        document.addProperty("y", 65);
        document.addProperty("z", 0);
        document.addProperty("yaw", 0);
        document.addProperty("setName", Cache.pokerCardSet);
        document.add("remaining", json.toJsonTree(List.of("one", "two")));
        return document;
    }

    private static String encode(Object value) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(value);
            output.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
    }

    private JsonObject stake(String item, int unit, int count) {
        JsonObject stake = new JsonObject();
        stake.addProperty("owner", player.getUniqueId().toString());
        if (item != null) stake.addProperty("item", item);
        stake.addProperty("typeKey", "coin:gold");
        stake.addProperty("unit", unit);
        stake.addProperty("count", count);
        stake.addProperty("streetId", 1);
        return stake;
    }

    private JsonObject pile(String item, int denars, int count) {
        JsonObject pile = new JsonObject();
        pile.addProperty("owner", player.getUniqueId().toString());
        if (item != null) pile.addProperty("item", item);
        pile.addProperty("typeKey", "coin:gold");
        pile.addProperty("denars", denars);
        pile.addProperty("count", count);
        pile.addProperty("streetId", 1);
        pile.addProperty("x", 1);
        pile.addProperty("z", 1);
        return pile;
    }

    private Table load(JsonObject document) throws Exception {
        Files.createDirectories(folder());
        UUID id = UUID.fromString(document.get("id").getAsString());
        Files.writeString(folder().resolve(id + ".json"), document.toString());
        manager.loadAll();
        return manager.table(id);
    }

    private JsonObject reread(UUID id) throws Exception {
        return JsonParser.parseString(Files.readString(folder().resolve(id + ".json"))).getAsJsonObject();
    }

    private int pocketGold() {
        return player.getInventory().all(Material.GOLD_NUGGET).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    private int droppedGold() {
        return world.getEntitiesByClass(Item.class).stream().map(Item::getItemStack)
                .filter(item -> item.getType() == Material.GOLD_NUGGET).mapToInt(ItemStack::getAmount).sum();
    }

    @Test void damagedLedgerEntriesAreSkippedWhileTheGoodStakeIsRefundedAndTheAuditCountsOnlyRealMoney()
            throws Exception {
        String gold = encode(new ItemStack(Material.GOLD_NUGGET));
        JsonObject saved = document(UUID.randomUUID());
        JsonArray ledger = new JsonArray();
        ledger.add(stake(gold, 1, 2));
        ledger.add(JsonNull.INSTANCE);
        ledger.add(stake(null, 1, 3));
        ledger.add(stake(gold, 0, 4));
        ledger.add(stake(gold, 1, 0));
        ledger.add(stake("", 1, 5));
        ledger.add(stake(encode("not an item"), 1, 6));
        saved.add("ledger", ledger);
        Table loaded = load(saved);
        assertNotNull(loaded);
        assertEquals(2, pocketGold(), "only the readable stake is money anybody can be paid");
        assertEquals(0, droppedGold());
        assertTrue(loaded.ledger().isEmpty());
        // Entries without a positive unit and count were never money; the rest were claimed.
        verify(Games.plugin.getLogger()).warning(contains("loaded holding 2 but the file says 16"));
    }

    @Test void damagedLegacyPilesAreSkippedWhileTheGoodPileIsRefunded() throws Exception {
        String gold = encode(new ItemStack(Material.GOLD_NUGGET));
        JsonObject saved = document(UUID.randomUUID());
        saved.add("ledger", JsonNull.INSTANCE);
        JsonArray piles = new JsonArray();
        piles.add(pile(gold, 1, 3));
        piles.add(JsonNull.INSTANCE);
        piles.add(pile(null, 1, 2));
        piles.add(pile(gold, 1, 0));
        piles.add(pile(gold, 0, 4));
        saved.add("piles", piles);
        Table loaded = load(saved);
        assertNotNull(loaded);
        assertEquals(3, pocketGold());
        assertTrue(loaded.ledger().isEmpty());
        verify(Games.plugin.getLogger()).warning(contains("loaded holding 3 but the file says 5"));
    }

    @Test void aFileWithoutSeatStakeOrPileListsLoadsAsAnIdleFirstStreetTable() throws Exception {
        JsonObject saved = document(UUID.randomUUID());
        saved.add("actives", JsonNull.INSTANCE);
        saved.add("ledger", JsonNull.INSTANCE);
        saved.add("piles", JsonNull.INSTANCE);
        saved.addProperty("street", 0);
        Table loaded = load(saved);
        assertNotNull(loaded);
        assertEquals(1, loaded.street());
        assertTrue(loaded.actives().isEmpty());
        assertTrue(loaded.ledger().isEmpty());
        assertEquals(2, loaded.getDeck().remaining());
        verify(Games.plugin.getLogger(), never()).warning(anyString());
    }

    @Test void aStakeWhoseItemCannotBeSavedIsReportedWhileTheRestOfTheTableIsStillSaved() throws Exception {
        Table table = place(false);
        ItemStack stubborn = spy(new ItemStack(Material.GOLD_NUGGET));
        // Stands in for an item from another plugin whose data cannot be written out.
        doReturn(stubborn).when(stubborn).clone();
        doReturn(Map.of("type", "GOLD_NUGGET", "unsaveable", new Object())).when(stubborn).serialize();
        WagerEngine.get().restore(table, player.getUniqueId(), stubborn, "gold", 1, 2, 1, null, null);
        WagerEngine.get().restore(table, player.getUniqueId(), new ItemStack(Material.IRON_NUGGET), "iron",
                1, 1, 1, null, null);
        TableHouse house = TableHouse.from(table);
        house.setMaxBet(33);
        manager.applyHouse(table, house);
        JsonObject saved = reread(table.getId());
        assertEquals(33, saved.get("maxBet").getAsInt());
        JsonArray ledger = saved.getAsJsonArray("ledger");
        assertEquals(1, ledger.size(), "only the stake that could be written is in the file");
        assertEquals("iron", ledger.get(0).getAsJsonObject().get("typeKey").getAsString());
        verify(Games.plugin.getLogger(), atLeastOnce()).warning(contains("Failed to encode pot item"));
        assertEquals(3, table.ledger().total(), "the money itself is still on the felt");
    }

    @Test void pickingUpATableWhoseFileWasAlreadyRemovedIsQuiet() throws Exception {
        Table table = place(false);
        Files.delete(folder().resolve(table.getId() + ".json"));
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(player);
        manager.onHitEntity(hit);
        assertNull(manager.table(table.getId()));
        assertFalse(Files.exists(folder().resolve(table.getId() + ".json")));
        verify(Games.plugin.getLogger(), never()).warning(contains("Could not delete"));
    }

    @Test void theSameSpotInAnotherWorldIsFarEnoughFromAnExistingTable() {
        place(false);
        WorldMock other = new WorldMock();
        other.setName("other-" + UUID.randomUUID());
        MockBukkit.getMock().addWorld(other);
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, new Location(other, 0, 65, 0)));
        assertEquals(2, manager.tables().size());
    }
}
