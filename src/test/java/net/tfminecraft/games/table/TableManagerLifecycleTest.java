package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.WagerEngine;

/** Loading, placing, shutting down and chunk reloads, driven through the public lifecycle. */
class TableManagerLifecycleTest extends TableManagerFixture {
    private final Gson json = new Gson();

    @Test void emptyAndIdentitylessFilesAreSkippedWhileTheValidTableLoads() throws Exception {
        UUID id = UUID.randomUUID();
        Files.createDirectories(folder());
        Files.writeString(folder().resolve("empty.json"), "");
        Files.writeString(folder().resolve("no-id.json"), "{}");
        write(id, document(id, "freeplay"));
        manager.loadAll();
        assertEquals(1, manager.tables().size());
        assertNotNull(manager.table(id));
        assertTrue(Files.exists(folder().resolve("empty.json")), "Unusable files are left for staff to inspect");
        assertTrue(Files.exists(folder().resolve("no-id.json")));
    }

    @Test void anUnreadableTableFileIsReportedAndTheOthersStillLoad() throws Exception {
        UUID id = UUID.randomUUID();
        // A directory with a table's name cannot be opened as a file.
        Files.createDirectories(folder().resolve("locked.json"));
        write(id, document(id, "freeplay"));
        manager.loadAll();
        assertNotNull(manager.table(id));
        assertEquals(1, manager.tables().size());
        verify(Games.plugin.getLogger()).warning(startsWith("[Games] Failed to load table locked.json"));
    }

    @Test void aFileWhereTheTablesFolderShouldBeLoadsNothingAndIsLeftAlone() throws Exception {
        Path tables = folder();
        Files.createDirectories(tables.getParent());
        Files.writeString(tables, "not a folder");
        manager.loadAll();
        assertTrue(manager.tables().isEmpty());
        assertEquals("not a folder", Files.readString(tables));
    }

    @Test void debugModeReportsHowManyTablesLoaded() throws Exception {
        boolean previous = Cache.debug;
        Cache.debug = true;
        try {
            UUID id = UUID.randomUUID();
            write(id, document(id, "freeplay"));
            manager.loadAll();
            verify(Games.plugin.getLogger()).info("[Games] Debug: loaded tables=1");
        } finally {
            Cache.debug = previous;
        }
    }

