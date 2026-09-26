package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.games.cache.Cache;

class RoundMoneyTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private RoundMoney round;
    private WagerItemOverride previousGold;

    @BeforeEach
    void setUp() {
        previousGold = Cache.wagerGold;
        Cache.wagerGold = new WagerItemOverride("GOLD_NUGGET", 1, null, null, null, null, null, null, false, null);
        round = new RoundMoney();
    }

    @AfterEach
    void restoreConfig() {
        Cache.wagerGold = previousGold;
    }

    @Test
    void playerToFeltCountsAsMoneyIn() {
        round.recordLeg(testPlayer(PLAYER), felt(), coins(20));
        assertEquals(20, round.moneyIn(PLAYER));
        assertEquals(0, round.moneyOut(PLAYER));
        assertEquals(0, round.moneyProfit(PLAYER));
    }

    @Test
    void feltToPlayerCountsAsMoneyOutAndProfit() {
        round.recordLeg(felt(), testPlayer(PLAYER), coins(40));
        assertEquals(0, round.moneyIn(PLAYER));
        assertEquals(40, round.moneyOut(PLAYER));
        assertEquals(40, round.moneyProfit(PLAYER));
    }

    @Test
    void stakeAndRefundNetToZeroProfit() {
        round.recordLeg(testPlayer(PLAYER), felt(), coins(20));
        round.recordLeg(felt(), testPlayer(PLAYER), coins(20));
        assertEquals(20, round.moneyIn(PLAYER));
        assertEquals(20, round.moneyOut(PLAYER));
        assertEquals(0, round.moneyProfit(PLAYER));
    }

    @Test
    void profitIsOutMinusIn() {
        round.recordLeg(testPlayer(PLAYER), felt(), coins(20));
        round.recordLeg(felt(), testPlayer(PLAYER), coins(40));
        assertEquals(20, round.moneyProfit(PLAYER));
    }

    @Test
    void ignoresLootStakes() {
        Stake coin = new Stake(new ItemStack(Material.GOLD_NUGGET), "gold", 10, 2, 0);
        Stake loot = new Stake(new ItemStack(Material.DIAMOND), "item:DIAMOND", 100, 1, 0);
        round.recordLeg(testPlayer(PLAYER), felt(), List.of(coin, loot));
        assertEquals(20, round.moneyIn(PLAYER));
    }

    @Test
    void trayToPlayerIsMoneyOut() {
        round.recordLeg(felt(), testPlayer(PLAYER), coins(15));
        assertEquals(15, round.moneyOut(PLAYER));
    }

    @Test
    void playerToNonFeltDoesNotCountAsMoneyIn() {
        round.recordLeg(testPlayer(PLAYER), notFelt(), coins(10));
        assertEquals(0, round.moneyIn(PLAYER));
    }

    @Test
    void dealerCoveringWinnerCountsAsWinnerMoneyOut() {
        round.recordLeg(testPlayer(OTHER), testPlayer(PLAYER), coins(25));
        assertEquals(0, round.moneyIn(PLAYER));
        assertEquals(25, round.moneyOut(PLAYER));
        assertEquals(0, round.moneyIn(OTHER));
    }

    @Test
    void clearResetsMaps() {
        round.recordLeg(testPlayer(PLAYER), felt(), coins(20));
        round.recordLeg(felt(), testPlayer(PLAYER), coins(40));
        round.clear();
        assertEquals(0, round.moneyIn(PLAYER));
        assertEquals(0, round.moneyOut(PLAYER));
        assertEquals(0, round.moneyProfit(PLAYER));
    }

    @Test
    void taxableProfitReturnsStakeBeforeTaxingWinnings() {
        round.recordLeg(testPlayer(PLAYER), felt(), coins(40));
        assertEquals(60, round.taxableProfit(PLAYER, 100));
    }

    @Test
    void taxableProfitAfterStakeReturnedIsFullPayout() {
        round.recordLeg(testPlayer(PLAYER), felt(), coins(40));
        round.recordLeg(felt(), testPlayer(PLAYER), coins(100));
        assertEquals(20, round.taxableProfit(PLAYER, 20));
    }

    @Test
    void taxableProfitWhileStakeStillReturningIsZero() {
        round.recordLeg(testPlayer(PLAYER), felt(), coins(40));
        round.recordLeg(felt(), testPlayer(PLAYER), coins(10));
        assertEquals(0, round.taxableProfit(PLAYER, 30));
    }

    @Test
    void lootOnlyLegRecordsNoMoney() {
        round.recordLeg(testPlayer(PLAYER), felt(),
                List.of(new Stake(new ItemStack(Material.DIAMOND), "item:DIAMOND", 100, 1, 0)));
        assertEquals(0, round.moneyIn(PLAYER));
    }

    @Test
    void coinsDroppedAtTheTableForNobodyAreNoPlayersMoneyOut() {
        round.recordLeg(felt(), new PlayerAccount(null, null, null, null, 0), coins(30));
        assertEquals(0, round.moneyOut(PLAYER));
        assertEquals(0, round.moneyOut(OTHER));
    }

    @Test
    void wonProfitAccumulatesPositivePayoutsAndIsASnapshot() {
        round.recordProfit(PLAYER, 5);
        round.recordProfit(PLAYER, 0);
        round.recordProfit(PLAYER, 7);
        var snapshot = round.wonProfit();
        round.recordProfit(OTHER, 3);
        assertEquals(java.util.Map.of(PLAYER, 12), snapshot);
        assertEquals(java.util.Map.of(PLAYER, 12, OTHER, 3), round.wonProfit());
        round.clear();
        assertEquals(java.util.Map.of(), round.wonProfit());
    }

    private static List<Stake> coins(int denars) {
        int unit = denars;
        return List.of(new Stake(new ItemStack(Material.GOLD_NUGGET), "gold", unit, 1, 0));
    }

    private static PlayerAccount testPlayer(UUID id) {
        return new PlayerAccount(null, id, null, null, 0);
    }

    private static FakeAccount felt() {
        return new FakeAccount("felt", PLAYER, true);
    }

    private static FakeAccount notFelt() {
        return new FakeAccount("citizen tax", null, false);
    }

    private static final class FakeAccount implements MoneyAccount {

        private final String label;
        private final UUID id;
        private final boolean onFelt;

        private FakeAccount(String label, UUID id, boolean onFelt) {
            this.label = label;
            this.id = id;
            this.onFelt = onFelt;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public Withdrawal planTake(int denars, ItemStack template) {
            return null;
        }

        @Override
        public Withdrawal planTakeAll(Integer street) {
            return null;
        }

        @Override
        public int largestTakeUpTo(int denars, ItemStack template) {
            return 0;
        }

        @Override
        public int accept(List<Stake> stakes) {
            return 0;
        }

        @Override
        public boolean onFelt() {
            return onFelt;
        }

        @Override
        public UUID flightTarget() {
            return id;
        }
    }
}
