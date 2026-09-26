package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.games.cache.Cache;

/** Citizen tax only counts coins, whatever value loot was wagered at. */
class ChipItemsMoneyValueTest {

    private WagerItemOverride previousGold;
    private WagerItemOverride previousSilver;
    private List<WagerItemOverride> previousItems;

    @BeforeEach
    void setUp() {
        previousGold = Cache.wagerGold;
        previousSilver = Cache.wagerSilver;
        previousItems = new ArrayList<>(Cache.wagerItems);
        Cache.wagerGold = override("GOLD_NUGGET", 1);
        Cache.wagerSilver = override("IRON_NUGGET", 1);
        Cache.wagerItems.clear();
        Cache.wagerItems.add(override("GOLD_INGOT", 10));
    }

    @AfterEach
    void restoreConfig() {
        Cache.wagerGold = previousGold;
        Cache.wagerSilver = previousSilver;
        Cache.wagerItems.clear();
        Cache.wagerItems.addAll(previousItems);
    }

    private static WagerItemOverride override(String material, Integer value) {
        return new WagerItemOverride(material, value, null, null, null, null, null, null, false, null);
    }

    private static Stake stake(Material material, int unit, int count) {
        return new Stake(new ItemStack(material), ChipItems.typeKey(new ItemStack(material)), unit, count, 1);
    }

    @Test
    void configuredGoldAndSilverCoinsCountAtTheirStakedValue() {
        assertEquals(5, ChipItems.moneyValue(stake(Material.GOLD_NUGGET, 1, 5)));
        assertEquals(3, ChipItems.moneyValue(stake(Material.IRON_NUGGET, 1, 3)));
    }

    @Test
    void valuedWagerItemsAndLootAreNotMoney() {
        assertEquals(0, ChipItems.moneyValue(stake(Material.GOLD_INGOT, 10, 1)));
        assertEquals(0, ChipItems.moneyValue(stake(Material.DIAMOND, 100, 1)));
    }

    @Test
    void sumsOnlyMoneyInAMix() {
        assertEquals(50, ChipItems.moneyValue(List.of(stake(Material.GOLD_NUGGET, 10, 5),
                stake(Material.DIAMOND, 100, 1))));
        assertEquals(0, ChipItems.moneyValue(List.of()));
    }
}
