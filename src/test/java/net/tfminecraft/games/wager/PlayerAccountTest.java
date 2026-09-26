package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.table.Table;

class PlayerAccountTest {
    private WagerItemOverride oldGold;
    private WagerItemOverride oldSilver;
    private List<WagerItemOverride> oldItems;
    private PlayerMock player;
    private Table table;
    private PlayerAccount account;

    @BeforeEach
    void setUp() {
        oldGold = Cache.wagerGold;
        oldSilver = Cache.wagerSilver;
        oldItems = new ArrayList<>(Cache.wagerItems);
        Cache.wagerGold = new WagerItemOverride("GOLD_NUGGET", 1, null, null, null, null,
                null, null, false, null);
        Cache.wagerSilver = new WagerItemOverride("IRON_NUGGET", 5, null, null, null, null,
                null, null, false, null);
        Cache.wagerItems.clear();
        player = MockBukkit.getMock().addPlayer();
        table = new Table(UUID.randomUUID(), "poker", player.getLocation(), 0, null);
        table.setStreet(3);
        account = Accounts.coins(table, player);
    }

    @AfterEach
    void restoreConfig() {
        Cache.wagerGold = oldGold;
        Cache.wagerSilver = oldSilver;
        Cache.wagerItems.clear();
        Cache.wagerItems.addAll(oldItems);
    }

    private ItemStack small(int count) {
        return new ItemStack(Material.GOLD_NUGGET, count);
    }

    private ItemStack large(int count) {
        return new ItemStack(Material.IRON_NUGGET, count);
    }

    private int count(Material type) {
        return player.getInventory().all(type).values().stream().mapToInt(ItemStack::getAmount).sum();
    }


    /** Models the external economy's denomination lookup and break-coin response. */
    private MockedStatic<ChipItems> externalChangeCurrency() {
        Cache.wagerSilver = null;
        MockedStatic<ChipItems> chips = mockStatic(ChipItems.class, CALLS_REAL_METHODS);
        chips.when(() -> ChipItems.unitDenars(any(ItemStack.class))).thenAnswer(call -> {
            ItemStack item = call.getArgument(0);
            return item.getType() == Material.IRON_NUGGET ? 5
                    : item.getType() == Material.GOLD_NUGGET ? 1 : 0;
        });
        chips.when(() -> ChipItems.isMoneyCoin(any(ItemStack.class))).thenAnswer(call -> {
            Material type = ((ItemStack) call.getArgument(0)).getType();
            return type == Material.IRON_NUGGET || type == Material.GOLD_NUGGET;
        });
        chips.when(() -> ChipItems.typeKey(any(ItemStack.class))).thenAnswer(call ->
                ((ItemStack) call.getArgument(0)).getType() == Material.IRON_NUGGET
                        ? "coin:five" : "coin:one");
        chips.when(() -> ChipItems.change(any(ItemStack.class))).thenReturn(List.of(small(5)));
        return chips;
    }

    @Test
    void exactPlanGroupsMatchingCoinsAcrossSlotsAndLeavesUnrelatedItemsUntouched() {
        player.getInventory().setItem(0, large(2));
        player.getInventory().setItem(3, small(1));
        player.getInventory().setItem(5, small(2));
        player.getInventory().setItem(8, new ItemStack(Material.DIAMOND, 4));
        assertEquals(13, account.available());
        assertEquals(8, account.largestTakeUpTo(9, null));
        Withdrawal withdrawal = account.planTake(7, null);
        assertNotNull(withdrawal);
        assertEquals(7, withdrawal.denars());
        assertEquals(7, TableLedger.valueOf(withdrawal.preview()));
        assertEquals(13, account.available());
        assertEquals(7, TableLedger.valueOf(withdrawal.take()));
        assertEquals(6, account.available());
        assertEquals(1, count(Material.IRON_NUGGET));
        assertEquals(1, count(Material.GOLD_NUGGET));
        assertEquals(4, count(Material.DIAMOND));
        assertTrue(withdrawal.preview().stream().allMatch(stake -> stake.streetId() == 3));
        assertNull(account.planTakeAll(null));
    }

    @Test
    void impossibleExactBetAndOfflinePlayerNeverLoseInventory() {
        player.getInventory().setItem(0, large(1));
        assertNull(account.planTake(3, null));
        assertEquals(5, account.available());
        assertNull(account.planTake(0, null));
        player.disconnect();
        assertNull(account.planTake(5, null));
        assertEquals(0, account.largestTakeUpTo(5, null));
        assertEquals(0, account.available(), "a bettor who has left has nothing to offer the table");
        assertEquals(1, count(Material.IRON_NUGGET));
    }

