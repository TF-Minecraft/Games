package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.events.PlayerWonMoneyEvent;
import net.tfminecraft.games.table.Table;

class WagerEngineTest {
    private WagerItemOverride oldGold;
    private WagerItemOverride oldSilver;
    private List<WagerItemOverride> oldItems;
    private WagerHost host;
    private WagerEngine engine;
    private WagerEngine previousEngine;
    private Table table;
    private PlayerMock winner;
    private PlayerMock opponent;

    @BeforeEach
    void setUp() {
        previousEngine = WagerEngine.get();
        oldGold = Cache.wagerGold;
        oldSilver = Cache.wagerSilver;
        oldItems = new ArrayList<>(Cache.wagerItems);
        Cache.wagerGold = new WagerItemOverride("GOLD_NUGGET", 1, null, null, null, null,
                null, null, false, null);
        Cache.wagerSilver = null;
        Cache.wagerItems.clear();
        host = mock(WagerHost.class);
        WagerEngine.init(host);
        engine = WagerEngine.get();
        winner = MockBukkit.getMock().addPlayer();
        opponent = MockBukkit.getMock().addPlayer();
        table = new Table(UUID.randomUUID(), "poker", winner.getLocation(), 0, null);
    }

    @AfterEach
    void restoreConfig() throws Exception {
        var instance = WagerEngine.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, previousEngine);
        Cache.wagerGold = oldGold;
        Cache.wagerSilver = oldSilver;
        Cache.wagerItems.clear();
        Cache.wagerItems.addAll(oldItems);
    }

    private ItemStack coin(int count) {
        return new ItemStack(Material.GOLD_NUGGET, count);
    }

    private void restore(UUID owner, int count, int street) {
        engine.restore(table, owner, coin(1), "gold", 1, count, street, null, null);
    }

    private int inventoryCoins(PlayerMock player) {
        return Accounts.coins(table, player).available();
    }

    private void bet(PlayerMock player, int count) {
        player.getInventory().addItem(coin(count));
        TxResult result = engine.begin(table, "bet")
                .move(Accounts.coins(table, player), Accounts.bucket(table, player.getUniqueId()), count)
                .commit();
        assertEquals(count, result.moved());
    }

    @Test
    void restorePreservesStreetPlacementAndSeparatesTrayFromPot() {
        UUID owner = winner.getUniqueId();
        table.setStreet(3);
        ItemStack saved = coin(12);
        engine.restore(table, owner, saved, null, 1, 4, 0, 2.0, 3.0, 4.0, 5.0);
        restore(table.getId(), 6, 1);
        assertEquals(10, engine.total(table));
        assertEquals(4, engine.felt(table));
        assertEquals(6, engine.tray(table));
        assertEquals(4, engine.owned(table, owner));
        assertEquals(4, engine.owned(table, owner, 3));
        assertEquals(0, engine.owned(table, owner, 1));
        assertEquals(Map.of(owner, 4), engine.totalsExcept(table, table.getId()));
        assertEquals(List.of(owner, table.getId()), engine.owners(table));
        assertEquals(List.of(owner), engine.potOwners(table));
        assertEquals(4, engine.potAccounts(table).getFirst().available());
        assertEquals(4, engine.coinPot(table));
        Stake loaded = table.ledger().stakes(owner).getFirst();
        assertEquals("gold", loaded.typeKey());
        assertTrue(loaded.placed());
        assertEquals(4, loaded.x());
        assertEquals(5, loaded.z());
        assertEquals(2, table.ledger().anchorX(owner));
        assertEquals(3, table.ledger().anchorZ(owner));
        assertNotSame(saved, loaded.item());
        assertEquals(1, loaded.item().getAmount());
        assertEquals(12, saved.getAmount());
        verifyNoInteractions(host);
    }

    @Test
    void streetRefundLeavesEarlierBetInPotThenFullRefundConservesAllCoins() {
        UUID owner = winner.getUniqueId();
        restore(owner, 4, 1);
        restore(owner, 3, 2);
        assertEquals(3, engine.refundStreet(table, owner, 2, null, "cancel street").moved());
        assertEquals(4, engine.owned(table, owner));
        assertEquals(3, inventoryCoins(winner));
        assertEquals(2, engine.refund(table, owner, winner, 2, null, "partial refund").moved());
        assertEquals(2, engine.owned(table, owner));
        assertEquals(5, inventoryCoins(winner));
        assertEquals(2, engine.refund(table, owner, winner, 0, null, "cancel bet").moved());
        assertEquals(0, engine.total(table));
        assertEquals(7, inventoryCoins(winner));
        engine.forget(table, owner);
        assertFalse(table.ledger().hasAnchor(owner));
        verify(host, times(3)).moneyMoved(eq(table), anyCollection());
    }

    @Test
    void cancellationReturnsEveryPlayersOwnStakeWithoutSpendingHouseTray() {
        bet(winner, 4);
        bet(opponent, 6);
        restore(table.getId(), 8, 1);
        assertEquals(10, engine.returnStakes(table, null, "cancel round").moved());
        assertEquals(4, inventoryCoins(winner));
        assertEquals(6, inventoryCoins(opponent));
        assertEquals(8, engine.tray(table));
        assertEquals(0, engine.felt(table));
        assertEquals(0, table.roundMoney().moneyProfit(winner.getUniqueId()));
        assertEquals(0, table.roundMoney().moneyProfit(opponent.getUniqueId()));
    }

    @Test
    void sweptPotReturnsOriginalStakeTaxesOnlyCoinProfitAndPreservesLoot() {
        bet(winner, 2);
        bet(opponent, 6);
        engine.restore(table, opponent.getUniqueId(), new ItemStack(Material.DIAMOND), "loot", 10,
                1, 1, null, null);
        restore(table.getId(), 5, 1);
        try (MockedStatic<CitizenTax> tax = mockStatic(CitizenTax.class)) {
            tax.when(() -> CitizenTax.levy(winner, 6)).thenReturn(new CitizenTax.Levy(2, 2));
            TxResult result = engine.sweepPot(table, winner, winner.getUniqueId(), null, "winner");
            assertEquals(18, result.moved());
            assertEquals(6, inventoryCoins(winner));
            assertEquals(1, winner.getInventory().all(Material.DIAMOND).values().stream()
                    .mapToInt(ItemStack::getAmount).sum());
            assertEquals(5, engine.tray(table));
            assertEquals(0, engine.felt(table));
            assertEquals(6, table.roundMoney().wonProfit().get(winner.getUniqueId()));
            assertEquals(2, table.roundMoney().moneyIn(winner.getUniqueId()));
            assertEquals(6, table.roundMoney().moneyOut(winner.getUniqueId()));
            tax.verify(() -> CitizenTax.levy(winner, 6));
            tax.verify(() -> CitizenTax.tell(winner, 2));
        }
    }

    @Test
    void stagedPotPayoutDoesNotTaxTheReturnedStakeTwice() {
        bet(winner, 4);
        bet(opponent, 6);
        try (MockedStatic<CitizenTax> tax = mockStatic(CitizenTax.class)) {
            tax.when(() -> CitizenTax.levy(winner, 0)).thenReturn(CitizenTax.Levy.NONE);
            tax.when(() -> CitizenTax.levy(winner, 3)).thenReturn(new CitizenTax.Levy(1, 1));
            assertEquals(3, engine.payFromPot(table, winner, winner.getUniqueId(), 3,
                    null, "first share").moved());
            assertEquals(4, engine.payFromPot(table, winner, winner.getUniqueId(), 4,
                    null, "second share").moved());
            assertEquals(6, inventoryCoins(winner));
            assertEquals(3, engine.felt(table));
            tax.verify(() -> CitizenTax.levy(winner, 0));
            tax.verify(() -> CitizenTax.levy(winner, 3));
        }
    }

    @Test
    void losingBetsGoToTrayAndStaffFundingAndPeelingUseExactCoinValues() {
        table.setStaffMint(true);
        UUID owner = winner.getUniqueId();
        Location anchor = new Location(null, 2, 0, 3);
        assertEquals(7, engine.fundFromHouse(table, owner, coin(1), 7, anchor, "float").moved());
        assertEquals(2, table.ledger().anchorX(owner));
        assertEquals(3, engine.toTray(table, owner, 3, null, "loss").moved());
        assertEquals(4, engine.owned(table, owner));
        assertEquals(3, engine.tray(table));
        assertEquals(4, engine.toTray(table, owner, 0, null, "remaining loss").moved());
        assertEquals(7, engine.tray(table));
        assertEquals(5, engine.peelToHouse(table, 5, "reduce float").moved());
        assertEquals(2, engine.total(table));
    }

    @Test
    void dealerBackedWinUsesTrayFirstThenDealerAndReportsRealShortfall() {
        restore(table.getId(), 3, 1);
        opponent.getInventory().addItem(coin(2));
        PayWinResult result = engine.payWin(table, winner, winner.getUniqueId(), 7, opponent,
                true, coin(1), null);
        assertEquals(new PayWinResult(5, 3, 2), result);
        assertEquals(5, inventoryCoins(winner));
        assertEquals(0, inventoryCoins(opponent));
        assertEquals(0, engine.tray(table));
    }

    @Test
    void houseBackedWinTaxesProfitAndUsesMintForTrayShortfall() {
        table.setStaffMint(true);
        restore(table.getId(), 3, 1);
        try (MockedStatic<CitizenTax> tax = mockStatic(CitizenTax.class)) {
            tax.when(() -> CitizenTax.levy(winner, 8)).thenReturn(new CitizenTax.Levy(2, 2));
            PayWinResult result = engine.payWin(table, winner, winner.getUniqueId(), 8, null,
                    false, coin(1), null);
            assertEquals(new PayWinResult(8, 3, 0), result);
            assertEquals(6, inventoryCoins(winner));
            assertEquals(0, engine.tray(table));
            assertEquals(8, table.roundMoney().wonProfit().get(winner.getUniqueId()));
            tax.verify(() -> CitizenTax.tell(winner, 2));
        }
    }

    @Test
    void multiPlayerBetPreflightLeavesEveryInventoryUntouchedWhenLaterBetIsUnaffordable() {
        winner.getInventory().addItem(coin(4));
        opponent.getInventory().addItem(coin(1));
        TxResult result = engine.begin(table, "paired bets")
                .move(Accounts.coins(table, winner), Accounts.bucket(table, winner.getUniqueId()), 4)
                .move(Accounts.coins(table, opponent), Accounts.bucket(table, opponent.getUniqueId()), 2)
                .commit();
        assertFalse(result.ok());
        assertEquals(4, inventoryCoins(winner));
        assertEquals(1, inventoryCoins(opponent));
        assertEquals(0, engine.total(table));
        assertEquals(0, table.roundMoney().moneyIn(winner.getUniqueId()));
        verifyNoInteractions(host);
    }

    @Test
    void sweepingCoinOnlyPotEmptiesItAndRepeatingTheSweepCannotPayTwice() {
        bet(winner, 2);
        bet(opponent, 3);
        assertEquals(5, engine.sweepPot(table, winner, winner.getUniqueId(), null, "winner").moved());
        assertEquals(5, inventoryCoins(winner));
        assertEquals(0, engine.felt(table));
        assertEquals(0, engine.sweepPot(table, winner, winner.getUniqueId(), null, "retry").moved());
        assertEquals(5, inventoryCoins(winner));
    }

    @Test
    void offlineRefundDropsExactStakeAtTableWithoutTakingHouseCoins() {
        bet(winner, 4);
        restore(table.getId(), 3, 1);
        var world = table.getOrigin().getWorld();
        List<UUID> before = world.getEntitiesByClass(Item.class).stream().map(Item::getUniqueId).toList();
        winner.disconnect();
        assertEquals(4, engine.refundStreet(table, winner.getUniqueId(), 1, null, "offline refund").moved());
        assertEquals(3, engine.tray(table));
        assertEquals(0, engine.owned(table, winner.getUniqueId()));
        assertEquals(4, world.getEntitiesByClass(Item.class).stream()
                .filter(item -> !before.contains(item.getUniqueId()))
                .filter(item -> item.getItemStack().getType() == Material.GOLD_NUGGET)
                .mapToInt(item -> item.getItemStack().getAmount()).sum());
    }

    @Test
    void absentOrOfflineDealerNeverFallsBackToStaffMintForPersonalDealerDebt() {
        table.setStaffMint(true);
        restore(table.getId(), 3, 1);
        assertEquals(new PayWinResult(3, 3, 2), engine.payWin(table, winner, winner.getUniqueId(),
                5, null, true, coin(1), null));
        opponent.getInventory().addItem(coin(5));
        opponent.disconnect();
        assertEquals(new PayWinResult(0, 0, 5), engine.payWin(table, winner, winner.getUniqueId(),
                5, opponent, true, coin(1), null));
        assertEquals(3, inventoryCoins(winner));
        assertEquals(5, opponent.getInventory().all(Material.GOLD_NUGGET).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
    }

    @Test
    void fullyWithheldPayoutReportsTaxOnlyWhenCoinsActuallyLeaveThePotOrTray() {
        try (MockedStatic<CitizenTax> tax = mockStatic(CitizenTax.class)) {
            tax.when(() -> CitizenTax.levy(winner, 2)).thenReturn(new CitizenTax.Levy(2, 2));
            assertEquals(0, engine.payFromPot(table, winner, winner.getUniqueId(), 2,
                    null, "empty pot").moved());
            tax.verify(() -> CitizenTax.tell(winner, 2), never());
            restore(opponent.getUniqueId(), 2, 1);
            assertEquals(2, engine.payFromPot(table, winner, winner.getUniqueId(), 2,
                    null, "withheld share").moved());
            assertEquals(0, inventoryCoins(winner));
            assertEquals(0, engine.felt(table));
            tax.verify(() -> CitizenTax.tell(winner, 2));
            assertEquals(new PayWinResult(0, 0, 2), engine.payWin(table, winner, winner.getUniqueId(),
                    2, null, true, coin(1), null));
            tax.verify(() -> CitizenTax.tell(winner, 2));
            restore(table.getId(), 2, 1);
            assertEquals(new PayWinResult(2, 2, 0), engine.payWin(table, winner, winner.getUniqueId(),
                    2, null, true, coin(1), null));
            tax.verify(() -> CitizenTax.tell(winner, 2), times(2));
            assertEquals(0, engine.total(table));
            assertEquals(0, inventoryCoins(winner));
        }
    }

    @Test
    void settlementAnnouncesOnlineWinnersOnceWithPretaxProfitAndSkipsOfflineWinners() {
        table.setStaffMint(true);
        assertEquals(5, engine.payWin(table, winner, winner.getUniqueId(), 5, null,
                false, coin(1), null).moved());
        assertEquals(3, engine.payWin(table, opponent, opponent.getUniqueId(), 3, null,
                false, coin(1), null).moved());
        opponent.disconnect();
        var plugins = MockBukkit.getMock().getPluginManager();
        plugins.clearEvents();
        engine.announceWins(table, "poker");
        List<PlayerWonMoneyEvent> events = plugins.getFiredEvents()
                .filter(PlayerWonMoneyEvent.class::isInstance)
                .map(PlayerWonMoneyEvent.class::cast).toList();
        assertEquals(1, events.size());
        assertSame(winner, events.getFirst().getPlayer());
        assertEquals(5, events.getFirst().getProfit());
        assertEquals("poker", events.getFirst().getGame());
    }

    @Test
    void emptyPotCannotRecordOrAnnounceUnpaidWinnings() {
        assertEquals(0, engine.payFromPot(table, winner, winner.getUniqueId(), 5,
                null, "empty settlement").moved());
        assertTrue(table.roundMoney().wonProfit().isEmpty());
        var plugins = MockBukkit.getMock().getPluginManager();
        plugins.clearEvents();
        engine.announceWins(table, "poker");
        assertTrue(plugins.getFiredEvents().noneMatch(PlayerWonMoneyEvent.class::isInstance));
    }

    @Test
    void dealerShortfallRecordsOnlyProfitActuallyPaid() {
        restore(table.getId(), 3, 1);
        assertEquals(new PayWinResult(3, 3, 2), engine.payWin(table, winner, winner.getUniqueId(),
                5, null, true, coin(1), null));
        assertEquals(3, inventoryCoins(winner));
        assertEquals(3, table.roundMoney().wonProfit().get(winner.getUniqueId()));
        assertEquals(new PayWinResult(0, 0, 2), engine.payWin(table, winner, winner.getUniqueId(),
                2, null, true, coin(1), null));
        assertEquals(3, table.roundMoney().wonProfit().get(winner.getUniqueId()));
    }

    @Test
    void shortPotReturnsPrincipalBeforeRecordingAnyProfit() {
        bet(winner, 4);
        bet(opponent, 2);
        assertEquals(6, engine.payFromPot(table, winner, winner.getUniqueId(), 10,
                null, "short pot").moved());
        assertEquals(6, inventoryCoins(winner));
        assertEquals(0, engine.felt(table));
        assertEquals(2, table.roundMoney().wonProfit().get(winner.getUniqueId()));
    }

    @Test
    void potContainingOnlyReturnedPrincipalHasNoWinningsToAnnounce() {
        bet(winner, 4);
        assertEquals(4, engine.payFromPot(table, winner, winner.getUniqueId(), 10,
                null, "principal only").moved());
        assertEquals(4, inventoryCoins(winner));
        assertEquals(0, engine.felt(table));
        assertTrue(table.roundMoney().wonProfit().isEmpty());
    }

    @Test
    void partlyWithheldDealerPayoutRecordsTransferredProfitAndRemainingDebt() {
        restore(table.getId(), 8, 1);
        try (MockedStatic<CitizenTax> tax = mockStatic(CitizenTax.class)) {
            tax.when(() -> CitizenTax.levy(winner, 10)).thenReturn(new CitizenTax.Levy(3, 3));
            PayWinResult result = engine.payWin(table, winner, winner.getUniqueId(), 10,
                    null, true, coin(1), null);
            assertEquals(new PayWinResult(8, 8, 2), result);
            assertEquals(7, inventoryCoins(winner));
            assertEquals(0, engine.tray(table));
            assertEquals(8, table.roundMoney().wonProfit().get(winner.getUniqueId()));
            // The eighth coin was withheld; the two unfunded coins remain owed.
            assertEquals(1, result.moved() - inventoryCoins(winner));
        }
    }

    @Test
    void partlyWithheldPotPayoutExcludesPrincipalAndUnfundedProfit() {
        bet(winner, 4);
        bet(opponent, 5);
        try (MockedStatic<CitizenTax> tax = mockStatic(CitizenTax.class)) {
            tax.when(() -> CitizenTax.levy(winner, 6)).thenReturn(new CitizenTax.Levy(2, 2));
            TxResult result = engine.payFromPot(table, winner, winner.getUniqueId(), 10,
                    null, "partial taxed pot");
            assertEquals(9, result.moved());
            assertEquals(8, inventoryCoins(winner));
            assertEquals(0, inventoryCoins(opponent));
            assertEquals(0, engine.felt(table));
            assertEquals(5, table.roundMoney().wonProfit().get(winner.getUniqueId()));
            assertEquals(1, result.moved() - inventoryCoins(winner));
        }
    }

    @Test
    void zeroPayoutsDoNotTouchExistingMoneyOrInventAWinner() {
        restore(winner.getUniqueId(), 4, 1);
        assertEquals(0, engine.payFromPot(table, winner, winner.getUniqueId(), 0, null, "zero share").moved());
        assertEquals(PayWinResult.NONE, engine.payWin(table, winner, winner.getUniqueId(), 0,
                null, false, coin(1), null));
        assertEquals(4, engine.total(table));
        assertEquals(0, inventoryCoins(winner));
        assertTrue(table.roundMoney().wonProfit().isEmpty());
        verifyNoInteractions(host);
    }

    @Test
    void potWithNoWinnerIsDroppedAtTheTableWithoutRecordingProfit() {
        bet(winner, 2);
        bet(opponent, 3);
        var world = table.getOrigin().getWorld();
        List<UUID> before = world.getEntitiesByClass(Item.class).stream().map(Item::getUniqueId).toList();
        assertEquals(5, engine.sweepPot(table, null, null, null, "nobody left").moved());
        assertEquals(0, engine.felt(table));
        assertEquals(0, inventoryCoins(winner));
        assertEquals(0, inventoryCoins(opponent));
        assertEquals(5, world.getEntitiesByClass(Item.class).stream()
                .filter(item -> !before.contains(item.getUniqueId()))
                .mapToInt(item -> item.getItemStack().getAmount()).sum());
        assertTrue(table.roundMoney().wonProfit().isEmpty());
    }

    @Test
    void savedStakeWithHalfAnAnchorKeepsItsMoneyButNoAnchor() {
        UUID owner = winner.getUniqueId();
        engine.restore(table, owner, coin(1), "gold", 1, 3, 1, 2.0, null);
        assertEquals(3, engine.owned(table, owner));
        assertFalse(table.ledger().hasAnchor(owner));
    }
}
