package net.tfminecraft.games.display;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.tfminecraft.games.utils.Keys;

/**
 * Real world entities for clicks and public labels. Packet displays are not clickable.
 */
public final class WorldAnchors {

    private WorldAnchors() {}

    public static Interaction spawnInteraction(Location location, float width, float height, String tokenId) {
        return spawnInteraction(location, width, height, tokenId, null);
    }

    public static Interaction spawnInteraction(Location location, float width, float height, String tokenId,
            String tableId) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return location.getWorld().spawn(location, Interaction.class, entity -> {
            entity.setInteractionWidth(width);
            entity.setInteractionHeight(height);
            entity.setResponsive(true);
            entity.setPersistent(false);
            if (tokenId != null) {
                entity.getPersistentDataContainer().set(Keys.anchorToken(), PersistentDataType.STRING, tokenId);
            }
            if (tableId != null) {
                entity.getPersistentDataContainer().set(Keys.tableId(), PersistentDataType.STRING, tableId);
            }
        });
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static TextDisplay spawnLabel(Location location, String text) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setText(text != null ? text : "");
            entity.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            entity.setPersistent(false);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            float scale = 0.4f;
            entity.setTransformation(new Transformation(
                    new Vector3f(0, 0.4f, 0),
                    new Quaternionf(),
                    new Vector3f(scale, scale, scale),
                    new Quaternionf()));
        });
    }

    public static String tokenId(Entity entity) {
        if (entity == null) {
            return null;
        }
        return entity.getPersistentDataContainer().get(Keys.anchorToken(), PersistentDataType.STRING);
    }

    public static String tableId(Entity entity) {
        if (entity == null) {
            return null;
        }
        return entity.getPersistentDataContainer().get(Keys.tableId(), PersistentDataType.STRING);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static void setText(UUID entityId, String text) {
        if (entityId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(entityId);
        if (entity instanceof TextDisplay display && !display.isDead()) {
            display.setSeeThrough(false);
            display.setText(text != null ? text : "");
        }
    }

    public static void move(UUID entityId, Location location) {
        if (entityId == null || location == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(entityId);
        if (entity != null && !entity.isDead()) {
            entity.teleport(location);
        }
    }

    public static void remove(UUID entityId) {
        if (entityId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(entityId);
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }
    }
}
