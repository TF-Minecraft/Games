package net.tfminecraft.games.wager;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks an exact set of coins to reach a target value.
 *
 * <p>Taking the largest coin that fits and repeating strands a remainder whenever the
 * denominations are not canonical, and table stakes carry any unit at all: {@code wager.items}
 * entries declare their own value and a loot wager can be any number of denars. So this
 * searches for a combination that lands exactly instead of guessing at one.
 *
 * <p>Pure arithmetic on (unit, count) pairs, with no dependency on Bukkit or the ledger, so
 * the same code can plan a take out of a player inventory and a take out of a felt bucket.
 * Those two used to disagree, which is how a refund could quietly hand back less than it took.
 */
public final class CoinPlanner {

    /** One kind of coin available to spend: its denar value, and how many are on hand. */
    public record Slot(int unit, int count) {

        public Slot {
            unit = Math.max(0, unit);
            count = Math.max(0, count);
        }

        public int value() {
            return unit * count;
        }
    }

    private CoinPlanner() {}

    /**
     * How many to take from each slot to make exactly {@code target}, in the order the slots
     * were given, or null when no combination reaches it. Earlier slots are spent first where
     * there is a choice, so callers control which coins go before which.
     */
    public static int[] exact(List<Slot> slots, int target) {
        if (slots == null || slots.isEmpty() || target < 1) {
            return null;
        }
        if (available(slots) < target) {
            return null;
        }
        int[] picks = new int[slots.size()];
        if (search(slots, 0, target, picks, new HashSet<>())) {
            return picks;
        }
        return null;
    }

    /**
     * The most that can be made without going over {@code target}. Used to tell a player how
     * far short their coins fall rather than only that they fell short.
     */
    public static int best(List<Slot> slots, int target) {
        if (target < 1) {
            return 0;
        }
        int held = available(slots);
        if (held < 1) {
            return 0;
        }
        // One bit per denar, so a silly target would mean a silly amount of memory. Above the
        // cap, take the biggest coins that fit and report that: it is only used for messages.
        int ceiling = Math.min(target, held);
        if (ceiling > 4_000_000) {
            return greedy(slots, target);
        }
        target = ceiling;
        // Bit n set means n denars is reachable. Doubling the counts keeps the shift count
        // logarithmic in how many coins are held rather than linear.
        BigInteger mask = BigInteger.ONE.shiftLeft(target + 1).subtract(BigInteger.ONE);
        BigInteger reach = BigInteger.ONE;
        for (Slot slot : slots) {
            if (slot.unit() < 1 || slot.count() < 1) {
                continue;
            }
            int left = Math.min(slot.count(), target / slot.unit());
            int step = 1;
            while (left > 0) {
                int take = Math.min(step, left);
                reach = reach.or(reach.shiftLeft(slot.unit() * take)).and(mask);
                left -= take;
                step *= 2;
            }
        }
        return reach.bitLength() - 1;
    }

    private static int greedy(List<Slot> slots, int target) {
        int left = target;
        for (Slot slot : biggestFirst(slots)) {
            if (slot.unit() < 1 || left < slot.unit()) {
                continue;
            }
            left -= Math.min(slot.count(), left / slot.unit()) * slot.unit();
        }
        return target - left;
    }

    /** Total denars across every slot, which is the ceiling on anything that can be planned. */
    public static int available(List<Slot> slots) {
        long sum = 0;
        if (slots != null) {
            for (Slot slot : slots) {
                sum += (long) slot.unit() * slot.count();
            }
        }
        return (int) Math.min(sum, Integer.MAX_VALUE);
    }

    /** Slots ordered biggest coin first, so a plan hands over as few items as it can. */
    public static List<Slot> biggestFirst(List<Slot> slots) {
        List<Slot> out = new ArrayList<>(slots);
        out.sort((a, b) -> Integer.compare(b.unit(), a.unit()));
        return out;
    }

    private static boolean search(List<Slot> slots, int index, int left, int[] picks, Set<Long> dead) {
        if (left == 0) {
            return true;
        }
        if (index >= slots.size()) {
            return false;
        }
        long key = ((long) index << 32) | left;
        if (dead.contains(key)) {
            return false;
        }
        Slot slot = slots.get(index);
        int unit = slot.unit();
        int max = unit > 0 ? Math.min(slot.count(), left / unit) : 0;
        for (int take = max; take >= 0; take--) {
            picks[index] = take;
            if (search(slots, index + 1, left - take * unit, picks, dead)) {
                return true;
            }
        }
        picks[index] = 0;
        dead.add(key);
        return false;
    }
}
