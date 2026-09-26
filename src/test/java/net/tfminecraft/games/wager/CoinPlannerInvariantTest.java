package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.wager.CoinPlanner.Slot;

class CoinPlannerInvariantTest {
    @Test
    void smallInventoriesAgreeWithExhaustiveEnumerationAndNeverOverspend() {
        Random cases = new Random(724931L);
        for (int sample = 0; sample < 200; sample++) {
            List<Slot> slots = new ArrayList<>();
            for (int i = 0; i < 4; i++) slots.add(new Slot(cases.nextInt(10), cases.nextInt(4)));
            TreeSet<Integer> possible = new TreeSet<>();
            // Independent oracle: enumerate the finite Cartesian product of coin counts.
            for (int a = 0; a <= slots.get(0).count(); a++)
                for (int b = 0; b <= slots.get(1).count(); b++)
                    for (int c = 0; c <= slots.get(2).count(); c++)
                        for (int d = 0; d <= slots.get(3).count(); d++)
                            possible.add(a * slots.get(0).unit() + b * slots.get(1).unit()
                                    + c * slots.get(2).unit() + d * slots.get(3).unit());
            for (int target = 1; target <= 40; target++) {
                String context = slots + " target=" + target;
                assertEquals(possible.floor(target).intValue(), CoinPlanner.best(slots, target), context);
                int[] picks = CoinPlanner.exact(slots, target);
                if (!possible.contains(target)) {
                    assertNull(picks, context);
                    continue;
                }
                assertNotNull(picks, context);
                assertEquals(slots.size(), picks.length);
                int spent = 0;
                for (int i = 0; i < picks.length; i++) {
                    assertTrue(picks[i] >= 0 && picks[i] <= slots.get(i).count(), context);
                    spent += picks[i] * slots.get(i).unit();
                }
                assertEquals(target, spent, context);
            }
        }
    }

    @Test
    void callerOrderBreaksEquivalentCoinChoicesWithoutMutatingInventory() {
        List<Slot> original = new ArrayList<>(List.of(new Slot(5, 1), new Slot(5, 1), new Slot(1, 5)));
        List<Slot> snapshot = List.copyOf(original);
        assertArrayEquals(new int[] {1, 0, 0}, CoinPlanner.exact(original, 5));
        assertEquals(snapshot, original);
        List<Slot> sorted = CoinPlanner.biggestFirst(original);
        sorted.clear();
        assertEquals(snapshot, original);
    }

    @Test
    void largeBalancesSaturateSafelyAndBoundedEstimateDoesNotExceedRequestedAmount() {
        List<Slot> large = List.of(new Slot(3_000_000, 1000), new Slot(2_000_000, 1000));
        assertEquals(Integer.MAX_VALUE, CoinPlanner.available(large));
        assertEquals(5_000_000, CoinPlanner.best(large, 5_000_000));
        assertEquals(3_000_000, CoinPlanner.best(large, 4_500_000));
        assertArrayEquals(new int[] {0, 2}, CoinPlanner.exact(large, 4_000_000));
    }
}
