package net.tfminecraft.games.display;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;

public final class ProtocolLibBridge {

    private static FakeItemDisplayPackets packets;

    private ProtocolLibBridge() {}

    public static void init(Plugin plugin) {
        packets = null;
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            plugin.getLogger().warning("[Games] ProtocolLib not found - display engine disabled.");
            return;
        }
        try {
            ProtocolManager manager = ProtocolLibrary.getProtocolManager();
            packets = new FakeItemDisplayPackets(manager);
            plugin.getLogger().info("[Games] ProtocolLib detected - packet ItemDisplays enabled.");
        } catch (Exception ex) {
            plugin.getLogger().warning("[Games] Failed to initialize ProtocolLib: " + ex.getMessage());
        }
    }

    public static boolean isReady() {
        return packets != null;
    }

    public static FakeItemDisplayPackets getPackets() {
        return packets;
    }

    public static void shutdown() {
        packets = null;
    }
}
