package net.tfminecraft.games.ownership;

import net.tfminecraft.games.table.TableManager;

import org.bukkit.plugin.Plugin;

public final class EstateBridge {
    private EstateBridge() {}

    public static boolean available() {
        try {
            Class.forName("net.tfminecraft.tlibs.character.estate.EstateParticipant");
            Class<?> estates = Class.forName("net.tfminecraft.tlibs.character.estate.Estates");
            estates.getMethod(
                    "guardHolds",
                    Class.forName("net.tfminecraft.tlibs.character.estate.EstateDecision"));
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            return false;
        }
    }

    public static void start(Plugin plugin, TableManager manager) {
        if (available()) TableEstate.start(plugin, manager);
    }
}
