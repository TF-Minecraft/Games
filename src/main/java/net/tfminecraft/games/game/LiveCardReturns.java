package net.tfminecraft.games.game;

import org.bukkit.entity.Player;

import net.tfminecraft.games.table.Table;

/** A game whose players may send selected cards back to the shoe during a live hand. */
public interface LiveCardReturns {

    /** After selected cards were returned to the shoe while live. */
    void onReturnedSelected(Table table, Player player, int count);
}
