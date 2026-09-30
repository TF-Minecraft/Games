package net.tfminecraft.games.game;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Tournament chips are counters, never economy items. The table ledger holds the prize. */
public final class PokerTournament {
    private int buyIn;
    private int startingChips = 1000;
    private int maxRebuys;
    private int ante;
    private int blindMinutes;
    private long startedAt;
    private final Map<UUID, Integer> stacks = new LinkedHashMap<>();
    private final Map<UUID, Integer> rebuys = new LinkedHashMap<>();
    private final Map<UUID, Integer> invested = new LinkedHashMap<>();
    private final Map<UUID, Integer> street = new LinkedHashMap<>();

    public int buyIn() { return buyIn; }
    public int startingChips() { return startingChips; }
    public int maxRebuys() { return maxRebuys; }
    public int ante() { return ante; }
    public int blindMinutes() { return blindMinutes; }
    public boolean enabled() { return buyIn > 0; }
    public boolean started() { return startedAt != 0; }
    public boolean registered(UUID id) { return stacks.containsKey(id); }
    public int stack(UUID id) { return stacks.getOrDefault(id, 0); }
    public int contribution(UUID id) { return street.getOrDefault(id, 0); }
    public Map<UUID, Integer> invested() { return Map.copyOf(invested); }
    public boolean occupied() { return !stacks.isEmpty(); }

    public void configure(int buyIn, int chips, int rebuys, int ante, int minutes) {
        if (occupied() || buyIn < 0 || buyIn > 1_000_000 || chips < 1 || rebuys < 0 || ante < 0 || minutes < 0
                || chips > 1_000_000 || rebuys > 100 || minutes > 10_080) {
            throw new IllegalArgumentException("Invalid or occupied tournament");
        }
        this.buyIn = buyIn;
        this.startingChips = chips;
        this.maxRebuys = rebuys;
        this.ante = ante;
        this.blindMinutes = minutes;
    }

    public boolean canBuy(UUID id) {
        return enabled() && ((!registered(id) && !started())
                || (registered(id) && stack(id) == 0 && rebuys.getOrDefault(id, 0) < maxRebuys));
    }

    public void buy(UUID id) {
        if (!canBuy(id)) throw new IllegalStateException("Buy-in refused");
        if (registered(id)) rebuys.merge(id, 1, Integer::sum);
        stacks.put(id, startingChips);
    }

    public void start(long now) { if (!started()) startedAt = now; }
    public int blind(int base, long now) {
        if (base == 0 || blindMinutes == 0 || !started()) return base;
        long level = Math.min(20, Math.max(0, now - startedAt) / (blindMinutes * 60_000L));
        return (int) Math.min(1_000_000L, (long) base << level);
    }
    public int bet(UUID id, int amount) {
        int paid = Math.min(stack(id), Math.max(0, amount));
        if (paid > 0) {
            stacks.put(id, stack(id) - paid);
            invested.merge(id, paid, Math::addExact);
            street.merge(id, paid, Math::addExact);
        }
        return paid;
    }
    public void nextStreet() { street.clear(); }
    public void award(UUID id, int amount) { stacks.merge(id, amount, Math::addExact); }
    public void finishHand() { invested.clear(); street.clear(); }
    public void abortHand() {
        invested.forEach((id, amount) -> { if (registered(id)) award(id, amount); });
        finishHand();
    }
    public void remove(UUID id) { stacks.remove(id); rebuys.remove(id); }
    public void reset() {
        stacks.clear(); rebuys.clear(); finishHand(); startedAt = 0;
    }
}
