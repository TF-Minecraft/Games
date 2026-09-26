package net.tfminecraft.games.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;

class HoldemRankTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("hands")
    void ranksEveryCategoryAndItsKickers(String description, List<Card> cards, int[] keys) {
        HoldemRank.Score score = HoldemRank.best(null, cards);
        assertScore(score, keys);
        assertEquals(5, score.cards().size());
        assertTrue(cards.containsAll(score.cards()));
    }

    static Stream<Arguments> hands() {
        return Stream.of(
                hand("high card", mixed(14, 11, 8, 5, 2), 0, 14, 11, 8, 5, 2),
                hand("pair", mixed(8, 14, 8, 12, 3), 1, 8, 14, 12, 3, 0),
                hand("high pair", mixed(14, 14, 12, 7, 2), 1, 14, 12, 7, 2, 0),
                hand("two pairs", mixed(5, 5, 12, 12, 14), 2, 12, 5, 14, 0, 0),
                hand("two pairs above kicker", mixed(14, 14, 12, 12, 2), 2, 14, 12, 2, 0, 0),
                hand("trips", mixed(8, 14, 8, 12, 8), 3, 8, 14, 12, 0, 0),
                hand("high trips", mixed(14, 14, 14, 5, 2), 3, 14, 5, 2, 0, 0),
                hand("straight", mixed(5, 8, 6, 9, 7), 4, 9, 0, 0, 0, 0),
                hand("ace high straight", mixed(14, 13, 12, 11, 10), 4, 14, 0, 0, 0, 0),
                hand("ace low straight", mixed(14, 2, 3, 4, 5), 4, 5, 0, 0, 0, 0),
                hand("flush", suited("hearts", 14, 10, 8, 4, 2), 5, 14, 10, 8, 4, 2),
                hand("full house", mixed(7, 7, 7, 14, 14), 6, 7, 14, 0, 0, 0),
                hand("quads with high kicker", mixed(9, 9, 9, 9, 14), 7, 9, 14, 0, 0, 0),
                hand("quads with low kicker", mixed(14, 14, 14, 14, 2), 7, 14, 2, 0, 0, 0),
                hand("straight flush", suited("clubs", 10, 9, 8, 7, 6), 8, 10, 0, 0, 0, 0),
                hand("wheel straight flush", suited("clubs", 14, 5, 4, 3, 2), 8, 5, 0, 0, 0, 0),
                hand("royal flush", suited("clubs", 14, 13, 12, 11, 10), 8, 14, 0, 0, 0, 0),
                hand("wheel missing three", mixed(2, 4, 5, 6, 14), 0, 14, 6, 5, 4, 2),
                hand("wheel missing four", mixed(2, 3, 5, 6, 14), 0, 14, 6, 5, 3, 2),
                hand("wheel missing five", mixed(2, 3, 4, 6, 14), 0, 14, 6, 4, 3, 2),
                hand("wheel missing ace", mixed(2, 3, 4, 5, 13), 0, 13, 5, 4, 3, 2));
    }

    @Test
    void noHandForNullEmptyOrFewerThanFiveUsableCards() {
        assertScore(HoldemRank.best(null, null), -1, 0, 0, 0, 0, 0);
        assertTrue(HoldemRank.best(null, List.of()).cards().isEmpty());
        List<Card> incomplete = new ArrayList<>(mixed(14, 13, 12, 11));
        incomplete.add(null);
        incomplete.add(new Card("joker", "clubs", 10, true, ""));
        HoldemRank.Score result = HoldemRank.best(null, incomplete);
        assertScore(result, -1, 0, 0, 0, 0, 0);
        assertTrue(result.cards().isEmpty());
    }

    @Test
    void ignoresJokersAndNullsWhenThereIsACompleteHand() {
        List<Card> cards = new ArrayList<>(mixed(14, 11, 8, 5, 2));
        Card joker = new Card("joker", "clubs", 14, true, "");
        cards.add(joker);
        cards.add(null);
        HoldemRank.Score result = HoldemRank.best(null, cards);
        assertScore(result, 0, 14, 11, 8, 5, 2);
        assertFalse(result.cards().contains(joker));
    }

    @Test
    void selectsBestFiveOfSevenAndRetainsTheirIdentity() {
        List<Card> cards = mixed(2, 3, 14, 14, 14, 13, 13);
        HoldemRank.Score result = HoldemRank.best(null, cards);
        assertScore(result, 6, 14, 13, 0, 0, 0);
        assertEquals(cards.subList(2, 7), result.cards());
    }

    @Test
    void retainsFirstWinningCombinationWhenLaterCombinationsTie() {
        List<Card> cards = mixed(14, 13, 12, 11, 10, 10);
        HoldemRank.Score result = HoldemRank.best(null, cards);
        assertScore(result, 4, 14, 0, 0, 0, 0);
        assertEquals(cards.subList(0, 5), result.cards());
    }

    @Test
    void scoredCardsAreSortedCopiedAndImmutable() {
        List<Card> original = mixed(2, 14, 5, 11, 8);
        Card[] array = original.toArray(Card[]::new);
        HoldemRank.Score base = HoldemRank.Score.of(0, 14, 11, 8, 5, 2);
        HoldemRank.Score held = base.withCards(null, array);
        array[0] = original.get(1);
        assertEquals(List.of(original.get(1), original.get(3), original.get(4),
                original.get(2), original.get(0)), held.cards());
        assertThrows(UnsupportedOperationException.class, () -> held.cards().clear());
        assertTrue(base.cards().isEmpty());
        assertEquals(0, base.compareTo(held));
    }

    @Test
    void flushBeatsStraightAndEachSuccessiveCardBreaksFlushTies() {
        HoldemRank.Score base = HoldemRank.best(null, suited("clubs", 14, 12, 10, 8, 6));
        assertTrue(base.compareTo(null) > 0);
        assertEquals(0, base.compareTo(HoldemRank.best(null, suited("hearts", 14, 12, 10, 8, 6))));
        List<List<Card>> weakerHands = List.of(
                mixed(14, 13, 12, 11, 10),
                suited("hearts", 13, 12, 11, 10, 8),
                suited("hearts", 14, 11, 10, 9, 8),
                suited("hearts", 14, 12, 9, 8, 7),
                suited("hearts", 14, 12, 10, 7, 6),
                suited("hearts", 14, 12, 10, 8, 5));
        for (List<Card> weakerHand : weakerHands) {
            HoldemRank.Score weaker = HoldemRank.best(null, weakerHand);
            assertTrue(base.compareTo(weaker) > 0);
            assertTrue(weaker.compareTo(base) < 0);
        }
        assertTrue(HoldemRank.Score.none().compareTo(base) < 0);
    }

    @Test
    void missingBlankOrMismatchedSuitsDoNotMakeAFlush() {
        for (String missingSuit : Arrays.asList(null, "", " ")) {
            assertScore(HoldemRank.best(null, suited(missingSuit, 14, 10, 8, 4, 2)),
                    0, 14, 10, 8, 4, 2);
        }
        List<Card> cards = new ArrayList<>(suited("clubs", 14, 10, 8, 4, 2));
        cards.set(4, card(2, null, 4));
        assertScore(HoldemRank.best(null, cards), 0, 14, 10, 8, 4, 2);
        cards.set(4, card(2, "hearts", 4));
        assertScore(HoldemRank.best(null, cards), 0, 14, 10, 8, 4, 2);
    }

    @Test
    void usesGameRankMappingForBothScoreAndCardOrder() {
        String game = "holdem-rank-test";
        Map<Integer, Integer> previous = Cache.gameRankValues.put(game, Map.of(1, 14));
        try {
            List<Card> cards = mixed(1, 10, 11, 12, 13);
            HoldemRank.Score result = HoldemRank.best(game, cards);
            assertScore(result, 4, 14, 0, 0, 0, 0);
            assertEquals(List.of(cards.get(0), cards.get(4), cards.get(3), cards.get(2), cards.get(1)),
                    result.cards());
        } finally {
            if (previous == null) {
                Cache.gameRankValues.remove(game);
            } else {
                Cache.gameRankValues.put(game, previous);
            }
        }
    }

    @Test
    void pairsCountWhateverValueTheConfigGivesARank() {
        // Without rank-values an ace keeps its catalogue rank of one.
        assertScore(HoldemRank.best(null, mixed(1, 1, 9, 7, 5)), 1, 1, 9, 7, 5, 0);
        String game = "holdem-rank-high-ace";
        Map<Integer, Integer> previous = Cache.gameRankValues.put(game, Map.of(1, 20));
        try {
            HoldemRank.Score aces = HoldemRank.best(game, mixed(1, 1, 9, 7, 5));
            assertScore(aces, 1, 20, 9, 7, 5, 0);
            assertTrue(aces.compareTo(HoldemRank.best(game, mixed(13, 13, 9, 7, 5))) > 0);
            assertScore(HoldemRank.best(game, mixed(1, 1, 1, 13, 13)), 6, 20, 13, 0, 0, 0);
        } finally {
            if (previous == null) {
                Cache.gameRankValues.remove(game);
            } else {
                Cache.gameRankValues.put(game, previous);
            }
        }
    }

    @Test
    void fiveOfAKindFromTheConfigScoresAsFourOfAKind() {
        // A rank-values map that counts every picture card as a ten, as a blackjack-style set might.
        String game = "holdem-rank-flat-faces";
        Map<Integer, Integer> previous = Cache.gameRankValues.put(game, Map.of(11, 10, 12, 10, 13, 10));
        try {
            HoldemRank.Score five = HoldemRank.best(game, mixed(10, 11, 12, 13, 10));
            assertScore(five, 7, 10, 0, 0, 0, 0);
            assertTrue(five.compareTo(HoldemRank.best(game, mixed(9, 9, 9, 9, 14))) > 0);
            assertTrue(five.compareTo(HoldemRank.best(game, mixed(10, 11, 12, 13, 14))) < 0,
                    "the same four tens with an ace kicker is the stronger hand");
            // A card set that lists one card twice deals the same rank and suit five times.
            assertScore(HoldemRank.best(null, suited("clubs", 6, 6, 6, 6, 6)), 7, 6, 0, 0, 0, 0);
        } finally {
            if (previous == null) {
                Cache.gameRankValues.remove(game);
            } else {
                Cache.gameRankValues.put(game, previous);
            }
        }
    }

    private static Arguments hand(String name, List<Card> cards, int... keys) {
        return Arguments.of(name, cards, keys);
    }

    private static void assertScore(HoldemRank.Score actual, int... keys) {
        assertEquals(0, actual.compareTo(score(keys)), "score differs from expected keys " + Arrays.toString(keys));
    }

    private static HoldemRank.Score score(int[] keys) {
        return HoldemRank.Score.of(keys[0], keys[1], keys[2], keys[3], keys[4], keys[5]);
    }

    private static List<Card> mixed(int... ranks) {
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < ranks.length; i++) {
            cards.add(card(ranks[i], i % 2 == 0 ? "clubs" : "hearts", i));
        }
        return cards;
    }

    private static List<Card> suited(String suit, int... ranks) {
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < ranks.length; i++) {
            cards.add(card(ranks[i], suit, i));
        }
        return cards;
    }

    private static Card card(int rank, String suit, int index) {
        return new Card(suit + "-" + rank + "-" + index, suit, rank, false, "item");
    }
}
