package net.tfminecraft.games.ownership;

import net.tfminecraft.games.gui.GameSelectHolder;
import net.tfminecraft.games.gui.TableOptionsGui;
import net.tfminecraft.games.gui.TableOptionsHolder;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

final class CharacterLifecycle implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void end(CharacterSessionEndEvent event) {
        var account = event.getSession().ref().ownerId();
        if (!account.equals(event.getPlayer().getUniqueId())) return;
        CharacterAccess.ended(account);
        TableOptionsGui.clear(account);
        var player = Bukkit.getPlayer(account);
        if (player != null) {
            TableManager.get().clearPlayer(player);
            var holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof TableOptionsHolder || holder instanceof GameSelectHolder)
                player.closeInventory();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void start(CharacterSessionStartEvent event) {
        var account = event.getSession().ref().ownerId();
        if (!account.equals(event.getPlayer().getUniqueId())) return;
        TableOptionsGui.clear(account);
        TableManager.get().clearPlacement(account);
        CharacterAccess.capture(Bukkit.getPlayer(account));
    }
}
