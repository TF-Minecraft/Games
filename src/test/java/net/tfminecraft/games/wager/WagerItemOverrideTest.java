package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;

/** How one {@code wager.items} entry recognises its item and draws its pile. */
class WagerItemOverrideTest {
    private static final WagerPileStyle BASE = new WagerPileStyle(8, 5, 0.03f, 0.4f, true, null, 10f, 0.1);

    private ItemAPI items;
    private MockedStatic<TLibs> libs;

    @BeforeEach
    void setUp() {
        items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        libs = mockStatic(TLibs.class);
        libs.when(TLibs::getItemAPI).thenReturn(items);
    }

    @AfterEach
    void tearDown() {
        libs.close();
    }

    private static WagerItemOverride override(String item, String model, Integer stackMax, Float layerGap,
            Float scale, Boolean randomYaw, Float pitch, boolean threeD, Double yOffset) {
        return new WagerItemOverride(item, 3, model, stackMax, layerGap, scale, randomYaw, pitch, threeD, yOffset);
    }

    @Test
    void unsetLookFallsBackToDefaultsAndDrawsTheItemItself() {
        WagerPileStyle style = override("GOLD_INGOT", null, null, null, null, null, null, false, null).style(BASE);
        assertEquals(new WagerPileStyle(8, 5, 0.03f, 0.4f, true, "GOLD_INGOT", 10f, 0.1), style);
    }

    @Test
    void configuredLookReplacesEveryDefaultAndThreeDimensionalItemsStandUpright() {
        WagerPileStyle style = override("GOLD_INGOT", "ia.tfmc:ingot", 4, 0.06f, 0.7f, false, 30f, true, 0.25)
                .style(BASE, 1);
        assertEquals(new WagerPileStyle(4, 1, 0.06f, 0.7f, false, "ia.tfmc:ingot", -60f, 0.25), style);
    }

    @Test
    void vanillaNamesMatchByMaterialWithOrWithoutTheMinecraftNamespace() {
        ItemStack ingot = new ItemStack(Material.GOLD_INGOT);
        assertTrue(override("gold_ingot", null, null, null, null, null, null, false, null).matches(ingot));
        assertTrue(override("minecraft:GOLD_INGOT", null, null, null, null, null, null, false, null).matches(ingot));
        assertFalse(override("minecraft:iron_ingot", null, null, null, null, null, null, false, null).matches(ingot));
        verifyNoInteractions(items);
    }

    @Test
    void customItemPathsAndUnknownNamesAreCheckedByTheItemLibrary() {
        ItemStack coin = new ItemStack(Material.PAPER);
        when(items.getChecker().checkItemWithPath(coin, "ia.tfmc:gold_coin")).thenReturn(true);
        assertTrue(override("ia.tfmc:gold_coin", null, null, null, null, null, null, false, null).matches(coin));
        assertFalse(override("m.currency.silver_coin", null, null, null, null, null, null, false, null)
                .matches(coin));
        assertFalse(override("shiny_coin", null, null, null, null, null, null, false, null).matches(coin));
        assertFalse(override("oraxen:coin", null, null, null, null, null, null, false, null).matches(coin));
        verify(items.getChecker()).checkItemWithPath(coin, "oraxen:coin");
        verify(items.getChecker()).checkItemWithPath(coin, "m.currency.silver_coin");
        verify(items.getChecker()).checkItemWithPath(coin, "shiny_coin");
    }
}
