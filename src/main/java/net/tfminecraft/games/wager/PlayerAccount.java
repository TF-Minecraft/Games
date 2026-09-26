package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A player's pockets. Coins are picked out before any of them leave, so a bet that cannot be
 * made exactly takes nothing at all rather than swallowing what fits and refunding the rest.
 */
public final class PlayerAccount implements MoneyAccount {

    private final Player player;
    private final Predicate<ItemStack> allowed;
    private final Location dropAt;
    private final UUID id;
    private final int streetId;
    /** Set when the items are worth what they were wagered for rather than a coin value. */
    private final int declaredUnit;

    PlayerAccount(Player player, UUID id, Predicate<ItemStack> allowed, Location dropAt, int streetId) {
        this(player, id, allowed, dropAt, streetId, 0);
    }

    PlayerAccount(Player player, UUID id, Predicate<ItemStack> allowed, Location dropAt, int streetId,
            int declaredUnit) {
        this.player = player;
        this.id = id;
        this.allowed = allowed;
        this.dropAt = dropAt;
        this.streetId = streetId;
        this.declaredUnit = declaredUnit;
    }

    @Override
    public String label() {
        return "player";
    }

    @Override
    public int available() {
        return CoinPlanner.available(slots(groups()));
    }

    @Override
    public Withdrawal planTake(int denars, ItemStack template) {
        if (denars < 1 || player == null || !player.isOnline()) {
            return null;
        }
        List<Group> groups = groups();
        int[] picks = CoinPlanner.exact(slots(groups), denars);
        if (picks != null) {
            return new PlayerWithdrawal(groups, picks, denars, null);
        }
        return planWithChange(groups, denars);
    }

    @Override
    public Withdrawal planTakeAll(Integer street) {
        // Emptying a player's pockets is never something a table should do.
        return null;
    }

    @Override
    public int largestTakeUpTo(int denars, ItemStack template) {
        if (denars < 1 || player == null || !player.isOnline()) {
            return 0;
        }
        return CoinPlanner.best(slots(groups()), denars);
    }

    @Override
    public int accept(List<Stake> stakes) {
        if (stakes == null || stakes.isEmpty()) {
            return 0;
        }
        int given = 0;
        for (Stake stake : stakes) {
            giveItems(stake);
            given += stake.value();
        }
        return given;
    }

    @Override
    public UUID flightTarget() {
        return id;
    }

    /**
     * A pouch of 100 cannot pay a 50 bet, but what it breaks into can. Break the smallest coin
     * that would help and plan again. The break is value neutral, so it changes what the player
     * is carrying without changing what it is worth.
     */
    private Withdrawal planWithChange(List<Group> groups, int denars) {
        if (declaredUnit > 0) {
            // Loot wagered at a declared value has no denominations to break.
            return null;
        }
        // Smallest first: break as little as will do the job.
        for (int i = groups.size() - 1; i >= 0; i--) {
            Group group = groups.get(i);
            if (group.unit < 2) {
                continue;
            }
            List<ItemStack> into = ChipItems.change(group.one);
            if (into.isEmpty() || !roomFor(into)) {
                continue;
            }
            List<Group> after = withChange(groups, i, into);
            if (after == null) {
                continue;
            }
            int[] picks = CoinPlanner.exact(slots(after), denars);
            if (picks != null) {
                return new PlayerWithdrawal(after, picks, denars, new Change(group.one, into));
            }
        }
        return null;
    }

