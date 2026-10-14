package net.tfminecraft.games.ownership;

import net.tfminecraft.tlibs.character.Characters;

import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

final class TLibsCharacters {
    private TLibsCharacters() {}

    static OwnerSession capture(Player player) {
        if (!player.isOnline()) return null;
        return Characters.currentSession(player)
                .filter(token -> token.ref().ownerId().equals(player.getUniqueId()))
                .filter(
                        token ->
                                Characters.info(token.ref().characterId())
                                        .filter(
                                                info ->
                                                        info.alive()
                                                                && info.ref().equals(token.ref()))
                                        .isPresent())
                .filter(Characters::isCurrent)
                .map(
                        token ->
                                new OwnerSession(
                                        token.ref().ownerId(),
                                        token.ref().characterId(),
                                        token.sessionId()))
                .orElse(null);
    }

    static boolean heirReady() {
        return Characters.legacyHeirsReady();
    }

    static boolean deals(net.tfminecraft.games.table.Table table, Player player) {
        OwnerSession token = CharacterAccess.capture(player);
        var captured = table.wagerCharacters().get(player.getUniqueId());
        return token != null
                && captured != null
                && token.account().equals(captured.ownerId())
                && token.character().equals(captured.characterId());
    }

    static Optional<UUID> heir(UUID account) {
        return Characters.legacyHeir(account)
                .filter(ref -> ref.ownerId().equals(account))
                .map(ref -> ref.characterId());
    }

    static void start(org.bukkit.plugin.Plugin plugin) {
        org.bukkit.Bukkit.getPluginManager().registerEvents(new CharacterLifecycle(), plugin);
        org.bukkit.Bukkit.getOnlinePlayers().forEach(CharacterAccess::capture);
    }
}
