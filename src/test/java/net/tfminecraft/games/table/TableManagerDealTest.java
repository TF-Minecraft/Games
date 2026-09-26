package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.FreePlayGame;
import net.tfminecraft.games.game.GamesRegistry;

/**
 * Games chain deals through their callbacks, so every deal must finish its callback exactly once,
 * however it ends, without inventing or losing cards.
 */
class TableManagerDealTest extends TableManagerFixture {

    @Test void aTableDealQueuedBehindAnotherIsDroppedWhenThePilesAreMucked() {
        Table table = place(false);
        Runnable queued = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true);
        dealAnimated(table, queued);
        manager.muckTable(table, "board");
        tick(10);
        verify(queued).run();
        assertTrue(table.tablePilesEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void aTableDealQueuedBehindAnotherCompletesWhenTheTableIsPickedUp() {
        Table table = place(false);
        Runnable queued = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true);
        dealAnimated(table, queued);
        pickUp(table);
        tick(10);
        verify(queued).run();
        assertNull(manager.table(table.getId()));
    }

    @Test void muckingBetweenCardsEndsATableDealAfterTheFirstCard() {
        Table table = place(false);
        Runnable complete = mock(Runnable.class);
        manager.dealToTable(table, "board", 2, true, complete);
        assertEquals(1, table.tablePile("board").size());
        manager.muckTable(table, "board");
        tick(5);
        verify(complete).run();
        assertTrue(table.tablePilesEmpty(), "the second card was never dealt");
        assertEquals(2, table.getDeck().remaining(), "the mucked card is shuffled back in");
    }

    @Test void pickingUpBetweenCardsEndsATableDealAndCompletesIt() {
        Table table = place(false);
        Runnable complete = mock(Runnable.class);
        manager.dealToTable(table, "board", 2, true, complete);
        pickUp(table);
        tick(5);
        verify(complete).run();
        assertTrue(manager.tables().isEmpty());
    }

    @Test void muckingAPileThatWasNeverDealtStillCancelsCardsInFlight() {
        Table table = place(false);
        net.tfminecraft.games.cache.Cache.handDealTicks = 3;
        manager.dealToTable(table, "board", 1, true);
        clearInvocations(game);
        manager.muckTable(table, "dealer");
        verify(game).onTablePilesChanged(table);
        tick(10);
        assertTrue(table.tablePilesEmpty(), "the card in the air belonged to a cancelled deal");
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void revealingAPileThatDoesNotExistChangesNothing() {
        Table table = place(false);
        clearInvocations(game);
        manager.revealTablePile(table, "dealer");
        assertTrue(table.tablePiles().isEmpty());
        verify(game, never()).onTablePilesChanged(any());
    }

    @Test void publishingAHandWithMissingFaceArtMarksItPublicButKeepsTheBackShowing() {
        Table table = place(false);
        manager.dealToPlayer(table, player, 1);
        tick(2);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        when(items.getCreator().getItemFromPath("face")).thenReturn(null);
        clearInvocations(display);
        manager.publishHand(table, player);
        assertTrue(held.faceUp());
        verify(display, never()).setItem(eq(held.tokenId()), any());
        verify(display).clearItemFor(held.tokenId(), player);
        assertEquals(List.of(held), table.handOf(player.getUniqueId()));
    }

    @Test void aSeatThatLoggedOffBeforeItsTurnIsSkippedAndTheDealContinues() {
        Table table = place(false);
        PlayerMock gone = opponent();
        gone.disconnect();
        Runnable next = mock(Runnable.class);
        manager.dealToPlayer(table, gone, 1, next);
        verify(next).run();
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining());
    }

    @Test void leavingWhileADealWaitsForARevealCompletesItWithoutDealing() {
        games.when(() -> GamesRegistry.of("freeplay")).thenReturn(new FreePlayGame());
        Table table = place(false);
        manager.dealToPlayer(table, player, 1);
        tick(2);
        manager.onSwapHands(new PlayerSwapHandItemsEvent(player, new ItemStack(Material.AIR),
                new ItemStack(Material.AIR)));
        Runnable next = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, next);
        verifyNoInteractions(next);
        quit(player);
        tick(10);
        verify(next).run();
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void leavingBetweenCardsCompletesTheDealWithoutTheRest() {
        Table table = place(false);
        Runnable next = mock(Runnable.class);
        manager.dealToPlayer(table, player, 2, next);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        quit(player);
        tick(10);
        verify(next).run();
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void pickingUpBetweenCardsCompletesAPlayerDeal() {
        Table table = place(false);
        Runnable next = mock(Runnable.class);
        manager.dealToPlayer(table, player, 2, next);
        pickUp(table);
        tick(10);
        verify(next).run();
        assertTrue(manager.tables().isEmpty());
        assertTrue(table.getHands().isEmpty());
    }

    /** Start a second, animated deal to the board while the first is still under way. */
    private void dealAnimated(Table table, Runnable after) {
        net.tfminecraft.games.cache.Cache.handDealTicks = 3;
        manager.dealToTable(table, "board", 1, true, after);
        verifyNoInteractions(after);
    }

    private void quit(PlayerMock leaving) {
        manager.onQuit(new PlayerQuitEvent(leaving, "left"));
        leaving.disconnect();
    }

    private void pickUp(Table table) {
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(player);
        manager.onHitEntity(hit);
    }
}
