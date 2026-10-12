package net.tfminecraft.games.voice;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.games.Games;
import net.tfminecraft.tlibs.character.Characters;

/**
 * Active RP character display name. Isolated so table labels do not import RPC.
 */
public final class RpNames {

    private static boolean loggedFail;

    private RpNames() {}

    public static String of(UUID id) {
        if (id == null) {
            return "-";
        }
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            String rp = characterDisplay(online);
            // A character name is only returned when it has text.
            if (rp != null) {
                return rp;
            }
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null && !name.isBlank() ? name : "-";
    }

    private static String characterDisplay(Player player) {
        try {
            String display = Characters.displayName(player);
            return !display.isBlank() ? display : null;
        } catch (RuntimeException | LinkageError ex) {
            if (!loggedFail) {
                loggedFail = true;
                Games.plugin.getLogger().warning("[Games] Shared character names skipped: " + ex.getMessage());
            }
            return null;
        }
    }
}
