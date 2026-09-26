package net.tfminecraft.games.voice;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.games.Games;

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
        Plugin rpc = Bukkit.getPluginManager().getPlugin("RPCharacters");
        if (rpc == null || !rpc.isEnabled()) {
            return null;
        }
        try {
            String name = DisplayIdentityService.resolveCharacterName(player);
            if (name == null || name.isBlank()) {
                return null;
            }
            String display = DisplayIdentityService.resolveDisplay(player);
            return display != null && !display.isBlank() ? display : name;
        } catch (RuntimeException | LinkageError ex) {
            if (!loggedFail) {
                loggedFail = true;
                Games.plugin.getLogger().warning("[Games] RPCharacters names skipped: " + ex.getMessage());
            }
            return null;
        }
    }
}
