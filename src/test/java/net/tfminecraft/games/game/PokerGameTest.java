package net.tfminecraft.games.game;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.table.HandCard;
import net.tfminecraft.games.table.ShufflePolicy;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.wager.WagerEngine;

class PokerGameTest extends GameScenarioFixture {
    @Override Game newGame() { return new PokerGame(); }
    @Override String gameId() { return "poker"; }

    @BeforeEach
    void cards() {
        give(alice, 14, 14);
        give(bob, 13, 13);
        give(carol, 12, 12);
        board.addAll(cards(2, 5, 8, 9, 10));
    }

    @Test
    void dealsTwoRoundsLeftOfButtonAndStartsPreflopOnlyAfterLastAnimation() {
        table.setShufflePolicy(ShufflePolicy.ROUND);
        seat(alice, bob, carol);
        table.startSession();
        game.onSessionStart(table);
        assertNull(table.actor());
        assertEquals(List.of(bob.getUniqueId()), dealt);
        drain();
        assertEquals(List.of(bob.getUniqueId(), carol.getUniqueId(), alice.getUniqueId(),
                bob.getUniqueId(), carol.getUniqueId(), alice.getUniqueId()), dealt);
        assertEquals(PokerGame.PREFLOP, table.phase());
        assertEquals(1, table.street());
        assertEquals(bob.getUniqueId(), table.actor());
        verify(manager).reshuffleFull(table);
        assertFalse(game.showStockDealer());
        assertFalse(game.allowFreeDraw(table, bob));
        assertFalse(game.allowReturnSelected(table, bob));
        assertTrue(game.allowPlayChat(table, bob));
        assertFalse(game.allowPlayChat(table, alice));
    }

    @Test
    void checksProgressThroughThreeOneOneBoardCardsThenPayBestHand() {
        invested.put(alice.getUniqueId(), 10);
        invested.put(bob.getUniqueId(), 10);
        start(alice, bob);
        for (String phase : List.of(PokerGame.FLOP, PokerGame.TURN, PokerGame.RIVER)) {
            act("check");
            act("check");
            assertEquals(phase, table.phase());
            assertNull(table.actor(), "betting must wait for the board animation");
            drain();
            assertEquals(bob.getUniqueId(), table.actor());
            assertTrue(game.extraLabel(table).contains("label.holdem_" + phase));
        }
        assertEquals(5, table.tablePile("board").size());
        act("check");
        act("check");
        assertEquals(PokerGame.SHOWDOWN, table.phase());
        assertNull(table.actor());
        assertFalse(game.allowPlayChat(table, alice));
        verify(manager).publishHand(table, alice);
        verify(manager).publishHand(table, bob);
        verify(wager).payFromPot(eq(table), eq(alice), eq(alice.getUniqueId()), eq(20), anyList(), eq("pot"));
        verify(wager).sweepPot(eq(table), eq(alice), eq(alice.getUniqueId()), anyList(), eq("pot remainder"));
        drain();
        assertFalse(table.live());
        assertEquals(bob.getUniqueId(), table.dealerId());
        assertTrue(table.getHands().isEmpty());
        verify(manager).dealToTable(eq(table), eq("board"), eq(3), eq(true), any(Runnable.class));
        verify(manager, times(2)).dealToTable(eq(table), eq("board"), eq(1), eq(true), any(Runnable.class));
    }

    @Test
    void raiseRequiresChipsAndReopensActionWhileShortCallCapsPlayer() {
        start(alice, bob, carol);
        act("raise");
        assertEquals(bob.getUniqueId(), table.actor());
        contribute(bob, 20);
        act("raise");
        assertEquals(carol.getUniqueId(), table.actor());
        act("check");
        assertEquals(carol.getUniqueId(), table.actor());
        contribute(carol, 5);
        act("call");
        assertEquals(alice.getUniqueId(), table.actor());
        contribute(alice, 30);
        act("raise");
        assertEquals(bob.getUniqueId(), table.actor());
        assertTrue(game.extraLabel(table).contains("10"));
        contribute(bob, 30);
        act("call");
        assertEquals(PokerGame.FLOP, table.phase());
        drain();
        assertEquals(bob.getUniqueId(), table.actor());
    }

