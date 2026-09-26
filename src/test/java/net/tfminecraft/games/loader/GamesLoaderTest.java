package net.tfminecraft.games.loader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.table.Table;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GamesLoaderTest {
    @TempDir Path temp;
    private Games previousPlugin;
    private Map<String, Map<Integer, Integer>> previousRanks;
    private Map<String, TableLayout> previousLayouts;
    private double previousLeave;
    private String previousSet;
    private String previousLabel;
    private String previousIcon;
    private Sound previousSound;
    private Logger logger;
    private final GamesLoader loader = new GamesLoader();

    @BeforeEach
    void setUp() {
        previousPlugin = Games.plugin;
        previousRanks = new HashMap<>(Cache.gameRankValues);
        previousLayouts = new HashMap<>(Cache.tableLayouts);
        previousLeave = Cache.pokerLeaveDistance;
        previousSet = Cache.pokerCardSet;
        previousLabel = Cache.pokerLabel;
        previousIcon = Cache.pokerIcon;
        previousSound = Cache.chipSound;
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
    }

    @AfterEach
    void tearDown() {
        Cache.gameRankValues.clear();
        Cache.gameRankValues.putAll(previousRanks);
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
        Cache.pokerLeaveDistance = previousLeave;
        Cache.pokerCardSet = previousSet;
        Cache.pokerLabel = previousLabel;
        Cache.pokerIcon = previousIcon;
        Cache.chipSound = previousSound;
        Games.plugin = previousPlugin;
    }

    @Test
    void loadsCompleteLayoutAndPromotesPokerDefaults() throws Exception {
        assertTrue(loader.loadSafe(yaml("""
                ignored: scalar
                PoKeR:
                  card-set: custom-cards
                  label: Custom Poker
                  icon: custom-icon
                  leave-distance: 9
                  rank-values: {'1': 15, '13': 20, wrong: 2}
                  piles:
                    Tray: {forward: 2, right: 3}
                    ignored: scalar
                  felt: {type: BoX, forward: 4, right: 5, width: 6, depth: 8}
                  stand: {forward: 7, right: 8}
                  no-bet-radius: 0.75
                  dealer-hits-soft-17: true
                  auto-dealer: true
                  min-bet: 5
                  max-bet: 100
                  bet-seconds: 20
                  voice: {channel: shout, hit: Hit me, stand: Stay, double: Twice, split: Divide}
                  bet-zone: {radius: 1.25, half-width: 99}
                  result-delay-ticks: 12
                  round-end-seconds: 15
                  max-boxes: 6
                  max-hands-per-box: 3
                  resplit-aces: true
                  blinds: {small: 5, big: 10}
                  chip-sound: {name: ENTITY_EXPERIENCE_ORB_PICKUP, volume: 0.5, pitch: 0.75}
                """)));
        TableLayout layout = Cache.layoutOf("POKER");
        assertEquals(1, Cache.tableLayouts.size());
        assertEquals("custom-cards", layout.cardSet());
        assertEquals("Custom Poker", layout.label());
        assertEquals("custom-icon", layout.icon());
        assertEquals(9, layout.leaveDistance());
        assertEquals("custom-cards", Cache.pokerCardSet);
        assertEquals("Custom Poker", Cache.pokerLabel);
        assertEquals("custom-icon", Cache.pokerIcon);
        assertEquals(9, Cache.pokerLeaveDistance);
        assertEquals(Map.of(1, 15, 13, 20), Cache.gameRankValues.get("poker"));
        verify(logger).warning("[Games] Ignored non-numeric rank-values key 'wrong' under PoKeR");
        assertEquals(Map.of("tray", new TableLayout.PileSlot(2, 3)), layout.piles());
        assertEquals(new TableLayout.PileSlot(7, 8), layout.stand());
        assertEquals(0.75, layout.noBetRadius());
        assertTrue(layout.dealerHitsSoft17());
        assertTrue(layout.autoDealer());
        assertEquals(5, layout.minBet());
        assertEquals(100, layout.maxBet());
        assertEquals(20, layout.betSeconds());
        assertEquals(new TableLayout.VoiceLines("shout", "Hit me", "Stay", "Twice", "Divide"), layout.voice());
        assertEquals(1.25, layout.betZone().radius());
        assertEquals(12, layout.resultDelayTicks());
        assertEquals(15, layout.roundEndSeconds());
        assertEquals(6, layout.maxBoxes());
        assertEquals(3, layout.maxHandsPerBox());
        assertTrue(layout.resplitAces());
        assertEquals(5, layout.smallBlind());
        assertEquals(10, layout.bigBlind());
        assertEquals(new TableLayout.SoundFx(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 0.75f), layout.chipFx());
        Table table = table();
        assertEquals(new Location(null, 5, 0, 4), layout.feltCenter(table));
        assertTrue(layout.onFelt(table, new Location(null, 8, 0, 8)));
        assertFalse(layout.onFelt(table, new Location(null, 8.01, 0, 8)));
    }

    @Test
    void emptyLayoutUsesDefaultsAndKeepsExistingPokerMetadata() throws Exception {
        loader.load(yaml("poker: {}\n"));
        TableLayout layout = Cache.layoutOf("poker");
        assertEquals(previousSet, Cache.pokerCardSet);
        assertEquals(previousLabel, Cache.pokerLabel);
        assertEquals(previousIcon, Cache.pokerIcon);
        assertEquals(previousLeave, Cache.pokerLeaveDistance);
        assertEquals(Map.of(1, 14), Cache.gameRankValues.get("poker"));
        assertEquals("", layout.cardSet());
        assertEquals("", layout.label());
        assertEquals("", layout.icon());
        assertTrue(layout.piles().isEmpty());
        assertNull(layout.stand());
        assertNull(layout.betZone());
        assertNull(layout.chipFx());
        assertEquals(0, layout.noBetRadius());
        assertFalse(layout.dealerHitsSoft17());
        assertFalse(layout.autoDealer());
        assertEquals(1, layout.minBet());
        assertEquals(0, layout.maxBet());
        assertEquals(10, layout.betSeconds());
        assertEquals(TableLayout.VoiceLines.defaults(), layout.voice());
        assertEquals(8, layout.resultDelayTicks());
        assertEquals(10, layout.roundEndSeconds());
        assertEquals(0, layout.maxBoxes());
        assertEquals(4, layout.maxHandsPerBox());
        assertFalse(layout.resplitAces());
        assertEquals(0, layout.smallBlind());
        assertEquals(0, layout.bigBlind());
    }

    @Test
    void clampsInvalidLimitsAndSupportsLegacyBetZoneWidth() throws Exception {
        assertTrue(loader.loadSafe(yaml("""
                blackjack:
                  min-bet: -2
                  max-bet: -3
                  bet-seconds: 0
                  result-delay-ticks: -1
                  round-end-seconds: 0
                  max-boxes: -1
                  max-hands-per-box: 0
                  blinds: {small: 0, big: -5}
                  bet-zone: {half-width: 0.75}
                  voice: {}
                  chip-sound: {}
                """)));
        TableLayout layout = Cache.layoutOf("blackjack");
        assertEquals(1, layout.minBet());
        assertEquals(0, layout.maxBet());
        assertEquals(1, layout.betSeconds());
        assertEquals(0, layout.resultDelayTicks());
        assertEquals(1, layout.roundEndSeconds());
        assertEquals(0, layout.maxBoxes());
        assertEquals(1, layout.maxHandsPerBox());
        assertEquals(0, layout.smallBlind());
        assertEquals(0, layout.bigBlind());
        assertEquals(0.75, layout.betZone().radius());
        assertEquals(TableLayout.VoiceLines.defaults(), layout.voice());
        assertNull(layout.chipFx().sound());
        assertEquals(Cache.chipSoundVolume, layout.chipFx().volume());
        assertEquals(Cache.chipSoundPitch, layout.chipFx().pitch());
    }

    @Test
    void ringDefaultsAndExplicitBoundsControlPlayableArea() throws Exception {
        assertTrue(loader.loadSafe(yaml("""
                default-ring:
                  felt: {}
                  bet-zone: {}
                custom-ring:
                  felt: {type: unknown-shape, min-range: 2, max-range: 4}
                """)));
        Table table = table();
        TableLayout defaults = Cache.layoutOf("default-ring");
        double mid = (Cache.wagerMinRange + Cache.wagerMaxRange) / 2;
        assertEquals(new Location(null, 0, 0, mid), defaults.feltCenter(table));
        assertTrue(defaults.onFelt(table, new Location(null, 0, 0, mid)));
        assertEquals(0.5, defaults.betZone().radius());
        TableLayout custom = Cache.layoutOf("custom-ring");
        assertEquals(new Location(null, 0, 0, 3), custom.feltCenter(table));
        assertTrue(custom.onFelt(table, new Location(null, 0, 0, 2)));
        assertTrue(custom.onFelt(table, new Location(null, 0, 0, 4)));
        assertFalse(custom.onFelt(table, new Location(null, 0, 0, 1.99)));
        assertFalse(custom.onFelt(table, new Location(null, 0, 0, 4.01)));
    }

    @Test
    void reloadClearsStaleGamesAndEmptyRankMappingsUsePokerFallback() throws Exception {
        loader.load(yaml("old: {rank-values: {'1': 7}}\npoker: {rank-values: {'1': 8}}\n"));
        assertEquals(7, Cache.sortValue("old", 1));
        assertTrue(loader.loadSafe(yaml("poker: {rank-values: {bad: 2}}\nempty: {rank-values: {}}\n")));
        assertNull(Cache.layoutOf("old"));
        assertEquals(Map.of("poker", Map.of(1, 14)), Cache.gameRankValues);
        assertTrue(loader.loadSafe(yaml("")));
        assertTrue(Cache.tableLayouts.isEmpty());
        assertEquals(Map.of("poker", Map.of(1, 14)), Cache.gameRankValues);
    }

    @Test
    void invalidSoundsFallBackToGlobalOrBuiltInDefault() throws Exception {
        Cache.chipSound = Sound.ITEM_BOOK_PAGE_TURN;
        assertTrue(loader.loadSafe(yaml("cards: {chip-sound: {name: invalid-sound}}\n")));
        assertSame(Sound.ITEM_BOOK_PAGE_TURN, Cache.layoutOf("cards").chipFx().sound());
        Cache.chipSound = null;
        assertTrue(loader.loadSafe(yaml("cards: {chip-sound: {name: invalid-sound}}\n")));
        assertSame(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, Cache.layoutOf("cards").chipFx().sound());
        verify(logger, times(2)).warning(startsWith("[Games] Unknown chip-sound.name 'invalid-sound', using "));
    }

    @Test
    void missingAndMalformedFilesRetainLastGoodConfiguration() throws Exception {
        assertTrue(loader.loadSafe(yaml("poker: {label: Preserved, rank-values: {'1': 16}}\n")));
        TableLayout preserved = Cache.layoutOf("poker");
        assertFalse(loader.loadSafe(temp.resolve("missing.yml").toFile()));
        assertSame(preserved, Cache.layoutOf("poker"));
        assertFalse(loader.loadSafe(yaml("poker: [broken")));
        assertSame(preserved, Cache.layoutOf("poker"));
        assertEquals(16, Cache.sortValue("poker", 1));
        assertEquals("Preserved", Cache.pokerLabel);
        verify(logger, times(2)).severe(startsWith("[Games] Failed to load games.yml: "));
    }

    private File yaml(String contents) throws Exception {
        return Files.writeString(Files.createTempFile(temp, "games-", ".yml"), contents).toFile();
    }

    private static Table table() {
        Table table = mock(Table.class);
        when(table.getOrigin()).thenReturn(new Location(null, 0, 0, 0));
        when(table.getYaw()).thenReturn(0f);
        return table;
    }
}
