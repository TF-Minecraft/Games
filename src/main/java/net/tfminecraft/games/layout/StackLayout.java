package net.tfminecraft.games.layout;

import net.tfminecraft.games.cache.Cache;

/**
 * How many face-down stack layers to show for a shoe size.
 */
public final class StackLayout {

    private StackLayout() {}

    public static int visibleLayers(int remaining, int compositionSize) {
        if (remaining <= 0 || compositionSize <= 0) {
            return 0;
        }
        int max = Math.max(1, Cache.stackVisibleMax);
        // A positive share of a positive maximum always rounds up to at least one layer.
        int layers = (int) Math.ceil(remaining / (double) compositionSize * max);
        return Math.min(layers, max);
    }
}