    @Test void aNullSeatListLoadsAsAnIdleTableWithoutRewritingTheFile() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id, "freeplay");
        saved.add("actives", JsonNull.INSTANCE);
        Files.createDirectories(folder());
        Files.writeString(folder().resolve(id + ".json"),
                new com.google.gson.GsonBuilder().serializeNulls().create().toJson(saved));
        assertTrue(Files.readString(folder().resolve(id + ".json")).contains("\"actives\":null"));
        String before = Files.readString(folder().resolve(id + ".json"));
        manager.loadAll();
        Table loaded = manager.table(id);
        assertNotNull(loaded);
        assertTrue(loaded.actives().isEmpty());
        assertEquals(List.of("one", "two"), loaded.getDeck().remainingIds());
        assertEquals(before, Files.readString(folder().resolve(id + ".json")),
                "Only a table with a session to undo is written back on load");
    }

    @Test void aStaleTableOfARetiredGameIsRefundedAndLabelledWithoutGameHooks() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id, "retired");
        JsonArray actives = new JsonArray();
        actives.add(player.getUniqueId().toString());
        saved.add("actives", actives);
        saved.add("remaining", json.toJsonTree(List.of("one")));
        saved.add("discarded", json.toJsonTree(List.of("two")));
        write(id, saved);
        manager.loadAll();
        Table loaded = manager.table(id);
        assertNotNull(loaded);
        assertTrue(loaded.actives().isEmpty());
        assertEquals(2, loaded.getDeck().remaining(), "Resetting to idle reshuffles the whole deck");
        assertEquals(0, loaded.getDeck().discarded());
        assertEquals(new JsonArray(), json.fromJson(Files.readString(folder().resolve(id + ".json")),
                JsonObject.class).getAsJsonArray("actives"));
        anchors.verify(() -> WorldAnchors.spawnLabel(any(Location.class), eq("label.title")));
        verifyNoInteractions(game);
    }

    @Test void shutdownReturnsDealtCardsToTheDeckAndLeavesTheSavedTableIdle() throws Exception {
        Table table = place(false);
        manager.dealToPlayer(table, player, 2);
        tick(3);
        List<UUID> tokens = table.handOf(player.getUniqueId()).stream().map(HandCard::tokenId).toList();
        assertEquals(2, tokens.size());
        manager.despawnWorldAll();
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
        for (UUID token : tokens) verify(display, atLeastOnce()).despawn(token);
        JsonObject saved = json.fromJson(Files.readString(folder().resolve(table.getId() + ".json")), JsonObject.class);
        assertEquals(2, saved.getAsJsonArray("remaining").size());
        assertEquals(0, saved.getAsJsonArray("discarded").size());
    }

    @Test void wipingHandsReportsARendererFailureAndStillWipesEveryTable() {
        Table first = place(false);
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, new Location(world, 10, 65, 0)));
        Table second = manager.tables().stream().filter(t -> t != first).findFirst().orElseThrow();
        manager.dealToPlayer(first, player, 1);
        PlayerMock other = opponent();
        other.teleport(second.getOrigin());
        manager.dealToPlayer(second, other, 1);
        tick(3);
        assertEquals(1, first.handOf(player.getUniqueId()).size());
        assertEquals(1, second.handOf(other.getUniqueId()).size());
        when(display.spawn(any(), any(), any(), any())).thenReturn(false);
        manager.wipeHands();
        for (Table table : List.of(first, second)) {
            assertTrue(table.getHands().isEmpty());
            assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
        }
        verify(Games.plugin.getLogger(), times(2)).warning(startsWith("[Games] Failed to rebuild table stack"));
    }

    @Test void anExpiredPlacementArmIsDiscardedWithoutCreatingATable() throws Exception {
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER, 2));
        manager.armPlace(player, "freeplay", true);
        expireArm();
        assertTrue(manager.tryPlace(player, new Location(world, 0, 65, 0)));
        assertEquals("place.expired", player.nextMessage());
        assertTrue(manager.tables().isEmpty());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertFalse(manager.tryPlace(player, new Location(world, 0, 65, 0)), "The expired arm is gone");
    }

    @Test void placingAPokerTableWithChosenOptionsAppliesThoseHouseRules() {
        TableHouse house = TableHouse.forPlace(player, null);
        house.setMinBet(2);
        house.setMaxBet(40);
        house.setSmallBlind(1);
        house.setBigBlind(2);
        manager.armPlace(player, "poker", false, house);
        assertTrue(manager.tryPlace(player, new Location(world, 0, 65, 0)));
        assertEquals("place.done", player.nextMessage());
        Table table = manager.tables().iterator().next();
        assertEquals("poker", table.getGameId());
        assertEquals(player.getUniqueId(), table.ownerPlayer());
        assertEquals(2, table.minBet());
        assertEquals(40, table.maxBet());
        assertEquals(1, table.smallBlind());
        assertEquals(2, table.bigBlind());
    }

    @Test void chunkLoadsElsewhereLeaveTheTableAlone() {
        Table table = place(false);
        clearInvocations(display);
        World elsewhere = MockBukkit.getMock().addSimpleWorld("elsewhere-" + UUID.randomUUID());
        manager.onChunkLoad(chunkLoad(elsewhere, 0, 0));
        manager.onChunkLoad(chunkLoad(world, 0, 1));
        verifyNoInteractions(display);
        assertEquals(Cache.stackVisibleMax, table.getStackTokens().size());
    }

    @Test void chunkReloadKeepsLiveAnchorsAndReplacesOnesThatDied() {
        LivingEntity shoeBody = (LivingEntity) world.spawnEntity(new Location(world, 0, 65, 0), EntityType.ZOMBIE);
        LivingEntity labelBody = (LivingEntity) world.spawnEntity(new Location(world, 0, 65, 0), EntityType.ZOMBIE);
        Interaction shoe = mock(Interaction.class);
        when(shoe.getUniqueId()).thenReturn(shoeBody.getUniqueId());
        TextDisplay label = mock(TextDisplay.class);
        when(label.getUniqueId()).thenReturn(labelBody.getUniqueId());
        anchors.when(() -> WorldAnchors.spawnInteraction(any(), anyFloat(), anyFloat(), anyString(), anyString()))
                .thenReturn(shoe);
        anchors.when(() -> WorldAnchors.spawnLabel(any(), anyString())).thenReturn(label);
        Table table = place(false);
        assertEquals(shoeBody.getUniqueId(), table.getInteractionId());
        assertEquals(labelBody.getUniqueId(), table.getLabelId());

        manager.onChunkLoad(chunkLoad(world, 0, 0));
        anchors.verify(() -> WorldAnchors.spawnInteraction(any(), anyFloat(), anyFloat(), anyString(), anyString()));
        anchors.verify(() -> WorldAnchors.spawnLabel(any(), anyString()));
        anchors.verify(() -> WorldAnchors.setText(labelBody.getUniqueId(), "label.title"));

        shoeBody.setHealth(0);
        labelBody.setHealth(0);
        anchors.when(() -> WorldAnchors.spawnInteraction(any(), anyFloat(), anyFloat(), anyString(), anyString()))
                .thenReturn(null);
        anchors.when(() -> WorldAnchors.spawnLabel(any(), anyString())).thenReturn(null);
        manager.onChunkLoad(chunkLoad(world, 0, 0));
        anchors.verify(() -> WorldAnchors.spawnInteraction(any(), anyFloat(), anyFloat(), anyString(), anyString()),
                times(2));
        anchors.verify(() -> WorldAnchors.spawnLabel(any(), anyString()), times(2));
        assertNull(table.getInteractionId(), "A failed respawn leaves no stale anchor id behind");
        assertNull(table.getLabelId());
    }

    @Test void pickingUpARetiredGameTableWithoutADeckItemStillRemovesItAndRefunds() throws Exception {
        UUID id = UUID.randomUUID();
        write(id, document(id, "retired"));
        manager.loadAll();
        Table table = manager.table(id);
        WagerEngine.get().restore(table, player.getUniqueId(), new ItemStack(Material.GOLD_NUGGET),
                "GOLD_NUGGET", 1, 1, 1, null, null);
        when(items.getCreator().getItemFromPath("deck")).thenReturn(null);
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(id.toString());
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(player);
        manager.onHitEntity(hit);
        verify(hit).setCancelled(true);
        assertNull(manager.table(id));
        assertFalse(Files.exists(folder().resolve(id + ".json")));
        assertEquals(1, Accounts.coins(table, player).available());
        assertTrue(world.getEntities().stream().noneMatch(Item.class::isInstance), "No deck item to drop");
        assertEquals("place.picked_up", player.nextMessage());
    }

    @Test void theDealerLeavingARetiredGameTableClearsTheSeatAndRedrawsTheLabel() throws Exception {
        UUID id = UUID.randomUUID();
        write(id, document(id, "retired"));
        manager.loadAll();
        Table table = manager.table(id);
        table.setDealerId(player.getUniqueId());
        anchors.clearInvocations();
        manager.onQuit(new PlayerQuitEvent(player, "quit"));
        assertNull(table.dealerId());
        anchors.verify(() -> WorldAnchors.spawnLabel(any(Location.class), eq("label.title")));
        assertSame(table, manager.table(id));
    }

    @Test void aGameThatHidesRevealDustShowsNoParticlesForPublishedCards() {
        Table table = place(false);
        manager.dealToPlayer(table, player, 1);
        tick(3);
        manager.publishHand(table, player);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        assertTrue(held.faceUp());
        when(display.worldLocation(held.tokenId())).thenReturn(table.getOrigin().clone().add(0, 1, 0));
        org.bukkit.entity.Player recipient = mock(org.bukkit.entity.Player.class,
                org.mockito.AdditionalAnswers.delegatesTo(player));
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(player.getUniqueId())).thenReturn(recipient);
            manager.startClock();
            tick(6);
            manager.stopClock();
        }
        verify(game, atLeastOnce()).showRevealDust(table);
        verify(recipient, never()).spawnParticle(any(org.bukkit.Particle.class), anyDouble(), anyDouble(), anyDouble(),
                anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
        assertEquals(List.of(held), table.handOf(player.getUniqueId()));
    }

    private void expireArm() throws Exception {
        // Stands in for the thirty seconds a real arm waits before it lapses.
        Field armsField = TableManager.class.getDeclaredField("arms");
        armsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> arms = (Map<UUID, Object>) armsField.get(manager);
        Object live = arms.get(player.getUniqueId());
        Class<?> type = live.getClass();
        Field gameId = type.getDeclaredField("gameId");
        Field requireDeck = type.getDeclaredField("requireDeck");
        Field house = type.getDeclaredField("house");
        gameId.setAccessible(true);
        requireDeck.setAccessible(true);
        house.setAccessible(true);
        Constructor<?> make = type.getDeclaredConstructor(String.class, long.class, boolean.class, TableHouse.class);
        make.setAccessible(true);
        arms.put(player.getUniqueId(), make.newInstance(gameId.get(live), System.currentTimeMillis() - 1L,
                requireDeck.get(live), house.get(live)));
    }

    private ChunkLoadEvent chunkLoad(World where, int x, int z) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        ChunkLoadEvent event = mock(ChunkLoadEvent.class);
        when(event.getWorld()).thenReturn(where);
        when(event.getChunk()).thenReturn(chunk);
        return event;
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
        document.add("remaining", json.toJsonTree(List.of("one", "two")));
        document.add("discarded", new JsonArray());
        document.add("actives", new JsonArray());
        document.add("ledger", new JsonArray());
        document.addProperty("minBet", 1);
        document.addProperty("maxBet", 20);
        return document;
    }

    private void write(UUID id, JsonObject document) throws Exception {
        Files.createDirectories(folder());
        Files.writeString(folder().resolve(id + ".json"), json.toJson(document));
    }

    private Path folder() {
        return data.resolve("Data/tables");
    }
}
