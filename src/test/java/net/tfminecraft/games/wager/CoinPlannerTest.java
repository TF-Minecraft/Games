package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.tfminecraft.games.wager.CoinPlanner.Slot;

class CoinPlannerTest {

    /** The live coins.yml, whole denars only: pouch, stack, handful, gold coin. */
    private static List<Slot> live(int pouches, int stacks, int handfuls, int coins) {
        return List.of(new Slot(100, pouches), new Slot(10, stacks), new Slot(5, handfuls),
                new Slot(1, coins));
    }

    @Test
    void takesTheBiggestCoinsThatFit() {
        int[] picks = CoinPlanner.exact(live(1, 5, 1, 3), 158);
        assertNotNull(picks);
        assertArrayEquals(new int[] {1, 5, 1, 3}, picks);
    }

    @Test
    void skipsACoinTooBigForTheTarget() {
        int[] picks = CoinPlanner.exact(live(1, 5, 0, 0), 50);
        assertNotNull(picks);
        assertArrayEquals(new int[] {0, 5, 0, 0}, picks);
    }

    @Test
    void refusesWhenTheOnlyCoinIsTooBig() {
        assertNull(CoinPlanner.exact(live(1, 0, 0, 0), 50));
    }

    @Test
    void refusesTheTenToHundredGap() {
        // Two pouches and nothing smaller cannot make 50, which is the common double failure.
        assertNull(CoinPlanner.exact(live(2, 0, 0, 0), 50));
    }

    /** Greedy takes the 6, strands 4, and fails. An exact subset was sitting right there. */
    @Test
    void findsASubsetGreedyWouldStrand() {
        List<Slot> odd = List.of(new Slot(6, 1), new Slot(5, 2));
        int[] picks = CoinPlanner.exact(odd, 10);
        assertNotNull(picks);
        assertArrayEquals(new int[] {0, 2}, picks);
    }

    /** A loot wager can be any value, so a bucket take has to cope with units like these. */
    @Test
    void findsASubsetBehindABiggerCoin() {
        List<Slot> odd = List.of(new Slot(37, 1), new Slot(9, 1), new Slot(4, 1));
        int[] picks = CoinPlanner.exact(odd, 13);
        assertNotNull(picks);
        assertArrayEquals(new int[] {0, 1, 1}, picks);
    }

    @Test
    void refusesWhenNoSubsetLands() {
        assertNull(CoinPlanner.exact(List.of(new Slot(5, 2)), 6));
    }

    @Test
    void handlesEmptyAndNonsenseInput() {
        assertNull(CoinPlanner.exact(List.of(), 10));
        assertNull(CoinPlanner.exact(null, 10));
        assertNull(CoinPlanner.exact(live(1, 1, 1, 1), 0));
        assertNull(CoinPlanner.exact(live(1, 1, 1, 1), -5));
    }

    @Test
    void bestReportsHowFarShortTheCoinsFall() {
        // Two pouches against a 50 target: nothing under 100, so nothing at all.
        assertEquals(0, CoinPlanner.best(live(2, 0, 0, 0), 50));
        // Four stacks of ten reach 40 of the 50 needed.
        assertEquals(40, CoinPlanner.best(live(0, 4, 0, 0), 50));
        // Exact plans report the target back.
        assertEquals(50, CoinPlanner.best(live(0, 5, 0, 0), 50));
    }

    @Test
    void bestNeverExceedsTheTarget() {
        assertEquals(9, CoinPlanner.best(List.of(new Slot(3, 100)), 10));
        assertEquals(0, CoinPlanner.best(List.of(new Slot(11, 100)), 10));
    }

    @Test
    void bestWithNothingToReachIsZero() {
        assertEquals(0, CoinPlanner.best(live(1, 1, 1, 1), 0));
        assertEquals(0, CoinPlanner.best(live(1, 1, 1, 1), -5));
    }

    @Test
    void worthlessCoinsAreIgnoredWhenEstimatingVeryLargeAmounts() {
        List<Slot> slots = List.of(new Slot(0, 5), new Slot(1_000_000, 5));
        assertEquals(4_000_000, CoinPlanner.best(slots, 4_500_000));
    }

    @Test
    void bestCopesWithManyCoins() {
        assertEquals(5000, CoinPlanner.best(live(0, 0, 0, 20000), 5000));
    }

    @Test
    void availableSumsEverySlot() {
        assertEquals(158, CoinPlanner.available(live(1, 5, 1, 3)));
        assertEquals(0, CoinPlanner.available(List.of()));
        assertEquals(0, CoinPlanner.available(null));
    }

    @Test
    void slotClampsNegatives() {
        assertEquals(0, new Slot(-5, 3).unit());
        assertEquals(0, new Slot(5, -3).count());
        assertEquals(15, new Slot(5, 3).value());
    }

    @Test
    void biggestFirstOrdersByUnit() {
        List<Slot> sorted = CoinPlanner.biggestFirst(
                List.of(new Slot(1, 1), new Slot(100, 1), new Slot(10, 1)));
        assertEquals(100, sorted.get(0).unit());
        assertEquals(10, sorted.get(1).unit());
        assertEquals(1, sorted.get(2).unit());
    }

    /** A big target over many distinct units must not blow up the search. */
    @Test
    void staysFastOnAWideSpread() {
        List<Slot> wide = List.of(new Slot(97, 40), new Slot(53, 40), new Slot(31, 40),
                new Slot(17, 40), new Slot(7, 40), new Slot(3, 40), new Slot(1, 40));
        int[] picks = CoinPlanner.exact(wide, 4321);
        assertNotNull(picks);
        int total = 0;
        List<Slot> slots = wide;
        for (int i = 0; i < picks.length; i++) {
            total += picks[i] * slots.get(i).unit();
        }
        assertEquals(4321, total);
    }
}
