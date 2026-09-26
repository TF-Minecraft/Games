package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;

/**
 * Hold'em 5-card ranking. Catalog ranks stay on {@link Card}; ace-high uses game rank-values.
 */
final class HoldemRank {

    private HoldemRank() {}

    static final class Score implements Comparable<Score> {

        private final int[] keys;
        private final List<Card> cards;

        private Score(int[] keys, List<Card> cards) {
            this.keys = keys;
            this.cards = cards;
        }

        static Score none() {
            return new Score(new int[] {-1, 0, 0, 0, 0, 0}, List.of());
        }

        static Score of(int category, int a, int b, int c, int d, int e) {
            return new Score(new int[] {category, a, b, c, d, e}, List.of());
        }

        /**
         * The same score, remembering which five cards made it, high card first. Copied because
         * the search reuses one array for every combination it tries.
         */
        Score withCards(String gameId, Card[] five) {
            List<Card> held = new ArrayList<>(Arrays.asList(five));
            held.sort(Comparator.comparingInt(
                    (Card card) -> Cache.sortValue(gameId, card.getRank())).reversed());
            return new Score(keys, List.copyOf(held));
        }

        /** The five that scored, high card first, or empty when there was no hand to score. */
        List<Card> cards() {
            return cards;
        }

        @Override
        public int compareTo(Score other) {
            if (other == null) {
                return 1;
            }
            for (int i = 0; i < keys.length; i++) {
                int cmp = Integer.compare(keys[i], other.keys[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
    }

    static Score best(String gameId, List<Card> cards) {
        List<Card> usable = new ArrayList<>();
        if (cards != null) {
            for (Card card : cards) {
                if (card != null && !card.isJoker()) {
                    usable.add(card);
                }
            }
        }
        if (usable.size() < 5) {
            return Score.none();
        }
        Score best = Score.none();
        Card[] winner = null;
        int n = usable.size();
        Card[] pick = new Card[5];
        for (int a = 0; a < n; a++) {
            pick[0] = usable.get(a);
            for (int b = a + 1; b < n; b++) {
                pick[1] = usable.get(b);
                for (int c = b + 1; c < n; c++) {
                    pick[2] = usable.get(c);
                    for (int d = c + 1; d < n; d++) {
                        pick[3] = usable.get(d);
                        for (int e = d + 1; e < n; e++) {
                            pick[4] = usable.get(e);
                            Score next = ofFive(gameId, pick);
                            if (next.compareTo(best) > 0) {
                                best = next;
                                winner = pick.clone();
                            }
                        }
                    }
                }
            }
        }
        // At least one five-card combination exists, and its category beats Score.none().
        return best.withCards(gameId, winner);
    }

    private static Score ofFive(String gameId, Card[] five) {
        int[] vals = new int[5];
        String suit0 = five[0].getSuit();
        boolean flush = suit0 != null && !suit0.isBlank();
        for (int i = 0; i < 5; i++) {
            vals[i] = Cache.sortValue(gameId, five[i].getRank());
            if (flush && (five[i].getSuit() == null || !suit0.equals(five[i].getSuit()))) {
                flush = false;
            }
        }
        Arrays.sort(vals);
        int[] desc = new int[] {vals[4], vals[3], vals[2], vals[1], vals[0]};
        int straightHigh = straightHigh(vals);
        if (flush && straightHigh > 0) {
            return Score.of(8, straightHigh, 0, 0, 0, 0);
        }
        // Rank values come from config, so group whatever values the cards carry: biggest group
        // first, higher value first within a size. The groups are then the score's tie-breakers.
        Map<Integer, Integer> counts = new HashMap<>();
        for (int v : vals) {
            counts.merge(v, 1, Integer::sum);
        }
        List<Integer> groups = new ArrayList<>(counts.keySet());
        groups.sort(Comparator.comparingInt((Integer v) -> counts.get(v)).thenComparingInt(v -> v).reversed());
        int largest = counts.get(groups.getFirst());
        int category;
        // Five of a kind is possible when the config maps two ranks to one value, or a set lists a
        // card twice. It scores as quads, as it always has, rather than falling through to high card.
        if (largest >= 4) {
            category = 7;
        } else if (largest == 3 && groups.size() == 2) {
            category = 6;
        } else if (flush) {
            return Score.of(5, desc[0], desc[1], desc[2], desc[3], desc[4]);
        } else if (straightHigh > 0) {
            return Score.of(4, straightHigh, 0, 0, 0, 0);
        } else if (largest == 3) {
            category = 3;
        } else if (largest == 2) {
            category = groups.size() == 3 ? 2 : 1;
        } else {
            category = 0;
        }
        int[] keys = new int[5];
        for (int i = 0; i < groups.size(); i++) {
            keys[i] = groups.get(i);
        }
        return Score.of(category, keys[0], keys[1], keys[2], keys[3], keys[4]);
    }

    private static int straightHigh(int[] sortedAsc) {
        if (sortedAsc[0] == 2 && sortedAsc[1] == 3 && sortedAsc[2] == 4 && sortedAsc[3] == 5
                && sortedAsc[4] == 14) {
            return 5;
        }
        for (int i = 1; i < 5; i++) {
            if (sortedAsc[i] != sortedAsc[i - 1] + 1) {
                return 0;
            }
        }
        return sortedAsc[4];
    }
}
