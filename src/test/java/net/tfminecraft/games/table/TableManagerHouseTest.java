package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.gui.TableOptionsGui;

/** Who may change a table's house rules, and what a change sets in motion. */
class TableManagerHouseTest extends TableManagerFixture {

    @Test void aTableWithNoRecordedOwnerCanOnlyBeEditedByStaff() {
        Table table = placeAt("freeplay", 0);
        TableHouse house = TableHouse.from(table);
        house.setOwnerPlayer(null);
        manager.applyHouse(table, house);
        assertNull(table.ownerPlayer());
        assertFalse(manager.canEditHouse(player, table));
        player.addAttachment(Games.plugin, "games.admin", true);
        assertTrue(manager.canEditHouse(player, table));
    }

    @Test void pokerOwnersOpenOptionsWhileOtherSneakersFallBackToTheManualFlushRule() {
        games.when(() -> GamesRegistry.of("poker")).thenReturn(game);
        Table table = placeAt("poker", 0);
        PlayerMock visitor = opponent();
        player.setSneaking(true);
        visitor.setSneaking(true);
        drain(visitor);
        try (var options = mockStatic(TableOptionsGui.class)) {
            shoe(table, player);
            options.verify(() -> TableOptionsGui.openEdit(player, table));
            options.clearInvocations();
            shoe(table, visitor);
            options.verifyNoInteractions();
        }
        assertEquals("wager.no_flush", visitor.nextMessage(), "poker keeps options private without a refusal");
        verify(game).allowManualPotFlush(table, visitor);
    }

    @Test void aGuildHouseChangeWakesItsOtherIdleTablesButNotLiveRoundsOrOtherGuilds() {
        Table edited = placeAt("freeplay", 0);
        Table idle = placeAt("freeplay", 10);
        Table live = placeAt("freeplay", 20);
        Table rival = placeAt("freeplay", 30);
        Table retired = placeAt("retired", 40);
        manager.beginSession(live);
        for (Table table : List.of(edited, idle, live, retired)) table.setOwnerGuildId("card-guild");
        rival.setOwnerGuildId("rival-guild");
        UUID retiredLabel = labelled(retired);
        clearInvocations(game);
        anchors.clearInvocations();
        TableHouse house = TableHouse.from(edited);
        house.setMaxBet(40);
        manager.applyHouse(edited, house);
        assertEquals(40, edited.maxBet());
        verify(game, atLeastOnce()).onTableReady(edited);
        verify(game).onTableReady(idle);
        verify(game, never()).onTableReady(live);
        verify(game, never()).onTableReady(rival);
        anchors.verify(() -> WorldAnchors.setText(eq(retiredLabel), anyString()));
    }

    @Test void tablesWithABlankGuildAreNotTreatedAsOneGuild() {
        Table edited = placeAt("freeplay", 0);
        Table other = placeAt("freeplay", 10);
        other.setOwnerGuildId(" ");
        clearInvocations(game);
        TableHouse house = TableHouse.from(edited);
        house.setOwnerGuildId(" ");
        manager.applyHouse(edited, house);
        verify(game).onTableReady(edited);
        verify(game, never()).onTableReady(other);
    }

    @Test void aGuildTableOverItsGuildsAllowanceCannotStartARound() {
        Table table = placeAt("freeplay", 0);
        // SimpleFactions is not loaded here, so the guild's allowance is zero tables.
        table.setOwnerGuildId("shrunk-guild");
        manager.beginSession(table);
        assertFalse(table.live());
        verify(game, never()).onSessionStart(table);
        table.setStaffMint(true);
        manager.beginSession(table);
        assertTrue(table.live(), "a staff-minted table does not count against the guild");
    }

