package net.tfminecraft.games.select;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayManager;
import net.tfminecraft.games.table.HandCard;

/**
 * Closest own-hand card to the player's look ray (furniture-style click point vs token).
 */
public final class CardSelector {

    private CardSelector() {}

    public static HandCard closestOnRay(Player player, List<HandCard> hand) {
        if (player == null || hand == null || hand.isEmpty()) {
            return null;
        }
        Vector origin = player.getEyeLocation().toVector();
        // A location's direction is always a unit vector.
        Vector look = player.getEyeLocation().getDirection();
        double range = Cache.handSelectRange;
        double radiusSq = Cache.handSelectRadius * Cache.handSelectRadius;
        HandCard best = null;
        double bestDistSq = Double.MAX_VALUE;
        DisplayManager displays = DisplayManager.get();
        for (HandCard card : hand) {
            Location loc = displays.worldLocation(card.tokenId());
            if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(player.getWorld())) {
                continue;
            }
            Vector point = loc.toVector();
            Vector toCard = point.clone().subtract(origin);
            double along = toCard.dot(look);
            if (along < 0 || along > range) {
                continue;
            }
            Vector closest = origin.clone().add(look.clone().multiply(along));
            double distSq = closest.distanceSquared(point);
            if (distSq > radiusSq || distSq >= bestDistSq) {
                continue;
            }
            bestDistSq = distSq;
            best = card;
        }
        return best;
    }
}
