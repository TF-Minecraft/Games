package net.tfminecraft.games.game;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.junit.jupiter.api.Test;

class DrawGameTest extends GameScenarioFixture {
    @Override Game newGame() { return new DrawGame(); }
    @Override String gameId() { return "draw"; }

    @BeforeEach
    void cards() {
        give(alice, 14, 14, 8, 7, 2);
        give(bob, 13, 13, 9, 6, 3);
        give(carol, 12, 12, 10, 5, 4);
    }

    @Test
    void dealsFiveRoundsLeftOfButtonThenAllowsOnlyActorToBet() {
        start(alice, bob, carol);
        assertEquals(15, dealt.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(List.of(bob.getUniqueId(), carol.getUniqueId(), alice.getUniqueId()),
                    dealt.subList(i * 3, i * 3 + 3));
        }
        assertEquals(5, table.handOf(alice.getUniqueId()).size());
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(1, table.street());
        assertEquals(bob.getUniqueId(), table.actor());
        assertTrue(game.allowPlayChat(table, bob));
        assertFalse(game.allowPlayChat(table, alice));
        assertFalse(game.allowReturnSelected(table, bob));
        assertFalse(game.allowFreeDraw(table, bob));
        assertFalse(game.showStockDealer());
        verify(manager).reshuffleFull(table);
    }

