package net.tfminecraft.games.game;

import org.bukkit.entity.Player;

import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;

/**
 * Shoe, hands, and felt only. No 21, streets, or dealer.
 */
public final class FreePlayGame implements Game {

    @Override
    public boolean allowFreeDraw(Table table, Player player) {
        return table != null && !table.live();
    }

    @Override
    public boolean allowReturnSelected(Table table, Player player) {
        return table != null && !table.live();
    }

    /**
     * The felt is paid out whole, other players' stakes included, so only the table's host may
     * do it: whoever put the table down, or whoever holds its shoe.
     */
    @Override
    public boolean allowManualPotFlush(Table table, Player player) {
        return table != null && !table.live() && table.hostedBy(player.getUniqueId());
    }

    @Override
    public String extraLabel(Table table) {
        if (table == null) {
            return "";
        }
        return PotLabel.lines(table);
    }

    @Override
    public void onFeltPilesChanged(Table table) {
        TableManager.get().refreshLabel(table);
    }

    @Override
    public void onChipIn(Table table, Player player) {
        TableManager.get().refreshLabel(table);
    }
}
