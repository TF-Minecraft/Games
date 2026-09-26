package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.games.cache.Cache;

class ChipItemsTest {
    private WagerItemOverride oldGold;
    private WagerItemOverride oldSilver;
    private List<WagerItemOverride> oldItems;
    private boolean oldInteger;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<DenarEconomy> economy;
    private Plugin plugin;
    private PluginManager plugins;
    private MoneyManager money;

    @BeforeEach
    void setUp() {
        oldGold = Cache.wagerGold;
        oldSilver = Cache.wagerSilver;
        oldItems = new ArrayList<>(Cache.wagerItems);
        oldInteger = Cache.wagerIntegerDenars;
        Cache.wagerGold = null;
        Cache.wagerSilver = null;
        Cache.wagerItems.clear();
        Cache.wagerIntegerDenars = true;
        plugins = mock(PluginManager.class);
        plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("DenarEconomy")).thenReturn(plugin);
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        money = mock(MoneyManager.class);
        economy = mockStatic(DenarEconomy.class);
        economy.when(DenarEconomy::getMoneyManager).thenReturn(money);
    }

    @AfterEach
    void restore() {
        economy.close();
        bukkit.close();
        Cache.wagerGold = oldGold;
        Cache.wagerSilver = oldSilver;
        Cache.wagerItems.clear();
        Cache.wagerItems.addAll(oldItems);
        Cache.wagerIntegerDenars = oldInteger;
    }

    private WagerItemOverride override(String material, Integer value, String model) {
        return new WagerItemOverride(material, value, model, null, null, null, null, null, false, null);
    }

    private Coin externalCoin(ItemStack item, Double value) {
        Coin coin = mock(Coin.class);
        when(coin.getId()).thenReturn("external");
        when(coin.getValue()).thenReturn(value);
        when(money.getCoin(item)).thenReturn(coin);
        return coin;
    }

    @Test
    void configuredCoinsAndValuedLootHaveDifferentTaxClassification() {
        Cache.wagerGold = override("GOLD_NUGGET", 2, "gold-display");
        Cache.wagerSilver = override("IRON_NUGGET", 1, "silver-display");
        Cache.wagerItems.add(override("DIAMOND", 10, "loot-display"));
        ItemStack gold = new ItemStack(Material.GOLD_NUGGET);
        ItemStack silver = new ItemStack(Material.IRON_NUGGET);
        ItemStack loot = new ItemStack(Material.DIAMOND);
        assertTrue(ChipItems.isGoldCoin(gold));
        assertTrue(ChipItems.isSilverCoin(silver));
        assertTrue(ChipItems.isChipKind(gold));
        assertEquals(2, ChipItems.unitDenars(gold));
        assertEquals(1, ChipItems.unitDenars(silver));
        assertEquals(10, ChipItems.unitDenars(loot));
        assertTrue(ChipItems.isChip(loot));
        assertTrue(ChipItems.isChipKind(loot));
        assertFalse(ChipItems.isMoneyCoin(loot));
        assertEquals("gold", ChipItems.typeKey(gold));
        assertEquals("silver", ChipItems.typeKey(silver));
        assertEquals("item:DIAMOND", ChipItems.typeKey(loot));
        assertEquals(6, ChipItems.moneyValue(List.of(new Stake(gold, "gold", 2, 3, 0),
                new Stake(loot, "item:DIAMOND", 10, 4, 0))));
        assertEquals("gold-display", ChipItems.displayModel(gold));
        assertEquals("loot-display", ChipItems.displayModel(loot));
        assertEquals(1, ChipItems.pileStyle(gold).stackUnit());
        assertFalse(ChipItems.needsDeclaredValue(loot));
    }

    @Test
    void unvaluedLootRequiresDeclaredValueButUnconfiguredItemsAreNotWagerable() {
        Cache.wagerItems.add(override("DIAMOND", null, null));
        ItemStack loot = new ItemStack(Material.DIAMOND);
        ItemStack ordinary = new ItemStack(Material.STONE);
        assertTrue(ChipItems.needsDeclaredValue(loot));
        assertFalse(ChipItems.isChip(loot));
        assertTrue(ChipItems.isChipKind(loot));
        assertEquals(0, ChipItems.unitDenars(loot));
        assertFalse(ChipItems.needsDeclaredValue(ordinary));
        assertFalse(ChipItems.isChipKind(ordinary));
        assertFalse(ChipItems.isMoneyCoin(ordinary));
        assertEquals("mat:STONE", ChipItems.typeKey(ordinary));
        assertEquals(WagerPileStyle.defaults(), ChipItems.pileStyle(ordinary));
    }

    @Test
    void namedCoinWithoutConfiguredValueUsesEconomyValueOrOneDenarFallback() {
        Cache.wagerGold = override("GOLD_NUGGET", null, null);
        ItemStack gold = new ItemStack(Material.GOLD_NUGGET);
        assertEquals(1, ChipItems.unitDenars(gold));
        externalCoin(gold, 7.0);
        assertEquals(7, ChipItems.unitDenars(gold));
        Cache.wagerGold = override("GOLD_NUGGET", 3, null);
        assertEquals(3, ChipItems.unitDenars(gold));
    }

    @Test
    void externalCurrencyRejectsFractionalOrInvalidWholeDenarsAndCanFloorByConfiguration() {
        ItemStack pouch = new ItemStack(Material.LEATHER);
        Coin coin = externalCoin(pouch, 5.0);
        assertEquals(5, ChipItems.unitDenars(pouch));
        assertTrue(ChipItems.isMoneyCoin(pouch));
        assertEquals("coin:external", ChipItems.typeKey(pouch));
        assertTrue(ChipItems.isChipKind(pouch));
        when(coin.getId()).thenReturn(null);
        assertEquals("mat:LEATHER", ChipItems.typeKey(pouch));
        when(coin.getValue()).thenReturn(1.5);
        assertTrue(ChipItems.integerDenars(pouch).isEmpty());
        assertEquals(0, ChipItems.unitDenars(pouch));
        Cache.wagerIntegerDenars = false;
        assertEquals(1, ChipItems.unitDenars(pouch));
        when(coin.getValue()).thenReturn(0.5);
        assertEquals(0, ChipItems.unitDenars(pouch));
        Cache.wagerIntegerDenars = true;
        for (Double invalid : new Double[] {null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
                (double) Integer.MAX_VALUE + 1, 1.0e-7}) {
            when(coin.getValue()).thenReturn(invalid);
            assertTrue(ChipItems.integerDenars(pouch).isEmpty(), "invalid currency value: " + invalid);
            assertNull(ChipItems.decoChips(pouch), "Invalid values must not create visible chip pieces: " + invalid);
        }
    }

    @Test
    void emptyInventorySlotsAreNeitherCurrencyNorLootProposals() {
        Cache.wagerGold = override("GOLD_NUGGET", 1, "gold-display");
        Cache.wagerItems.add(override("DIAMOND", null, "loot-display"));
        for (ItemStack empty : new ItemStack[] {null, new ItemStack(Material.AIR)}) {
            assertFalse(ChipItems.isChipKind(empty));
            assertFalse(ChipItems.isMoneyCoin(empty));
            assertFalse(ChipItems.needsDeclaredValue(empty));
            assertTrue(ChipItems.integerDenars(empty).isEmpty());
            assertNull(ChipItems.decoChips(empty));
            assertEquals(0, ChipItems.unitDenars(empty));
        }
        verifyNoInteractions(money);
    }

    @Test
    void externalCoinBelowOneSilverPieceCannotCreateAStakeOrVisibleChips() {
        Cache.wagerGold = override("GOLD_NUGGET", 1, "gold-display");
        Cache.wagerSilver = override("IRON_NUGGET", 1, "silver-display");
        ItemStack coin = new ItemStack(Material.LEATHER, 5);
        externalCoin(coin, 0.004);
        assertTrue(ChipItems.isMoneyCoin(coin), "The economy still owns the currency type");
        assertTrue(ChipItems.isChipKind(coin));
        assertFalse(ChipItems.isChip(coin));
        assertNull(ChipItems.decoChips(coin));
        assertEquals(0, ChipItems.unitDenars(coin));
        assertFalse(ChipItems.needsDeclaredValue(coin), "Low-value currency must not become declared loot");
        assertEquals(5, coin.getAmount());
        verify(money, never()).breakCoin(any(), anyDouble());
    }

    @Test
    void externalPouchesDrawConfiguredGoldOrSilverPiecesWithoutStakingFractionalDenars() {
        Cache.wagerGold = override("GOLD_NUGGET", 1, "gold-display");
        Cache.wagerSilver = override("IRON_NUGGET", 1, "silver-display");
        ItemStack pouch = new ItemStack(Material.LEATHER);
        Coin coin = externalCoin(pouch, 5.0);
        ChipItems.DecoChips gold = ChipItems.decoChips(pouch);
        assertNotNull(gold);
        assertEquals(5, gold.pieces());
        assertEquals(5, gold.denars());
        assertEquals("gold-display", ChipItems.displayModel(pouch));
        assertEquals(5, ChipItems.unitDenars(pouch));
        when(coin.getValue()).thenReturn(0.25);
        ChipItems.DecoChips silver = ChipItems.decoChips(pouch);
        assertEquals(25, silver.pieces());
        assertEquals(0, silver.denars());
        assertEquals("silver-display", silver.style().model());
        assertTrue(ChipItems.isChip(pouch));
        assertFalse(ChipItems.needsDeclaredValue(pouch));
        assertEquals(0, ChipItems.unitDenars(pouch));
        Cache.wagerSilver = null;
        assertNull(ChipItems.decoChips(pouch));
    }

    @Test
    void onlyUnconfiguredEconomyCoinsCanAskEconomyForChange() {
        ItemStack pouch = new ItemStack(Material.LEATHER);
        externalCoin(pouch, 5.0);
        List<ItemStack> change = List.of(new ItemStack(Material.GOLD_NUGGET, 5));
        when(money.breakCoin(pouch, 1.0)).thenReturn(change);
        assertEquals(change, ChipItems.change(pouch));
        verify(money).breakCoin(pouch, 1.0);
        when(money.breakCoin(pouch, 1.0)).thenReturn(null);
        assertTrue(ChipItems.change(pouch).isEmpty());
        clearInvocations(money);
        Cache.wagerGold = override("LEATHER", 5, null);
        assertTrue(ChipItems.change(pouch).isEmpty());
        Cache.wagerGold = null;
        Cache.wagerItems.add(override("LEATHER", 5, null));
        assertTrue(ChipItems.change(pouch).isEmpty());
        assertTrue(ChipItems.change(new ItemStack(Material.STONE)).isEmpty());
        verify(money, never()).breakCoin(any(), anyDouble());
    }

    @Test
    void missingOrDisabledEconomyLeavesConfiguredCoinsUsableWithoutCallingIntegration() {
        Cache.wagerGold = override("GOLD_NUGGET", 2, null);
        when(plugin.isEnabled()).thenReturn(false);
        assertFalse(ChipItems.denarEconomyPresent());
        assertEquals(2, ChipItems.unitDenars(new ItemStack(Material.GOLD_NUGGET)));
        assertTrue(ChipItems.change(new ItemStack(Material.LEATHER)).isEmpty());
        when(plugins.getPlugin("DenarEconomy")).thenReturn(null);
        assertFalse(ChipItems.denarEconomyPresent());
        assertEquals(0, ChipItems.unitDenars(new ItemStack(Material.LEATHER)));
        verifyNoInteractions(money);
    }
}
