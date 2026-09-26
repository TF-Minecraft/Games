package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import net.tfminecraft.games.cache.Cache;

class PotLayoutTest {
    private static WagerPileStyle style(int max, int unit) {
        return new WagerPileStyle(max, unit, .02f, .35f, false, null, -90, 0);
    }

    @Test
    void individualChipsShowOneLayerEachUpToTheStackLimit() {
        var style = style(6, 1);
        assertEquals(0, PotLayout.visibleLayers(0, style));
        assertEquals(0, PotLayout.visibleLayers(-1, style));
        assertEquals(3, PotLayout.visibleLayers(3, style));
        assertEquals(6, PotLayout.visibleLayers(10, style));
        assertTrue(PotLayout.canAdd(5, style));
        assertFalse(PotLayout.canAdd(6, style));
        assertEquals(4, PotLayout.room(2, style));
        assertEquals(0, PotLayout.room(10, style));
    }

    @Test
    void compactPilesAlwaysShowAtLeastOneLayerAndStopAtCapacity() {
        var style = style(6, 64);
        assertEquals(1, PotLayout.visibleLayers(1, style));
        assertEquals(3, PotLayout.visibleLayers(32, style));
        assertEquals(6, PotLayout.visibleLayers(64, style));
        assertEquals(6, PotLayout.visibleLayers(100, style));
        assertTrue(PotLayout.canAdd(58, style));
        assertFalse(PotLayout.canAdd(59, style));
        assertEquals(27, PotLayout.room(32, style));
        assertEquals(0, PotLayout.room(64, style));
        // The scan is bounded even if configuration requests an exceptionally large pile.
        assertEquals(1024, PotLayout.room(0, style(6, 100000)));
    }

    @Test
    void missingOrInvalidStylesHaveSafeCapacity() {
        assertEquals(0, PotLayout.visibleLayers(1, null));
        assertFalse(PotLayout.canAdd(1, null));
        assertEquals(0, PotLayout.room(1, null));
        assertEquals(1, PotLayout.visibleLayers(10, style(0, 0)));
        assertFalse(PotLayout.canAdd(1, style(0, 0)));
        assertEquals(1, PotLayout.room(0, style(0, 0)));
    }

    @Test
    void defaultStyleReflectsCurrentConfiguredRenderingOptions() {
        var style = WagerPileStyle.defaults();
        assertEquals(Cache.wagerStackMax, style.stackMax());
        assertEquals(Cache.wagerStackUnit, style.stackUnit());
        assertEquals(Cache.wagerLayerGap, style.layerGap());
        assertEquals(Cache.wagerItemScale, style.scale());
        assertEquals(Cache.wagerRandomYaw, style.randomYaw());
        assertEquals(Cache.tableCardPitch, style.pitch());
        assertEquals(Cache.wagerYOffset, style.yOffset());
        assertNull(style.model());
    }
}
