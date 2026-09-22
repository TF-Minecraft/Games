package net.tfminecraft.games.wager;

import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.games.cache.Cache;

/**
 * Special gold/silver coins, DenarEconomy coins, and {@code wager.items} overrides.
 */
public final class ChipItems {

    private static final double GOLD_DENARS = 1.0;
    private static final double SILVER_DENARS = 0.01;

    private ChipItems() {}

    public static boolean denarEconomyPresent() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("DenarEconomy");
        return plugin != null && plugin.isEnabled();
    }

    public static boolean isChip(ItemStack stack) {
        return integerDenars(stack).isPresent() || decoChips(stack) != null;
    }

    /** Gold/silver, {@code wager.items}, or a DenarEconomy coin - even if not a whole denar. */
    public static boolean isChipKind(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        return namedCoin(stack) != null || itemOverride(stack) != null || coinOf(stack) != null;
    }

    /** Configured gold/silver coin items or a DenarEconomy coin. Not {@code wager.items} (gold ingot). */
    public static boolean isMoneyCoin(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        return namedCoin(stack) != null || coinOf(stack) != null;
    }

    /**
     * Denars in this stake that count as money for citizen tax. Loot and {@code wager.items}
     * are worth 0 here even when they have a declared value on the felt.
     */
    public static int moneyValue(Stake stake) {
        if (stake == null || stake.count() < 1) {
            return 0;
        }
        ItemStack item = stake.item();
        if (item != null) {
            return isMoneyCoin(item) ? stake.value() : 0;
        }
        return moneyValueFromTypeKey(stake.typeKey(), stake.unit(), stake.count());
    }

    /** Sum of {@link #moneyValue(Stake)} over a collection. */
    public static int moneyValue(Collection<Stake> stakes) {
        if (stakes == null || stakes.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (Stake stake : stakes) {
            sum += moneyValue(stake);
        }
        return sum;
    }

    /** For tests and saved stakes with no item stack: gold, silver, and coin: keys only. */
    private static int moneyValueFromTypeKey(String typeKey, int unit, int count) {
        if (typeKey == null || unit < 1 || count < 1) {
            return 0;
        }
        if ("gold".equals(typeKey) || "silver".equals(typeKey) || typeKey.startsWith("coin:")) {
            return unit * count;
        }
        return 0;
    }

    public static boolean needsDeclaredValue(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
            return false;
        }
        if (integerDenars(stack).isPresent() || decoChips(stack) != null) {
            return false;
        }
        return itemOverride(stack) != null;
    }

    /** Denars one of these is worth, or 0 when it is not money that can be staked. */
    public static int unitDenars(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        DecoChips deco = decoChips(stack);
        if (deco != null) {
            return deco.denars();
        }
        OptionalInt whole = integerDenars(stack);
        return whole.isPresent() ? whole.getAsInt() : 0;
    }

    public static OptionalInt integerDenars(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return OptionalInt.empty();
        }
        WagerItemOverride named = namedCoin(stack);
        if (named != null) {
            if (named.value() != null) {
                return wholeDenars((double) named.value());
            }
            Coin coin = coinOf(stack);
            if (coin != null) {
                return wholeDenars(coin.getValue());
            }
            return OptionalInt.of(1);
        }
        WagerItemOverride item = itemOverride(stack);
        if (item != null && item.value() != null) {
            return wholeDenars((double) item.value());
        }
        Coin coin = coinOf(stack);
        if (coin != null) {
            return wholeDenars(coin.getValue());
        }
        return OptionalInt.empty();
    }

    /**
     * Smaller coins worth exactly as much as one of these, or empty when it cannot be broken.
     *
     * <p>A pouch of 100 is no use to somebody paying 50. Never breaks below a whole denar,
     * because sub-denar silver cannot be staked.
     */
    public static List<ItemStack> change(ItemStack stack) {
        if (stack == null || !denarEconomyPresent()) {
            return List.of();
        }
        // Only real coins have change. Deco chips and wager.items are worth what they are.
        if (coinOf(stack) == null || namedCoin(stack) != null || itemOverride(stack) != null) {
            return List.of();
        }
        List<ItemStack> out = DenarEconomy.getMoneyManager().breakCoin(stack, 1.0);
        return out == null ? List.of() : out;
    }

    public static String displayModel(ItemStack stack) {
        return pileStyle(stack).model();
    }

    public static WagerPileStyle pileStyle(ItemStack stack) {
        WagerPileStyle base = WagerPileStyle.defaults();
        WagerItemOverride named = namedCoin(stack);
        if (named != null) {
            return named.style(base, 1);
        }
        WagerItemOverride item = itemOverride(stack);
        if (item != null) {
            return item.style(base);
        }
        DecoChips deco = decoChips(stack);
        if (deco != null) {
            return deco.style();
        }
        return base;
    }

    /**
     * DenarEconomy coin not listed in {@code wager.items}: gold pieces at 1.0d, silver at 0.01d.
     */
    public static DecoChips decoChips(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        if (itemOverride(stack) != null || namedCoin(stack) != null) {
            return null;
        }
        Coin coin = coinOf(stack);
        if (coin == null || coin.getValue() == null) {
            return null;
        }
        double value = coin.getValue();
        if (value <= 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        WagerPileStyle base = WagerPileStyle.defaults();
        if (value >= GOLD_DENARS) {
            if (Cache.wagerGold == null) {
                return null;
            }
            int pieces = (int) Math.round(value / GOLD_DENARS);
            if (pieces < 1) {
                return null;
            }
            return new DecoChips(Cache.wagerGold.style(base, 1), pieces, pieces);
        }
        if (Cache.wagerSilver == null) {
            return null;
        }
        int pieces = (int) Math.round(value / SILVER_DENARS);
        if (pieces < 1) {
            return null;
        }
        return new DecoChips(Cache.wagerSilver.style(base, 1), pieces, 0);
    }

    public record DecoChips(WagerPileStyle style, int pieces, int denars) {}

    public static String typeKey(ItemStack stack) {
        if (isGoldCoin(stack)) {
            return "gold";
        }
        if (isSilverCoin(stack)) {
            return "silver";
        }
        for (WagerItemOverride override : Cache.wagerItems) {
            if (override.matches(stack)) {
                return "item:" + override.item();
            }
        }
        Coin coin = coinOf(stack);
        if (coin != null && coin.getId() != null) {
            return "coin:" + coin.getId();
        }
        if (stack == null) {
            return "air";
        }
        return "mat:" + stack.getType().name();
    }

    public static boolean isGoldCoin(ItemStack stack) {
        return Cache.wagerGold != null && Cache.wagerGold.matches(stack);
    }

    public static boolean isSilverCoin(ItemStack stack) {
        return Cache.wagerSilver != null && Cache.wagerSilver.matches(stack);
    }

    private static WagerItemOverride namedCoin(ItemStack stack) {
        if (Cache.wagerGold != null && Cache.wagerGold.matches(stack)) {
            return Cache.wagerGold;
        }
        if (Cache.wagerSilver != null && Cache.wagerSilver.matches(stack)) {
            return Cache.wagerSilver;
        }
        return null;
    }

    private static WagerItemOverride itemOverride(ItemStack stack) {
        for (WagerItemOverride override : Cache.wagerItems) {
            if (override.matches(stack)) {
                return override;
            }
        }
        return null;
    }

    private static Coin coinOf(ItemStack stack) {
        if (!denarEconomyPresent()) {
            return null;
        }
        return DenarEconomy.getMoneyManager().getCoin(stack);
    }

    private static OptionalInt wholeDenars(Double raw) {
        if (raw == null || raw.isNaN() || raw.isInfinite() || raw <= 0) {
            return OptionalInt.empty();
        }
        double value = raw;
        if (Cache.wagerIntegerDenars) {
            long rounded = Math.round(value);
            if (Math.abs(value - rounded) > 1.0e-6) {
                return OptionalInt.empty();
            }
            if (rounded > Integer.MAX_VALUE || rounded < 1) {
                return OptionalInt.empty();
            }
            return OptionalInt.of((int) rounded);
        }
        int floored = (int) Math.floor(value);
        if (floored < 1) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(floored);
    }
}