    @Test
    void replacementWaitsForAnimationThenSecondBettingRoundPaysImprovedHand() {
        invested.put(alice.getUniqueId(), 10);
        invested.put(bob.getUniqueId(), 10);
        start(alice, bob);
        enterDraw();
        assertTrue(game.allowReturnSelected(table, bob));
        assertFalse(game.allowReturnSelected(table, alice));
        table.handOf(bob.getUniqueId()).getFirst().setSelected(true);
        act("draw");
        assertEquals(bob.getUniqueId(), table.actor(), "selected cards require replacement, not standing pat");
        table.handOf(bob.getUniqueId()).subList(0, 2).clear();
        give(bob, 14, 14);
        ((DrawGame) game).onReturnedSelected(table, bob, 2);
        assertEquals(bob.getUniqueId(), table.actor(), "turn stays put during animation");
        drain();
        assertEquals(alice.getUniqueId(), table.actor());
        assertFalse(game.allowReturnSelected(table, bob));
        assertEquals(5, table.handOf(bob.getUniqueId()).size());
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(2, table.street());
        assertEquals(bob.getUniqueId(), table.actor());
        act("check");
        act("check");
        assertEquals(DrawGame.SHOWDOWN, table.phase());
        assertFalse(game.allowPlayChat(table, alice));
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(20), anyList(), eq("pot"));
        verify(manager).publishHand(table, alice);
        verify(manager).publishHand(table, bob);
        drain();
        assertFalse(table.live());
        assertEquals(bob.getUniqueId(), table.dealerId());
        assertTrue(table.getHands().isEmpty());
    }

    @Test
    void repeatedDrawChatCannotAdvanceTurnWhileReplacementCardsAreStillArriving() {
        start(alice, bob);
        enterDraw();
        // TableManager removes returned cards before notifying the game to replace them.
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        int dealtBefore = dealt.size();
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        assertEquals(bob.getUniqueId(), table.actor());
        for (int attempt = 0; attempt < 2; attempt++) {
            // This is the same public eligibility check used by TableManager's chat dispatch.
            if (game.allowPlayChat(table, bob)) {
                game.onPlayWord(table, bob, "draw");
            }
        }
        assertNotEquals(alice.getUniqueId(), table.actor(), "replacement animation must finish before the next turn");
        assertEquals(DrawGame.DRAW, table.phase());
        assertFalse(game.allowPlayChat(table, bob), "the pending draw cannot accept another chat action");
        assertFalse(game.allowReturnSelected(table, bob), "the same draw cannot request another replacement");
        drain();
        assertEquals(alice.getUniqueId(), table.actor());
        assertEquals(5, table.handOf(bob.getUniqueId()).size());
        assertEquals(dealtBefore + 1, dealt.size());
        assertFalse(game.allowPlayChat(table, bob));
        assertTrue(game.allowPlayChat(table, alice));
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(2, table.street());
    }

    @Test
    void departedDrawersOldCompletionCannotAdvanceAReplacementForTheNextPlayer() {
        start(alice, bob, carol);
        enterDraw();
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        Runnable oldCompletion = animations.removeFirst();
        table.getHands().remove(bob.getUniqueId());
        game.onLeave(table, bob);
        drain();
        assertNotEquals(bob.getUniqueId(), table.actor());
        org.bukkit.entity.Player next = org.bukkit.Bukkit.getPlayer(table.actor());
        assertNotNull(next);
        table.handOf(next.getUniqueId()).removeFirst();
        give(next, 11);
        ((DrawGame) game).onReturnedSelected(table, next, 1);
        Runnable newCompletion = animations.removeFirst();
        oldCompletion.run();
        assertEquals(next.getUniqueId(), table.actor());
        assertFalse(game.allowPlayChat(table, next), "old completion must not clear the new player's pending draw");
        assertEquals(DrawGame.DRAW, table.phase());
        newCompletion.run();
        assertNotEquals(next.getUniqueId(), table.actor());
        assertTrue(table.actives().contains(table.actor()));
        assertEquals(5, table.handOf(next.getUniqueId()).size());
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(2, table.street());
        verify(wager).refundStreet(eq(table), eq(bob.getUniqueId()), eq(1), anyList(), eq("player left"));
        verify(wager, never()).sweepPot(any(), any(), any(), anyList(), anyString());
    }

    @Test
    void anotherPlayersDepartureWaitsForTheCurrentReplacementToFinish() {
        start(alice, bob, carol);
        enterDraw();
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        Runnable replacement = animations.removeFirst();
        table.getHands().remove(alice.getUniqueId());
        game.onLeave(table, alice);
        drain();
        assertEquals(bob.getUniqueId(), table.actor());
        assertFalse(game.allowPlayChat(table, bob));
        assertEquals(DrawGame.DRAW, table.phase());
        replacement.run();
        assertEquals(carol.getUniqueId(), table.actor());
        assertTrue(game.allowPlayChat(table, carol));
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(2, table.street());
        verify(wager, never()).sweepPot(any(), any(), any(), anyList(), anyString());
    }

    @Test
    void lastOpponentLeavingSettlesHandEvenWhileWinnerHasPendingReplacement() {
        start(alice, bob);
        enterDraw();
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        Runnable replacement = animations.removeFirst();
        table.getHands().remove(alice.getUniqueId());
        game.onLeave(table, alice);
        drain();
        assertFalse(table.live());
        verify(wager).sweepPot(eq(table), eq(bob), eq(bob.getUniqueId()), anyList(), eq("hand abandoned"));
        replacement.run();
        assertFalse(table.live());
        assertNull(table.actor());
        assertTrue(table.getHands().isEmpty());
        verify(manager, times(1)).endSession(table);
    }

    @Test
    void completedOldSessionCannotReleaseTheSamePlayersNewPendingDraw() {
        start(alice, bob);
        enterDraw();
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        Runnable oldCompletion = animations.removeFirst();
        manager.endSession(table);
        give(alice, 14, 14, 8, 7, 2);
        give(bob, 13, 13, 9, 6, 3);
        table.startSession();
        game.onSessionStart(table);
        drain();
        enterDraw();
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        Runnable newCompletion = animations.removeFirst();
        oldCompletion.run();
        assertEquals(bob.getUniqueId(), table.actor());
        assertFalse(game.allowPlayChat(table, bob), "matching owner IDs do not make callbacks from an old hand current");
        newCompletion.run();
        assertEquals(alice.getUniqueId(), table.actor());
        assertEquals(5, table.handOf(bob.getUniqueId()).size());
    }

    @Test
    void onlyUnfoldedSurvivorWinsDespitePendingReplacementAndAnotherSeatedFoldedPlayer() {
        start(alice, bob, carol);
        act("check");
        act("check");
        act("fold");
        assertEquals(DrawGame.DRAW, table.phase());
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        Runnable replacement = animations.removeFirst();
        table.getHands().remove(carol.getUniqueId());
        game.onLeave(table, carol);
        drain();
        verify(wager).sweepPot(eq(table), eq(bob), eq(bob.getUniqueId()), anyList(), eq("fold win"));
        assertFalse(table.live());
        replacement.run();
        verify(wager, times(1)).sweepPot(eq(table), eq(bob), eq(bob.getUniqueId()), anyList(), eq("fold win"));
        verify(manager, times(1)).endSession(table);
        assertNull(table.actor());
    }

    @Test
    void standingPatKeepsCardsAndUnrecognizedDrawWordsDoNotAdvance() {
        start(alice, bob);
        enterDraw();
        var bobCards = List.copyOf(table.handOf(bob.getUniqueId()));
        act("check");
        assertEquals(bob.getUniqueId(), table.actor());
        act("draw");
        assertEquals(alice.getUniqueId(), table.actor());
        assertEquals(bobCards, table.handOf(bob.getUniqueId()));
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(2, table.street());
        act("check");
        act("check");
        assertFalse(table.live(), "a zero-pot hand still finishes");
        verify(wager, never()).payFromPot(any(), any(), any(), anyInt(), anyList(), anyString());
    }

    @Test
    void bettingRejectsEmptyRaiseAndUnmatchedCheckButAllowsShortAllInCall() {
        start(alice, bob, carol);
        act("raise");
        assertEquals(bob.getUniqueId(), table.actor());
        contribute(bob, 10);
        act("raise");
        act("check");
        assertEquals(carol.getUniqueId(), table.actor());
        contribute(carol, 3);
        act("call");
        contribute(alice, 20);
        act("raise");
        assertEquals(bob.getUniqueId(), table.actor());
        assertTrue(game.extraLabel(table).contains("10"));
        contribute(bob, 20);
        act("call");
        assertEquals(DrawGame.DRAW, table.phase());
        assertEquals(bob.getUniqueId(), table.actor());
        act("draw");
        assertEquals(carol.getUniqueId(), table.actor(), "all-in player still gets a draw turn");
        act("draw");
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(2, table.street());
    }

    @Test
    void foldWinsPotImmediatelyAndFoldedSeatCannotReenter() {
        start(alice, bob, carol);
        act("fold");
        assertEquals(carol.getUniqueId(), table.actor());
        assertFalse(game.allowPlayChat(table, bob), "TableManager refuses chat from a folded seat");
        assertEquals(carol.getUniqueId(), table.actor());
        act("fold");
        verify(wager).sweepPot(eq(table), eq(alice), eq(alice.getUniqueId()), anyList(), eq("fold win"));
        assertNull(table.actor(), "nobody holds the turn while the fold win pays out");
        assertFalse(game.allowPlayChat(table, carol));
        verify(manager, never()).publishHand(any(), any());
        drain();
        assertFalse(table.live());
    }

    @Test
    void shortStackWinsMainPotAndStrongerRemainingHandWinsSidePot() {
        invested.put(alice.getUniqueId(), 5);
        invested.put(bob.getUniqueId(), 10);
        invested.put(carol.getUniqueId(), 10);
        start(alice, bob, carol);
        standPatToShowdown();
        verify(wager).payFromPot(eq(table), eq(alice), eq(alice.getUniqueId()), eq(15), anyList(), eq("pot"));
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(10), anyList(), eq("pot"));
        verify(wager, never()).payFromPot(eq(table), eq(carol), any(), anyInt(), anyList(), anyString());
        drain();
        assertFalse(table.live());
    }

    @Test
    void tiedHandsShareFoldedContributionWithOddChipLeftOfButton() {
        give(alice, 14, 13, 12, 11, 10);
        give(bob, 14, 13, 12, 11, 10);
        invested.put(alice.getUniqueId(), 1);
        invested.put(bob.getUniqueId(), 1);
        invested.put(carol.getUniqueId(), 1);
        start(alice, bob, carol);
        act("check");
        act("fold");
        act("check");
        standPatToShowdown();
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(2), anyList(), eq("pot"));
        verify(wager).payFromPot(eq(table), eq(alice), eq(alice.getUniqueId()), eq(1), anyList(), eq("pot"));
        verify(manager, never()).publishHand(table, carol);
    }

    @Test
    void leavingDrawRoundRefundsOnlyCurrentStreetAndKeepsOtherPlayersDrawing() {
        start(alice, bob, carol);
        enterDraw();
        game.onLeave(table, bob);
        drain();
        assertTrue(table.live());
        assertEquals(DrawGame.DRAW, table.phase());
        assertNotEquals(bob.getUniqueId(), table.actor());
        verify(wager).refundStreet(eq(table), eq(bob.getUniqueId()), eq(1), anyList(), eq("player left"));
        act("draw");
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(2, table.street());
    }

    @Test
    void buttonLeavingHeadsUpEndsAfterRefundAnimationAndPassesButton() {
        start(alice, bob);
        game.onLeave(table, alice);
        assertEquals(bob.getUniqueId(), table.dealerId());
        verify(wager).sweepPot(eq(table), eq(bob), eq(bob.getUniqueId()), anyList(), eq("hand abandoned"));
        assertTrue(table.live());
        drain();
        assertFalse(table.live());
    }

    @Test
    void removingTableDuringReplacementDoesNotContinueDrawRound() {
        start(alice, bob);
        enterDraw();
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        game.onTableRemoved(table);
        when(manager.table(table.getId())).thenReturn(null);
        drain();
        assertEquals(bob.getUniqueId(), table.actor());
        assertEquals(DrawGame.DRAW, table.phase());
        verify(manager, never()).endSession(table);
    }

    @Test
    void endedSessionDoesNotResumeInitialDealCallbacks() {
        seat(alice, bob);
        table.startSession();
        game.onSessionStart(table);
        manager.endSession(table);
        drain();
        assertFalse(table.live());
        assertEquals(1, dealt.size());
        assertNull(table.actor());
    }

    @Test
    void dealerClaimAndInitialChipRequireTwoSeatsAndIdleTable() {
        assertFalse(game.tryClaimDealer(table, alice));
        seat(alice);
        assertFalse(game.tryClaimDealer(table, alice));
        table.setDealerId(null);
        game.onChipIn(table, alice, 5, null);
        assertEquals(alice.getUniqueId(), table.dealerId());
        assertTrue(game.allowReturnSelected(table, alice));
        assertTrue(game.allowFreeDraw(table, alice));
        seat(bob);
        assertTrue(game.tryClaimDealer(table, alice));
        verify(manager).beginSession(table);
        ((DrawGame) game).passButton(table);
        assertEquals(bob.getUniqueId(), table.dealerId());
        ((DrawGame) game).passButton(table);
        assertEquals(alice.getUniqueId(), table.dealerId());
    }

    @Test
    void insufficientSeatsEndSessionWithoutDealing() {
        seat(alice);
        table.startSession();
        game.onSessionStart(table);
        assertFalse(table.live());
        assertTrue(dealt.isEmpty());
    }

    @Test
    void lateRaiseReopensActionForBothPreviouslyCheckedPlayers() {
        start(alice, bob, carol);
        act("check");
        act("check");
        contribute(alice, 10);
        act("raise");
        assertEquals(bob.getUniqueId(), table.actor());
        contribute(bob, 10);
        act("call");
        assertEquals(carol.getUniqueId(), table.actor());
        assertEquals(DrawGame.BET, table.phase());
        contribute(carol, 10);
        act("call");
        assertEquals(DrawGame.DRAW, table.phase());
    }

    @Test
    void foldedSeatIsSkippedThroughoutDrawAndSecondBettingRound() {
        start(alice, bob, carol);
        act("fold");
        act("check");
        act("check");
        assertEquals(DrawGame.DRAW, table.phase());
        assertEquals(carol.getUniqueId(), table.actor());
        assertFalse(game.allowReturnSelected(table, bob));
        act("draw");
        assertEquals(alice.getUniqueId(), table.actor());
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(carol.getUniqueId(), table.actor());
        act("check");
        act("check");
        assertFalse(table.live());
        verify(manager, never()).publishHand(table, bob);
    }

    @Test
    void endingSessionDuringReplacementCancelsDrawContinuation() {
        start(alice, bob);
        enterDraw();
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        manager.endSession(table);
        drain();
        assertFalse(table.live());
        assertNull(table.actor());
        assertTrue(table.getHands().isEmpty());
        verify(manager, times(1)).endSession(table);
    }

    @Test
    void unmatchedTopContributionReturnsToItsOnlyEligiblePlayer() {
        invested.put(alice.getUniqueId(), 5);
        invested.put(bob.getUniqueId(), 10);
        invested.put(carol.getUniqueId(), 15);
        start(alice, bob, carol);
        standPatToShowdown();
        verify(wager).payFromPot(eq(table), eq(alice), eq(alice.getUniqueId()), eq(15), anyList(), eq("pot"));
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(10), anyList(), eq("pot"));
        verify(wager).payFromPot(eq(table), eq(carol), eq(carol.getUniqueId()), eq(5), anyList(), eq("pot"));
        drain();
        assertFalse(table.live());
    }

    @Test
    void removingTableDuringShowdownPayoutDoesNotFinishRemovedSession() {
        invested.put(alice.getUniqueId(), 10);
        invested.put(bob.getUniqueId(), 10);
        start(alice, bob);
        standPatToShowdown();
        assertEquals(DrawGame.SHOWDOWN, table.phase());
        game.onTableRemoved(table);
        when(manager.table(table.getId())).thenReturn(null);
        drain();
        verify(manager, never()).endSession(table);
        assertEquals(alice.getUniqueId(), table.dealerId());
    }

    @Test
    void nextHandAllowsPreviouslyFoldedPlayerToActWithFreshCards() {
        start(alice, bob);
        act("fold");
        drain();
        assertFalse(table.live());
        give(alice, 14, 14, 8, 7, 2);
        give(bob, 13, 13, 9, 6, 3);
        table.startSession();
        game.onSessionStart(table);
        drain();
        assertEquals(alice.getUniqueId(), table.actor());
        act("check");
        assertEquals(bob.getUniqueId(), table.actor());
        act("check");
        assertEquals(DrawGame.DRAW, table.phase());
        assertEquals(alice.getUniqueId(), table.actor());
        assertEquals(5, table.handOf(bob.getUniqueId()).size());
    }

    @Test
    void lastIdleSeatLeavingReturnsRemainingPotAndClearsButton() {
        seat(alice);
        game.onLeave(table, alice);
        drain();
        assertTrue(table.actives().isEmpty());
        assertNull(table.dealerId());
        verify(wager).sweepPot(eq(table), eq(alice), eq(alice.getUniqueId()), anyList(), eq("hand abandoned"));
        verify(manager, never()).endSession(table);
    }

    @Test
    void disconnectedSeatDoesNotCountTowardStartingHand() {
        seat(alice, bob);
        ((org.mockbukkit.mockbukkit.entity.PlayerMock) bob).disconnect();
        table.startSession();
        game.onSessionStart(table);
        assertFalse(table.live());
        assertTrue(dealt.isEmpty());
        verify(manager).endSession(table);
    }

    @Test
    void labelTracksBetDrawAndShowdownWithoutStaleCallAmount() {
        start(alice, bob);
        contribute(bob, 10);
        act("raise");
        String facingBet = game.extraLabel(table);
        assertTrue(facingBet.contains("label.button"));
        assertTrue(facingBet.contains("label.draw_bet"));
        assertTrue(facingBet.lines().anyMatch(line -> line.startsWith("label.turn") && line.contains(alice.getName())));
        assertTrue(facingBet.lines().anyMatch(line -> line.startsWith("label.draw_tocall") && line.contains("n, 10")));
        contribute(alice, 10);
        invested.put(alice.getUniqueId(), 10);
        invested.put(bob.getUniqueId(), 10);
        act("call");
        String drawing = game.extraLabel(table);
        assertTrue(drawing.contains("label.draw_draw"));
        assertTrue(drawing.lines().anyMatch(line -> line.startsWith("label.turn") && line.contains(bob.getName())));
        assertFalse(drawing.contains("label.draw_tocall"));
        assertTrue(drawing.lines().anyMatch(line -> line.startsWith("label.pot[") && line.contains("n, 20")));
        act("draw");
        act("draw");
        String secondBet = game.extraLabel(table);
        assertTrue(secondBet.contains("label.draw_bet"));
        assertTrue(secondBet.lines().anyMatch(line -> line.startsWith("label.draw_tocall") && line.contains("n, 0")));
        act("check");
        act("check");
        String showdown = game.extraLabel(table);
        assertTrue(showdown.contains("label.draw_showdown"));
        assertFalse(showdown.contains("label.turn"));
        assertFalse(showdown.contains("label.draw_tocall"));
    }

    @Test
    void bettingActorLeavingKeepsOutstandingRaiseForRemainingPlayer() {
        start(alice, bob, carol);
        contribute(bob, 20);
        act("raise");
        assertEquals(carol.getUniqueId(), table.actor());
        game.onLeave(table, carol);
        drain();
        assertEquals(alice.getUniqueId(), table.actor());
        act("check");
        assertEquals(alice.getUniqueId(), table.actor(), "departure must not erase the outstanding bet");
        contribute(alice, 20);
        act("call");
        assertEquals(DrawGame.DRAW, table.phase());
        assertEquals(bob.getUniqueId(), table.actor());
        verify(wager).refundStreet(eq(table), eq(carol.getUniqueId()), eq(1), anyList(), eq("player left"));
        verify(manager, never()).endSession(table);
    }

    @Test
    void nonActingButtonLeavingDoesNotConsumeCurrentPlayersBettingTurn() {
        start(alice, bob, carol);
        game.onLeave(table, alice);
        drain();
        assertEquals(bob.getUniqueId(), table.dealerId());
        assertEquals(bob.getUniqueId(), table.actor());
        assertEquals(DrawGame.BET, table.phase());
        act("check");
        assertEquals(carol.getUniqueId(), table.actor());
        act("check");
        assertEquals(DrawGame.DRAW, table.phase());
        assertEquals(carol.getUniqueId(), table.actor(), "draw starts left of the new button");
    }

    @Test
    void drawChatWordDuringBettingCannotSkipThePlayersBettingDecision() {
        start(alice, bob);
        var before = List.copyOf(table.handOf(bob.getUniqueId()));
        act("draw");
        assertEquals(bob.getUniqueId(), table.actor());
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(before, table.handOf(bob.getUniqueId()));
        act("check");
        assertEquals(alice.getUniqueId(), table.actor());
        assertEquals(DrawGame.BET, table.phase());
    }

    @Test
    void departureRefundLandingAfterAFoldWinDoesNotSettleTheHandAgain() {
        start(alice, bob, carol);
        game.onLeave(table, carol);
        Runnable departure = animations.removeFirst();
        act("fold");
        departure.run();
        drain();
        verify(wager, times(1)).sweepPot(eq(table), eq(alice), eq(alice.getUniqueId()), anyList(), eq("fold win"));
        verify(wager, times(1)).announceWins(table, "draw");
        verify(manager, times(1)).endSession(table);
        assertEquals(bob.getUniqueId(), table.dealerId(), "a settled hand passes the button exactly once");
    }

    @Test
    void departedDrawerCannotUseChatDuringTheirRefundToMoveTheTurn() {
        start(alice, bob, carol);
        enterDraw();
        assertEquals(bob.getUniqueId(), table.actor());
        game.onLeave(table, bob);
        Runnable departure = animations.removeFirst();
        // TableManager dispatches chat only after this same eligibility check.
        if (game.allowPlayChat(table, bob)) {
            game.onPlayWord(table, bob, "draw");
        }
        assertFalse(game.allowPlayChat(table, bob), "a player who has left the table no longer acts");
        assertFalse(game.allowReturnSelected(table, bob), "a player who has left the table cannot draw");
        assertEquals(bob.getUniqueId(), table.actor(), "the turn moves on only when the refund lands");
        departure.run();
        UUID first = table.actor();
        assertTrue(List.of(alice.getUniqueId(), carol.getUniqueId()).contains(first));
        act("draw");
        UUID second = table.actor();
        assertTrue(List.of(alice.getUniqueId(), carol.getUniqueId()).contains(second));
        assertNotEquals(first, second, "each remaining player draws once");
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(2, table.street());
    }

    @Test
    void handsAreDealtAroundAnOfflineSeat() {
        ((PlayerMock) carol).disconnect();
        start(alice, bob, carol);
        assertEquals(10, dealt.size());
        assertFalse(dealt.contains(carol.getUniqueId()));
        assertTrue(table.handOf(carol.getUniqueId()).isEmpty());
        assertEquals(5, table.handOf(bob.getUniqueId()).size());
    }

    @Test
    void removingTheTableDuringTheInitialDealStopsDealing() {
        seat(alice, bob);
        table.startSession();
        game.onSessionStart(table);
        when(manager.table(table.getId())).thenReturn(null);
        game.onTableRemoved(table);
        drain();
        assertEquals(1, dealt.size());
        assertNull(table.actor());
    }

    @Test
    void allInSeatIsSkippedWhileTheOthersKeepRaising() {
        start(alice, bob, carol);
        contribute(bob, 10);
        act("raise");
        contribute(carol, 3);
        act("call");
        contribute(alice, 20);
        act("raise");
        assertEquals(bob.getUniqueId(), table.actor());
        contribute(bob, 30);
        act("raise");
        assertEquals(alice.getUniqueId(), table.actor(), "the all-in seat cannot be asked to call again");
        contribute(alice, 30);
        act("call");
        assertEquals(DrawGame.DRAW, table.phase());
    }

    @Test
    void foldedSeatIsPassedOverInTheDrawOrder() {
        start(alice, bob, carol);
        act("check");
        act("fold");
        act("check");
        assertEquals(DrawGame.DRAW, table.phase());
        assertEquals(bob.getUniqueId(), table.actor());
        act("draw");
        assertEquals(alice.getUniqueId(), table.actor());
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
    }

    @Test
    void foldedTopContributorsUnmatchedExcessGoesToTheMainPotWinner() {
        invested.put(alice.getUniqueId(), 10);
        invested.put(bob.getUniqueId(), 5);
        invested.put(carol.getUniqueId(), 5);
        start(alice, bob, carol);
        act("check");
        act("check");
        act("fold");
        standPatToShowdown();
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(15), anyList(), eq("pot"));
        verify(wager).sweepPot(eq(table), eq(bob), eq(bob.getUniqueId()), anyList(), eq("pot remainder"));
        verify(wager, never()).payFromPot(eq(table), eq(alice), any(), anyInt(), anyList(), anyString());
    }

    @Test
    void replacementLandingBeforeTheLastOpponentsRefundSettlesTheHandOnce() {
        start(alice, bob, carol);
        act("check");
        act("fold");
        act("check");
        assertEquals(bob.getUniqueId(), table.actor());
        table.handOf(bob.getUniqueId()).removeFirst();
        give(bob, 4);
        ((DrawGame) game).onReturnedSelected(table, bob, 1);
        Runnable replacement = animations.removeFirst();
        game.onLeave(table, alice);
        replacement.run();
        verify(wager, times(1)).sweepPot(eq(table), eq(bob), eq(bob.getUniqueId()), anyList(), eq("fold win"));
        assertNull(table.actor());
        drain();
        verify(wager, times(1)).sweepPot(eq(table), eq(bob), eq(bob.getUniqueId()), anyList(), eq("fold win"));
        verify(manager, times(1)).endSession(table);
        assertFalse(table.live());
    }

    @Test
    void stakesGoBackWhenEveryUnfoldedPlayerLeavesAFourSeatHand() {
        org.bukkit.entity.Player dave = org.mockbukkit.mockbukkit.MockBukkit.getMock().addPlayer();
        give(dave, 11, 11, 9, 4, 3);
        start(alice, bob, carol, dave);
        act("fold");
        act("fold");
        assertEquals(dave.getUniqueId(), table.actor());
        game.onLeave(table, dave);
        game.onLeave(table, alice);
        drain();
        verify(wager).returnStakes(eq(table), anyList(), eq("hand abandoned"));
        verify(wager, never()).sweepPot(any(), any(), any(), anyList(), eq("fold win"));
        verify(manager, times(1)).endSession(table);
        assertFalse(table.live());
    }

    @Test
    void offlineLastSeatCannotCollectAFoldWinSoEveryStakeGoesBack() {
        table.setDealerId(carol.getUniqueId());
        ((PlayerMock) carol).disconnect();
        start(alice, bob, carol);
        assertEquals(alice.getUniqueId(), table.actor());
        act("fold");
        act("fold");
        verify(wager).returnStakes(eq(table), anyList(), eq("hand abandoned"));
        verify(wager, never()).sweepPot(any(), any(), any(), anyList(), anyString());
    }

    @Test
    void departedButtonIsReplacedByTheFirstSeatAndPotsCannotBeFlushedByHand() {
        seat(alice, bob);
        assertFalse(game.allowManualPotFlush(table, alice), "only a showdown or fold pays a draw pot");
        // TableManager clears a departed dealer before telling the game.
        table.setDealerId(null);
        game.onDealerGone(table);
        assertEquals(alice.getUniqueId(), table.dealerId());
        verify(manager, atLeastOnce()).refreshLabel(table);
    }

    @Test
    void wagerCommitIsNotADrawBetAndLeavesTheTurnAlone() {
        start(alice, bob);
        game.onStreetCommit(table, bob, 5, false);
        game.onStreetCommit(table, bob, 0, true);
        game.onShoeClick(table, bob);
        assertEquals(bob.getUniqueId(), table.actor());
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(10, dealt.size());
        act("check");
        assertEquals(alice.getUniqueId(), table.actor());
    }

    @Test
    void bettingActorLeavingPassesTheTurnToTheNextSeatNotBackToTheButton() {
        org.bukkit.entity.Player dave = org.mockbukkit.mockbukkit.MockBukkit.getMock().addPlayer();
        give(dave, 11, 11, 9, 4, 3);
        start(alice, bob, carol, dave);
        act("check");
        assertEquals(carol.getUniqueId(), table.actor());
        game.onLeave(table, carol);
        drain();
        assertEquals(dave.getUniqueId(), table.actor(), "the seat after the leaver bets next");
        act("check");
        assertEquals(alice.getUniqueId(), table.actor());
        act("check");
        assertEquals(DrawGame.DRAW, table.phase());
    }

    @Test
    void drawingActorLeavingPassesTheTurnToTheNextSeatNotBackToTheButton() {
        org.bukkit.entity.Player dave = org.mockbukkit.mockbukkit.MockBukkit.getMock().addPlayer();
        give(dave, 11, 11, 9, 4, 3);
        start(alice, bob, carol, dave);
        enterDraw();
        assertEquals(bob.getUniqueId(), table.actor());
        act("draw");
        assertEquals(carol.getUniqueId(), table.actor());
        game.onLeave(table, carol);
        drain();
        assertEquals(dave.getUniqueId(), table.actor(), "the seat after the leaver draws next");
        act("draw");
        assertEquals(alice.getUniqueId(), table.actor());
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
        assertEquals(2, table.street());
    }

    @Test
    void drawingActorLeavingSkipsSeatsThatHaveAlreadyDrawn() {
        table.setDealerId(carol.getUniqueId());
        seat(alice, bob, carol);
        table.startSession();
        game.onSessionStart(table);
        drain();
        enterDraw();
        assertEquals(alice.getUniqueId(), table.actor());
        act("draw");
        assertEquals(bob.getUniqueId(), table.actor());
        game.onLeave(table, bob);
        drain();
        assertEquals(carol.getUniqueId(), table.actor(), "a seat that has drawn does not draw again");
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
    }

    @Test
    void anotherSeatLeavingDuringTheDrawKeepsTheCurrentDrawersTurn() {
        start(alice, bob, carol);
        enterDraw();
        assertEquals(bob.getUniqueId(), table.actor());
        game.onLeave(table, alice);
        drain();
        assertEquals(bob.getUniqueId(), table.actor(), "a departure elsewhere does not skip the drawer");
        act("draw");
        assertEquals(carol.getUniqueId(), table.actor());
        act("draw");
        assertEquals(DrawGame.BET, table.phase());
    }

    @Test
    void showdownShowsOnlyTheHandsOfOnlineSeats() {
        start(alice, bob, carol);
        int turns = 0;
        drain();
        while (!(DrawGame.BET.equals(table.phase()) && table.street() == 2 && table.actor() != null)) {
            assertTrue(turns++ < 30, "hand did not reach the last betting round");
            act(DrawGame.DRAW.equals(table.phase()) ? "draw" : "check");
            drain();
        }
        act("check");
        act("check");
        Player last = Bukkit.getPlayer(table.actor());
        Player gone = last == carol ? bob : carol;
        // A seat restored from disk can be offline without ever having left the table.
        ((PlayerMock) gone).disconnect();
        act("check");
        verify(manager).publishHand(table, last);
        verify(manager, never()).publishHand(eq(table), isNull());
    }

    private void enterDraw() {
        int turns = 0;
        while (DrawGame.BET.equals(table.phase())) {
            assertTrue(turns++ < 10, "first betting round did not complete");
            act("check");
        }
        assertEquals(DrawGame.DRAW, table.phase());
    }

    private void standPatToShowdown() {
        int turns = 0;
        while (table.live() && !DrawGame.SHOWDOWN.equals(table.phase())) {
            assertTrue(turns++ < 30, "draw hand did not reach showdown");
            act(DrawGame.DRAW.equals(table.phase()) ? "draw" : "check");
        }
    }
}
