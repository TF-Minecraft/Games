package net.tfminecraft.games.wager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coin money a player put on the felt this round and coin money paid back to them.
 * Loot stakes are ignored via {@link ChipItems#moneyValue}. Cleared when the session ends.
 */
public final class RoundMoney {

    private final Map<UUID, Integer> moneyIn = new HashMap<>();
    private final Map<UUID, Integer> moneyOut = new HashMap<>();
    private final Map<UUID, Integer> wonProfit = new HashMap<>();

    /** Record one successful leg from {@link MoneyTx}. */
    public void recordLeg(MoneyAccount from, MoneyAccount to, List<Stake> coins) {
        int value = ChipItems.moneyValue(coins);
        if (value < 1) {
            return;
        }
        if (from instanceof PlayerAccount && to.onFelt()) {
            // A player's pockets can only give money while they are online, so the owner is known.
            moneyIn.merge(from.flightTarget(), value, Integer::sum);
        }
        if (to instanceof PlayerAccount) {
            UUID owner = to.flightTarget();
            if (owner != null) {
                moneyOut.merge(owner, value, Integer::sum);
            }
        }
    }

    public int moneyIn(UUID owner) {
        return moneyIn.getOrDefault(owner, 0);
    }

    public int moneyOut(UUID owner) {
        return moneyOut.getOrDefault(owner, 0);
    }

    /** Net coin profit this round: paid out minus staked, never negative. */
    public int moneyProfit(UUID owner) {
        return Math.max(0, moneyOut(owner) - moneyIn(owner));
    }

    /**
     * Coin profit in one payout after stake is returned. Uses running {@link #moneyIn} /
     * {@link #moneyOut} so multi-street pots only tax what is left after earlier payouts.
     */
    public int taxableProfit(UUID owner, int payout) {
        int stakeBack = Math.min(payout, Math.max(0, moneyIn(owner) - moneyOut(owner)));
        return Math.max(0, payout - stakeBack);
    }

    /** Remember a pre-tax profit payout so the round can be announced once at the end. */
    public void recordProfit(UUID owner, int profit) {
        if (profit < 1) {
            return;
        }
        wonProfit.merge(owner, profit, Integer::sum);
    }

    /** Pre-tax coin profit per player so far this round, the same figure citizen tax is taken on. */
    public Map<UUID, Integer> wonProfit() {
        return Map.copyOf(wonProfit);
    }

    public void clear() {
        moneyIn.clear();
        moneyOut.clear();
        wonProfit.clear();
    }
}
