package net.tfminecraft.games.voice;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.rpcharacters.loaders.ChatLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.chat.ChatManager;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.layout.TableLayout.VoiceLines;

/**
 * RPCharacters chat without {@code /rp}. Isolated so BlackjackGame does not import RPC.
 */
public final class RpVoice {

    private static boolean loggedFail;

    private RpVoice() {}

    public static void say(Player player, TableLayout layout, String key) {
        if (player == null || layout == null || key == null || key.isBlank()) {
            return;
        }
        // Layouts always carry voice lines, and an unknown or unset line reads as blank.
        VoiceLines voice = layout.voice();
        String line = voice.line(key);
        if (line.isBlank()) {
            return;
        }
        String channelId = voice.channel();
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        Plugin rpc = Bukkit.getPluginManager().getPlugin("RPCharacters");
        if (rpc == null || !rpc.isEnabled()) {
            return;
        }
        try {
            speak(player, channelId, line);
        } catch (RuntimeException | LinkageError ex) {
            if (!loggedFail) {
                loggedFail = true;
                Games.plugin.getLogger().warning("[Games] RPCharacters voice skipped: " + ex.getMessage());
            }
        }
    }

    private static void speak(Player player, String channelId, String line) {
        ChatChannel channel = ChatLoader.getChannel(channelId);
        if (channel == null) {
            return;
        }
        PlayerData data = PlayerManager.get(player);
        if (data == null || data.getActiveCharacter() == null) {
            return;
        }
        ChatManager.dispatch(player, channel, line, true);
    }
}
