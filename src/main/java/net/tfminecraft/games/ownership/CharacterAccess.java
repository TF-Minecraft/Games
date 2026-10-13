package net.tfminecraft.games.ownership;

import net.tfminecraft.games.table.Table;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/** Capability probe precedes all links to newer TLibs classes. */
public final class CharacterAccess {
    private static final java.util.Map<UUID, OwnerSession> published =
            new java.util.concurrent.ConcurrentHashMap<>();

    private CharacterAccess() {}

    public static boolean available() {
        try {
            Class<?> api = Class.forName("net.tfminecraft.tlibs.character.Characters");
            api.getMethod("legacyHeirsReady");
            api.getMethod("legacyHeir", UUID.class);
            Class.forName("net.tfminecraft.tlibs.character.CharacterSessionEndEvent");
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            return false;
        }
    }

    public static OwnerSession capture(Player player) {
        if (player == null || !available()) return null;
        OwnerSession token = TLibsCharacters.capture(player);
        if (token == null) published.remove(player.getUniqueId());
        else published.put(player.getUniqueId(), token);
        return token;
    }

    public static OwnerSession published(UUID account) {
        return published.get(account);
    }

    public static void ended(UUID account) {
        published.remove(account);
    }

    public static void start(org.bukkit.plugin.Plugin plugin) {
        if (available()) TLibsCharacters.start(plugin);
    }

    public static boolean current(OwnerSession token) {
        return token != null && token.equals(capture(Bukkit.getPlayer(token.account())));
    }

    public static boolean owns(Table table, Player player) {
        OwnerSession token = capture(player);
        return token != null
                && !table.ownershipHeld()
                && token.account().equals(table.ownerPlayer())
                && token.character().equals(table.ownerCharacterId());
    }

    public static boolean heirReady() {
        return available() && TLibsCharacters.heirReady();
    }

    public static boolean deals(Table table, Player player) {
        return available() && TLibsCharacters.deals(table, player);
    }

    public static Optional<UUID> heir(UUID account) {
        return available() ? TLibsCharacters.heir(account) : Optional.empty();
    }
}
