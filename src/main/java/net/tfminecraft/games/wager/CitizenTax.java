package net.tfminecraft.games.wager;

import org.bukkit.entity.Player;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.loaders.MessageLoader;

/**
 * Citizen tax on net coin profit from a table round. Uses DenarEconomy {@code doTaxes} so
 * SimpleFactions records the levy the same way as any other earned income.
 */
public final class CitizenTax {

    /** Chips to withhold and the tax figure for the player message. */
    public record Levy(int chips, double tax) {

        public static final Levy NONE = new Levy(0, 0.0);
    }

    private CitizenTax() {}

    /**
     * Work out citizen tax on {@code moneyProfit} and fire {@code PlayerEarnMoneyEvent} once.
     * Returns zero when DenarEconomy is down or there is nothing to tax.
     */
    public static Levy levy(Player player, int moneyProfit) {
        if (player == null || moneyProfit < 1 || !ChipItems.denarEconomyPresent()) {
            return Levy.NONE;
        }
        double tax = DenarEconomy.getMoneyManager().doTaxes(player.getName(), moneyProfit);
        return new Levy(chipsDue(moneyProfit, tax), tax);
    }

    /**
     * How many denars of chips to withhold from a payout for citizen tax on {@code moneyProfit}.
     */
    public static int due(Player player, int moneyProfit) {
        return levy(player, moneyProfit).chips();
    }

    /** Same line as picking up money: {@code (%tax% in tax)} from DenarEconomy messages. */
    public static void tell(Player player, double tax) {
        if (player == null || !player.isOnline() || tax <= 0) {
            return;
        }
        MessageLoader.send(player, "money.tax", "tax", tax);
    }

    /**
     * Whole denars to peel off a payout, capped at the profit being taxed. Matches DenarEconomy
     * rounding before chips move.
     */
    static int chipsDue(int moneyProfit, double tax) {
        if (moneyProfit < 1 || tax <= 0 || Double.isNaN(tax) || Double.isInfinite(tax)) {
            return 0;
        }
        long rounded = Math.round(tax);
        if (rounded < 1) {
            return 0;
        }
        return (int) Math.min(moneyProfit, rounded);
    }
}