    @Test
    void foldedSeatCannotActAgainAndLastSeatWinsWithoutShowingCards() {
        start(alice, bob, carol);
        act("fold");
        assertEquals(carol.getUniqueId(), table.actor());
        game.onPlayWord(table, bob, "raise");
        assertEquals(carol.getUniqueId(), table.actor());
        act("fold");
        verify(wager).sweepPot(eq(table), eq(alice), eq(alice.getUniqueId()), anyList(), eq("fold win"));
        verify(manager, never()).publishHand(any(), any());
        drain();
        assertFalse(table.live());
    }

    @Test
    void shortStackWinsMainPotWhileNextBestWinsSidePot() {
        invested.put(alice.getUniqueId(), 5);
        invested.put(bob.getUniqueId(), 10);
        invested.put(carol.getUniqueId(), 10);
        start(alice, bob, carol);
        checkToShowdown();
        verify(wager).payFromPot(eq(table), eq(alice), eq(alice.getUniqueId()), eq(15), anyList(), eq("pot"));
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(10), anyList(), eq("pot"));
        verify(wager, never()).payFromPot(eq(table), eq(carol), any(), anyInt(), anyList(), anyString());
        drain();
        assertFalse(table.live());
    }

    @Test
    void boardTieSplitsFoldedMoneyAndOddChipGoesLeftOfButton() {
        give(alice, 2, 3);
        give(bob, 4, 5);
        give(carol, 6, 7);
        board.clear();
        board.addAll(cards(10, 11, 12, 13, 14));
        invested.put(alice.getUniqueId(), 1);
        invested.put(bob.getUniqueId(), 1);
        invested.put(carol.getUniqueId(), 1);
        start(alice, bob, carol);
        act("check");
        act("fold");
        act("check");
        drain();
        checkToShowdown();
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(2), anyList(), eq("pot"));
        verify(wager).payFromPot(eq(table), eq(alice), eq(alice.getUniqueId()), eq(1), anyList(), eq("pot"));
        verify(manager, never()).publishHand(table, carol);
    }

    @Test
    void checkedHandWithoutMoneyEndsWithoutPayout() {
        start(alice, bob);
        checkToShowdown();
        assertFalse(table.live());
        verify(wager, never()).payFromPot(any(), any(), any(), anyInt(), anyList(), anyString());
        assertEquals(bob.getUniqueId(), table.dealerId());
    }

    @Test
    void leavingRefundsCurrentStreetAndHandsAbandonedPotToRemainingSeat() {
        start(alice, bob);
        game.onLeave(table, alice);
        assertEquals(bob.getUniqueId(), table.dealerId());
        verify(wager).refundStreet(eq(table), eq(alice.getUniqueId()), eq(1), anyList(), eq("player left"));
        verify(wager).sweepPot(eq(table), eq(bob), eq(bob.getUniqueId()), anyList(), eq("hand abandoned"));
        assertTrue(table.live());
        drain();
        assertFalse(table.live());
    }

    @Test
    void actorLeavingThreeSeatHandMovesTurnToRemainingSeatWithoutEnding() {
        start(alice, bob, carol);
        game.onLeave(table, bob);
        drain();
        assertTrue(table.live());
        assertTrue(table.actives().contains(table.actor()));
        assertNotEquals(bob.getUniqueId(), table.actor());
        verify(wager, never()).sweepPot(any(), any(), any(), anyList(), anyString());
    }

    @Test
    void removalDuringInitialDealDoesNotResumeSession() {
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
    void endingSessionDuringBoardAnimationCancelsNextActor() {
        start(alice, bob);
        act("check");
        act("check");
        manager.endSession(table);
        drain();
        assertFalse(table.live());
        assertNull(table.actor());
    }

    @Test
    void dealerClaimNeedsTwoSeatsAndIdleTableThenButtonWraps() {
        assertFalse(game.tryClaimDealer(table, alice));
        seat(alice);
        assertFalse(game.tryClaimDealer(table, alice));
        seat(alice, bob);
        assertTrue(game.allowFreeDraw(table, alice));
        assertTrue(game.allowReturnSelected(table, alice));
        assertTrue(game.tryClaimDealer(table, alice));
        verify(manager).beginSession(table);
        PokerGame poker = (PokerGame) game;
        poker.passButton(table);
        assertEquals(bob.getUniqueId(), table.dealerId());
        poker.passButton(table);
        assertEquals(alice.getUniqueId(), table.dealerId());
    }

    @Test
    void sessionRequiresTwoOnlineSeatsAndChipInEstablishesButton() {
        seat(alice);
        table.setDealerId(null);
        game.onChipIn(table, alice, 5, null);
        assertEquals(alice.getUniqueId(), table.dealerId());
        table.startSession();
        game.onSessionStart(table);
        assertFalse(table.live());
        verify(manager, never()).dealToPlayer(any(), any(), anyInt(), any(Runnable.class));
    }

    @Test
    void lateRaiseRequiresPreviouslyCheckedSeatsToRespondAgain() {
        start(alice, bob, carol);
        act("check");
        act("check");
        contribute(alice, 10);
        act("raise");
        assertEquals(bob.getUniqueId(), table.actor());
        contribute(bob, 10);
        act("call");
        assertEquals(carol.getUniqueId(), table.actor());
        assertEquals(PokerGame.PREFLOP, table.phase());
        contribute(carol, 10);
        act("call");
        assertEquals(PokerGame.FLOP, table.phase());
        drain();
        assertEquals(bob.getUniqueId(), table.actor());
    }

    @Test
    void unmatchedTopContributionReturnsToItsOnlyEligiblePlayer() {
        invested.put(alice.getUniqueId(), 5);
        invested.put(bob.getUniqueId(), 10);
        invested.put(carol.getUniqueId(), 15);
        start(alice, bob, carol);
        checkToShowdown();
        verify(wager).payFromPot(eq(table), eq(alice), eq(alice.getUniqueId()), eq(15), anyList(), eq("pot"));
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(10), anyList(), eq("pot"));
        verify(wager).payFromPot(eq(table), eq(carol), eq(carol.getUniqueId()), eq(5), anyList(), eq("pot"));
        drain();
        assertFalse(table.live());
    }

    @Test
    void strongerHandLaterInSeatOrderWinsWholePot() {
        give(alice, 13, 13);
        give(bob, 14, 14);
        invested.put(alice.getUniqueId(), 10);
        invested.put(bob.getUniqueId(), 10);
        start(alice, bob);
        checkToShowdown();
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(20), anyList(), eq("pot"));
        verify(wager, never()).payFromPot(eq(table), eq(alice), any(), anyInt(), anyList(), anyString());
    }

    @Test
    void removingTableWhilePotIsFlyingDoesNotFinishRemovedSession() {
        invested.put(alice.getUniqueId(), 10);
        invested.put(bob.getUniqueId(), 10);
        start(alice, bob);
        checkToShowdown();
        assertEquals(PokerGame.SHOWDOWN, table.phase());
        game.onTableRemoved(table);
        when(manager.table(table.getId())).thenReturn(null);
        drain();
        verify(manager, never()).endSession(table);
        assertEquals(alice.getUniqueId(), table.dealerId());
    }

    @Test
    void nextHandClearsFoldedSeatsAndRotatesFirstActorWithButton() {
        start(alice, bob);
        act("fold");
        drain();
        assertFalse(table.live());
        assertEquals(bob.getUniqueId(), table.dealerId());
        give(alice, 14, 14);
        give(bob, 13, 13);
        table.startSession();
        game.onSessionStart(table);
        drain();
        assertEquals(alice.getUniqueId(), table.actor());
        act("check");
        assertEquals(bob.getUniqueId(), table.actor());
        assertTrue(game.allowPlayChat(table, bob), "the previous hand's fold must not carry over");
        act("check");
        assertEquals(PokerGame.FLOP, table.phase());
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
    void labelShowsConfiguredBlindsShuffleAndOnlyTheCurrentBettingActor() {
        table.setSmallBlind(5);
        table.setBigBlind(10);
        String idle = game.extraLabel(table);
        assertTrue(idle.contains("label.blinds"));
        assertTrue(idle.contains("small, 5, big, 10"));
        assertTrue(idle.contains("label.shuffle_shoe"));
        assertFalse(idle.contains("label.turn"));
        table.setShufflePolicy(ShufflePolicy.ROUND);
        start(alice, bob);
        assertTrue(game.extraLabel(table).contains("label.shuffle_round"));
        assertTrue(game.extraLabel(table).contains("label.button"));
        contribute(bob, 20);
        act("raise");
        String facingBet = game.extraLabel(table);
        assertTrue(facingBet.contains("label.holdem_preflop"));
        assertTrue(facingBet.lines().anyMatch(line -> line.startsWith("label.turn") && line.contains(alice.getName())));
        assertTrue(facingBet.lines().anyMatch(line -> line.startsWith("label.holdem_tocall") && line.contains("n, 20")));
        contribute(alice, 20);
        invested.put(alice.getUniqueId(), 20);
        invested.put(bob.getUniqueId(), 20);
        act("call");
        String dealingFlop = game.extraLabel(table);
        assertTrue(dealingFlop.contains("label.holdem_flop"));
        assertFalse(dealingFlop.contains("label.turn"), "no actor is advertised during the board animation");
        assertTrue(dealingFlop.lines().anyMatch(line -> line.startsWith("label.pot[") && line.contains("n, 40")));
        checkToShowdown();
        String showdown = game.extraLabel(table);
        assertTrue(showdown.contains("label.holdem_showdown"));
        assertFalse(showdown.contains("label.turn"));
        assertFalse(showdown.contains("label.holdem_tocall"));
    }

    @Test
    void tableRemovalDuringFlopAnimationCannotStartAnotherBettingStreet() {
        start(alice, bob);
        act("check");
        act("check");
        assertEquals(PokerGame.FLOP, table.phase());
        assertNull(table.actor());
        game.onTableRemoved(table);
        when(manager.table(table.getId())).thenReturn(null);
        drain();
        assertNull(table.actor());
        assertEquals(3, table.tablePile("board").size());
        verify(manager, times(1)).dealToTable(eq(table), eq("board"), anyInt(), eq(true), any(Runnable.class));
        verify(manager, never()).endSession(table);
    }

    @Test
    void drawChatWordCannotActAsACheckAtAPokerTable() {
        start(alice, bob);
        List<HandCard> before = List.copyOf(table.handOf(bob.getUniqueId()));
        act("draw");
        assertEquals(bob.getUniqueId(), table.actor());
        assertEquals(PokerGame.PREFLOP, table.phase());
        assertEquals(before, table.handOf(bob.getUniqueId()));
        act("check");
        assertEquals(alice.getUniqueId(), table.actor());
        assertEquals(PokerGame.PREFLOP, table.phase());
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
        verify(wager, times(1)).announceWins(table, "poker");
        verify(manager, times(1)).endSession(table);
        assertEquals(bob.getUniqueId(), table.dealerId(), "a settled hand passes the button exactly once");
    }

    @Test
    void blindsLineAppearsOnlyOnceABlindIsConfigured() {
        assertFalse(game.extraLabel(table).contains("label.blinds"));
        table.setBigBlind(10);
        String label = game.extraLabel(table);
        assertTrue(label.contains("label.blinds"));
        assertTrue(label.contains("small, 0, big, 10"));
    }

    @Test
    void holeCardsGoAroundAnOfflineSeat() {
        ((PlayerMock) carol).disconnect();
        start(alice, bob, carol);
        assertEquals(List.of(bob.getUniqueId(), alice.getUniqueId(), bob.getUniqueId(), alice.getUniqueId()), dealt);
        assertTrue(table.handOf(carol.getUniqueId()).isEmpty());
        assertEquals(bob.getUniqueId(), table.actor());
    }

    @Test
    void endingTheSessionDuringTheHoleDealStopsDealing() {
        seat(alice, bob);
        table.startSession();
        game.onSessionStart(table);
        manager.endSession(table);
        drain();
        assertEquals(1, dealt.size());
        assertNull(table.actor());
        assertFalse(table.live());
    }

    @Test
    void allInSeatIsSkippedWhileTheOthersKeepRaising() {
        start(alice, bob, carol);
        contribute(bob, 10);
        act("raise");
        contribute(carol, 3);
        act("call");
        assertEquals(alice.getUniqueId(), table.actor());
        contribute(alice, 20);
        act("raise");
        assertEquals(bob.getUniqueId(), table.actor());
        contribute(bob, 30);
        act("raise");
        assertEquals(alice.getUniqueId(), table.actor(), "the all-in seat cannot be asked to call again");
        contribute(alice, 30);
        act("call");
        assertEquals(PokerGame.FLOP, table.phase());
        drain();
        assertEquals(bob.getUniqueId(), table.actor());
        act("check");
        assertEquals(carol.getUniqueId(), table.actor(), "a new street reopens action for the all-in seat");
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
        checkToShowdown();
        verify(wager).payFromPot(eq(table), eq(bob), eq(bob.getUniqueId()), eq(15), anyList(), eq("pot"));
        verify(wager).sweepPot(eq(table), eq(bob), eq(bob.getUniqueId()), anyList(), eq("pot remainder"));
        verify(wager, never()).payFromPot(eq(table), eq(alice), any(), anyInt(), anyList(), anyString());
        verify(manager, never()).publishHand(table, alice);
    }

    @Test
    void departureWhileBoardCardsLandHandsNobodyTheTurnEarly() {
        start(alice, bob, carol);
        act("check");
        act("check");
        act("check");
        assertEquals(PokerGame.FLOP, table.phase());
        game.onLeave(table, carol);
        animations.removeLast().run();
        assertNull(table.actor(), "nobody acts until the flop has landed");
        drain();
        assertEquals(bob.getUniqueId(), table.actor());
        act("check");
        act("check");
        assertEquals(PokerGame.TURN, table.phase());
    }

    @Test
    void stakesGoBackWhenEveryUnfoldedPlayerLeavesAFourSeatHand() {
        Player dave = MockBukkit.getMock().addPlayer();
        give(dave, 11, 11);
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
        List<String> heard = new ArrayList<>();
        for (String line = ((PlayerMock) bob).nextMessage(); line != null; line = ((PlayerMock) bob).nextMessage()) {
            heard.add(line);
        }
        assertTrue(heard.stream().noneMatch(line -> line.startsWith("poker.win_fold")), "nobody won by folding");
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
        drain();
        assertFalse(table.live());
    }

    @Test
    void blackjackActionsShoeClicksAndStreetCommitsDoNotActAtAPokerTable() {
        assertFalse(game.allowManualPotFlush(table, alice), "only a showdown or fold pays a poker pot");
        start(alice, bob);
        assertFalse(game.allowManualPotFlush(table, bob));
        game.onShoeClick(table, bob);
        game.onBetHit(table, bob);
        game.onBetStand(table, bob);
        game.onBetDouble(table, bob);
        game.onBetSplit(table, bob);
        game.onStreetCommit(table, bob, 5, false);
        game.onStreetCommit(table, bob, 0, true);
        assertEquals(bob.getUniqueId(), table.actor());
        assertEquals(PokerGame.PREFLOP, table.phase());
        assertEquals(4, dealt.size());
        assertTrue(table.tablePile("board").isEmpty());
        assertEquals(0, game.denarsToMatch(table, bob), "poker bets are made by chat, never matched by commit");
        act("check");
        assertEquals(alice.getUniqueId(), table.actor());
    }

    @Test
    void actorLeavingPassesTheTurnToTheNextSeatNotBackToTheButton() {
        Player dave = MockBukkit.getMock().addPlayer();
        give(dave, 11, 11);
        start(alice, bob, carol, dave);
        act("check");
        assertEquals(carol.getUniqueId(), table.actor());
        game.onLeave(table, carol);
        drain();
        assertEquals(dave.getUniqueId(), table.actor(), "the seat after the leaver acts next");
        act("check");
        assertEquals(alice.getUniqueId(), table.actor());
        act("check");
        assertEquals(PokerGame.FLOP, table.phase());
    }

    @Test
    void anotherSeatLeavingKeepsTheCurrentActorsTurn() {
        start(alice, bob, carol);
        assertEquals(bob.getUniqueId(), table.actor());
        game.onLeave(table, carol);
        drain();
        assertEquals(bob.getUniqueId(), table.actor());
        act("check");
        assertEquals(alice.getUniqueId(), table.actor());
    }

    @Test
    void showdownShowsOnlyTheHandsOfOnlineSeats() {
        start(alice, bob, carol);
        int turns = 0;
        drain();
        while (!(PokerGame.RIVER.equals(table.phase()) && table.actor() != null)) {
            assertTrue(turns++ < 30, "hand did not reach the last betting round");
            act("check");
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

    @Test
    void departedActorCannotUseChatDuringTheirRefundToMoveTheTurn() {
        start(alice, bob, carol);
        assertEquals(bob.getUniqueId(), table.actor());
        game.onLeave(table, bob);
        Runnable departure = animations.removeFirst();
        // TableManager dispatches chat only after this same eligibility check.
        if (game.allowPlayChat(table, bob)) {
            game.onPlayWord(table, bob, "check");
        }
        assertFalse(game.allowPlayChat(table, bob), "a player who has left the table no longer acts");
        assertEquals(bob.getUniqueId(), table.actor(), "the turn moves on only when the refund lands");
        departure.run();
        assertEquals(carol.getUniqueId(), table.actor(), "the seat after the leaver acts next");
    }

    private void checkToShowdown() {
        int turns = 0;
        while (table.live() && !PokerGame.SHOWDOWN.equals(table.phase())) {
            assertTrue(turns++ < 30, "hand did not reach showdown");
            drain();
            if (table.actor() != null) act("check");
        }
    }
}

/** Runs game callbacks against real table state while holding animation completions explicitly. */
abstract class GameScenarioFixture {
    Game game;
    Table table;
    TableManager manager;
    WagerEngine wager;
    Player alice;
    Player bob;
    Player carol;
    final Map<UUID, Deque<Card>> hands = new HashMap<>();
    final Deque<Card> board = new ArrayDeque<>();
    final Deque<Runnable> animations = new ArrayDeque<>();
    final Map<UUID, Integer> invested = new HashMap<>();
    final Map<Integer, Map<UUID, Integer>> contributions = new HashMap<>();
    final List<UUID> dealt = new ArrayList<>();
    private MockedStatic<TableManager> managers;
    private MockedStatic<WagerEngine> wagers;
    private MockedStatic<Messages> messages;

    abstract Game newGame();
    abstract String gameId();

    @BeforeEach
    void setUpScenario() {
        game = newGame();
        table = new Table(UUID.randomUUID(), gameId(), null, 0, null);
        manager = mock(TableManager.class);
        wager = mock(WagerEngine.class);
        managers = mockStatic(TableManager.class);
        managers.when(TableManager::get).thenReturn(manager);
        wagers = mockStatic(WagerEngine.class);
        wagers.when(WagerEngine::get).thenReturn(wager);
        messages = mockStatic(Messages.class, call -> call.getArgument(0, String.class)
                + (call.getArguments().length > 1 ? Arrays.deepToString(call.getArguments()) : ""));
        alice = MockBukkit.getMock().addPlayer();
        bob = MockBukkit.getMock().addPlayer();
        carol = MockBukkit.getMock().addPlayer();
        when(manager.table(table.getId())).thenReturn(table);
        when(wager.totalsExcept(table, table.getId())).thenAnswer(call -> new HashMap<>(invested));
        when(wager.owned(eq(table), any(UUID.class), anyInt())).thenAnswer(call ->
                contributions.getOrDefault(call.getArgument(2), Map.of()).getOrDefault(call.getArgument(1), 0));
        doAnswer(call -> {
            Player player = call.getArgument(1);
            int count = call.getArgument(2);
            for (int i = 0; i < count; i++) {
                Card card = hands.get(player.getUniqueId()).removeFirst();
                table.handOf(player.getUniqueId()).add(new HandCard(card, UUID.randomUUID()));
                dealt.add(player.getUniqueId());
            }
            animations.add(call.getArgument(3));
            return null;
        }).when(manager).dealToPlayer(eq(table), any(Player.class), anyInt(), any(Runnable.class));
        doAnswer(call -> {
            int count = call.getArgument(2);
            for (int i = 0; i < count; i++) {
                table.tablePile(call.getArgument(1)).add(new HandCard(board.removeFirst(), UUID.randomUUID(), true));
            }
            animations.add(call.getArgument(4));
            return null;
        }).when(manager).dealToTable(eq(table), anyString(), anyInt(), anyBoolean(), any(Runnable.class));
        doAnswer(call -> { animations.add(call.getArgument(2)); return null; })
                .when(manager).flushPiles(eq(table), anyList(), any(Runnable.class));
        doAnswer(call -> { table.getHands().remove(call.getArgument(1)); return null; })
                .when(manager).muckPlayer(eq(table), any(UUID.class));
        doAnswer(call -> { table.clearSession(); game.onSessionEnd(table); return null; })
                .when(manager).endSession(table);
    }

    @AfterEach
    void closeScenario() {
        assertAll(
                () -> { if (messages != null) messages.close(); },
                () -> { if (wagers != null) wagers.close(); },
                () -> { if (managers != null) managers.close(); });
    }

    void seat(Player... players) {
        for (Player player : players) table.actives().add(player.getUniqueId());
        game.onTableReady(table);
    }

    void start(Player... players) {
        seat(players);
        table.startSession();
        game.onSessionStart(table);
        drain();
    }

    void give(Player player, int... ranks) {
        String[] suits = {"clubs", "hearts", "diamonds", "spades"};
        int offset = player == alice ? 0 : player == bob ? 2 : 1;
        Deque<Card> cards = new ArrayDeque<>();
        for (int i = 0; i < ranks.length; i++) {
            cards.add(new Card(UUID.randomUUID().toString(), suits[(i + offset) % 4], ranks[i], false, "item"));
        }
        hands.put(player.getUniqueId(), cards);
    }

    static List<Card> cards(int... ranks) {
        List<Card> cards = new ArrayList<>();
        String[] suits = {"clubs", "hearts", "diamonds", "spades"};
        for (int i = 0; i < ranks.length; i++) {
            cards.add(new Card(UUID.randomUUID().toString(), suits[i % 4], ranks[i], false, "item"));
        }
        return cards;
    }

    void contribute(Player player, int amount) {
        contributions.computeIfAbsent(table.street(), ignored -> new HashMap<>()).put(player.getUniqueId(), amount);
    }

    void act(String word) {
        assertNotNull(table.actor(), "game must nominate an actor before accepting an action");
        game.onPlayWord(table, org.bukkit.Bukkit.getPlayer(table.actor()), word);
    }

    void drain() {
        int callbacks = 0;
        while (!animations.isEmpty()) {
            assertTrue(callbacks++ < 100, "animation callbacks did not terminate");
            animations.removeFirst().run();
        }
    }
}
