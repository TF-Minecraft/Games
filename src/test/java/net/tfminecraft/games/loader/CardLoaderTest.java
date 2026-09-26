package net.tfminecraft.games.loader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardLoaderTest {
    @TempDir Path temp;
    private Games previousPlugin;
    private boolean previousDebug;
    private Map<String, Card> previousCards;
    private Map<String, List<String>> previousSets;
    private String previousBack;
    private String previousDeck;
    private Logger logger;
    private final CardLoader loader = new CardLoader();

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = Games.plugin;
        previousDebug = Cache.debug;
        previousCards = new LinkedHashMap<>(cards());
        previousSets = new LinkedHashMap<>(sets());
        previousBack = CardLoader.getBackItem();
        previousDeck = CardLoader.getDeckItem();
        cards().clear();
        sets().clear();
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
        Cache.debug = false;
    }

    @AfterEach
    void tearDown() throws Exception {
        cards().clear();
        cards().putAll(previousCards);
        sets().clear();
        sets().putAll(previousSets);
        field("backItem").set(null, previousBack);
        field("deckItem").set(null, previousDeck);
        Games.plugin = previousPlugin;
        Cache.debug = previousDebug;
    }

    @Test
    void loadsCatalogDefaultsAndCaseInsensitiveOrderedSets() throws Exception {
        assertTrue(loader.loadSafe(yaml("""
                back: ia:back
                deck-item: ia:deck
                cards:
                  Ace:
                    suit: cerrith
                    rank: 1
                    item: ia:ace
                  Joker:
                    joker: true
                  ignored: scalar
                sets:
                  Mixed: [JOKER, ace, ACE, missing, '', ' ']
                  Empty: []
                """)));
        assertEquals(2, CardLoader.cardCount());
        Card ace = CardLoader.get("ACE");
        assertEquals("Ace", ace.getId());
        assertEquals("cerrith", ace.getSuit());
        assertEquals(1, ace.getRank());
        assertFalse(ace.isJoker());
        assertEquals("ia:ace", ace.getItem());
        Card joker = CardLoader.get("joker");
        assertEquals("none", joker.getSuit());
        assertEquals(0, joker.getRank());
        assertTrue(joker.isJoker());
        assertEquals("ia.tfmc_games:Joker", joker.getItem());
        assertEquals(List.of(joker, ace, ace), CardLoader.getSet("MIXED"));
        assertTrue(CardLoader.hasSet("MiXeD"));
        assertTrue(CardLoader.hasSet("empty"));
        assertEquals(Set.of("mixed", "empty"), CardLoader.setNames());
        assertEquals("ia:back", CardLoader.getBackItem());
        assertEquals("ia:deck", CardLoader.getDeckItem());
        verify(logger).warning("[Games] Set 'Mixed' skips unknown card id: missing");
        assertNull(CardLoader.get(null));
        assertNull(CardLoader.get("unknown"));
        assertFalse(CardLoader.hasSet(null));
        assertFalse(CardLoader.hasSet("unknown"));
        assertTrue(CardLoader.getSet(null).isEmpty());
        assertTrue(CardLoader.getSet("unknown").isEmpty());
        assertTrue(CardLoader.getSet("empty").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> CardLoader.getSet("mixed").clear());
        assertThrows(UnsupportedOperationException.class, () -> CardLoader.setNames().clear());
    }

    @Test
    void successfulReloadReplacesCatalogAndKeepsUnspecifiedItemDefaults() throws Exception {
        loader.load(yaml("""
                back: first-back
                deck-item: first-deck
                cards:
                  old: {}
                sets:
                  old-set: [old]
                """));
        assertEquals(1, CardLoader.cardCount());
        assertTrue(loader.loadSafe(yaml("""
                cards:
                  new: {}
                sets:
                  new-set: [new]
                """)));
        assertNull(CardLoader.get("old"));
        assertFalse(CardLoader.hasSet("old-set"));
        assertEquals("new", CardLoader.getSet("new-set").getFirst().getId());
        assertEquals("first-back", CardLoader.getBackItem());
        assertEquals("first-deck", CardLoader.getDeckItem());
        assertTrue(loader.loadSafe(yaml("")));
        assertEquals(0, CardLoader.cardCount());
        assertTrue(CardLoader.setNames().isEmpty());
    }

    @Test
    void missingAndMalformedFilesPreserveLastSuccessfulCatalog() throws Exception {
        assertTrue(loader.loadSafe(yaml("""
                cards:
                  saved: {}
                sets:
                  saved: [saved]
                """)));
        Card saved = CardLoader.get("saved");
        assertFalse(loader.loadSafe(temp.resolve("absent.yml").toFile()));
        assertSame(saved, CardLoader.get("saved"));
        assertFalse(loader.loadSafe(yaml("cards: [broken")));
        assertSame(saved, CardLoader.get("saved"));
        assertEquals(List.of(saved), CardLoader.getSet("saved"));
        verify(logger, times(2)).severe(startsWith("[Games] Failed to load cards.yml: "));
    }

    @Test
    void debugLogsCatalogSummaryAndAcceptsAbsentPlugin() throws Exception {
        Cache.debug = true;
        assertTrue(loader.loadSafe(yaml("cards: {one: {}}\nsets: {solo: [one]}\n")));
        verify(logger).info("[Games] Debug: cards=1 sets=[solo]");
        Games.plugin = null;
        assertTrue(loader.loadSafe(yaml("")));
        assertEquals(0, CardLoader.cardCount());
    }

    private File yaml(String contents) throws Exception {
        return Files.writeString(Files.createTempFile(temp, "cards-", ".yml"), contents).toFile();
    }

    private static Field field(String name) throws Exception {
        Field field = CardLoader.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Card> cards() throws Exception {
        return (Map<String, Card>) field("cards").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> sets() throws Exception {
        return (Map<String, List<String>>) field("setIds").get(null);
    }
}
