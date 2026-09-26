package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;

/** Public card piles, the discard pile and the recycle that turns one back into the shoe. */
class TableManagerPileTest extends TableManagerFixture {
    private final ItemStack face = new ItemStack(Material.DIAMOND);
    private final ItemStack back = new ItemStack(Material.PAPER);

    @Test void chunkReloadRefreshesPileCardsThatStillHaveDisplays() {
        Table table = boardWithOneUpOneDown();
        HandCard up = table.tablePile("board").get(0);
        HandCard down = table.tablePile("board").get(1);
        table.tablePile("empty");
        when(display.worldLocation(any())).thenReturn(table.getOrigin().clone());
        clearInvocations(display);
        manager.onChunkLoad(chunkLoad(table));
        verify(display).setTransform(eq(up.tokenId()), any(DisplayPose.class), eq(0));
        verify(display).setTransform(eq(down.tokenId()), any(DisplayPose.class), eq(0));
        verify(display).setItem(up.tokenId(), face);
        verify(display).setItem(down.tokenId(), back);
        verify(display, never()).spawn(eq(up.tokenId()), any(), any(), any());
        assertEquals(2, table.tablePile("board").size());
    }

    @Test void chunkReloadRedrawsPileCardsWhoseDisplaysWereLostAndKeepsCardsItCannotDraw() {
        Table table = boardWithOneUpOneDown();
        HandCard up = table.tablePile("board").get(0);
        HandCard down = table.tablePile("board").get(1);
        when(display.spawn(eq(down.tokenId()), any(), any(), any())).thenReturn(false);
        clearInvocations(display);
        manager.onChunkLoad(chunkLoad(table));
        verify(display).spawn(eq(up.tokenId()), eq(table.getOrigin()), eq(face), any(DisplayPose.class));
        verify(display).spawn(eq(down.tokenId()), eq(table.getOrigin()), eq(back), any(DisplayPose.class));
        assertEquals(List.of(up, down), table.tablePile("board"), "An undrawable card is still on the table");
    }

    @Test void aFaceUpCardWithoutFaceArtIsDrawnWithItsBack() {
        Table table = place(false);
        when(items.getCreator().getItemFromPath("face")).thenReturn(null);
        manager.dealToTable(table, "board", 1, true);
        tick(2);
        HandCard dealt = table.tablePile("board").getFirst();
        assertTrue(dealt.faceUp());
        verify(display).spawn(eq(dealt.tokenId()), any(), eq(back), any(DisplayPose.class));
        clearInvocations(display);
        manager.onChunkLoad(chunkLoad(table));
        verify(display).spawn(eq(dealt.tokenId()), any(), eq(back), any(DisplayPose.class));
    }

    @Test void withoutCardBackArtAChunkReloadOnlyMovesPileCards() {
        Table table = boardWithOneUpOneDown();
        HandCard up = table.tablePile("board").get(0);
        HandCard down = table.tablePile("board").get(1);
        when(items.getCreator().getItemFromPath("back")).thenReturn(null);
        when(items.getCreator().getItemFromPath("face")).thenReturn(null);
        when(display.worldLocation(up.tokenId())).thenReturn(table.getOrigin().clone());
        clearInvocations(display);
        manager.onChunkLoad(chunkLoad(table));
        verify(display).setTransform(eq(up.tokenId()), any(DisplayPose.class), eq(0));
        verify(display, never()).setItem(eq(up.tokenId()), any());
        verify(display, never()).spawn(eq(down.tokenId()), any(), any(), any());
        assertEquals(2, table.tablePile("board").size());
    }

    @Test void aTableDealPastAnEmptyShoeAndDiscardStopsAndCompletesOnce() {
        Table table = place(false);
        Runnable done = mock(Runnable.class);
        manager.dealToTable(table, "board", 3, true, done);
        tick(5);
        assertEquals(2, table.tablePile("board").size());
        assertEquals(0, table.getDeck().remaining() + table.getDeck().discarded());
        verify(done).run();
    }

