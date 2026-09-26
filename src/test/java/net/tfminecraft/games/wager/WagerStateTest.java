package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.events.PlayerWonMoneyEvent;
import net.tfminecraft.games.table.PayoutFlight;

class WagerStateTest {
    @Test
    void resultsKeepAmountsAnimationAndPlayerFacingReason() {
        List<PayoutFlight> flights = new ArrayList<>();
        TxResult done = TxResult.done(8, flights);
        assertTrue(done.ok());
        assertEquals(8, done.moved());
        assertEquals(8, done.wanted());
        assertEquals(8, done.best());
        assertEquals(0, done.shortfall());
        assertSame(flights, done.flights());
        assertNull(done.messageKey());
        TxResult nothing = TxResult.nothing();
        assertTrue(nothing.ok());
        assertEquals(0, nothing.moved());
        assertTrue(nothing.flights().isEmpty());
        assertNull(nothing.messageKey());
        for (TxResult.Reason reason : TxResult.Reason.values()) {
            if (reason == TxResult.Reason.OK || reason == TxResult.Reason.NOTHING) continue;
            TxResult failure = TxResult.failed(reason, 10, 3);
            assertFalse(failure.ok());
            assertEquals(reason, failure.reason());
            assertEquals(0, failure.moved());
            assertEquals(10, failure.wanted());
            assertEquals(3, failure.best());
            assertEquals(7, failure.shortfall());
            String message = switch (reason) {
                case NO_CHANGE -> "bet.no_change";
                case PLAYER_SHORT, FELT_SHORT -> "bet.need_chips";
                default -> "bet.bank_short";
            };
            assertEquals(message, failure.messageKey());
        }
        assertEquals(0, TxResult.failed(TxResult.Reason.NO_CHANGE, 3, 10).shortfall());
        assertEquals(new PayWinResult(0, 0, 0), PayWinResult.NONE);
        var win = new PayWinResult(10, 6, 2);
        assertEquals(10, win.moved());
        assertEquals(6, win.trayMoved());
        assertEquals(2, win.owe());
    }

    @Test
    void votesRequireStrictMajorityAndCancelExpiryOnce() {
        UUID proposer = UUID.randomUUID();
        ItemStack item = mock(ItemStack.class);
        WagerVote vote = new WagerVote(proposer, item, 25);
        assertSame(proposer, vote.proposerId());
        assertSame(item, vote.item());
        assertEquals(25, vote.denars());
        assertFalse(vote.majorityYes());
        assertTrue(vote.allVoted());
        UUID other = UUID.randomUUID();
        vote.eligible().addAll(List.of(proposer, other));
        assertFalse(vote.allVoted());
        vote.yes().add(proposer);
        assertFalse(vote.majorityYes());
        assertFalse(vote.allVoted());
        vote.no().add(other);
        assertTrue(vote.allVoted());
        vote.no().clear();
        vote.yes().add(other);
        assertTrue(vote.majorityYes());
        assertTrue(vote.allVoted());
        vote.cancelExpire();
        BukkitTask task = mock(BukkitTask.class);
        vote.setExpireTask(task);
        assertSame(task, vote.expireTask());
        vote.cancelExpire();
        vote.cancelExpire();
        verify(task).cancel();
        assertNull(vote.expireTask());
    }

    @Test
    void visualPilesKeepPositionTokensAndClampedPieceCounts() {
        UUID owner = UUID.randomUUID();
        ItemStack item = mock(ItemStack.class);
        PotPile pile = new PotPile(owner, item, "coin", 5, 2, 3);
        assertSame(owner, pile.ownerId());
        assertSame(item, pile.item());
        assertEquals("coin", pile.typeKey());
        assertEquals(5, pile.denars());
        assertEquals(2, pile.x());
        assertEquals(3, pile.z());
        pile.setX(8);
        pile.setZ(9);
        assertEquals(8, pile.x());
        assertEquals(9, pile.z());
        pile.setPieces(-1);
        assertEquals(0, pile.pieces());
        pile.addPieces(0);
        pile.addPieces(-5);
        pile.addPieces(2);
        assertEquals(2, pile.pieces());
        pile.setPieces(6);
        assertEquals(6, pile.pieces());
        pile.setStreetId(3);
        assertEquals(3, pile.streetId());
        pile.tokens().add(owner);
        pile.layerYaws().add(90f);
        assertEquals(List.of(owner), pile.tokens());
        assertEquals(List.of(90f), pile.layerYaws());
        PayoutFlight payout = new PayoutFlight(pile, owner);
        assertSame(pile, payout.pile());
        assertSame(owner, payout.destId());
        assertFalse(payout.stayOnTray());
        PayoutFlight tray = PayoutFlight.toTray(pile);
        assertSame(pile, tray.pile());
        assertNull(tray.destId());
        assertTrue(tray.stayOnTray());
    }

    @Test
    void winningEventSharesHandlerListAndPreservesPreTaxProfit() {
        Player player = mock(Player.class);
        PlayerWonMoneyEvent event = new PlayerWonMoneyEvent(player, 12.5, "blackjack");
        assertSame(player, event.getPlayer());
        assertEquals(12.5, event.getProfit());
        assertEquals("blackjack", event.getGame());
        assertFalse(event.isAsynchronous());
        assertSame(PlayerWonMoneyEvent.getHandlerList(), event.getHandlers());
        assertSame(event.getHandlers(), new PlayerWonMoneyEvent(player, 1, "poker").getHandlers());
    }
}
