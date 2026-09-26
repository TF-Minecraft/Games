package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.table.Table;

/**
 * One owner's money on the felt: a player's box, the pot, or the house tray. The chips drawn
 * on top of it are a picture of this and hold nothing themselves.
 */
public final class BucketAccount implements MoneyAccount {

    private final Table table;
    private final UUID owner;
    private Location anchor;
    private boolean placed;

    BucketAccount(Table table, UUID owner) {
        this.table = table;
        this.owner = owner;
    }

    /** Where to draw these chips if the bucket has not been given a spot yet. */
    public BucketAccount at(Location where) {
        this.anchor = where;
        this.placed = false;
        return this;
    }

    /**
     * A spot somebody chose, so these coins are drawn exactly there and stay a heap of their own.
     * Money the house puts down uses {@link #at} instead, and keeps the tray layout.
     */
    public BucketAccount placedAt(Location where) {
        this.anchor = where;
        this.placed = where != null;
        return this;
    }

    public UUID owner() {
        return owner;
    }

    @Override
    public String label() {
        return isTray() ? "tray" : "felt";
    }

    @Override
    public int available() {
        return table.ledger().total(owner);
    }

    @Override
    public Withdrawal planTake(int denars, ItemStack template) {
        if (denars < 1) {
            return null;
        }
        // The ledger refuses empty or worthless stakes and every take tidies what it empties, so
        // each live stake can be spent.
        List<Stake> usable = new ArrayList<>(table.ledger().liveStakes(owner));
        // Biggest coins first so a take hands over as few pieces as it can, but the search
        // behind this will still find a combination that only the smaller ones can make.
        usable.sort((a, b) -> Integer.compare(b.unit(), a.unit()));
        List<CoinPlanner.Slot> slots = new ArrayList<>();
        for (Stake stake : usable) {
            slots.add(new CoinPlanner.Slot(stake.unit(), stake.count()));
        }
        int[] picks = CoinPlanner.exact(slots, denars);
        return picks == null ? null : new BucketWithdrawal(usable, picks, denars);
    }

    @Override
    public Withdrawal planTakeAll(Integer street) {
        List<Stake> usable = new ArrayList<>();
        int[] picks;
        List<Stake> live = table.ledger().liveStakes(owner);
        for (Stake stake : live) {
            if (street == null || stake.streetId() == street) {
                usable.add(stake);
            }
        }
        picks = new int[usable.size()];
        int total = 0;
        for (int i = 0; i < usable.size(); i++) {
            picks[i] = usable.get(i).count();
            total += usable.get(i).value();
        }
        if (total < 1) {
            return null;
        }
        return new BucketWithdrawal(usable, picks, total);
    }

    @Override
    public int largestTakeUpTo(int denars, ItemStack template) {
        if (denars < 1) {
            return 0;
        }
        List<CoinPlanner.Slot> slots = new ArrayList<>();
        for (Stake stake : table.ledger().liveStakes(owner)) {
            slots.add(new CoinPlanner.Slot(stake.unit(), stake.count()));
        }
        return CoinPlanner.best(slots, denars);
    }

    @Override
    public int accept(List<Stake> stakes) {
        if (stakes == null || stakes.isEmpty()) {
            return 0;
        }
        if (anchor != null && !table.ledger().hasAnchor(owner)) {
            table.ledger().setAnchor(owner, anchor.getX(), anchor.getZ());
        }
        // The spot the money was put on, so these chips are drawn there rather than on a layout.
        Double spotX = placed ? anchor.getX() : null;
        Double spotZ = placed ? anchor.getZ() : null;
        int took = 0;
        for (Stake stake : stakes) {
            if (stake.count() < 1) {
                continue;
            }
            table.ledger().put(owner, stake, spotX, spotZ);
            took += stake.value();
        }
        return took;
    }

    @Override
    public boolean onFelt() {
        return true;
    }

    @Override
    public UUID feltOwner() {
        return owner;
    }

    @Override
    public UUID flightTarget() {
        return isTray() ? null : owner;
    }

    @Override
    public boolean isTray() {
        return owner.equals(table.getId());
    }

    /** Splits the planned counts off the very stakes that were measured, so nothing drifts. */
    private final class BucketWithdrawal implements Withdrawal {

        private final List<Stake> sources;
        private final int[] counts;
        private final int denars;

        private BucketWithdrawal(List<Stake> sources, int[] counts, int denars) {
            this.sources = sources;
            this.counts = counts;
            this.denars = denars;
        }

        @Override
        public int denars() {
            return denars;
        }

        @Override
        public List<Stake> preview() {
            List<Stake> out = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                Stake stake = sources.get(i);
                if (counts[i] > 0) {
                    out.add(new Stake(stake.item(), stake.typeKey(), stake.unit(), counts[i],
                            stake.streetId()));
                }
            }
            return out;
        }

        @Override
        public List<Stake> take() {
            List<Stake> out = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                if (counts[i] < 1) {
                    continue;
                }
                Stake part = sources.get(i).split(counts[i]);
                if (part.count() > 0) {
                    out.add(part);
                }
            }
            table.ledger().tidy();
            return out;
        }
    }
}