    @Test
    void declaredLootCannotBeBrokenIntoCoinsAndPaysOnlyMatchingItems() {
        ItemStack sample = new ItemStack(Material.DIAMOND);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.getInventory().setItem(1, small(20));
        PlayerAccount loot = Accounts.declared(table, player, sample, 7);
        assertEquals(21, loot.available());
        assertNull(loot.planTake(5, null));
        assertEquals(14, TableLedger.valueOf(loot.planTake(14, null).take()));
        assertEquals(1, count(Material.DIAMOND));
        assertEquals(20, count(Material.GOLD_NUGGET));
    }

    @Test
    void changeIsOnlyAppliedWhenWithdrawalCommitsAndPreservesTotalValue() {
        player.getInventory().setItem(0, large(1));
        try (MockedStatic<ChipItems> chips = externalChangeCurrency()) {
            Withdrawal withdrawal = account.planTake(3, null);
            assertNotNull(withdrawal);
            assertEquals(1, count(Material.IRON_NUGGET));
            assertEquals(0, count(Material.GOLD_NUGGET));
            assertEquals(3, TableLedger.valueOf(withdrawal.preview()));
            assertEquals(3, TableLedger.valueOf(withdrawal.take()));
            assertEquals(0, count(Material.IRON_NUGGET));
            assertEquals(2, count(Material.GOLD_NUGGET));
            assertEquals(2, account.available());
        }
    }

    @Test
    void rejectedChangeLeavesOriginalCoinWhenInventoryHasNoRoomOrRuleExcludesChange() {
        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        player.getInventory().setItem(0, large(1));
        try (MockedStatic<ChipItems> chips = externalChangeCurrency()) {
            assertNull(account.planTake(3, null));
            assertEquals(1, count(Material.IRON_NUGGET));
            player.getInventory().clear(1);
            PlayerAccount onlyLarge = Accounts.pockets(table, player,
                    item -> item.getType() == Material.IRON_NUGGET);
            assertNull(onlyLarge.planTake(3, null));
            assertEquals(1, count(Material.IRON_NUGGET));
            assertEquals(0, count(Material.GOLD_NUGGET));
        }
    }

    @Test
    void changedInventoryCannotCreateCoinsWhenPlannedChangeIsCommitted() {
        player.getInventory().setItem(0, large(1));
        try (MockedStatic<ChipItems> chips = externalChangeCurrency()) {
            Withdrawal withdrawal = account.planTake(3, null);
            assertNotNull(withdrawal);
            player.getInventory().clear();
            assertTrue(withdrawal.take().isEmpty());
            assertEquals(0, account.available());
        }
    }

