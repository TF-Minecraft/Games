package net.tfminecraft.games.wager;

import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;

/**
 * Per-item look and optional denar value. Null value means /wager amount is required.
 */
public final class WagerItemOverride {

    private final String item;
    private final Integer value;
    private final String model;
    private final Integer stackMax;
    private final Float layerGap;
    private final Float scale;
    private final Boolean randomYaw;
    private final Float pitch;
    private final boolean threeD;
    private final Double yOffset;

    public WagerItemOverride(String item, Integer value, String model, Integer stackMax, Float layerGap, Float scale,
            Boolean randomYaw, Float pitch, boolean threeD, Double yOffset) {
        this.item = item;
        this.value = value;
        this.model = model;
        this.stackMax = stackMax;
        this.layerGap = layerGap;
        this.scale = scale;
        this.randomYaw = randomYaw;
        this.pitch = pitch;
        this.threeD = threeD;
        this.yOffset = yOffset;
    }

    public String item() {
        return item;
    }

    public Integer value() {
        return value;
    }

    public boolean matches(ItemStack stack) {
        return matchesPath(stack, item);
    }

    public WagerPileStyle style(WagerPileStyle base) {
        return style(base, base.stackUnit());
    }

    public WagerPileStyle style(WagerPileStyle base, int stackUnit) {
        String display = model != null && !model.isBlank() ? model : (base.model() != null ? base.model() : item);
        int unit = stackUnit > 0 ? stackUnit : base.stackUnit();
        float resolvedPitch = pitch != null ? pitch : base.pitch();
        if (threeD) {
            resolvedPitch -= 90f;
        }
        return new WagerPileStyle(
                stackMax != null && stackMax > 0 ? stackMax : base.stackMax(),
                unit,
                layerGap != null && layerGap > 0 ? layerGap : base.layerGap(),
                scale != null && scale > 0 ? scale : base.scale(),
                randomYaw != null ? randomYaw : base.randomYaw(),
                display,
                resolvedPitch,
                yOffset != null ? yOffset : base.yOffset());
    }

    static boolean matchesPath(ItemStack stack, String path) {
        if (stack == null || stack.getType() == Material.AIR || path == null || path.isBlank()) {
            return false;
        }
        String trimmed = path.trim();
        Material vanilla = vanillaMaterial(trimmed);
        if (vanilla != null) {
            return stack.getType() == vanilla;
        }
        return TLibs.getItemAPI().getChecker().checkItemWithPath(stack, trimmed);
    }

    static Material vanillaMaterial(String raw) {
        String key = raw.trim().toUpperCase(Locale.ROOT);
        if (key.startsWith("MINECRAFT:")) {
            key = key.substring("MINECRAFT:".length());
        }
        if (key.indexOf('.') >= 0 || key.indexOf(':') >= 0) {
            return null;
        }
        try {
            return Material.valueOf(key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
