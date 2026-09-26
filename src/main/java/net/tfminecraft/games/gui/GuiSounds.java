package net.tfminecraft.games.gui;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** Same click / deny as SimpleFactions menus. */
public final class GuiSounds {

    private GuiSounds() {}

    public static void click(Player player) {
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1f);
    }

    public static void deny(Player player) {
        player.playSound(player, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }
}
