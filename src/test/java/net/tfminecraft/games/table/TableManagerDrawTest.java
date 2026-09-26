package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.cache.Cache;

/** Drawing from the shoe, shuffling the discards back in and sending cards back. */
class TableManagerDrawTest extends TableManagerHandFixture {

    /** Two cards dealt, one sent back: the shoe is empty and a single card waits in the discards. */
    private Table emptyShoeWithOneDiscard() throws InterruptedException {
        Table table = dealt(2);
        HandCard second = table.handOf(player.getUniqueId()).get(1);
        select(second);
        assertTrue(shoeClick(table).isCancelled());
        tick(3);
        assertEquals(List.of("hand.returned_selected"), List.of(player.nextMessage()));
        assertEquals(0, table.getDeck().remaining());
        assertEquals(1, table.getDeck().discarded());
        return table;
    }

    private void quit(PlayerMock who) {
        manager.onQuit(new PlayerQuitEvent(who, "quit"));
        who.disconnect();
    }

    @Test void aDealWaitingOnAnAnimatedShuffleStillArrivesWhenThePlayerStartsARevealMeanwhile()
            throws InterruptedException {
        Cache.tableRecycleTicks = 2;
        Table table = emptyShoeWithOneDiscard();
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete);
        assertTrue(table.isRecycling());
        assertTrue(swap().isCancelled());
        tick(20);
        verify(complete).run();
        List<HandCard> hand = table.handOf(player.getUniqueId());
        assertEquals(2, hand.size(), "the requested card is dealt once the reveal has finished");
        assertAllCardsAccountedFor(table, 2);
        assertTrue(hand.stream().allMatch(this::isPublic), "a card joining a revealed hand is shown too");
    }

    @Test void aDealWaitingOnAnAnimatedShuffleCompletesWhenThePlayerLeavesMeanwhile()
            throws InterruptedException {
        Cache.tableRecycleTicks = 2;
        Table table = emptyShoeWithOneDiscard();
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete);
        assertTrue(table.isRecycling());
        quit(player);
        tick(20);
        verify(complete).run();
        assertTrue(table.getHands().isEmpty());
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void aFreeDrawFromAnEmptyShoeWaitsForTheShuffleAndThenDealsTheCard() throws InterruptedException {
        Cache.tableRecycleTicks = 2;
        Table table = emptyShoeWithOneDiscard();
        assertTrue(shoeClick(table).isCancelled());
        assertTrue(table.isRecycling(), "the discard is shuffled back before the draw");
        tick(20);
        assertEquals(2, table.handOf(player.getUniqueId()).size(), "the shuffled-in card reaches the hand");
        assertEquals(0, table.getDeck().remaining());
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void aDealRequestedWhileTheDiscardsAreAlreadyShufflingInArrivesAfterTheShuffle() {
        Cache.tableRecycleTicks = 3;
        Table table = dealt(2);
        manager.muckPlayer(table, player);
        assertTrue(table.isRecycling(), "the mucked cards are being shuffled back into the empty shoe");
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete);
        tick(20);
        assertEquals(1, table.handOf(player.getUniqueId()).size(), "the card is dealt, not silently skipped");
        verify(complete).run();
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void aDealQueuedBehindARunningShuffleCompletesWhenTheTableIsPickedUpFirst() {
        Cache.tableRecycleTicks = 3;
        Table table = dealt(2);
        manager.muckPlayer(table, player);
        assertTrue(table.isRecycling());
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete);
        pickUp(table);
        tick(20);
        verify(complete).run();
        assertNull(manager.table(table.getId()));
        assertTrue(table.handOf(player.getUniqueId()).isEmpty(), "no card is dealt at a table that is gone");
    }

    @Test void anEmptyShoeOnARoundShuffledBlackjackTableRefusesTheDealUntilTheNextRound() {
        games.when(() -> net.tfminecraft.games.game.GamesRegistry.of("blackjack")).thenReturn(game);
        player.addAttachment(net.tfminecraft.games.Games.plugin, TableHouse.STAFF_PERM, true);
        TableHouse house = TableHouse.forPlace(player, null);
        house.setStaffMint(true);
        house.setShufflePolicy(ShufflePolicy.ROUND);
        manager.armPlace(player, "blackjack", false, house);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table table = manager.tables().iterator().next();
        while (player.nextMessage() != null) {
            // Placement notices are not under test here.
        }
        manager.dealToPlayer(table, player, 2);
        tick(3);
        manager.muckPlayer(table, player);
        assertEquals(0, table.getDeck().remaining());
        assertEquals(2, table.getDeck().discarded(), "round shuffling leaves the discards until the round ends");
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete);
        tick(3);
        verify(complete).run();
        assertEquals("hand.empty", player.nextMessage());
        assertTrue(table.handOf(player.getUniqueId()).isEmpty());
        assertEquals(2, table.getDeck().discarded());
    }

    @Test void clickingAnEmptyRoundShuffledShoeSaysItIsEmpty() {
        games.when(() -> net.tfminecraft.games.game.GamesRegistry.of("blackjack")).thenReturn(game);
        when(game.allowFreeDraw(any(), any())).thenReturn(true);
        player.addAttachment(net.tfminecraft.games.Games.plugin, TableHouse.STAFF_PERM, true);
        TableHouse house = TableHouse.forPlace(player, null);
        house.setStaffMint(true);
        house.setShufflePolicy(ShufflePolicy.ROUND);
        manager.armPlace(player, "blackjack", false, house);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table table = manager.tables().iterator().next();
        manager.dealToPlayer(table, player, 2);
        tick(3);
        manager.muckPlayer(table, player);
        while (player.nextMessage() != null) {
            // Placement notices are not under test here.
        }
        assertTrue(shoeClick(table).isCancelled());
        assertEquals("hand.empty", player.nextMessage());
        assertTrue(table.handOf(player.getUniqueId()).isEmpty());
        assertEquals(2, table.getDeck().discarded());
    }

    @Test void aCardWhoseHandDisplayFailsDuringAnInstantDealGoesToTheDiscardsEvenIntoAShownHand() {
        Table table = dealt(1);
        swap();
        tick(8);
        failNextHandSpawn();
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete);
        tick(5);
        verify(complete).run();
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(1, table.getDeck().discarded(), "the undrawable card is kept, on the discard pile");
        assertTrue(table.handOf(player.getUniqueId()).stream().allMatch(this::isPublic));
    }

    private void pickUp(Table table) {
        org.bukkit.entity.Entity anchor = mock(org.bukkit.entity.Entity.class);
        anchors.when(() -> net.tfminecraft.games.display.WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        org.bukkit.event.entity.EntityDamageByEntityEvent hit = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(player);
        manager.onHitEntity(hit);
    }
}
