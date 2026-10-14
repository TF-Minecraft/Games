package net.tfminecraft.games.wager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** The end event runs after session removal, while the outgoing body is still loaded. */
public final class OutgoingRefund {
    private record Scope(Player player, CharacterSession session) {}
    private static final Map<UUID, Scope> OPEN = new HashMap<>();

    private OutgoingRefund() {}

    public static void during(Player player, CharacterSession ending, Runnable cleanup) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Outgoing refunds require the main thread");
        UUID account = ending.ref().ownerId();
        boolean open = account.equals(player.getUniqueId()) && Bukkit.getPlayer(account) == player
                && player.isOnline() && !player.isDead()
                && !Characters.isCurrent(ending) && Characters.currentSession(player).isEmpty()
                && OPEN.putIfAbsent(account, new Scope(player, ending)) == null;
        try { cleanup.run(); }
        finally { if (open) OPEN.remove(account); }
    }

    static CharacterRef recipient(Player player) {
        if (!Bukkit.isPrimaryThread()) return null;
        Scope scope = player == null ? null : OPEN.get(player.getUniqueId());
        if (scope == null || scope.player() != player || !player.isOnline() || player.isDead()
                || Characters.isCurrent(scope.session()) || Characters.currentSession(player).isPresent())
            return null;
        return scope.session().ref();
    }
}
