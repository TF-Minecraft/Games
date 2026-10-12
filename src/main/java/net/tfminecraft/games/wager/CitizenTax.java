package net.tfminecraft.games.wager;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.TaxPolicy;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;

/** Pure quote; only MoneyTx credits the actual chip tax that it withholds. */
public final class CitizenTax {
    public record Levy(int chips, double tax, CharacterRef earner, String account) {
        public static final Levy NONE = new Levy(0, 0.0, null, null);
        public Levy(int chips, double tax) { this(chips, tax, null, Denars.TAX_VOID); }
    }

    private CitizenTax() {}

    /** Null means defer payment until identity and taxation are available. */
    public static Levy levy(Player player, int moneyProfit) {
        return quote(player == null ? null : Characters.currentSession(player).map(session -> session.ref()).orElse(null), moneyProfit);
    }

    public static Levy quote(CharacterRef character, int moneyProfit) {
        if (moneyProfit < 1) return Levy.NONE;
        if (character == null || !Denars.taxReady()) return null;
        TaxPolicy policy = Bukkit.getServicesManager().load(TaxPolicy.class);
        if (policy == null) return Levy.NONE;
        long gross = moneyProfit * 100L;
        return policy.tax(character, gross).map(tax -> {
            if (tax.cents() > gross) throw new IllegalStateException("Tax exceeds the taxable profit");
            return new Levy(chipsDue(moneyProfit, tax.cents() / 100.0), tax.cents() / 100.0, character, tax.account());
        }).orElse(Levy.NONE);
    }

    public static int due(Player player, int moneyProfit) {
        Levy levy = levy(player, moneyProfit);
        return levy == null ? 0 : levy.chips();
    }

    public static void tell(Player player, double tax) {
        if (player == null || !player.isOnline() || tax <= 0) return;
        MessageLoader.send(player, "money.tax", "tax", tax);
    }

    static int chipsDue(int moneyProfit, double tax) {
        if (tax <= 0 || !Double.isFinite(tax)) return 0;
        return (int) Math.min(moneyProfit, Math.round(tax));
    }
}
