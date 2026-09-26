package net.tfminecraft.games.loader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.wager.WagerItemOverride;
import net.tfminecraft.games.wager.WagerPileStyle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigLoaderTest {
    @TempDir Path temp;
    private final ConfigLoader loader = new ConfigLoader();
    private final Map<Field, Object> previousSettings = new LinkedHashMap<>();
    private List<WagerItemOverride> previousItems;
    private Games previousPlugin;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = Games.plugin;
        // Capture public configuration values, leaving final runtime maps untouched.
        for (Field field : Cache.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                previousSettings.put(field, field.get(null));
            }
        }
        previousItems = new ArrayList<>(Cache.wagerItems);
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Map.Entry<Field, Object> entry : previousSettings.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
        Cache.wagerItems.clear();
        Cache.wagerItems.addAll(previousItems);
        Games.plugin = previousPlugin;
    }

    @ParameterizedTest(name = "{0} accepts positive values and rejects zero/negative values")
    @CsvSource({
            "stack-visible-max, stackVisibleMax",
            "display-range, displayRange",
            "stack-layer-gap, stackLayerGap",
            "table.board-offset, tableBoardOffset",
            "hand.distance, handDistance",
            "hand.selected-distance, handSelectedDistance",
            "hand.select-range, handSelectRange",
            "hand.select-radius, handSelectRadius",
            "hand.sit-range, handSitRange",
            "hand.sit-edge, handSitEdge",
            "wager.stack-max, wagerStackMax",
            "wager.stack-unit, wagerStackUnit",
            "wager.layer-gap, wagerLayerGap",
            "wager.item-scale, wagerItemScale",
            "wager.merge-range, wagerMergeRange",
            "wager.vote-seconds, wagerVoteSeconds",
            "wager.place-seconds, wagerPlaceSeconds",
            "wager.min-players, wagerMinPlayers"
    })
    void positiveSettingsRetainLastValidValueOnInvalidReload(String path, String fieldName) throws Exception {
        Field field = Cache.class.getField(fieldName);
        assertTrue(loader.loadSafe(singleSetting(path, 3)));
        assertEquals(3.0, ((Number) field.get(null)).doubleValue(), path);
        assertTrue(loader.loadSafe(singleSetting(path, 0)));
        assertEquals(3.0, ((Number) field.get(null)).doubleValue(), path);
        assertTrue(loader.loadSafe(singleSetting(path, -1)));
        assertEquals(3.0, ((Number) field.get(null)).doubleValue(), path);
    }

    @ParameterizedTest(name = "{0} permits disabling with zero and rejects negatives")
    @CsvSource({
            "interpolation-ticks, interpolationTicks",
            "table.recycle-ticks, tableRecycleTicks",
            "hand.follow-ticks, handFollowTicks",
            "hand.yaw-stick, handYawStick",
            "hand.select-ticks, handSelectTicks",
            "hand.pos-stick, handPosStick",
            "hand.sit-inset, handSitInset",
            "hand.split-group-gap, handSplitGroupGap",
            "hand.reveal-stagger, handRevealStagger",
            "hand.reveal-flip, handRevealFlip",
            "hand.deal-ticks, handDealTicks",
            "hand.reveal-dust, handRevealDust",
            "wager.min-range, wagerMinRange",
            "wager.payout-ticks, wagerPayoutTicks"
    })
    void nonnegativeSettingsPermitZeroAndRejectNegativeReload(String path, String fieldName) throws Exception {
        Field field = Cache.class.getField(fieldName);
        assertTrue(loader.loadSafe(singleSetting(path, 2)));
        assertEquals(2.0, ((Number) field.get(null)).doubleValue(), path);
        assertTrue(loader.loadSafe(singleSetting(path, -1)));
        assertEquals(2.0, ((Number) field.get(null)).doubleValue(), path);
        assertTrue(loader.loadSafe(singleSetting(path, 0)));
        assertEquals(0.0, ((Number) field.get(null)).doubleValue(), path);
    }

    @Test
    void visualOffsetsFlagsAndSoundSettingsLoadAndSurviveUnspecifiedReload() throws Exception {
        loader.load(yaml("""
                debug: true
                card-scale: 0.6
                table-y-offset: -0.2
                table: {discard-offset: -0.4}
                table-card: {yaw-offset: 12, pitch: -80, roll: 15}
                hand: {spread: 0.3, lift: 1.5, layer-gap: 0.025, yaw-limit: 45}
                card-sound: {name: 'minecraft:item.book.page.turn', volume: 0.5, pitch: 0.75}
                chip-sound: {name: '', volume: 0.25, pitch: 1.5}
                wager:
                  integer-denars: false
                  random-yaw: true
                  y-offset: -0.5
                  audit-log: true
                  show-chips: false
                """));
        assertTrue(Cache.debug);
        assertEquals(0.6f, Cache.cardScale);
        assertEquals(-0.2, Cache.tableYOffset);
        assertEquals(-0.4, Cache.tableDiscardOffset);
        assertEquals(12f, Cache.tableCardYawOffset);
        assertEquals(-80f, Cache.tableCardPitch);
        assertEquals(15f, Cache.tableCardRoll);
        assertEquals(0.3f, Cache.handSpread);
        assertEquals(1.5f, Cache.handLift);
        assertEquals(0.025f, Cache.handLayerGap);
        assertEquals(45f, Cache.handYawLimit);
        assertSame(Sound.ITEM_BOOK_PAGE_TURN, Cache.cardSound);
        assertEquals(0.5f, Cache.cardSoundVolume);
        assertEquals(0.75f, Cache.cardSoundPitch);
        assertNull(Cache.chipSound);
        assertEquals(0.25f, Cache.chipSoundVolume);
        assertEquals(1.5f, Cache.chipSoundPitch);
        assertFalse(Cache.wagerIntegerDenars);
        assertTrue(Cache.wagerRandomYaw);
        assertEquals(-0.5, Cache.wagerYOffset);
        assertTrue(Cache.wagerAuditLog);
        assertFalse(Cache.wagerShowChips);
        assertTrue(loader.loadSafe(yaml("debug: false\n")));
        assertFalse(Cache.debug);
        assertEquals(0.6f, Cache.cardScale);
        assertEquals(-0.5, Cache.wagerYOffset);
        assertFalse(Cache.wagerShowChips);
    }

    @Test
    void maximumWagerRangeMustExceedConfiguredMinimum() throws Exception {
        assertTrue(loader.loadSafe(yaml("wager: {min-range: 1, max-range: 3}\n")));
        assertEquals(1, Cache.wagerMinRange);
        assertEquals(3, Cache.wagerMaxRange);
        assertTrue(loader.loadSafe(yaml("wager: {max-range: 1}\n")));
        assertEquals(3, Cache.wagerMaxRange);
        assertTrue(loader.loadSafe(yaml("wager: {max-range: -1}\n")));
        assertEquals(3, Cache.wagerMaxRange);
    }

    @Test
    void itemOverridesApplyConfiguredValuesAndMergeUnspecifiedStyle() throws Exception {
        assertTrue(loader.loadSafe(yaml("""
                wager:
                  gold:
                    item: ' minecraft:gold_ingot '
                    value: 10
                    model: ' custom:model '
                    stack-max: 3
                    layer-gap: 0.05
                    scale: 0.75
                    random-yaw: false
                    pitch: -30
                    3d: true
                    y-offset: -0.2
                  silver: {item: ' '}
                  items:
                    ' minecraft:emerald ': {value: 4}
                    diamond: {item: minecraft:diamond}
                    ' ': {value: 100}
                """)));
        WagerPileStyle base = new WagerPileStyle(8, 64, 0.01f, 0.4f, true, null, -90f, 0.1);
        assertEquals("minecraft:gold_ingot", Cache.wagerGold.item());
        assertEquals(10, Cache.wagerGold.value());
        assertEquals(new WagerPileStyle(3, 64, 0.05f, 0.75f, false, "custom:model", -120f, -0.2),
                Cache.wagerGold.style(base));
        assertEquals("m.currency.silver_coin", Cache.wagerSilver.item());
        assertNull(Cache.wagerSilver.value());
        assertEquals(2, Cache.wagerItems.size());
        WagerItemOverride emerald = Cache.wagerItems.getFirst();
        assertEquals("minecraft:emerald", emerald.item());
        assertEquals(4, emerald.value());
        assertEquals(new WagerPileStyle(8, 64, 0.01f, 0.4f, true, "minecraft:emerald", -90f, 0.1),
                emerald.style(base));
        assertEquals("minecraft:diamond", Cache.wagerItems.get(1).item());
    }

    @Test
    void invalidItemStyleValuesUseDefaultsAndReloadRemovesOldOverrides() throws Exception {
        assertTrue(loader.loadSafe(yaml("""
                wager:
                  items:
                    emerald:
                      value: 0
                      model: ' '
                      stack-max: -2
                      layer-gap: 0
                      scale: -1
                    diamond: {}
                """)));
        WagerItemOverride emerald = Cache.wagerItems.getFirst();
        assertNull(emerald.value());
        WagerPileStyle base = new WagerPileStyle(8, 64, 0.01f, 0.4f, true, null, -90f, 0.1);
        assertEquals(new WagerPileStyle(8, 64, 0.01f, 0.4f, true, "emerald", -90f, 0.1), emerald.style(base),
                "invalid sizes keep the defaults and a blank model draws the item itself");
        assertTrue(loader.loadSafe(yaml("")));
        assertTrue(Cache.wagerItems.isEmpty());
        assertEquals("ia.tfmc:gold_coin", Cache.wagerGold.item());
        assertEquals("m.currency.silver_coin", Cache.wagerSilver.item());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ITEM_BOOK_PAGE_TURN", "item.book.page.turn", "item-book-page-turn", " minecraft:item.book.page.turn "})
    void acceptsDocumentedSoundNameFormats(String name) {
        assertSame(Sound.ITEM_BOOK_PAGE_TURN, ConfigLoader.parseSound(name, "card-sound.name", null));
    }

    @Test
    void blankSoundsDisablePlaybackAndUnknownNamesWarnAndUseFallback() {
        assertNull(ConfigLoader.parseSound("", "card-sound.name", Sound.ITEM_BOOK_PAGE_TURN));
        assertNull(ConfigLoader.parseSound(" ", "card-sound.name", Sound.ITEM_BOOK_PAGE_TURN));
        assertSame(Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                ConfigLoader.parseSound("not-a-sound", "chip-sound.name", Sound.ENTITY_EXPERIENCE_ORB_PICKUP));
        assertSame(Sound.ITEM_BOOK_PAGE_TURN, ConfigLoader.parseSound("not-a-sound", "card-sound.name", null));
        verify(logger, times(2)).warning(startsWith("[Games] Unknown "));
    }

    @Test
    void missingAndMalformedFilesLeaveLoadedSettingsAndOverridesIntact() throws Exception {
        assertTrue(loader.loadSafe(yaml("stack-visible-max: 7\nwager: {items: {emerald: {value: 2}}}\n")));
        WagerItemOverride saved = Cache.wagerItems.getFirst();
        assertFalse(loader.loadSafe(temp.resolve("missing.yml").toFile()));
        assertEquals(7, Cache.stackVisibleMax);
        assertSame(saved, Cache.wagerItems.getFirst());
        assertFalse(loader.loadSafe(yaml("hand: [broken")));
        assertEquals(7, Cache.stackVisibleMax);
        assertSame(saved, Cache.wagerItems.getFirst());
        verify(logger, times(2)).severe(startsWith("[Games] Failed to load config.yml: "));
    }

    private File yaml(String contents) throws Exception {
        return Files.writeString(Files.createTempFile(temp, "config-", ".yml"), contents).toFile();
    }

    private File singleSetting(String path, int value) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        if (path.equals("wager.min-range")) {
            config.set("wager.max-range", 3);
        }
        config.set(path, value);
        return yaml(config.saveToString());
    }
}