    @Test void aTableDealRequestedWhileTheDiscardIsBeingRecycledIsRefused() {
        Cache.tableRecycleTicks = 3;
        Table table = place(false);
        manager.dealToPlayer(table, player, 2);
        tick(3);
        manager.muckPlayer(table, player);
        assertTrue(table.isRecycling());
        Runnable done = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, done);
        verify(done).run();
        assertTrue(table.tablePile("board").isEmpty());
        tick(10);
        assertFalse(table.isRecycling());
        assertEquals(2, table.getDeck().remaining());
        assertTrue(table.tablePile("board").isEmpty(), "The refused deal is not replayed later");
    }

    @Test void muckingTheBoardDuringTheRecycleADealStartedCancelsThatDeal() throws Exception {
        // An emptied shoe with a full discard, as a table is saved when the last hand was mucked.
        Table table = loadFreeplay(List.of(), List.of("one", "two"));
        Cache.tableRecycleTicks = 3;
        manager.dealToTable(table, "board", 1, true);
        assertTrue(table.isRecycling());
        manager.muckTable(table, "board");
        tick(10);
        assertFalse(table.isRecycling());
        assertTrue(table.tablePile("board").isEmpty());
        assertEquals(2, table.getDeck().remaining());
        Runnable next = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, next);
        tick(3);
        assertEquals(1, table.tablePile("board").size(), "The cancelled deal left the queue open");
        verify(next).run();
    }

    @Test void aTableDealFromAnEmptyShoeShufflesTheDiscardBackInAndDeals() throws Exception {
        Table table = loadFreeplay(List.of(), List.of("one", "two"));
        Runnable done = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, done);
        tick(5);
        verify(done).run();
        assertEquals(1, table.tablePile("board").size());
        assertEquals(1, table.getDeck().remaining(), "The other discard is back in the shoe");
        assertEquals(0, table.getDeck().discarded());
    }

    @Test void aTableDealThatWaitedOnARecycleStillCompletesWhenTheRendererRefusesTheCard() throws Exception {
        Table table = loadFreeplay(List.of(), List.of("one", "two"));
        when(items.getCreator().getItemFromPath("face")).thenReturn(face);
        doReturn(false).when(display).spawn(any(), any(), eq(face), any());
        Runnable done = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, done);
        tick(5);
        verify(done).run();
        assertTrue(table.tablePile("board").isEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded(), "The refused card is kept");
        doReturn(true).when(display).spawn(any(), any(), eq(face), any());
        Runnable next = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, next);
        tick(5);
        assertEquals(1, table.tablePile("board").size(), "The failed deal released the queue");
        verify(next).run();
    }

    @Test void anEmptyRoundShuffledBlackjackShoeEndsATableDealInsteadOfLoopingForever() throws Exception {
        Table table = loadBlackjack(ShufflePolicy.ROUND, List.of(), List.of("one", "two"));
        Runnable done = mock(Runnable.class);
        manager.dealToTable(table, "dealer", 1, true, done);
        tick(3);
        verify(done).run();
        assertTrue(table.tablePile("dealer").isEmpty());
        assertEquals(0, table.getDeck().remaining(), "Round shuffling waits for the next round");
        assertEquals(2, table.getDeck().discarded());
    }

    @Test void aRoundShuffledBlackjackTableKeepsItsDiscardUntilTheNextRound() throws Exception {
        Table table = loadBlackjack(ShufflePolicy.ROUND, List.of("one", "two"), List.of());
        manager.dealToPlayer(table, player, 2);
        tick(3);
        manager.muckPlayer(table, player);
        assertFalse(table.isRecycling());
        assertEquals(0, table.getDeck().remaining());
        assertEquals(2, table.getDeck().discarded());
    }

    @Test void aShoeShuffledBlackjackTableRecyclesOnlyOnceTheShoeRunsOut() throws Exception {
        Table table = loadBlackjack(ShufflePolicy.SHOE, List.of("one", "two"), List.of());
        PlayerMock other = opponent();
        manager.dealToPlayer(table, player, 1);
        tick(3);
        manager.muckPlayer(table, player);
        assertEquals(1, table.getDeck().remaining());
        assertEquals(1, table.getDeck().discarded(), "Cards still in the shoe, so the discard waits");
        manager.dealToPlayer(table, other, 1);
        tick(3);
        manager.muckPlayer(table, other);
        assertEquals(2, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
    }

    @Test void aSecondMuckDuringARecycleJoinsItRatherThanStartingAnother() {
        Cache.tableRecycleTicks = 3;
        Table table = place(false);
        PlayerMock other = opponent();
        manager.dealToPlayer(table, player, 1);
        manager.dealToPlayer(table, other, 1);
        tick(3);
        manager.muckPlayer(table, player);
        manager.muckPlayer(table, other);
        assertTrue(table.isRecycling());
        int generation = table.recycleGen();
        tick(10);
        assertEquals(generation, table.recycleGen(), "Only the first muck started a recycle");
        assertFalse(table.isRecycling());
        assertEquals(2, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
    }

    @Test void withoutCardBackArtARecycleHasNothingToAnimateAndFinishesAtOnce() {
        Cache.tableRecycleTicks = 5;
        Table table = place(false);
        manager.dealToPlayer(table, player, 1);
        tick(3);
        when(items.getCreator().getItemFromPath("back")).thenReturn(null);
        manager.muckPlayer(table, player);
        assertFalse(table.isRecycling());
        assertEquals(2, table.getDeck().remaining());
        assertTrue(table.getDiscardTokens().isEmpty());
    }

    @Test void pickingUpATableBeforeItsRecycleAnimatesStopsTheAnimation() {
        Table table = recyclingTable();
        List<UUID> discardTokens = List.copyOf(table.getDiscardTokens());
        pickUp(table);
        clearInvocations(display);
        tick(10);
        for (UUID token : discardTokens) verify(display, never()).setTransform(eq(token), any(), anyInt());
        assertNull(manager.table(table.getId()));
        assertFalse(table.isRecycling());
    }

    @Test void pickingUpATableMidRecycleStopsTheRemainingSteps() {
        Table table = recyclingTable();
        List<UUID> discardTokens = List.copyOf(table.getDiscardTokens());
        tick(1);
        pickUp(table);
        clearInvocations(display);
        tick(10);
        for (UUID token : discardTokens) verify(display, never()).setTransform(eq(token), any(), anyInt());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void shuttingDownBeforeTheRecycleAnimatesLeavesAFullShoe() {
        Table table = recyclingTable();
        manager.despawnWorldAll();
        tick(10);
        assertFalse(table.isRecycling());
        assertEquals(2, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
    }

    @Test void shuttingDownMidRecycleLeavesAFullShoe() {
        Table table = recyclingTable();
        List<UUID> discardTokens = List.copyOf(table.getDiscardTokens());
        tick(1);
        manager.despawnWorldAll();
        clearInvocations(display);
        tick(10);
        for (UUID token : discardTokens) verify(display, never()).setTransform(eq(token), any(), anyInt());
        assertFalse(table.isRecycling());
        assertEquals(2, table.getDeck().remaining());
    }

    @Test void pickingUpATableRemovesItsDiscardPileDisplays() throws Exception {
        Table table = loadFreeplay(List.of("one"), List.of("two"));
        List<UUID> discardTokens = List.copyOf(table.getDiscardTokens());
        assertFalse(discardTokens.isEmpty());
        pickUp(table);
        for (UUID token : discardTokens) verify(display, atLeastOnce()).despawn(token);
        assertTrue(table.getDiscardTokens().isEmpty());
    }

    @Test void aDiscardPileTheRendererRejectsIsReportedWhenStacksAreRebuilt() throws Exception {
        Table table = loadFreeplay(List.of("one"), List.of("two"));
        Location discard = TableManager.discardOrigin(table);
        when(display.spawn(any(), eq(discard), any(), any())).thenReturn(false);
        manager.rebuildAllStacks();
        verify(Games.plugin.getLogger()).warning("[Games] Failed to rebuild table stack: Failed to spawn discard layer 0");
        assertEquals(1, table.getDeck().discarded());
    }

    private Table boardWithOneUpOneDown() {
        when(items.getCreator().getItemFromPath("face")).thenReturn(face);
        when(items.getCreator().getItemFromPath("back")).thenReturn(back);
        Table table = place(false);
        manager.dealToTable(table, "board", 1, true);
        tick(2);
        manager.dealToTable(table, "board", 1, false);
        tick(2);
        assertEquals(2, table.tablePile("board").size());
        return table;
    }

    private Table recyclingTable() {
        Cache.tableRecycleTicks = 3;
        Table table = place(false);
        manager.dealToPlayer(table, player, 2);
        tick(3);
        manager.muckPlayer(table, player);
        assertTrue(table.isRecycling());
        assertFalse(table.getDiscardTokens().isEmpty());
        return table;
    }

    private void pickUp(Table table) {
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(player);
        manager.onHitEntity(hit);
    }

    private ChunkLoadEvent chunkLoad(Table table) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(table.getOrigin().getBlockX() >> 4);
        when(chunk.getZ()).thenReturn(table.getOrigin().getBlockZ() >> 4);
        ChunkLoadEvent event = mock(ChunkLoadEvent.class);
        when(event.getWorld()).thenReturn(world);
        when(event.getChunk()).thenReturn(chunk);
        return event;
    }

    private Table loadBlackjack(ShufflePolicy policy, List<String> remaining, List<String> discarded) throws Exception {
        return load("blackjack", policy, remaining, discarded);
    }

    private Table loadFreeplay(List<String> remaining, List<String> discarded) throws Exception {
        return load("freeplay", ShufflePolicy.SHOE, remaining, discarded);
    }

    private Table load(String gameId, ShufflePolicy policy, List<String> remaining, List<String> discarded)
            throws Exception {
        Gson json = new Gson();
        UUID id = UUID.randomUUID();
        JsonObject saved = new JsonObject();
        saved.addProperty("id", id.toString());
        saved.addProperty("gameId", gameId);
        saved.addProperty("world", world.getName());
        saved.addProperty("y", 65);
        saved.addProperty("setName", Cache.pokerCardSet);
        saved.addProperty("shufflePolicy", policy.name());
        saved.add("remaining", json.toJsonTree(remaining));
        saved.add("discarded", json.toJsonTree(discarded));
        var folder = data.resolve("Data/tables");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(id + ".json"), saved.toString());
        manager.loadAll();
        Table table = manager.table(id);
        assertNotNull(table);
        return table;
    }
}
