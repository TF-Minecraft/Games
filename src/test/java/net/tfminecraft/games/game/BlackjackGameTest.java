package net.tfminecraft.games.game;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.table.HandCard;
import net.tfminecraft.games.table.ShufflePolicy;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.wager.MoneyTx;
import net.tfminecraft.games.wager.MoneyLog;
import net.tfminecraft.games.wager.PayWinResult;
import net.tfminecraft.games.wager.TxResult;
import net.tfminecraft.games.wager.WagerEngine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class BlackjackGameTest {
    private final BlackjackGame game = new BlackjackGame();
    private final Deque<Integer> shoe = new ArrayDeque<>();
    private final Deque<Runnable> animations = new ArrayDeque<>();
    private final Map<UUID, Integer> bets = new LinkedHashMap<>();
    private final Map<UUID, Location> positions = new LinkedHashMap<>();
    private final List<String> dealOrder = new ArrayList<>();
    private ServerMock server;
    private Table table;
    private PlayerMock player;
    private PlayerMock dealer;
    private TableManager manager;
    private WagerEngine wagers;
    private MoneyTx extraBet;
    private MockedStatic<TableManager> managers;
    private MockedStatic<WagerEngine> engines;
    private MockedStatic<GuildTables> guilds;
    private MockedStatic<WorldAnchors> anchors;
    private Games previousPlugin;
    private TableLayout previousLayout;
    private int previousInterpolation;
    private boolean previousAudit;
    private boolean available = true;
    private boolean extraAccepted = true;
    private int extraAmount;
    private int trayBalance;
    private boolean fundingAccepted = true;
    private UUID failedSpawnFor;

    @BeforeEach
    void setUp() {
        server = MockBukkit.getMock();
        player = server.addPlayer();
        dealer = server.addPlayer();
        previousPlugin = Games.plugin;
        previousLayout = Cache.tableLayouts.get("blackjack");
        previousInterpolation = Cache.interpolationTicks;
        previousAudit = Cache.wagerAuditLog;
        Games.plugin = mock(Games.class);
        when(Games.plugin.isEnabled()).thenReturn(true);
        when(Games.plugin.getName()).thenReturn("BlackjackTest");
        Cache.interpolationTicks = 0;
        Cache.wagerAuditLog = false;
        configure(false, 4, false, 0);
        table = new Table(UUID.randomUUID(), "blackjack", player.getLocation(), 0, null);
        table.setDealerId(dealer.getUniqueId());
        table.setMinBet(5);
        seat(player, 20, -1);
        manager = mock(TableManager.class);
        wagers = mock(WagerEngine.class);
        extraBet = mock(MoneyTx.class);
        managers = mockStatic(TableManager.class);
        managers.when(TableManager::get).thenReturn(manager);
        engines = mockStatic(WagerEngine.class);
        engines.when(WagerEngine::get).thenReturn(wagers);
        guilds = mockStatic(GuildTables.class);
        anchors = mockStatic(WorldAnchors.class);
        guilds.when(() -> GuildTables.mayDeal(eq(table), any(Player.class))).thenReturn(true);
        guilds.when(() -> GuildTables.canStartGuildAutoRound(table)).thenReturn(true);
        when(manager.table(table.getId())).thenAnswer(call -> available ? table : null);
        when(manager.boxOwners(table)).thenAnswer(call -> new ArrayList<>(bets.keySet()));
        when(manager.ownedDenars(eq(table), any(UUID.class)))
                .thenAnswer(call -> bets.getOrDefault(call.getArgument(1), 0));
        when(manager.trayDenars(table)).thenAnswer(call -> trayBalance);
        when(manager.boxLocation(eq(table), any(UUID.class)))
                .thenAnswer(call -> positions.get(call.getArgument(1)));
        when(manager.hasLegalBlackjackBox(table))
                .thenAnswer(call -> bets.values().stream().anyMatch(value -> value >= table.minBet()));
        when(manager.feltItem(eq(table), any(UUID.class))).thenReturn(new ItemStack(Material.GOLD_NUGGET));
        when(manager.chipUnitDenars(any())).thenReturn(1);
        when(wagers.felt(table)).thenAnswer(call -> bets.values().stream().mapToInt(Integer::intValue).sum());
        when(wagers.fundFromHouse(eq(table), eq(table.getId()), any(ItemStack.class), anyInt(),
                nullable(Location.class), anyString())).thenAnswer(call -> {
                    int amount = call.getArgument(3);
                    if (fundingAccepted) {
                        trayBalance += amount;
                    }
                    return result(fundingAccepted, fundingAccepted ? amount : 0);
                });
        when(wagers.peelToHouse(eq(table), anyInt(), eq("peel"))).thenAnswer(call -> {
            int amount = call.getArgument(1);
            trayBalance -= amount;
            return result(true, amount);
        });
        when(wagers.toTray(eq(table), any(UUID.class), anyInt(), anyList(), eq("loss"))).thenAnswer(call -> {
            UUID owner = call.getArgument(1);
            int amount = call.getArgument(2);
            bets.computeIfPresent(owner, (id, held) -> held > amount ? held - amount : null);
            trayBalance += amount;
            return result(true, amount);
        });
        doAnswer(call -> {
            trayBalance = 0;
            return null;
        }).when(manager).bankAutoTray(table);
        doAnswer(call -> {
            Player owner = call.getArgument(1);
            bets.remove(owner.getUniqueId());
            return null;
        }).when(manager).refundOwnedPiles(eq(table), any(Player.class));
        when(wagers.begin(table, "extra bet")).thenReturn(extraBet);
        doAnswer(call -> {
            extraAmount = call.getArgument(2);
            return extraBet;
        }).when(extraBet).move(any(), any(), anyInt());
        when(extraBet.commit()).thenAnswer(call -> {
            if (extraAccepted) {
                bets.merge(table.actor(), extraAmount, Integer::sum);
                return result(true, extraAmount);
            }
            TxResult refused = result(false, 0);
            when(refused.reason()).thenReturn(TxResult.Reason.PLAYER_SHORT);
            when(refused.messageKey()).thenReturn("bet.need_chips");
            return refused;
        });
        when(wagers.refund(eq(table), any(UUID.class), nullable(Player.class), anyInt(), anyList(), anyString()))
                .thenAnswer(call -> {
                    UUID owner = call.getArgument(1);
                    int requested = call.getArgument(3);
                    int held = bets.getOrDefault(owner, 0);
                    int moved = requested == 0 ? held : Math.min(held, requested);
                    if (moved == held) {
                        bets.remove(owner);
                    } else {
                        bets.put(owner, held - moved);
                    }
                    return result(true, moved);
                });
        when(wagers.payWin(eq(table), nullable(Player.class), any(UUID.class), anyInt(),
                nullable(Player.class), anyBoolean(), nullable(ItemStack.class), anyList()))
                .thenAnswer(call -> new PayWinResult(call.getArgument(3), 0, 0));
        doAnswer(call -> {
            Player target = call.getArgument(1);
            if (target.getUniqueId().equals(failedSpawnFor)) {
                // TableManager still completes the deal when a card display fails to spawn.
                animations.add(call.getArgument(4));
                return null;
            }
            HandCard card = nextCard(true);
            card.setSlot(call.getArgument(3));
            table.handOf(target.getUniqueId()).add(card);
            dealOrder.add(target.getUniqueId() + ":" + card.card().getRank());
            animations.add(call.getArgument(4));
            return null;
        }).when(manager).dealToPlayer(eq(table), any(Player.class), eq(1), anyInt(), any(Runnable.class));
        doAnswer(call -> {
            boolean faceUp = call.getArgument(3);
            HandCard card = nextCard(faceUp);
            table.tablePile("dealer").add(card);
            dealOrder.add("dealer:" + card.card().getRank() + ":" + faceUp);
            animations.add(call.getArgument(4));
            return null;
        }).when(manager).dealToTable(eq(table), eq("dealer"), eq(1), anyBoolean(), any(Runnable.class));
        doAnswer(call -> {
            table.tablePile("dealer").forEach(card -> card.setFaceUp(true));
            return null;
        }).when(manager).revealTablePile(table, "dealer");
        doAnswer(call -> {
            Runnable after = call.getArgument(2);
            if (after != null) {
                animations.add(after);
            }
            return null;
        }).when(manager).flushPiles(eq(table), anyList(), nullable(Runnable.class));
        doAnswer(call -> {
            table.startSession();
            game.onSessionStart(table);
            return null;
        }).when(manager).beginSession(table);
        doAnswer(call -> {
            table.clearSession();
            game.onSessionEnd(table);
            return null;
        }).when(manager).endSession(table);
    }

    @AfterEach
    void tearDown() {
        game.onTableRemoved(table);
        server.getScheduler().cancelTasks(Games.plugin);
        anchors.close();
        guilds.close();
        engines.close();
        managers.close();
        Games.plugin = previousPlugin;
        Cache.interpolationTicks = previousInterpolation;
        Cache.wagerAuditLog = previousAudit;
        if (previousLayout == null) {
            Cache.tableLayouts.remove("blackjack");
        } else {
            Cache.tableLayouts.put("blackjack", previousLayout);
        }
    }

    @Test
    void onlyClaimedDealerStartsRoundAndPlayersActOnlyOnTheirTurn() {
        queue(5, 10, 6, 7, 2);
        table.startSession();
        game.onSessionStart(table);
        assertEquals(BlackjackGame.WAIT_DEAL, table.phase());
        game.onShoeClick(table, player);
        assertTrue(dealOrder.isEmpty());
        game.onShoeClick(table, dealer);
        finishAnimations();
        assertEquals(List.of(player.getUniqueId() + ":5", "dealer:10:true",
                player.getUniqueId() + ":6", "dealer:7:false"), dealOrder);
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertFalse(table.tablePile("dealer").getLast().faceUp());
        game.onBetHit(table, dealer);
        game.onBetStand(table, dealer);
        game.onBetDouble(table, dealer);
        game.onBetSplit(table, dealer);
        assertEquals(4, dealOrder.size());
        assertEquals(player.getUniqueId(), table.actor());
        game.onShoeClick(table, player);
        finishAnimations();
        assertEquals(13, BlackjackGame.bestTotal(table.handOf(player.getUniqueId())));
        assertEquals(player.getUniqueId(), table.actor());
    }

    @Test
    void standPaysWinRevealsDealerAndEndsRoundAfterVisibleCountdown() {
        start(10, 10, 8, 7);
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
        assertTrue(table.tablePile("dealer").stream().allMatch(HandCard::faceUp));
        verifyWin(20);
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(player), eq(0), anyList(), eq("bet returned"));
        assertEquals(2, table.autoCountdown());
        server.getScheduler().performTicks(20);
        assertTrue(table.live());
        assertEquals(1, table.autoCountdown());
        server.getScheduler().performTicks(20);
        assertFalse(table.live());
        verify(manager).muckPlayer(table, player.getUniqueId());
        verify(manager).muckTable(table, "dealer");
        verify(manager).endSession(table);
    }

    @Test
    void bustLosesStakeWithoutUnnecessaryDealerDraws() {
        start(10, 4, 6, 8, 10);
        game.onBetHit(table, player);
        finishAnimations();
        assertEquals(26, BlackjackGame.bestTotal(table.handOf(player.getUniqueId())));
        assertEquals(2, table.tablePile("dealer").size());
        assertEquals(BlackjackGame.SETTLE, table.phase());
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(dealer), eq(20), anyList(), eq("loss to dealer"));
        verify(wagers, never()).payWin(any(), any(), any(), anyInt(), any(), anyBoolean(), any(), anyList());
        assertMessage(player, "bet.bust");
    }

    @Test
    void naturalTwentyOneSkipsActionAndPaysThreeToTwo() {
        start(1, 10, 13, 7);
        assertEquals(BlackjackGame.SETTLE, table.phase());
        assertNull(table.actor());
        verifyWin(30);
        assertMessage(player, "bet.natural");
    }

    @Test
    void equalTotalsReturnStakeWithoutWinPayment() {
        start(10, 10, 8, 8);
        game.onBetStand(table, player);
        finishAnimations();
        verify(wagers, never()).payWin(any(), any(), any(), anyInt(), any(), anyBoolean(), any(), anyList());
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(player), eq(0), anyList(), eq("bet returned"));
        assertMessage(player, "bet.push");
    }

    @ParameterizedTest
    @CsvSource({"false, 2, true", "true, 3, false"})
    void configuredSoftSeventeenRuleChangesDealerDrawAndOutcome(boolean hitSoft, int dealerCards, boolean wins) {
        configure(hitSoft, 4, false, 0);
        start(10, 1, 8, 6, 4);
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(dealerCards, table.tablePile("dealer").size());
        if (wins) {
            verifyWin(20);
        } else {
            verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(dealer), eq(20), anyList(), eq("loss to dealer"));
        }
    }

    @Test
    void dealerDrawsUntilHardSeventeenAndDealerBustPaysPlayer() {
        start(10, 5, 7, 7, 10);
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(22, BlackjackGame.bestTotal(table.tablePile("dealer")));
        verifyWin(20);
    }

    @Test
    void hitSoftSeventeenDealerStillStandsOnFaceCardHardSeventeen() {
        configure(true, 4, false, 0);
        start(10, 13, 8, 7);
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(2, table.tablePile("dealer").size());
        assertEquals(17, BlackjackGame.bestTotal(table.tablePile("dealer")));
        verifyWin(20);
    }

    @Test
    void multipleDealerAcesBecomeHardSeventeenAfterRequiredSoftHit() {
        configure(true, 4, false, 0);
        start(10, 1, 8, 1, 5, 10);
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(List.of(1, 1, 5, 10), table.tablePile("dealer").stream()
                .map(held -> held.card().getRank()).toList());
        assertEquals(17, BlackjackGame.bestTotal(table.tablePile("dealer")));
        assertEquals(17, BlackjackGame.hardTotal(table.tablePile("dealer")));
        verifyWin(20);
    }

    @Test
    void doubleAddsOneStakeDealsExactlyOneCardAndAutomaticallyStands() {
        start(5, 10, 6, 7, 9);
        game.onBetDouble(table, player);
        finishAnimations();
        verify(extraBet).move(any(), any(), eq(20));
        verify(extraBet).commit();
        assertEquals(3, table.handOf(player.getUniqueId()).size());
        assertEquals(BlackjackGame.SETTLE, table.phase());
        verifyWin(40);
        game.onBetHit(table, player);
        assertEquals(3, table.handOf(player.getUniqueId()).size());
    }

    @Test
    void rejectedDoubleLeavesStakeHandAndTurnUnchanged() {
        extraAccepted = false;
        start(5, 10, 6, 7, 9);
        game.onBetDouble(table, player);
        assertEquals(20, bets.get(player.getUniqueId()));
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(player.getUniqueId(), table.actor());
        assertMessage(player, "bet.need_chips");
        extraAccepted = true;
        game.onBetDouble(table, player);
        finishAnimations();
        verifyWin(40);
    }

    @Test
    void doubleAfterHitIsRefusedBeforeMoneyMoves() {
        start(3, 10, 4, 7, 2);
        game.onBetHit(table, player);
        finishAnimations();
        game.onBetDouble(table, player);
        verify(extraBet, never()).commit();
        assertEquals(20, bets.get(player.getUniqueId()));
        assertEquals(player.getUniqueId(), table.actor());
        assertMessage(player, "bet.no_double");
    }

    @Test
    void resplittingSecondHandKeepsEarlierHandAndResumesAtTheSplitHand() {
        start(8, 10, 8, 10, 2, 8, 3, 4);
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(List.of(8, 2), ranks(0));
        assertEquals(List.of(8, 8), ranks(1));
        game.onBetStand(table, player);
        assertEquals(1, table.handIndex());
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(1, table.handIndex());
        assertEquals(List.of(8, 2), ranks(0));
        assertEquals(List.of(8, 3), ranks(1));
        assertEquals(List.of(8, 4), ranks(2));
        assertEquals(60, bets.get(player.getUniqueId()));
        game.onBetStand(table, player);
        assertEquals(2, table.handIndex());
        game.onBetStand(table, player);
        finishAnimations();
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(dealer), eq(60), anyList(), eq("loss to dealer"));
    }

    @Test
    void splitAcesReceiveOneCardEachAndTwentyOnePaysEvenMoney() {
        start(1, 10, 1, 9, 10, 9);
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(List.of(1, 10), ranks(0));
        assertEquals(List.of(1, 9), ranks(1));
        assertEquals(BlackjackGame.SETTLE, table.phase());
        verifyWin(40);
    }

    @Test
    void enabledResplitAcesOffersNewAcePairWithoutAllowingHitOrDouble() {
        configure(false, 3, true, 0);
        start(1, 10, 1, 7, 1, 9, 10, 8);
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.PLAY, table.phase(), "Enabled resplitting must offer the new ace pair for action");
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(0, table.handIndex());
        assertEquals(List.of(1, 1), ranks(0));
        assertEquals(List.of(1, 9), ranks(1));
        game.onBetHit(table, player);
        game.onBetDouble(table, player);
        assertEquals(4, table.handOf(player.getUniqueId()).size());
        verify(extraBet, times(1)).commit();
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(List.of(1, 10), ranks(0));
        assertEquals(List.of(1, 8), ranks(1));
        assertEquals(List.of(1, 9), ranks(2));
        assertEquals(BlackjackGame.SETTLE, table.phase());
        verify(extraBet, times(2)).commit();
        verifyWin(60);
    }

    @ParameterizedTest
    @CsvSource({"false, 4", "true, 2"})
    void splitAcePairAutomaticallyStandsWhenResplittingIsDisabledOrHandCapReached(boolean resplit, int cap) {
        configure(false, cap, resplit, 0);
        start(1, 10, 1, 7, 1, 9);
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
        assertEquals(List.of(1, 1), ranks(0));
        assertEquals(List.of(1, 9), ranks(1));
        verify(extraBet, times(1)).commit();
        verifyWin(20);
    }

    @Test
    void splitLimitAndUnpairedCardsRefuseExtraStake() {
        configure(false, 2, false, 0);
        start(8, 10, 8, 7, 8, 3);
        game.onBetSplit(table, player);
        finishAnimations();
        game.onBetSplit(table, player);
        assertEquals(40, bets.get(player.getUniqueId()));
        assertEquals(4, table.handOf(player.getUniqueId()).size());
        assertMessage(player, "bet.no_split");
        game.onBetStand(table, player);
        game.onBetSplit(table, player);
        verify(extraBet, times(1)).commit();
    }

    @Test
    void differentTenValueRanksCanSplitButOrdinaryMixedRanksCannot() {
        start(10, 10, 13, 7, 2, 3);
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(List.of(10, 2), ranks(0));
        assertEquals(List.of(13, 3), ranks(1));
        game.onBetSplit(table, player);
        verify(extraBet, times(1)).commit();
        assertMessage(player, "bet.no_split");
    }

    @Test
    void houseCoverRefusesDoubleAndSplitBeforePlayerStakeMoves() {
        guilds.when(() -> GuildTables.houseBacked(table)).thenReturn(true);
        when(manager.trayDenars(table)).thenReturn(20);
        start(8, 10, 8, 7);
        game.onBetDouble(table, player);
        game.onBetSplit(table, player);
        verify(wagers, never()).begin(table, "extra bet");
        assertEquals(20, bets.get(player.getUniqueId()));
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertMessage(player, "bet.bank_short");
    }

    @Test
    void boxesPlayLeftToRightAndLeavingCurrentPlayerAdvancesToNextBox() {
        PlayerMock left = server.addPlayer();
        seat(left, 10, -3);
        start(4, 5, 10, 6, 7, 7);
        assertEquals(List.of(left.getUniqueId(), player.getUniqueId()), table.boxes());
        assertEquals(left.getUniqueId(), table.actor());
        game.onLeave(table, left);
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(List.of(player.getUniqueId()), table.boxes());
        verify(manager).refundOwnedPiles(table, left);
    }

    @Test
    void dealerLeavingBeforeFirstDealContinuesRoundWithoutADealerClick() {
        queue(5, 10, 6, 7);
        table.startSession();
        game.onSessionStart(table);
        table.setDealerId(null);
        game.onDealerGone(table);
        finishAnimations();
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(2, table.handOf(player.getUniqueId()).size());
    }

    @Test
    void leavingLastBoxEndsRoundBeforeCardsAreDealt() {
        table.startSession();
        game.onSessionStart(table);
        game.onLeave(table, player);
        assertFalse(table.live());
        verify(manager).endSession(table);
        assertTrue(dealOrder.isEmpty());
    }

    @Test
    void removedTableStopsPendingDealAnimation() {
        queue(5, 10, 6, 7);
        table.startSession();
        game.onSessionStart(table);
        game.onShoeClick(table, dealer);
        assertEquals(1, dealOrder.size());
        available = false;
        finishAnimations();
        assertEquals(1, dealOrder.size());
        verify(wagers, never()).announceWins(any(), anyString());
    }

    @Test
    void resultDelayDefersBustSettlementAndIgnoresRemovedTable() {
        configure(false, 4, false, 5);
        start(10, 10, 6, 7, 10);
        game.onBetHit(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.PLAY, table.phase());
        verify(wagers, never()).announceWins(any(), anyString());
        available = false;
        server.getScheduler().performTicks(5);
        verify(wagers, never()).announceWins(any(), anyString());
    }

    @Test
    void automaticBetTimerStartsOnlyOnceAndClosesBeforeAutomaticDeal() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        queue(5, 10, 6, 7);
        game.onTableReady(table);
        assertTrue(table.betOpen());
        game.onChipIn(table, player);
        assertEquals(2, table.autoCountdown());
        server.getScheduler().performTicks(20);
        assertEquals(1, table.autoCountdown());
        game.onChipIn(table, player);
        assertEquals(1, table.autoCountdown(), "Additional chips must not restart the countdown");
        server.getScheduler().performTicks(20);
        finishAnimations();
        assertFalse(table.betOpen());
        assertTrue(table.live());
        assertEquals(BlackjackGame.PLAY, table.phase());
        verify(manager, times(1)).beginSession(table);
    }

    @Test
    void removedAutomaticTableCancelsTimerAndNeverStartsRound() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        game.onTableReady(table);
        game.onChipIn(table, player);
        game.onTableRemoved(table);
        server.getScheduler().performTicks(60);
        verify(manager, never()).beginSession(table);
    }

    @Test
    void underMinimumBoxesAreRefundedWhenBetWindowCloses() {
        bets.put(player.getUniqueId(), 3);
        game.closeBets(table);
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(player), eq(0), anyList(), eq("under min refund"));
        assertTrue(bets.isEmpty());
        verify(manager, never()).beginSession(table);
        verify(manager).flushPiles(eq(table), anyList(), isNull());
    }

    @Test
    void automaticWindowWithOnlyUnderMinimumBetsRefundsAndReopens() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        bets.put(player.getUniqueId(), 3);
        game.onTableReady(table);
        // The betting clock closes this public gate before invoking closeBets.
        table.setBetOpen(false);
        game.closeBets(table);
        assertTrue(table.betOpen());
        assertFalse(table.live());
        assertEquals(0, table.autoCountdown());
        assertTrue(bets.isEmpty());
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(player), eq(0), anyList(),
                eq("under min refund"));
        verify(manager, never()).beginSession(table);
        assertMessage(player, "bet.under_min", "min", "5");
    }

    @Test
    void humanTakeoverStartingRoundCancelsTheOldAutomaticCountdown() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        game.onTableReady(table);
        game.onChipIn(table, player);
        assertEquals(2, table.autoCountdown());
        game.tryClaimDealer(table, dealer);
        assertEquals(dealer.getUniqueId(), table.dealerId());
        queue(5, 10, 6, 7);
        manager.beginSession(table);
        game.onShoeClick(table, dealer);
        finishAnimations();
        server.getScheduler().performTicks(40);
        verify(manager, times(1)).beginSession(table);
        assertTrue(table.live());
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(0, table.autoCountdown());
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertEquals(20, bets.get(player.getUniqueId()));
    }

    @Test
    void unavailableGuildSlotsRefundLegalBetsInsteadOfStarting() {
        guilds.when(() -> GuildTables.canStartGuildAutoRound(table)).thenReturn(false);
        game.closeBets(table);
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(player), eq(0), anyList(), eq("box refund"));
        verify(manager, never()).beginSession(table);
        assertTrue(bets.isEmpty());
    }

    @Test
    void humanDealerClaimsUnsetsAndCannotStealAnotherDealersShoe() {
        table.setDealerId(null);
        assertTrue(game.tryClaimDealer(table, player));
        assertEquals(player.getUniqueId(), table.dealerId());
        assertTrue(game.tryClaimDealer(table, dealer));
        assertEquals(player.getUniqueId(), table.dealerId());
        assertMessage(dealer, "dealer.taken");
        assertTrue(game.tryClaimDealer(table, player));
        assertNull(table.dealerId());
        verify(manager, times(2)).persistHouseChange(table);
    }

    @Test
    void automaticDealerTakeoverRequiresStandRangeAndPreservesAutomaticSetting() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        player.teleport(table.getOrigin().clone().add(3, 0, 0));
        game.tryClaimDealer(table, player);
        assertNull(table.dealerId());
        player.teleport(table.getOrigin());
        game.tryClaimDealer(table, player);
        assertEquals(player.getUniqueId(), table.dealerId());
        assertTrue(table.autoDealer());
        verify(manager).bankAutoTray(table);
        game.tryClaimDealer(table, player);
        assertNull(table.dealerId());
        assertTrue(BlackjackGame.auto(table));
    }

    @Test
    void staffMintAndGuildPermissionsPreventDealerTakeover() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        table.setStaffMint(true);
        game.tryClaimDealer(table, player);
        assertNull(table.dealerId());
        assertMessage(player, "dealer.staff_table");
        table.setStaffMint(false);
        guilds.when(() -> GuildTables.mayDeal(table, player)).thenReturn(false);
        game.tryClaimDealer(table, player);
        assertNull(table.dealerId());
        assertMessage(player, "dealer.denied");
    }

    @Test
    void automaticDealerCannotBeClaimedFromIdenticalCoordinatesInAnotherWorld() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        Location remote = table.getOrigin().clone();
        remote.setWorld(server.addSimpleWorld("remote-dealer"));
        player.teleport(remote);
        assertTrue(game.tryClaimDealer(table, player));
        assertNull(table.dealerId());
        verify(manager, never()).bankAutoTray(table);
        verify(manager, never()).persistHouseChange(table);
    }

    @Test
    void unavailableStandLayoutPreventsAutomaticDealerTakeover() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        Cache.tableLayouts.remove("blackjack");
        assertTrue(game.tryClaimDealer(table, player));
        assertNull(table.dealerId());
        verify(manager, never()).bankAutoTray(table);
        verify(manager, never()).persistHouseChange(table);
    }

    @Test
    void guildPermissionAlsoProtectsAnUnclaimedManualDealerSeat() {
        table.setDealerId(null);
        guilds.when(() -> GuildTables.mayDeal(table, player)).thenReturn(false);
        assertTrue(game.tryClaimDealer(table, player));
        assertNull(table.dealerId());
        assertMessage(player, "dealer.denied");
        verify(manager, never()).persistHouseChange(table);
    }

    @Test
    void idleHumanDealerDepartureReopensAutomaticBetting() {
        table.setAutoDealer(true);
        game.onTableReady(table);
        assertFalse(table.betOpen());
        table.setDealerId(null);
        game.onDealerGone(table);
        assertTrue(table.betOpen());
        assertFalse(table.live());
        game.onChipIn(table, player);
        assertEquals(2, table.autoCountdown());
        assertEquals(20, bets.get(player.getUniqueId()));
        verify(manager, never()).beginSession(table);
    }

    @Test
    void disconnectedBoxDuringInitialDealDoesNotBlockRemainingPlayersCards() {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        queue(5, 4, 10, 5, 7);
        table.startSession();
        game.onSessionStart(table);
        game.onShoeClick(table, dealer);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        table.getHands().remove(player.getUniqueId());
        player.disconnect();
        game.onLeave(table, player);
        finishAnimations();
        assertEquals(List.of(next.getUniqueId()), table.boxes());
        assertEquals(next.getUniqueId(), table.actor());
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(List.of(4, 5), table.handOf(next.getUniqueId()).stream()
                .map(held -> held.card().getRank()).toList());
        assertEquals(2, table.tablePile("dealer").size());
        verify(manager, times(1)).dealToPlayer(eq(table), eq(player), eq(1), eq(0), any(Runnable.class));
    }

    @Test
    void configuredRoundShuffleHappensBeforeCardsAreDealt() {
        table.setShufflePolicy(ShufflePolicy.ROUND);
        start(5, 10, 6, 7);
        var order = inOrder(manager);
        order.verify(manager).reshuffleFull(table);
        order.verify(manager, times(2)).dealToPlayer(eq(table), eq(player), eq(1), eq(0), any(Runnable.class));
    }

    @Test
    void houseCoversNewChipsWithoutRefillingAnAlreadyCoveredTray() {
        houseBacked();
        ItemStack coin = new ItemStack(Material.GOLD_NUGGET);
        game.onChipIn(table, player, 20, coin);
        assertEquals(20, trayBalance);
        verify(wagers).fundFromHouse(eq(table), eq(table.getId()), eq(coin), eq(20), any(Location.class), eq("tray cover"));
        game.onChipIn(table, player, 20, coin);
        verify(wagers, times(1)).fundFromHouse(any(), any(), any(), anyInt(), any(), eq("tray cover"));
        assertEquals(20, bets.get(player.getUniqueId()));
    }

    @Test
    void uncoveredChipIsRefundedExactlyAndDoesNotStartCountdown() {
        houseBacked();
        table.setDealerId(null);
        table.setAutoDealer(true);
        fundingAccepted = false;
        game.onTableReady(table);
        bets.put(player.getUniqueId(), 27);
        game.onChipIn(table, player, 7, new ItemStack(Material.GOLD_NUGGET));
        assertEquals(20, bets.get(player.getUniqueId()));
        assertEquals(0, table.autoCountdown());
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(player), eq(7), anyList(), eq("bet not covered"));
        verify(manager).flushPiles(eq(table), anyList(), isNull());
        assertMessage(player, "bet.bank_short");
    }

    @Test
    void coverUsesAvailableHouseCoinForLootAndWaitsForSubCoinShortfall() {
        houseBacked();
        ItemStack loot = new ItemStack(Material.DIAMOND);
        ItemStack coin = new ItemStack(Material.GOLD_NUGGET);
        when(manager.chipUnitDenars(loot)).thenReturn(0);
        when(manager.chipUnitDenars(coin)).thenReturn(5);
        when(manager.feltItem(table, table.getId())).thenReturn(coin);
        trayBalance = 5;
        game.onChipIn(table, player, 15, loot);
        assertEquals(20, trayBalance);
        verify(wagers).fundFromHouse(eq(table), eq(table.getId()), eq(coin), eq(15), any(), eq("tray cover"));
        bets.put(player.getUniqueId(), 23);
        game.onChipIn(table, player, 3, loot);
        assertEquals(20, trayBalance);
        assertEquals(23, bets.get(player.getUniqueId()));
        verify(wagers, times(1)).fundFromHouse(any(), any(), any(), anyInt(), any(), eq("tray cover"));
    }

    @Test
    void lootCoverageUsesAPlayersExistingCoinWhenTheHouseTrayIsEmpty() {
        houseBacked();
        ItemStack loot = new ItemStack(Material.DIAMOND);
        ItemStack coin = new ItemStack(Material.GOLD_INGOT);
        when(manager.chipUnitDenars(loot)).thenReturn(0);
        when(manager.chipUnitDenars(coin)).thenReturn(5);
        when(manager.feltItem(table, table.getId())).thenReturn(null);
        when(manager.feltItem(table, player.getUniqueId())).thenReturn(coin);
        game.onChipIn(table, player, 5, loot);
        verify(wagers).fundFromHouse(eq(table), eq(table.getId()), eq(coin), eq(20), any(), eq("tray cover"));
        assertEquals(20, trayBalance);
        assertEquals(20, bets.get(player.getUniqueId()));
        verify(wagers, never()).refund(any(), any(), any(), anyInt(), anyList(), anyString());
    }

    @Test
    void roundReservesWorstCaseForEveryBoxAndRoundsUpToWholeHouseCoins() {
        houseBacked();
        configure(false, 3, false, 0);
        PlayerMock other = server.addPlayer();
        seat(other, 15, 1);
        ItemStack houseCoin = new ItemStack(Material.GOLD_INGOT);
        when(manager.feltItem(table, table.getId())).thenReturn(houseCoin);
        when(manager.chipUnitDenars(houseCoin)).thenReturn(25);
        trayBalance = 25;
        game.closeBets(table);
        // (20 + 15) * 3 hands * double = 210; a 25-denar float needs 200 more in 25-denar coins.
        verify(wagers).fundFromHouse(eq(table), eq(table.getId()), eq(houseCoin), eq(200), any(), eq("round reserve"));
        assertEquals(225, trayBalance);
        verify(manager).beginSession(table);
        assertEquals(BlackjackGame.WAIT_DEAL, table.phase());
        table.clearSession();
        game.onSessionEnd(table);
        verify(manager).bankAutoTray(table);
        assertEquals(0, trayBalance);
    }

    @Test
    void alreadyReservedRoundDoesNotRequestMoreBankMoney() {
        houseBacked();
        trayBalance = 160;
        game.closeBets(table);
        verify(wagers, never()).fundFromHouse(any(), any(), any(), anyInt(), any(), anyString());
        verify(manager).beginSession(table);
    }

    @Test
    void emptyTrayReserveUsesPlayersCoinAndBankRefusalRefundsEveryone() {
        houseBacked();
        when(manager.feltItem(table, table.getId())).thenReturn(null);
        fundingAccepted = false;
        game.closeBets(table);
        verify(wagers).fundFromHouse(eq(table), eq(table.getId()), any(ItemStack.class), eq(160), any(), eq("round reserve"));
        verify(manager, never()).beginSession(table);
        assertTrue(bets.isEmpty());
        assertMessage(player, "bet.house_short");
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(player), eq(0), anyList(), eq("box refund"));
    }

    @Test
    void unpayableLootCannotStartHouseRoundAndReturnsItsStake() {
        houseBacked();
        ItemStack loot = new ItemStack(Material.DIAMOND);
        when(manager.feltItem(eq(table), any(UUID.class))).thenReturn(loot);
        when(manager.chipUnitDenars(loot)).thenReturn(0);
        game.onChipIn(table, player, 20, loot);
        assertEquals(20, bets.get(player.getUniqueId()), "Coverage gate waits until close for loot without a coin template");
        game.closeBets(table);
        verify(manager, never()).beginSession(table);
        verify(wagers, never()).fundFromHouse(any(), any(), any(), anyInt(), any(), anyString());
        assertTrue(bets.isEmpty());
    }

    @Test
    void underMinimumRefundReturnsMatchingExcessTrayMoneyToBank() {
        houseBacked();
        bets.put(player.getUniqueId(), 3);
        trayBalance = 3;
        game.closeBets(table);
        assertTrue(bets.isEmpty());
        assertEquals(0, trayBalance);
        verify(wagers).peelToHouse(table, 3, "peel");
        verify(manager, never()).beginSession(table);
    }

    @Test
    void leavingOpenAutomaticBoxRefundsItAndPeelsOnlyUnusedCover() {
        houseBacked();
        table.setDealerId(null);
        table.setAutoDealer(true);
        trayBalance = 20;
        game.onTableReady(table);
        game.onChipIn(table, player);
        game.onLeave(table, player);
        assertTrue(bets.isEmpty());
        assertEquals(0, trayBalance);
        assertEquals(0, table.autoCountdown());
        verify(wagers).peelToHouse(table, 20, "peel");
        server.getScheduler().performTicks(40);
        verify(manager, never()).beginSession(table);
    }

    @Test
    void frozenGuildRefundsOpenBoxesAndClosesItsBetWindow() {
        houseBacked();
        table.setDealerId(null);
        table.setAutoDealer(true);
        trayBalance = 20;
        game.onTableReady(table);
        game.onChipIn(table, player);
        guilds.when(() -> GuildTables.frozen(table)).thenReturn(true);
        game.onTableReady(table);
        assertTrue(bets.isEmpty());
        assertFalse(table.betOpen());
        assertEquals(0, table.autoCountdown());
        assertEquals(0, trayBalance);
        assertEquals(Messages.get("label.no_slots"), game.extraLabel(table));
        server.getScheduler().performTicks(40);
        verify(manager, never()).beginSession(table);
    }

    @Test
    void guildBackedLosingStakeGoesToTrayEvenWithHumanDealer() {
        houseBacked();
        start(10, 10, 6, 7);
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(20, trayBalance);
        verify(wagers).toTray(eq(table), eq(player.getUniqueId()), eq(20), anyList(), eq("loss"));
        verify(wagers, never()).refund(any(), any(), eq(dealer), anyInt(), anyList(), eq("loss to dealer"));
        server.getScheduler().performTicks(40);
        assertEquals(0, trayBalance);
        verify(manager).bankAutoTray(table);
    }

    @Test
    void successfulHouseCoveredDoubleUsesExistingReserveAndPaysFromHouse() {
        houseBacked();
        trayBalance = 160;
        start(5, 10, 6, 7, 9);
        game.onBetDouble(table, player);
        finishAnimations();
        verify(extraBet).commit();
        verify(wagers, never()).fundFromHouse(any(), any(), any(), anyInt(), any(), anyString());
        verify(wagers).payWin(eq(table), eq(player), eq(player.getUniqueId()), eq(40), eq(dealer), eq(false),
                any(ItemStack.class), anyList());
    }

    @Test
    void unpaidWinRemainderIsReportedToPrivateDealer() {
        when(wagers.payWin(eq(table), eq(player), eq(player.getUniqueId()), eq(20), eq(dealer), eq(true),
                any(ItemStack.class), anyList())).thenReturn(new PayWinResult(15, 0, 5));
        start(10, 10, 8, 7);
        game.onBetStand(table, player);
        finishAnimations();
        assertMessage(dealer, "bet.owe", "amount", "5");
    }

    @Test
    void unpaidHouseWinRemainderIsReportedToWinnerInsteadOfHumanDealer() {
        houseBacked();
        when(wagers.payWin(eq(table), eq(player), eq(player.getUniqueId()), eq(20), eq(dealer), eq(false),
                any(ItemStack.class), anyList())).thenReturn(new PayWinResult(15, 0, 5));
        start(10, 10, 8, 7);
        game.onBetStand(table, player);
        finishAnimations();
        assertMessage(player, "bet.owe", "amount", "5");
        String message;
        while ((message = dealer.nextMessage()) != null) {
            assertNotEquals(Messages.get("bet.owe", "amount", "5"), message);
        }
        verify(wagers).payWin(eq(table), eq(player), eq(player.getUniqueId()), eq(20), eq(dealer), eq(false),
                any(ItemStack.class), anyList());
    }

    @Test
    void delayedResultReturnsTurnForNonBustAndLaterSettlesOnStand() {
        configure(false, 4, false, 5);
        start(5, 10, 6, 7, 2);
        game.onBetHit(table, player);
        finishAnimations();
        server.getScheduler().performTicks(5);
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(BlackjackGame.PLAY, table.phase());
        game.onBetStand(table, player);
        server.getScheduler().performTicks(10);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void leavingDuringResultDelayCannotReclaimOrSkipTheNextPlayersTurn(boolean doubled) {
        configure(false, 4, false, 5);
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(5, 4, 10, 6, 5, 7, 2);
        if (doubled) {
            game.onBetDouble(table, player);
        } else {
            game.onBetHit(table, player);
        }
        finishAnimations();
        assertEquals(3, table.handOf(player.getUniqueId()).size());

        // TableManager.returnHand mucks the cards before notifying the game of departure.
        table.getHands().remove(player.getUniqueId());
        game.onLeave(table, player);
        assertEquals(next.getUniqueId(), table.actor());
        assertEquals(List.of(next.getUniqueId()), table.boxes());

        server.getScheduler().performTicks(5);
        finishAnimations();
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(next.getUniqueId(), table.actor(), "The old result belongs only to the departed box");
        assertEquals(2, table.handOf(next.getUniqueId()).size());
        assertEquals(10, bets.get(next.getUniqueId()));
        verify(manager, never()).revealTablePile(table, "dealer");
        verify(wagers, never()).announceWins(any(), anyString());
    }

    @Test
    void departureOfAnAlreadyPlayedBoxPreservesTheCurrentPlayersTurn() {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(5, 4, 10, 6, 5, 7);
        game.onBetStand(table, player);
        assertEquals(next.getUniqueId(), table.actor());
        table.getHands().remove(player.getUniqueId());
        game.onLeave(table, player);
        assertEquals(next.getUniqueId(), table.actor());
        assertEquals(0, table.boxIndex());
        queue(2);
        game.onBetHit(table, next);
        finishAnimations();
        assertEquals(11, BlackjackGame.bestTotal(table.handOf(next.getUniqueId())));
        assertEquals(next.getUniqueId(), table.actor());
        verify(manager, never()).revealTablePile(table, "dealer");
    }

    @Test
    void delayedDoubleAdvancesToNextPlayerWhenItsOwnerStays() {
        configure(false, 4, false, 5);
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(5, 4, 10, 6, 5, 7, 2);
        game.onBetDouble(table, player);
        finishAnimations();
        assertEquals(player.getUniqueId(), table.actor());
        server.getScheduler().performTicks(5);
        assertEquals(next.getUniqueId(), table.actor());
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(40, bets.get(player.getUniqueId()));
        assertEquals(List.of(5, 6, 2), ranks(0));
        verify(manager, never()).revealTablePile(table, "dealer");
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void leavingAfterCardArrivalBeforeCompletionDoesNotApplyOldActionToNextBox(boolean doubled) {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(5, 4, 10, 6, 5, 7, 2);
        if (doubled) {
            game.onBetDouble(table, player);
        } else {
            game.onBetHit(table, player);
        }
        assertEquals(BlackjackGame.DEAL, table.phase());
        assertEquals(3, table.handOf(player.getUniqueId()).size());
        // runAfterDraw schedules completion after arrival; departure does not cancel that task.
        table.getHands().remove(player.getUniqueId());
        game.onLeave(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(next.getUniqueId(), table.actor());
        assertEquals(2, table.handOf(next.getUniqueId()).size());
        assertEquals(10, bets.get(next.getUniqueId()));
        verify(manager, never()).revealTablePile(table, "dealer");
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void leavingDuringSecondSplitHandCardAnimationPreservesNextPlayersFirstHand(boolean doubled) {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(8, 4, 10, 8, 5, 7, 2, 3, 2);
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(List.of(8, 2), ranks(0));
        assertEquals(List.of(8, 3), ranks(1));
        game.onBetStand(table, player);
        assertEquals(1, table.handIndex());
        if (doubled) {
            game.onBetDouble(table, player);
        } else {
            game.onBetHit(table, player);
        }
        assertEquals(BlackjackGame.DEAL, table.phase());
        assertEquals(List.of(8, 3, 2), ranks(1));
        // TableManager returns departing players' cards before delivering onLeave.
        table.getHands().remove(player.getUniqueId());
        game.onLeave(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(next.getUniqueId(), table.actor(), "The next box must start at its own first hand");
        assertEquals(0, table.handIndex());
        assertEquals(0, table.boxIndex());
        assertEquals(9, BlackjackGame.bestTotal(table.handOf(next.getUniqueId())));
        assertEquals(10, bets.get(next.getUniqueId()));
        verify(manager, never()).revealTablePile(table, "dealer");
        verify(wagers, never()).announceWins(any(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void naturalResultDelayPreservesNextPlayersTurnWhetherNaturalOwnerStaysOrLeaves(boolean leaves) {
        configure(false, 4, false, 5);
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(1, 4, 10, 10, 5, 7);
        assertNull(table.actor(), "Natural blackjack requires no player action");
        if (leaves) {
            table.getHands().remove(player.getUniqueId());
            game.onLeave(table, player);
        }
        server.getScheduler().performTicks(5);
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(next.getUniqueId(), table.actor());
        assertEquals(2, table.handOf(next.getUniqueId()).size());
        assertEquals(10, bets.get(next.getUniqueId()));
        verify(manager, never()).revealTablePile(table, "dealer");
    }

    @Test
    void earlierBoxDepartureDoesNotShiftNaturalDelayOntoTheFollowingBox() {
        configure(false, 4, false, 5);
        PlayerMock natural = server.addPlayer();
        PlayerMock next = server.addPlayer();
        seat(natural, 10, 1);
        seat(next, 10, 3);
        start(5, 1, 4, 10, 6, 10, 5, 7);
        game.onBetStand(table, player);
        assertEquals(1, table.boxIndex());
        table.getHands().remove(player.getUniqueId());
        game.onLeave(table, player);
        assertEquals(List.of(natural.getUniqueId(), next.getUniqueId()), table.boxes());
        assertEquals(0, table.boxIndex());
        server.getScheduler().performTicks(5);
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(next.getUniqueId(), table.actor());
        assertEquals(1, table.boxIndex());
        assertEquals(0, table.handIndex());
        verify(manager, never()).revealTablePile(table, "dealer");
    }

    @Test
    void refusedDoubleExplainsUnavailableChangeAndKeepsTheOriginalBet() {
        TxResult refused = result(false, 0);
        when(refused.reason()).thenReturn(TxResult.Reason.NO_CHANGE);
        when(refused.best()).thenReturn(15);
        doReturn(refused).when(extraBet).commit();
        start(5, 10, 6, 7);
        game.onBetDouble(table, player);
        assertMessage(player, "bet.no_change", "amount", "20", "best", "15");
        assertEquals(20, bets.get(player.getUniqueId()));
        assertEquals(List.of(5, 6), ranks(0));
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(BlackjackGame.PLAY, table.phase());
    }

    @Test
    void auditChecksTheCombinedSplitStakeAgainstTheFelt() {
        Cache.wagerAuditLog = true;
        when(wagers.owned(eq(table), any(UUID.class)))
                .thenAnswer(call -> bets.getOrDefault(call.getArgument(1), 0));
        try (MockedStatic<MoneyLog> log = mockStatic(MoneyLog.class)) {
            start(8, 10, 8, 7, 2, 3);
            game.onBetSplit(table, player);
            finishAnimations();
            assertEquals(40, bets.get(player.getUniqueId()));
            assertEquals(List.of(8, 2), ranks(0));
            assertEquals(List.of(8, 3), ranks(1));
            verify(wagers).owned(table, player.getUniqueId());
            log.verify(() -> MoneyLog.mismatch(eq(table), anyString()), never());
        }
    }

    @Test
    void removedTableCancelsLingerWithoutMuckingOrEndingAnotherSession() {
        start(10, 10, 8, 7);
        game.onBetStand(table, player);
        finishAnimations();
        // TableManager clears the session and notifies the game before forgetting the table.
        table.clearSession();
        game.onTableRemoved(table);
        available = false;
        server.getScheduler().performTicks(60);
        verify(manager, never()).endSession(table);
        verify(manager, never()).muckPlayer(any(Table.class), any(UUID.class));
    }

    @Test
    void aRoundStoppedWhileItsPayoutLandsDoesNotLingerIntoTheNextRound() {
        start(10, 10, 8, 7);
        game.onBetStand(table, player);
        Runnable payoutLanded = settleUntilThePayoutWave();
        // An admin runs /games session stop while the winnings are still in the air.
        manager.endSession(table);
        payoutLanded.run();
        assertEquals(0, table.autoCountdown(), "an idle table shows no round-end countdown");
        freshRound(5, 10, 6, 7);
        game.onShoeClick(table, dealer);
        finishAnimations();
        assertEquals(BlackjackGame.PLAY, table.phase());
        server.getScheduler().performTicks(60);
        assertTrue(table.live(), "the new round is still being played");
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(player.getUniqueId(), table.actor());
        verify(manager, never()).muckPlayer(any(Table.class), any(UUID.class));
    }

    @Test
    void aRoundRestartedBeforeTheOldPayoutLandsKeepsItsOwnPhase() {
        start(10, 10, 8, 7);
        game.onBetStand(table, player);
        Runnable payoutLanded = settleUntilThePayoutWave();
        // /games session stop and then start, both before the winnings reach the player.
        manager.endSession(table);
        freshRound(5, 10, 6, 7);
        assertEquals(BlackjackGame.WAIT_DEAL, table.phase());
        payoutLanded.run();
        assertEquals(BlackjackGame.WAIT_DEAL, table.phase(), "the new round still waits for the dealer");
        assertEquals(0, table.autoCountdown());
        server.getScheduler().performTicks(60);
        assertTrue(table.live());
        game.onShoeClick(table, dealer);
        finishAnimations();
        assertEquals(BlackjackGame.PLAY, table.phase());
    }

    /** Plays every animation of the settle except the payout wave, and hands that wave back. */
    private Runnable settleUntilThePayoutWave() {
        while (animations.size() > 1) {
            animations.removeFirst().run();
        }
        assertEquals(BlackjackGame.SETTLE, table.phase());
        return animations.removeFirst();
    }

    /** Clears the felt as the real session end does, then stakes and opens the next round. */
    private void freshRound(int... ranks) {
        table.getHands().clear();
        table.tablePiles().clear();
        bets.put(player.getUniqueId(), 20);
        queue(ranks);
        manager.beginSession(table);
        assertTrue(table.live());
    }

    @Test
    void dealerHologramHidesHoleCardUpdatesInPlaceAndDisappearsWhenPileClears() {
        TextDisplay label = label();
        UUID id = label.getUniqueId();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                MockedStatic<Messages> messages = mockStatic(Messages.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(label);
            messages.when(() -> Messages.get("label.total_hole", "up", "10")).thenReturn("Showing 10, hole hidden");
            messages.when(() -> Messages.get("label.total", "n", "17")).thenReturn("Total 17");
            table.tablePile("dealer").add(card(10, true));
            table.tablePile("dealer").add(card(7, false));
            game.onTablePilesChanged(table);
            anchors.verify(() -> WorldAnchors.spawnLabel(any(Location.class), eq("Showing 10, hole hidden")));
            messages.verify(() -> Messages.get("label.total_hole", "up", "10"), atLeastOnce());
            table.tablePile("dealer").getLast().setFaceUp(true);
            game.onTablePilesChanged(table);
            anchors.verify(() -> WorldAnchors.setText(id, "Total 17"));
            anchors.verify(() -> WorldAnchors.move(eq(id), any(Location.class)));
            table.tablePile("dealer").clear();
            game.onTablePilesChanged(table);
            anchors.verify(() -> WorldAnchors.remove(id));
        }
    }

    @Test
    void dealerHologramShowsSoftAndBustTotalsAndRecoversFromRemovedEntity() {
        TextDisplay first = label();
        TextDisplay replacement = mock(TextDisplay.class);
        when(replacement.getUniqueId()).thenReturn(UUID.randomUUID());
        anchors.when(() -> WorldAnchors.spawnLabel(any(Location.class), anyString())).thenReturn(first, replacement);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                MockedStatic<Messages> messages = mockStatic(Messages.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getEntity(first.getUniqueId())).thenReturn(first);
            bukkit.when(() -> Bukkit.getEntity(replacement.getUniqueId())).thenReturn(replacement);
            table.tablePile("dealer").add(card(1, true));
            table.tablePile("dealer").add(card(6, true));
            game.onTablePilesChanged(table);
            messages.verify(() -> Messages.get("label.total_soft", "hard", "7", "soft", "17"));
            when(first.isDead()).thenReturn(true);
            table.tablePile("dealer").add(card(10, true));
            table.tablePile("dealer").add(card(10, true));
            game.onTablePilesChanged(table);
            anchors.verify(() -> WorldAnchors.remove(first.getUniqueId()));
            messages.verify(() -> Messages.get("label.total_bust"));
            game.onTableRemoved(table);
            anchors.verify(() -> WorldAnchors.remove(replacement.getUniqueId()));
        }
    }

    @Test
    void trayHologramTracksMoneyAndIsRemovedWhenTrayEmpties() {
        TextDisplay label = label();
        UUID id = label.getUniqueId();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                MockedStatic<Messages> messages = mockStatic(Messages.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(label);
            messages.when(() -> Messages.get("label.tray_total", "n", "30")).thenReturn("Tray 30");
            messages.when(() -> Messages.get("label.tray_total", "n", "10")).thenReturn("Tray 10");
            trayBalance = 30;
            game.onFeltPilesChanged(table);
            Location tray = Cache.layoutOf("blackjack").trayLocation(table);
            anchors.verify(() -> WorldAnchors.spawnLabel(tray, "Tray 30"));
            trayBalance = 10;
            game.onFeltPilesChanged(table);
            anchors.verify(() -> WorldAnchors.setText(id, "Tray 10"));
            anchors.verify(() -> WorldAnchors.move(id, tray));
            trayBalance = 0;
            game.onFeltPilesChanged(table);
            anchors.verify(() -> WorldAnchors.remove(id));
        }
    }

    @Test
    void removingTrayLayoutClearsExistingTrayHologram() {
        TextDisplay label = label();
        trayBalance = 10;
        game.onFeltPilesChanged(table);
        Cache.tableLayouts.remove("blackjack");
        game.onFeltPilesChanged(table);
        anchors.verify(() -> WorldAnchors.remove(label.getUniqueId()));
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void failedHologramSpawnRetriesAndThenUpdatesTheRecoveredLabel(boolean tray) {
        TextDisplay recovered = label();
        anchors.when(() -> WorldAnchors.spawnLabel(any(Location.class), anyString()))
                .thenReturn(null, recovered);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getEntity(recovered.getUniqueId())).thenReturn(recovered);
            trayBalance = 20;
            table.tablePile("dealer").add(card(10, true));
            Runnable refresh = tray ? () -> game.onFeltPilesChanged(table)
                    : () -> game.onTablePilesChanged(table);
            refresh.run();
            refresh.run();
            anchors.verify(() -> WorldAnchors.spawnLabel(any(Location.class), anyString()), times(2));
            trayBalance = 30;
            table.tablePile("dealer").add(card(7, true));
            refresh.run();
            String expected = tray ? Messages.get("label.tray_total", "n", "30")
                    : Messages.get("label.total", "n", "17");
            anchors.verify(() -> WorldAnchors.setText(recovered.getUniqueId(), expected));
            anchors.verify(() -> WorldAnchors.spawnLabel(any(Location.class), anyString()), times(2));
            game.onTableRemoved(table);
            anchors.verify(() -> WorldAnchors.remove(recovered.getUniqueId()));
        }
    }

    @Test
    void dealerTurnLabelKeepsTheOutstandingBoxAndActionVisible() {
        configure(false, 4, false, 5);
        try (MockedStatic<Messages> messages = mockStatic(Messages.class, CALLS_REAL_METHODS)) {
            messages.when(() -> Messages.get("label.turn", "name", "Dealer")).thenReturn("Dealer's turn");
            messages.when(() -> Messages.get("label.box", "name", player.getName(), "stake", "20"))
                    .thenReturn("Box: 20");
            messages.when(() -> Messages.get("label.action", "total", "20")).thenReturn("Action: 20");
            assertEquals("", game.extraLabel(table), "An available idle table has no round summary");
            start(10, 10, 8, 7);
            game.onBetStand(table, player);
            assertEquals(BlackjackGame.DEALER, table.phase());
            assertEquals("Dealer's turn\nBox: 20\nAction: 20", game.extraLabel(table));
            assertEquals(20, bets.get(player.getUniqueId()));
        }
    }

    @Test
    void liveLabelReflectsCurrentPlayerAndSplitStakeTotals() {
        try (MockedStatic<Messages> messages = mockStatic(Messages.class, CALLS_REAL_METHODS)) {
            messages.when(() -> Messages.get("label.turn", "name", player.getName())).thenReturn("Your turn");
            messages.when(() -> Messages.get("label.box", "name", player.getName(), "stake", "40"))
                    .thenReturn("Your box: 40");
            messages.when(() -> Messages.get("label.action", "total", "40")).thenReturn("Action: 40");
            start(8, 10, 8, 7, 2, 3);
            game.onBetSplit(table, player);
            finishAnimations();
            assertEquals("Your turn\nYour box: 40\nAction: 40", game.extraLabel(table));
        }
    }

    @ParameterizedTest
    @CsvSource({"1, 1, 9, 21, 11", "1, 1, 5, 17, 7", "1, 13, 10, 21, 21", "11, 12, 2, 22, 22"})
    void totalsUseOneSoftAceWhenPossibleAndFaceCardsCountTen(int a, int b, int c, int best, int hard) {
        List<HandCard> cards = List.of(card(a, true), card(b, true), card(c, true));
        assertEquals(best, BlackjackGame.bestTotal(cards));
        assertEquals(hard, BlackjackGame.hardTotal(cards));
    }

    @Test
    void shoeNeverDealsFreeCardsAndPokerWordsDoNotPlayAHand() {
        assertFalse(game.allowFreeDraw(table, player), "an idle blackjack shoe is not a sandbox deck");
        assertFalse(game.allowRevealToggle(table, player), "blackjack hands are always public");
        assertFalse(game.showRevealDust(table));
        start(10, 10, 6, 7);
        assertFalse(game.allowFreeDraw(table, player));
        assertFalse(game.allowReturnSelected(table, player));
        assertTrue(game.allowPlayChat(table, player));
        for (String word : List.of("check", "call", "raise", "fold", "draw")) {
            game.onPlayWord(table, player, word);
        }
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertEquals(20, bets.get(player.getUniqueId()));
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
    }

    @Test
    void roundWithoutAConfiguredLayoutUsesDefaultTimingsAndDealerStandsOnSoftSeventeen() {
        Cache.tableLayouts.remove("blackjack");
        table.setDealerId(null);
        table.setAutoDealer(true);
        game.onTableReady(table);
        game.onChipIn(table, player);
        assertEquals(10, table.autoCountdown(), "ten seconds to bet by default");
        queue(10, 1, 9, 6);
        server.getScheduler().performTicks(200);
        finishAnimations();
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(player.getUniqueId(), table.actor());
        game.onBetStand(table, player);
        assertEquals(BlackjackGame.DEALER, table.phase());
        server.getScheduler().performTicks(7);
        assertEquals(BlackjackGame.DEALER, table.phase(), "results pause eight ticks by default");
        server.getScheduler().performTicks(1);
        assertEquals(2, table.tablePile("dealer").size(), "the dealer stands on soft seventeen by default");
        server.getScheduler().performTicks(8);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
        assertEquals(10, table.autoCountdown(), "the result lingers ten seconds by default");
        verify(wagers).payWin(eq(table), eq(player), eq(player.getUniqueId()), eq(20), isNull(),
                eq(true), any(ItemStack.class), anyList());
        assertMessage(player, "bet.win");
    }

    @Test
    void splitAcesWithoutAConfiguredLayoutCannotBeSplitAgain() {
        Cache.tableLayouts.remove("blackjack");
        start(1, 10, 1, 7);
        queue(1, 5);
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(List.of(1, 1), ranks(0));
        assertEquals(List.of(1, 5), ranks(1));
        assertNull(table.actor(), "split aces take one card each and stand");
        server.getScheduler().performTicks(40);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(dealer), eq(40), anyList(), eq("loss to dealer"));
    }

    @Test
    void automaticTakeoverNeedsAConfiguredStandAndDefaultsItsReach() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        Cache.tableLayouts.put("blackjack", new TableLayout("cards", "Blackjack", "icon", 6, Map.of(),
                null, null, null, 0.5));
        player.teleport(table.getOrigin());
        assertTrue(game.tryClaimDealer(table, player));
        assertNull(table.dealerId(), "without a stand there is nowhere to take the shoe from");
        Cache.tableLayouts.put("blackjack", new TableLayout("cards", "Blackjack", "icon", 6, Map.of(),
                null, null, new TableLayout.PileSlot(0, 0), 0));
        player.teleport(table.getOrigin().clone().add(0.6, 0, 0));
        game.tryClaimDealer(table, player);
        assertNull(table.dealerId(), "half a block is the default reach");
        player.teleport(table.getOrigin().clone().add(0.4, 0, 0));
        game.tryClaimDealer(table, player);
        assertEquals(player.getUniqueId(), table.dealerId());
    }

    @Test
    void labelDuringANaturalsPauseShowsNoTurnAndEachBoxsOwnStake() {
        configure(false, 4, false, 5);
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(1, 5, 9, 10, 6, 7);
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertNull(table.actor());
        String label = game.extraLabel(table);
        assertFalse(label.contains("label.turn"));
        assertTrue(label.contains(Messages.get("label.box", "name",
                net.tfminecraft.games.voice.RpNames.of(player.getUniqueId()), "stake", "20")));
        assertTrue(label.contains(Messages.get("label.box", "name",
                net.tfminecraft.games.voice.RpNames.of(next.getUniqueId()), "stake", "10")));
        assertTrue(label.contains(Messages.get("label.action", "total", "30")));
        server.getScheduler().performTicks(5);
        assertEquals(next.getUniqueId(), table.actor());
    }

    @Test
    void lastBoxLeavingDuringTheLingerStillFinishesTheRound() {
        start(10, 10, 8, 7);
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
        game.onLeave(table, player);
        assertTrue(table.live());
        assertTrue(table.boxes().isEmpty());
        String label = game.extraLabel(table);
        assertFalse(label.contains("label.box"));
        assertFalse(label.contains("label.action"));
        server.getScheduler().performTicks(40);
        assertFalse(table.live());
        verify(manager).endSession(table);
    }

    @Test
    void leavingDuringAnAutomaticRoundNeitherPeelsTheTrayNorRestartsTheBetClock() {
        houseBacked();
        trayBalance = 100;
        table.setDealerId(null);
        table.setAutoDealer(true);
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        game.onTableReady(table);
        queue(10, 9, 10, 8, 8, 7);
        game.closeBets(table);
        finishAnimations();
        assertEquals(player.getUniqueId(), table.actor());
        table.getHands().remove(next.getUniqueId());
        game.onLeave(table, next);
        assertEquals(0, table.autoCountdown());
        verify(wagers, never()).peelToHouse(any(), anyInt(), anyString());
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(List.of(player.getUniqueId()), table.boxes());
    }

    @Test
    void frozenGuildTableKeepsItsWindowShutAndLeaversAreNotPeeled() {
        houseBacked();
        table.setDealerId(null);
        table.setAutoDealer(true);
        guilds.when(() -> GuildTables.frozen(table)).thenReturn(true);
        game.onTableReady(table);
        assertFalse(table.betOpen());
        game.onTableReady(table);
        verify(wagers, never()).refund(any(), any(), any(), anyInt(), anyList(), anyString());
        game.onLeave(table, player);
        verify(wagers, never()).peelToHouse(any(), anyInt(), anyString());
        assertEquals(0, table.autoCountdown());
    }

    @Test
    void playerRefundedUnderTheMinimumLeavesWithoutPeelingTheTray() {
        houseBacked();
        trayBalance = 40;
        table.setDealerId(null);
        table.setAutoDealer(true);
        table.setMinBet(25);
        game.onTableReady(table);
        game.closeBets(table);
        assertFalse(bets.containsKey(player.getUniqueId()));
        clearInvocations(wagers);
        game.onLeave(table, player);
        verify(wagers, never()).peelToHouse(any(), anyInt(), anyString());
        assertFalse(table.actives().contains(player.getUniqueId()));
        assertEquals(0, table.autoCountdown());
    }

    @Test
    void laterBoxLeavingKeepsTheCurrentPlayersHand() {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(5, 4, 10, 6, 5, 7);
        assertEquals(player.getUniqueId(), table.actor());
        queue(3);
        game.onBetHit(table, player);
        finishAnimations();
        table.getHands().remove(next.getUniqueId());
        game.onLeave(table, next);
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(BlackjackGame.PLAY, table.phase());
        assertEquals(List.of(5, 6, 3), ranks(0));
        queue(10);
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
    }

    @Test
    void manualCloseWithOnlyUnderMinimumBetsRefundsAndStaysIdle() {
        table.setMinBet(25);
        game.closeBets(table);
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(player), eq(0), anyList(), eq("under min refund"));
        verify(manager, never()).beginSession(table);
        assertFalse(table.live());
        assertFalse(table.betOpen());
    }

    @Test
    void houseChangesDuringARoundDoNotReopenBetting() {
        table.setDealerId(null);
        table.setAutoDealer(true);
        queue(10, 9, 10, 8);
        game.onTableReady(table);
        game.closeBets(table);
        finishAnimations();
        table.setBetOpen(false);
        game.onTableReady(table);
        assertFalse(table.betOpen());
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(BlackjackGame.PLAY, table.phase());
    }

    @Test
    void shoeClicksFromAnyoneButTheActorAreIgnored() {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        queue(5, 4, 10, 6, 5, 7);
        table.startSession();
        game.onSessionStart(table);
        game.onShoeClick(table, dealer);
        game.onShoeClick(table, dealer);
        assertEquals(1, dealOrder.size(), "a click while dealing does not deal again");
        finishAnimations();
        game.onShoeClick(table, next);
        game.onShoeClick(table, dealer);
        assertEquals(6, dealOrder.size());
        assertEquals(player.getUniqueId(), table.actor());
    }

    @Test
    void refusedSplitStakeKeepsASingleHandAndTheTurn() {
        start(8, 10, 8, 7);
        extraAccepted = false;
        game.onBetSplit(table, player);
        assertMessage(player, "bet.need_chips");
        assertEquals(List.of(8, 8), ranks(0));
        assertEquals(20, bets.get(player.getUniqueId()));
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(BlackjackGame.PLAY, table.phase());
    }

    @ParameterizedTest
    @CsvSource({"0", "1"})
    void removingTheTableDuringASplitDealStopsTheSplit(int cardsBeforeRemoval) {
        start(8, 10, 8, 7);
        queue(2, 3);
        game.onBetSplit(table, player);
        for (int i = 0; i < cardsBeforeRemoval; i++) {
            animations.removeFirst().run();
        }
        available = false;
        finishAnimations();
        assertEquals(BlackjackGame.DEAL, table.phase());
        assertEquals(3 + cardsBeforeRemoval, table.handOf(player.getUniqueId()).size());
        verify(manager, never()).revealTablePile(table, "dealer");
    }

    @Test
    void auditReportsAFeltThatNoLongerMatchesTheBets() {
        Cache.wagerAuditLog = true;
        // A staff payout can empty the felt under a live round.
        when(wagers.owned(eq(table), any(UUID.class))).thenReturn(0);
        try (MockedStatic<MoneyLog> log = mockStatic(MoneyLog.class)) {
            start(8, 10, 8, 7, 2, 3);
            game.onBetSplit(table, player);
            finishAnimations();
            log.verify(() -> MoneyLog.mismatch(eq(table), contains("split box")));
        }
    }

    @Test
    void auditReportsASettleThatPaysOutMoreThanItRecords() {
        Cache.wagerAuditLog = true;
        when(wagers.owned(eq(table), any(UUID.class)))
                .thenAnswer(call -> bets.getOrDefault(call.getArgument(1), 0));
        // A win the money engine paid without reporting it leaves the table short.
        when(wagers.payWin(eq(table), nullable(Player.class), any(UUID.class), anyInt(),
                nullable(Player.class), anyBoolean(), nullable(ItemStack.class), anyList()))
                .thenAnswer(call -> {
                    trayBalance -= 5;
                    return new PayWinResult(call.getArgument(3), 0, 0);
                });
        houseBacked();
        trayBalance = 200;
        try (MockedStatic<MoneyLog> log = mockStatic(MoneyLog.class)) {
            start(10, 10, 9, 7);
            game.onBetStand(table, player);
            finishAnimations();
            log.verify(() -> MoneyLog.mismatch(eq(table), contains("settle held")));
            log.verify(() -> MoneyLog.mismatch(eq(table), contains("box")), never());
        }
    }

    @Test
    void hittingToTwentyOneEndsTheTurnWithoutAStand() {
        start(5, 10, 6, 7, 10);
        game.onBetHit(table, player);
        finishAnimations();
        assertEquals(21, BlackjackGame.bestTotal(table.handOf(player.getUniqueId())));
        assertEquals(BlackjackGame.SETTLE, table.phase());
        verifyWin(20);
    }

    @Test
    void boxWhoseCardsFailedToSpawnIsPassedOverAndForfeits() {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        failedSpawnFor = player.getUniqueId();
        start(4, 10, 5, 7);
        assertTrue(table.handOf(player.getUniqueId()).isEmpty());
        assertEquals(next.getUniqueId(), table.actor(), "a box holding no cards has nothing to play");
        game.onBetStand(table, next);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
        verify(wagers).refund(eq(table), eq(player.getUniqueId()), eq(dealer), eq(20), anyList(), eq("loss to dealer"));
    }

    @Test
    void lastBoxLeavingWhileItsCardIsInTheAirRevealsTheDealerOnce() {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(5, 4, 10, 6, 5, 7);
        game.onBetStand(table, player);
        assertEquals(next.getUniqueId(), table.actor());
        queue(9);
        game.onBetHit(table, next);
        assertEquals(BlackjackGame.DEAL, table.phase());
        table.getHands().remove(next.getUniqueId());
        game.onLeave(table, next);
        finishAnimations();
        verify(manager, times(1)).revealTablePile(table, "dealer");
        assertEquals(BlackjackGame.SETTLE, table.phase());
    }

    @Test
    void removingTheTableDuringAHitStopsTheRound() {
        start(5, 10, 6, 7, 2);
        game.onBetHit(table, player);
        available = false;
        finishAnimations();
        assertEquals(BlackjackGame.DEAL, table.phase());
        verify(manager, never()).revealTablePile(table, "dealer");
    }

    @Test
    void removingTheTableWhileTheDealerDrawsStopsTheDraw() {
        start(10, 5, 9, 6, 2, 10);
        game.onBetStand(table, player);
        assertEquals(3, table.tablePile("dealer").size());
        available = false;
        finishAnimations();
        assertEquals(3, table.tablePile("dealer").size());
        verify(wagers, never()).announceWins(any(), anyString());
    }

    @Test
    void jokersCountForNothingAndNeverMakeATenValuePair() {
        Card joker = new Card("joker", "", 12, true, "item");
        List<HandCard> cards = List.of(card(10, true), new HandCard(joker, UUID.randomUUID(), true), card(1, true));
        assertEquals(21, BlackjackGame.bestTotal(cards));
        assertEquals(11, BlackjackGame.hardTotal(cards));
        table.getHands().clear();
        start(10, 10, 7, 7);
        table.handOf(player.getUniqueId()).set(1, new HandCard(joker, UUID.randomUUID(), true));
        game.onBetSplit(table, player);
        assertMessage(player, "bet.no_split");
        assertEquals(20, bets.get(player.getUniqueId()));
    }

    @Test
    void boxesSharingALateralPositionPlayFrontToBack() {
        PlayerMock behind = server.addPlayer();
        bets.put(behind.getUniqueId(), 10);
        positions.put(behind.getUniqueId(), positions.get(player.getUniqueId()).clone().add(0, 0, 1));
        table.actives().add(behind.getUniqueId());
        start(5, 4, 10, 6, 5, 7);
        List<UUID> expected = new ArrayList<>(List.of(player.getUniqueId(), behind.getUniqueId()));
        expected.sort(java.util.Comparator.comparingDouble(
                id -> TableLayout.localForward(table, positions.get(id))));
        assertEquals(expected, table.boxes());
        assertEquals(expected.getFirst(), table.actor());
    }

    @Test
    void departedPlayersHandSettlesQuietlyWhileTheOtherBoxIsPaid() {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        start(5, 10, 10, 6, 9, 7);
        // Quitting mucks the hand and refunds the box before the game hears of the departure.
        table.getHands().remove(player.getUniqueId());
        player.disconnect();
        game.onLeave(table, player);
        assertEquals(next.getUniqueId(), table.actor());
        game.onBetStand(table, next);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
        verify(wagers).payWin(eq(table), eq(next), eq(next.getUniqueId()), eq(10), eq(dealer),
                eq(true), any(ItemStack.class), anyList());
        verify(wagers, never()).payWin(any(), any(), eq(player.getUniqueId()), anyInt(), any(), anyBoolean(), any(), anyList());
        assertMessage(next, "bet.win");
    }

    @Test
    void lastBoxLeavingDuringTheFirstDealEndsTheRoundAndDealsNoMore() {
        queue(5, 10, 6, 7);
        table.startSession();
        game.onSessionStart(table);
        game.onShoeClick(table, dealer);
        assertEquals(1, dealOrder.size());
        table.getHands().remove(player.getUniqueId());
        game.onLeave(table, player);
        assertFalse(table.live());
        finishAnimations();
        assertEquals(1, dealOrder.size());
        assertTrue(table.tablePile("dealer").isEmpty());
        verify(manager, times(1)).endSession(table);
    }

    @Test
    void boxLeavingBeforeTheDealStillWaitsForTheDealersClick() {
        PlayerMock next = server.addPlayer();
        seat(next, 10, 1);
        queue(5, 10, 6, 7);
        table.startSession();
        game.onSessionStart(table);
        game.onLeave(table, next);
        assertTrue(table.live());
        assertEquals(BlackjackGame.WAIT_DEAL, table.phase());
        assertTrue(dealOrder.isEmpty());
        assertEquals(List.of(player.getUniqueId()), table.boxes());
        game.onShoeClick(table, dealer);
        finishAnimations();
        assertEquals(player.getUniqueId(), table.actor());
    }

    @Test
    void handThatHasTakenACardCannotBeSplit() {
        start(4, 10, 4, 7, 2);
        game.onBetHit(table, player);
        finishAnimations();
        game.onBetSplit(table, player);
        assertMessage(player, "bet.no_split");
        assertEquals(List.of(4, 4, 2), ranks(0));
        assertEquals(20, bets.get(player.getUniqueId()));
    }

    @Test
    void reloadDisablingResplitRefusesASecondAceSplitAlreadyOffered() {
        configure(false, 4, true, 0);
        start(1, 10, 1, 7);
        queue(1, 5);
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(player.getUniqueId(), table.actor(), "the new ace pair is offered for splitting");
        configure(false, 4, false, 0);
        game.onBetSplit(table, player);
        assertMessage(player, "bet.no_split");
        assertEquals(40, bets.get(player.getUniqueId()));
        assertEquals(List.of(1, 1), ranks(0));
    }

    @Test
    void auditedRoundThatBalancesLogsNothing() {
        Cache.wagerAuditLog = true;
        when(wagers.owned(eq(table), any(UUID.class)))
                .thenAnswer(call -> bets.getOrDefault(call.getArgument(1), 0));
        try (MockedStatic<MoneyLog> log = mockStatic(MoneyLog.class)) {
            start(10, 10, 9, 7);
            game.onBetStand(table, player);
            finishAnimations();
            assertEquals(BlackjackGame.SETTLE, table.phase());
            log.verify(() -> MoneyLog.mismatch(any(), anyString()), never());
        }
    }

    @Test
    void onlyBoxLeavingOnItsTurnEndsTheRoundWithoutADealerHand() {
        start(5, 10, 6, 7);
        assertEquals(player.getUniqueId(), table.actor());
        table.getHands().remove(player.getUniqueId());
        game.onLeave(table, player);
        assertFalse(table.live());
        verify(manager, never()).revealTablePile(table, "dealer");
        verify(wagers, never()).announceWins(any(), anyString());
        verify(manager, times(1)).endSession(table);
    }

    @Test
    void dealersOwnLootOnAnEmptyGuildTableStaysWhereTheyPutIt() {
        houseBacked();
        ItemStack loot = new ItemStack(Material.DIAMOND);
        when(manager.chipUnitDenars(loot)).thenReturn(0);
        when(manager.chipUnitDenars(isNull())).thenReturn(0);
        when(manager.feltItem(table, table.getId())).thenReturn(null);
        bets.clear();
        bets.put(dealer.getUniqueId(), 15);
        // The dealer's own chips are never a box.
        when(manager.boxOwners(table)).thenReturn(new ArrayList<>());
        game.onChipIn(table, dealer, 15, loot);
        verify(wagers, never()).fundFromHouse(any(), any(), any(), anyInt(), any(), anyString());
        verify(wagers, never()).refund(any(), any(), any(), anyInt(), anyList(), anyString());
        assertEquals(15, bets.get(dealer.getUniqueId()));
    }

    @Test
    void doubledHandTakesNoMoreCardsWhileItsResultIsShowing() {
        configure(false, 4, false, 5);
        start(5, 10, 6, 7, 2, 9);
        game.onBetDouble(table, player);
        finishAnimations();
        assertEquals(List.of(5, 6, 2), ranks(0));
        game.onBetHit(table, player);
        game.onShoeClick(table, player);
        finishAnimations();
        assertEquals(List.of(5, 6, 2), ranks(0), "a double takes exactly one card");
        server.getScheduler().performTicks(20);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
    }

    @Test
    void aHandThatHitsTwentyOneTakesNoMoreCardsWhileItsResultIsShowing() {
        configure(false, 4, false, 5);
        start(5, 10, 6, 7, 10, 3);
        game.onBetHit(table, player);
        finishAnimations();
        assertEquals(List.of(5, 6, 10), ranks(0));
        game.onBetHit(table, player);
        finishAnimations();
        assertEquals(List.of(5, 6, 10), ranks(0), "a quick second hit cannot turn twenty-one into a bust");
        server.getScheduler().performTicks(20);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
    }

    @Test
    void doubleWhoseCardFailedToSpawnCannotBeDoubledAgain() {
        configure(false, 4, false, 5);
        start(5, 10, 6, 7);
        failedSpawnFor = player.getUniqueId();
        game.onBetDouble(table, player);
        finishAnimations();
        assertEquals(40, bets.get(player.getUniqueId()));
        assertEquals(List.of(5, 6), ranks(0));
        game.onBetDouble(table, player);
        assertMessage(player, "bet.no_double");
        assertEquals(40, bets.get(player.getUniqueId()), "a hand is doubled at most once");
    }

    @Test
    void splitAcesWhoseCardsFailedToSpawnCanOnlyStand() {
        start(1, 10, 1, 7);
        failedSpawnFor = player.getUniqueId();
        game.onBetSplit(table, player);
        finishAnimations();
        assertEquals(List.of(1), ranks(0));
        assertEquals(List.of(1), ranks(1));
        assertEquals(player.getUniqueId(), table.actor());
        game.onBetHit(table, player);
        game.onBetDouble(table, player);
        finishAnimations();
        assertEquals(List.of(1), ranks(0), "a split ace is never hit");
        assertEquals(40, bets.get(player.getUniqueId()));
        game.onBetStand(table, player);
        assertEquals(player.getUniqueId(), table.actor(), "the second ace is played next");
        game.onBetStand(table, player);
        finishAnimations();
        assertEquals(BlackjackGame.SETTLE, table.phase());
    }

    private void configure(boolean hitSoft, int maxHands, boolean resplitAces, int resultDelay) {
        Cache.tableLayouts.put("blackjack", new TableLayout("cards", "Blackjack", "icon", 6,
                Map.of("tray", new TableLayout.PileSlot(0, 1)), null, null, new TableLayout.PileSlot(0, 0),
                0.5, hitSoft, false, 5, 0, 2, TableLayout.VoiceLines.defaults(), null, resultDelay, 2,
                null, 0, 0, 0, maxHands, resplitAces));
    }

    private void seat(PlayerMock owner, int bet, double right) {
        bets.put(owner.getUniqueId(), bet);
        positions.put(owner.getUniqueId(), table.getOrigin().clone().add(right, 0, 1));
        table.actives().add(owner.getUniqueId());
    }

    private void queue(int... ranks) {
        for (int rank : ranks) {
            shoe.add(rank);
        }
    }

    private void start(int... ranks) {
        queue(ranks);
        table.startSession();
        game.onSessionStart(table);
        if (!BlackjackGame.auto(table)) {
            game.onShoeClick(table, dealer);
        }
        finishAnimations();
    }

    private void finishAnimations() {
        int remaining = 100;
        while (!animations.isEmpty()) {
            assertTrue(remaining-- > 0, "Round must not schedule unbounded animation callbacks");
            animations.removeFirst().run();
        }
    }

    private HandCard nextCard(boolean faceUp) {
        assertFalse(shoe.isEmpty(), "Scenario must explicitly supply every dealt card");
        return card(shoe.removeFirst(), faceUp);
    }

    private static HandCard card(int rank, boolean faceUp) {
        return new HandCard(new Card(UUID.randomUUID().toString(), "cerrith", rank, false, "item"),
                UUID.randomUUID(), faceUp);
    }

    private List<Integer> ranks(int slot) {
        return table.handOf(player.getUniqueId()).stream().filter(card -> card.slot() == slot)
                .map(card -> card.card().getRank()).toList();
    }

    private static TxResult result(boolean success, int moved) {
        TxResult result = mock(TxResult.class);
        when(result.ok()).thenReturn(success);
        when(result.moved()).thenReturn(moved);
        return result;
    }

    private void houseBacked() {
        table.setOwnerGuildId("test-guild");
        guilds.when(() -> GuildTables.houseBacked(table)).thenReturn(true);
    }

    private TextDisplay label() {
        TextDisplay label = mock(TextDisplay.class);
        when(label.getUniqueId()).thenReturn(UUID.randomUUID());
        anchors.when(() -> WorldAnchors.spawnLabel(any(Location.class), anyString())).thenReturn(label);
        return label;
    }

    private void verifyWin(int profit) {
        verify(wagers).payWin(eq(table), eq(player), eq(player.getUniqueId()), eq(profit), eq(dealer),
                eq(true), any(ItemStack.class), anyList());
    }

    private static void assertMessage(PlayerMock player, String key, String... arguments) {
        String expected = Messages.get(key, arguments);
        List<String> messages = new ArrayList<>();
        String message;
        while ((message = player.nextMessage()) != null) {
            messages.add(message);
        }
        assertTrue(messages.contains(expected), "Expected " + expected + " among " + messages);
    }
}
