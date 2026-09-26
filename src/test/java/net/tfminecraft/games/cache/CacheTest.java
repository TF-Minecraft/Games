package net.tfminecraft.games.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.layout.TableLayout;

class CacheTest {
    private static TableLayout layout(String set, String label, String icon, double distance) {
        return new TableLayout(set, label, icon, distance, null, null, null, null, 0);
    }

    @Test
    void gameOverridesAreCaseInsensitiveAndMissingSettingsUseGlobalDefaults() {
        var saved = Map.copyOf(Cache.tableLayouts);
        try {
            Cache.tableLayouts.clear();
            assertNull(Cache.layoutOf(null));
            assertNull(Cache.layoutOf("missing"));
            assertDefaults(null);
            assertDefaults("missing");
            Cache.tableLayouts.put("empty", layout(null, null, null, 0));
            Cache.tableLayouts.put("blank", layout(" ", "", "  ", -1));
            assertDefaults("empty");
            assertDefaults("blank");
            var custom = layout("custom-shoe", "Custom game", "custom-icon", 8);
            Cache.tableLayouts.put("custom", custom);
            assertSame(custom, Cache.layoutOf("CUSTOM"));
            assertEquals("custom-shoe", Cache.cardSetOf("CUSTOM"));
            assertEquals("Custom game", Cache.labelOf("CUSTOM"));
            assertEquals("custom-icon", Cache.iconOf("CUSTOM"));
            assertEquals(8, Cache.leaveDistanceOf("CUSTOM"));
        } finally {
            Cache.tableLayouts.clear();
            Cache.tableLayouts.putAll(saved);
        }
    }

    @Test
    void rankOverridesAreIsolatedByGameAndLeaveUnmappedRanksAlone() {
        var saved = Map.copyOf(Cache.gameRankValues);
        try {
            Cache.gameRankValues.clear();
            Cache.gameRankValues.put("poker", Map.of(1, 14));
            assertEquals(14, Cache.sortValue("POKER", 1));
            assertEquals(10, Cache.sortValue("poker", 10));
            assertEquals(1, Cache.sortValue("blackjack", 1));
            assertEquals(1, Cache.sortValue(null, 1));
        } finally {
            Cache.gameRankValues.clear();
            Cache.gameRankValues.putAll(saved);
        }
    }

    private static void assertDefaults(String game) {
        assertEquals(Cache.pokerCardSet, Cache.cardSetOf(game));
        assertEquals(Cache.pokerLabel, Cache.labelOf(game));
        assertEquals(Cache.pokerIcon, Cache.iconOf(game));
        assertEquals(Cache.pokerLeaveDistance, Cache.leaveDistanceOf(game));
    }
}