    /** The pools as they would be with one coin of {@code index} broken into {@code into}. */
    private List<Group> withChange(List<Group> groups, int index, List<ItemStack> into) {
        List<Group> out = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            int count = i == index ? group.count - 1 : group.count;
            if (count > 0) {
                out.add(new Group(group.unit, group.one, count));
            }
        }
        for (ItemStack made : into) {
            int unit = ChipItems.unitDenars(made);
            if (unit < 1 || (allowed != null && !allowed.test(made))) {
                // Change we could not stake would strand value, so leave the coin whole.
                return null;
            }
            merge(out, made, unit);
        }
        out.sort((a, b) -> Integer.compare(b.unit, a.unit));
        return out;
    }

    private static void merge(List<Group> groups, ItemStack made, int unit) {
        for (Group group : groups) {
            if (group.unit == unit && group.one.isSimilar(made)) {
                group.count += made.getAmount();
                return;
            }
        }
        ItemStack one = made.clone();
        one.setAmount(1);
        groups.add(new Group(unit, one, made.getAmount()));
    }

    /** Change has to land somewhere. Counts slots as if nothing merges, which is the safe way round. */
    private boolean roomFor(List<ItemStack> into) {
        int need = 0;
        for (ItemStack made : into) {
            int max = Math.max(1, made.getMaxStackSize());
            need += (made.getAmount() + max - 1) / max;
        }
        int free = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null) {
                free++;
            }
        }
        return free >= need;
    }

    private void giveItems(Stake stake) {
        int left = stake.count();
        int max = Math.max(1, stake.item().getMaxStackSize());
        while (left > 0) {
            int give = Math.min(max, left);
            ItemStack items = stake.item().clone();
            items.setAmount(give);
            if (player != null && player.isOnline()) {
                give(items);
            } else {
                drop(items);
            }
            left -= give;
        }
    }

    /** Into the player's inventory, with whatever does not fit dropped at their feet. */
    private void give(ItemStack items) {
        for (ItemStack rest : player.getInventory().addItem(items).values()) {
            drop(rest);
        }
    }

    /**
     * Only payees and the ground carry a drop spot, and a player's own pockets only receive while
     * they are online, so there is always somewhere to put the items.
     */
    private void drop(ItemStack items) {
        Location at = dropAt != null ? dropAt : player.getLocation();
        at.getWorld().dropItemNaturally(at, items);
    }

    /** Coins of the same kind and value, wherever they sit, counted as one pool. */
    private List<Group> groups() {
        List<Group> out = new ArrayList<>();
        if (player == null || !player.isOnline()) {
            return out;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null) {
                continue;
            }
            if (allowed != null && !allowed.test(stack)) {
                continue;
            }
            int unit = declaredUnit > 0 ? declaredUnit : ChipItems.unitDenars(stack);
            if (unit < 1) {
                continue;
            }
            merge(out, stack, unit);
        }
        // Biggest coins first, so paying a bet hands over as few items as it can.
        out.sort((a, b) -> Integer.compare(b.unit, a.unit));
        return out;
    }

    private static List<CoinPlanner.Slot> slots(List<Group> groups) {
        List<CoinPlanner.Slot> out = new ArrayList<>();
        for (Group group : groups) {
            out.add(new CoinPlanner.Slot(group.unit, group.count));
        }
        return out;
    }

    private static final class Group {
        private final int unit;
        private final ItemStack one;
        private int count;

        private Group(int unit, ItemStack one, int count) {
            this.unit = unit;
            this.one = one;
            this.count = count;
        }
    }

    /** One coin swapped for smaller ones of the same total value, carried out as part of the take. */
    private record Change(ItemStack coin, List<ItemStack> into) {}

    private final class PlayerWithdrawal implements Withdrawal {

        private final List<Group> groups;
        private final int[] counts;
        private final int denars;
        private final Change change;

        private PlayerWithdrawal(List<Group> groups, int[] counts, int denars, Change change) {
            this.groups = groups;
            this.counts = counts;
            this.denars = denars;
            this.change = change;
        }

        @Override
        public int denars() {
            return denars;
        }

        @Override
        public List<Stake> preview() {
            List<Stake> out = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                Group group = groups.get(i);
                if (counts[i] > 0) {
                    out.add(new Stake(group.one, ChipItems.typeKey(group.one), group.unit,
                            counts[i], streetId));
                }
            }
            return out;
        }

        @Override
        public List<Stake> take() {
            if (change != null && !makeChange()) {
                return new ArrayList<>();
            }
            List<Stake> out = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                Group group = groups.get(i);
                int want = counts[i];
                if (want < 1) {
                    continue;
                }
                int got = strip(group, want);
                if (got > 0) {
                    out.add(new Stake(group.one, ChipItems.typeKey(group.one), group.unit, got,
                            streetId));
                }
            }
            return out;
        }

        /** Swap the oversized coin for its smaller equivalents before anything is stripped. */
        private boolean makeChange() {
            ItemStack one = change.coin().clone();
            one.setAmount(1);
            if (strip(new Group(ChipItems.unitDenars(one), one, 1), 1) < 1) {
                return false;
            }
            for (ItemStack made : change.into()) {
                give(made.clone());
            }
            return true;
        }

        /** Pulls the counted items back out of wherever they are sitting now. */
        private int strip(Group group, int want) {
            int left = want;
            ItemStack[] contents = player.getInventory().getContents();
            for (int slot = 0; slot < contents.length && left > 0; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null || !group.one.isSimilar(stack)) {
                    continue;
                }
                int take = Math.min(left, stack.getAmount());
                if (stack.getAmount() <= take) {
                    player.getInventory().setItem(slot, null);
                } else {
                    stack.setAmount(stack.getAmount() - take);
                    player.getInventory().setItem(slot, stack);
                }
                left -= take;
            }
            return want - left;
        }
    }
}