    @Test
    void payoutSplitsStacksAndDropsOverflowRatherThanDiscardingCoins() {
        // MockBukkit's player addItem scans 43 slots, including non-storage slots.
        // Delegate this external API to a real 36-slot mock inventory to model Bukkit storage.
        Inventory storage = Bukkit.createInventory(null, 36);
        for (int slot = 0; slot < storage.getSize(); slot++) {
            storage.setItem(slot, new ItemStack(Material.STONE, 64));
        }
        storage.clear(0);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call ->
                storage.addItem((ItemStack) call.getArgument(0)));
        Player recipient = mock(Player.class);
        when(recipient.isOnline()).thenReturn(true);
        when(recipient.getInventory()).thenReturn(inventory);
        when(recipient.getLocation()).thenReturn(player.getLocation());
        PlayerAccount payout = new PlayerAccount(recipient, player.getUniqueId(), null, null, 0);
        List<UUID> before = player.getWorld().getEntitiesByClass(Item.class).stream()
                .map(Item::getUniqueId).toList();
        assertEquals(70, payout.accept(List.of(new Stake(small(1), "gold", 1, 70, 0))));
        int stored = storage.all(Material.GOLD_NUGGET).values().stream()
                .mapToInt(ItemStack::getAmount).sum();
        assertEquals(64, stored);
        int dropped = player.getWorld().getEntitiesByClass(Item.class).stream()
                .filter(item -> !before.contains(item.getUniqueId()))
                .filter(item -> item.getItemStack().getType() == Material.GOLD_NUGGET)
                .mapToInt(item -> item.getItemStack().getAmount()).sum();
        assertEquals(6, dropped);
        assertEquals(70, stored + dropped);
    }

    @Test
    void makingChangePreservesOtherDenominationsAndMergesExistingSmallCoins() {
        player.getInventory().setItem(0, large(2));
        player.getInventory().setItem(1, small(1));
        try (MockedStatic<ChipItems> chips = externalChangeCurrency()) {
            Withdrawal withdrawal = account.planTake(4, null);
            assertNotNull(withdrawal);
            assertEquals(1, withdrawal.preview().size());
            assertEquals(4, TableLedger.valueOf(withdrawal.preview()));
            assertEquals(11, account.available());
            assertEquals(4, TableLedger.valueOf(withdrawal.take()));
            assertEquals(1, count(Material.IRON_NUGGET));
            assertEquals(2, count(Material.GOLD_NUGGET));
            assertEquals(7, account.available());
        }
    }

    @Test
    void insufficientFundsCannotBeFixedByBreakingCoinsOrByTakingUnvaluedItems() {
        player.getInventory().setItem(0, small(1));
        player.getInventory().setItem(1, new ItemStack(Material.DIAMOND, 2));
        PlayerAccount anyItems = Accounts.pockets(table, player, null);
        assertNull(anyItems.planTake(2, null));
        assertEquals(1, anyItems.available());
        assertEquals(1, anyItems.largestTakeUpTo(20, null));
        assertEquals(0, anyItems.largestTakeUpTo(0, null));
        assertEquals(2, count(Material.DIAMOND));
        player.getInventory().setItem(0, large(1));
        try (MockedStatic<ChipItems> chips = externalChangeCurrency()) {
            assertNull(anyItems.planTake(6, null));
            assertEquals(1, count(Material.IRON_NUGGET));
            assertEquals(0, count(Material.GOLD_NUGGET));
        }
    }

    @Test
    void emptyPaymentsAndUnavailableAccountsHaveNoInventorySideEffects() {
        player.getInventory().setItem(0, small(2));
        assertEquals(0, account.accept(null));
        assertEquals(0, account.accept(List.of()));
        assertEquals(2, count(Material.GOLD_NUGGET));
        PlayerAccount unavailable = Accounts.pockets(table, null);
        assertNull(unavailable.planTake(1, null));
        assertEquals(0, unavailable.largestTakeUpTo(1, null));
        assertEquals(0, unavailable.available());
        assertEquals(2, count(Material.GOLD_NUGGET));
    }

    @Test
    void exactWithdrawalSkipsUnusedDenominationsAndCannotTakeReplacedInventoryItems() {
        player.getInventory().setItem(0, large(1));
        player.getInventory().setItem(1, small(2));
        Withdrawal first = account.planTake(5, null);
        assertNotNull(first);
        assertEquals(1, first.preview().size());
        assertEquals(5, TableLedger.valueOf(first.take()));
        assertEquals(2, count(Material.GOLD_NUGGET));
        Withdrawal stale = account.planTake(2, null);
        assertNotNull(stale);
        player.getInventory().setItem(1, new ItemStack(Material.DIAMOND, 2));
        assertTrue(stale.take().isEmpty());
        assertEquals(2, count(Material.DIAMOND));
        assertEquals(0, count(Material.GOLD_NUGGET));
    }

    @Test
    void unstakeableChangeFromTheEconomyLeavesTheCoinWhole() {
        player.getInventory().setItem(0, large(1));
        try (MockedStatic<ChipItems> chips = externalChangeCurrency()) {
            // Change below a whole denar cannot be staked, so breaking into it would strand value.
            chips.when(() -> ChipItems.change(any(ItemStack.class)))
                    .thenReturn(List.of(small(4), new ItemStack(Material.DIAMOND)));
            assertNull(account.planTake(3, null));
            assertEquals(1, count(Material.IRON_NUGGET));
            assertEquals(0, count(Material.GOLD_NUGGET));
        }
    }

    @Test
    void coinsOfEqualValueButDifferentKindsAreStakedAsSeparateHeaps() {
        Cache.wagerItems.add(new WagerItemOverride("EMERALD", 1, null, null, null, null,
                null, null, false, null));
        player.getInventory().setItem(0, small(2));
        player.getInventory().setItem(1, new ItemStack(Material.EMERALD, 2));
        PlayerAccount chips = Accounts.pockets(table, player);
        assertEquals(4, chips.available());
        List<Stake> taken = chips.planTake(4, null).take();
        assertEquals(2, taken.size());
        assertEquals(java.util.Set.of("gold", "item:EMERALD"),
                taken.stream().map(Stake::typeKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, count(Material.GOLD_NUGGET));
        assertEquals(0, count(Material.EMERALD));
    }
}
