package net.tfminecraft.games.deck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.loader.CardLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class DeckTest {
    private final Card ace = new Card("ace", "cerrith", 1, false, "ace-item");
    private final Card king = new Card("king", "mitlan", 13, false, "king-item");
    private final Card outsider = new Card("outsider", "seithr", 2, false, "other-item");
    private Games previousPlugin;
    private boolean previousDebug;
    private Logger logger;
    private MockedStatic<CardLoader> loader;

    @BeforeEach
    void setUp() {
        previousPlugin = Games.plugin;
        previousDebug = Cache.debug;
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
        Cache.debug = false;
        loader = mockStatic(CardLoader.class);
        loader.when(() -> CardLoader.hasSet("standard")).thenReturn(true);
        loader.when(() -> CardLoader.getSet("standard")).thenReturn(List.of(ace, king));
        loader.when(() -> CardLoader.get("ace")).thenReturn(ace);
        loader.when(() -> CardLoader.get("king")).thenReturn(king);
        loader.when(() -> CardLoader.get("outsider")).thenReturn(outsider);
    }

    @AfterEach
    void tearDown() {
        if (loader != null) {
            loader.close();
        }
        Games.plugin = previousPlugin;
        Cache.debug = previousDebug;
    }

    @Test
    void rejectsUnknownAndEmptySetsIncludingRestoration() {
        assertTrue(Deck.create("missing").isEmpty());
        verify(logger).warning("[Games] Unknown card set: missing");
        loader.when(() -> CardLoader.hasSet("empty")).thenReturn(true);
        loader.when(() -> CardLoader.getSet("empty")).thenReturn(List.of());
        assertTrue(Deck.create("empty").isEmpty());
        verify(logger).warning("[Games] Card set is empty: empty");
        assertTrue(Deck.create("missing", List.of("ace"), List.of("king")).isEmpty());
    }

    @Test
    void drawsInCompositionOrderAndKeepsOriginalSize() {
        Deck deck = Deck.create("standard").orElseThrow();
        assertEquals("standard", deck.getSetName());
        assertEquals(2, deck.size());
        assertEquals(2, deck.remaining());
        assertEquals(0, deck.discarded());
        assertSame(ace, deck.draw().orElseThrow());
        assertSame(king, deck.draw().orElseThrow());
        assertTrue(deck.draw().isEmpty());
        assertEquals(0, deck.remaining());
        assertEquals(2, deck.size());
    }

    @Test
    void shufflePreservesRemainingMultiplicityAndDiscardPile() {
        Deck deck = Deck.create("standard", List.of("ace", "ace", "king"), List.of("king"))
                .orElseThrow();
        deck.shuffle();
        assertEquals(List.of("ace", "ace", "king"), sorted(deck.remainingIds()));
        assertEquals(List.of("king"), deck.discardedIds());
    }

    @Test
    void discardsOnlyCompositionMembersAndReturnsIndependentSnapshots() {
        Deck deck = Deck.create("standard").orElseThrow();
        deck.discard(null);
        deck.discard(outsider);
        assertEquals(0, deck.discarded());
        verifyNoInteractions(logger);
        deck.discard(deck.draw().orElseThrow());
        assertEquals(List.of("ace"), deck.discardedIds());
        assertEquals(List.of("king"), deck.remainingIds());
        deck.remainingIds().clear();
        deck.discardedIds().add("outsider");
        assertEquals(1, deck.remaining());
        assertEquals(1, deck.discarded());
    }

    @Test
    void debugLogsIgnoredCardsAndToleratesMissingPlugin() {
        Deck deck = Deck.create("standard").orElseThrow();
        Cache.debug = true;
        deck.discard(null);
        deck.discard(outsider);
        verify(logger).info("[Games] Debug: ignored discard of card not in set standard: null");
        verify(logger).info("[Games] Debug: ignored discard of card not in set standard: outsider");
        Games.plugin = null;
        assertDoesNotThrow(() -> deck.discard(outsider));
        assertEquals(0, deck.discarded());
    }

    @Test
    void recycleMovesDiscardedCardsBackAndEmptyRecyclePreservesOrder() {
        Deck deck = Deck.create("standard").orElseThrow();
        deck.recycle();
        assertEquals(List.of("ace", "king"), deck.remainingIds());
        deck.discard(deck.draw().orElseThrow());
        deck.recycle();
        assertEquals(List.of("ace", "king"), sorted(deck.remainingIds()));
        assertEquals(0, deck.discarded());
    }

    @Test
    void reshuffleMovesDiscardedCardsBackEvenAfterExhaustion() {
        Deck deck = Deck.create("standard").orElseThrow();
        deck.discard(deck.draw().orElseThrow());
        deck.discard(deck.draw().orElseThrow());
        deck.reshuffleAll();
        assertEquals(List.of("ace", "king"), sorted(deck.remainingIds()));
        assertEquals(0, deck.discarded());
        deck.reshuffleAll();
        assertEquals(List.of("ace", "king"), sorted(deck.remainingIds()));
    }

    @Test
    void restoresOrderWhileSkippingUnknownAndOutOfSetCards() {
        Deck deck = Deck.create("standard", List.of("king", "missing", "outsider", "ace"),
                List.of("outsider", "ace", "missing", "king")).orElseThrow();
        assertEquals(List.of("king", "ace"), deck.remainingIds());
        assertEquals(List.of("ace", "king"), deck.discardedIds());
        assertEquals(2, deck.size());
    }

    @Test
    void nullSavedOrderUsesFullCompositionAndEmptySavedOrderExhaustsShoe() {
        Deck defaults = Deck.create("standard", null).orElseThrow();
        assertEquals(List.of("ace", "king"), defaults.remainingIds());
        assertTrue(defaults.discardedIds().isEmpty());
        Deck empty = Deck.create("standard", List.of(), List.of()).orElseThrow();
        assertTrue(empty.remainingIds().isEmpty());
        assertTrue(empty.discardedIds().isEmpty());
    }

    private static List<String> sorted(List<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        return sorted;
    }
}
