package net.tfminecraft.games.layout;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import net.tfminecraft.games.cache.Cache;

/**
 * Fan origin: standing at the player, or the near top edge of the table when mounted.
 */
public final class HandAnchor {

    private static final double RAY_STEP = 0.1;

    private HandAnchor() {}

    public record Raw(Location location, boolean sitting, float placeYaw) {}

    public static Raw resolve(Player player, Location shoe) {
        // The shoe is a table origin, which always has a world.
        if (player.isInsideVehicle() && player.getWorld().equals(shoe.getWorld())) {
            return sitRim(player, shoe);
        }
        Location at = player.getLocation();
        Location stand = at.clone();
        stand.setY(at.getY() + Cache.handLift);
        return new Raw(stand, false, 0f);
    }

    private static Raw sitRim(Player player, Location shoe) {
        Location at = player.getLocation();
        double dx = shoe.getX() - at.getX();
        double dz = shoe.getZ() - at.getZ();
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1e-4) {
            Location fallback = player.getLocation().clone();
            fallback.setY(shoe.getY());
            return new Raw(fallback, true, shoe.getYaw());
        }
        double range = Math.min(Cache.handSitRange, horiz);
        double ux = dx / horiz;
        double uz = dz / horiz;
        World world = player.getWorld();
        Block skipPlayer = at.getBlock();
        Block skipBelow = skipPlayer.getRelative(0, -1, 0);
        Block skipVehicle = player.getVehicle().getLocation().getBlock();
        int baseY = at.getBlockY();
        for (double t = RAY_STEP; t <= range + 1e-6; t += RAY_STEP) {
            double x = at.getX() + ux * t;
            double z = at.getZ() + uz * t;
            int bx = Location.locToBlock(x);
            int bz = Location.locToBlock(z);
            for (int by = baseY - 1; by <= baseY + 1; by++) {
                Block block = world.getBlockAt(bx, by, bz);
                if (sameBlock(block, skipPlayer) || sameBlock(block, skipBelow) || sameBlock(block, skipVehicle)) {
                    continue;
                }
                if (!block.getType().isSolid()) {
                    continue;
                }
                return rimOf(block, player, shoe);
            }
        }
        Location fallback = player.getLocation().clone();
        fallback.setY(shoe.getY());
        return new Raw(fallback, true, HandLayout.deckYaw(shoe, fallback, shoe.getYaw()));
    }

    private static Raw rimOf(Block block, Player player, Location shoe) {
        double top = shoe.getY();
        double cx = block.getX() + 0.5;
        double cz = block.getZ() + 0.5;
        double pdx = player.getLocation().getX() - cx;
        double pdz = player.getLocation().getZ() - cz;
        double edge = Cache.handSitEdge;
        float placeYaw;
        double ex;
        double ez;
        if (Math.abs(pdx) >= Math.abs(pdz)) {
            if (pdx >= 0) {
                ex = cx + edge;
                ez = cz;
                placeYaw = 90f;
            } else {
                ex = cx - edge;
                ez = cz;
                placeYaw = 270f;
            }
        } else if (pdz >= 0) {
            ex = cx;
            ez = cz + edge;
            placeYaw = 180f;
        } else {
            ex = cx;
            ez = cz - edge;
            placeYaw = 0f;
        }
        Location at = new Location(block.getWorld(), ex, top, ez);
        return new Raw(at, true, placeYaw);
    }

    /** Every block compared here comes from the seated player's own world. */
    private static boolean sameBlock(Block block, Block other) {
        return block.getX() == other.getX()
                && block.getY() == other.getY()
                && block.getZ() == other.getZ();
    }
}
