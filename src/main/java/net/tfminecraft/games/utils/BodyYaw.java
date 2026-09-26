package net.tfminecraft.games.utils;

import org.bukkit.entity.Player;

/**
 * Torso yaw, falling back to look yaw when the running server cannot report
 * {@code LivingEntity.getBodyYaw()}.
 */
public final class BodyYaw {

    private BodyYaw() {}

    public static float of(Player player) {
        if (player == null) {
            return 0f;
        }
        try {
            return player.getBodyYaw();
        } catch (RuntimeException unsupported) {
            return player.getLocation().getYaw();
        }
    }
}