    @Test void savedTablesMissingOneBlindReadItAsZero() throws Exception {
        UUID onlyBig = UUID.randomUUID();
        JsonObject big = document(onlyBig, "freeplay");
        big.addProperty("bigBlind", 6);
        write(onlyBig, big);
        UUID onlySmall = UUID.randomUUID();
        JsonObject small = document(onlySmall, "freeplay");
        small.addProperty("smallBlind", 3);
        write(onlySmall, small);
        manager.loadAll();
        assertEquals(0, manager.table(onlyBig).smallBlind());
        assertEquals(6, manager.table(onlyBig).bigBlind());
        assertEquals(3, manager.table(onlySmall).smallBlind());
        assertEquals(0, manager.table(onlySmall).bigBlind());
    }

    @Test void aLegacyTableForAGameWithoutALayoutLoadsAsAPlainPlayerTable() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id, "retired");
        for (String field : List.of("autoDealer", "staffMint", "minBet", "maxBet")) saved.remove(field);
        write(id, saved);
        manager.loadAll();
        Table loaded = manager.table(id);
        assertNotNull(loaded);
        assertNull(Cache.layoutOf("retired"));
        assertFalse(loaded.autoDealer());
        assertFalse(loaded.staffMint());
        assertEquals(0, loaded.minBet());
        assertEquals(0, loaded.maxBet());
        assertEquals(0, loaded.maxBoxes());
        assertEquals(0, loaded.smallBlind());
        assertEquals(0, loaded.bigBlind());
        assertEquals(List.of("one", "two"), loaded.getDeck().remainingIds());
    }

    @Test void houseChangesToATableWithoutAGameAreSavedAndShown() throws Exception {
        Table table = placeAt("retired", 0);
        UUID label = labelled(table);
        anchors.clearInvocations();
        TableHouse house = TableHouse.from(table);
        house.setMaxBet(35);
        manager.applyHouse(table, house);
        assertEquals(35, table.maxBet());
        anchors.verify(() -> WorldAnchors.setText(eq(label), anyString()));
        String saved = Files.readString(data.resolve("Data/tables/" + table.getId() + ".json"));
        assertEquals(35, com.google.gson.JsonParser.parseString(saved).getAsJsonObject().get("maxBet").getAsInt());
    }

    private Table placeAt(String gameId, double x) {
        manager.armPlace(player, gameId, false);
        assertTrue(manager.tryPlace(player, new Location(world, x, 65, 0)));
        drain(player);
        return manager.tables().stream().filter(table -> table.getOrigin().getX() == x).findFirst().orElseThrow();
    }

    /** Give the table a label display, as a loaded chunk would. */
    private UUID labelled(Table table) {
        TextDisplay label = mock(TextDisplay.class);
        UUID id = UUID.randomUUID();
        when(label.getUniqueId()).thenReturn(id);
        anchors.when(() -> WorldAnchors.spawnLabel(any(Location.class), anyString())).thenReturn(label);
        manager.refreshLabel(table);
        assertEquals(id, table.getLabelId());
        return id;
    }

    private void shoe(Table table, PlayerMock actor) {
        Entity shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
        PlayerInteractAtEntityEvent event = new PlayerInteractAtEntityEvent(actor, shoe, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(event);
        assertTrue(event.isCancelled());
    }

    private JsonObject document(UUID id, String gameId) {
        JsonObject document = new JsonObject();
        document.addProperty("id", id.toString());
        document.addProperty("gameId", gameId);
        document.addProperty("world", world.getName());
        document.addProperty("x", 0);
        document.addProperty("y", 65);
        document.addProperty("z", 0);
        document.addProperty("yaw", 0);
        document.addProperty("setName", Cache.pokerCardSet);
        document.add("remaining", new Gson().toJsonTree(List.of("one", "two")));
        document.add("discarded", new JsonArray());
        document.add("actives", new JsonArray());
        document.add("ledger", new JsonArray());
        document.addProperty("autoDealer", false);
        document.addProperty("staffMint", false);
        document.addProperty("street", 1);
        document.addProperty("minBet", 1);
        document.addProperty("maxBet", 20);
        return document;
    }

    private void write(UUID id, JsonObject document) throws Exception {
        Path folder = data.resolve("Data/tables");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(id + ".json"), new Gson().toJson(document));
    }

    private static void drain(PlayerMock target) {
        while (target.nextMessage() != null) { }
    }
}
