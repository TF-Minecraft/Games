package net.tfminecraft.games.game;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.layout.HandLayout;
import net.tfminecraft.games.layout.RevealLayout;
import net.tfminecraft.games.layout.TablePileLayout;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;

/**
 * Per-game hooks. Implementations must not own packets or deck shuffle.
 */
public interface Game {

    /**
     * Open table: refund every pile this player owns, then drop them from actives.
     * Poker overrides to refund only the current street and pay the last remaining player.
     */
    default void onLeave(Table table, Player player) {
        TableManager manager = TableManager.get();
        manager.refundOwnedPiles(table, player);
        table.actives().remove(player.getUniqueId());
    }

    default List<DisplayPose> revealSlots(Table table, Player player, int count, Location origin, Location anchor,
            float placeYaw, boolean sitting) {
        return RevealLayout.line(count, origin, anchor, placeYaw, sitting, HandLayout.FACE_UP_PITCH);
    }

    default int denarsToMatch(Table table, Player player) {
        return 0;
    }

    default void onStreetCommit(Table table, Player player, int streetValue, boolean folded) {}

    /**
     * Shift+right-click the shoe pays the pot to that player. Set games: never. Free play can override.
     */
    default boolean allowManualPotFlush(Table table, Player player) {
        return false;
    }

    default boolean allowFreeDraw(Table table, Player player) {
        return false;
    }

    default boolean allowReturnSelected(Table table, Player player) {
        return false;
    }

    /** When true, held cards are fanned in rank order. Blackjack keeps deal order. */
    default boolean sortHeldCards() {
        return true;
    }

    /** F hide/show. Blackjack stays public. */
    default boolean allowRevealToggle(Table table, Player player) {
        return true;
    }

    /** Suit dust on public cards. */
    default boolean showRevealDust(Table table) {
        return true;
    }

    /**
     * Players needed in actives before tryBeginSession starts a hand. 0 means never auto-start.
     */
    default int minActives() {
        return 0;
    }

    default void onSessionStart(Table table) {
        if (Cache.debug) {
            Games.plugin.getLogger().info("[Games] onSessionStart table=" + table.getId());
        }
    }

    default void onSessionEnd(Table table) {}

    default void onTableReady(Table table) {}

    default void onTableRemoved(Table table) {}

    /**
     * Human dealer walked off or quit. Idle tables refresh the claim label.
     * Live games that need a dealer click should continue without them.
     */
    default void onDealerGone(Table table) {
        onTableReady(table);
    }

    /** Every game reacts to chips arriving or a seat leaving, if only to refresh its label. */
    void onChipIn(Table table, Player player);

    default void onChipIn(Table table, Player player, int denars, ItemStack item) {
        onChipIn(table, player);
    }

    /** Live shoe click. Default: no draw. Later games use this for hit. */
    default void onShoeClick(Table table, Player player) {}

    default void onBetHit(Table table, Player player) {}

    default void onBetStand(Table table, Player player) {}

    default void onBetDouble(Table table, Player player) {}

    default void onBetSplit(Table table, Player player) {}

    /**
     * Chat /games bet play words. Blackjack: phase play and actor.
     */
    default boolean allowPlayChat(Table table, Player player) {
        return table != null && player != null && table.live()
                && "play".equals(table.phase())
                && player.getUniqueId().equals(table.actor());
    }

    default void onPlayWord(Table table, Player player, String word) {}

    /** Extra shoe hologram lines. Empty means none. */
    String extraLabel(Table table);

    /** When false, the stock Auto/Dealer hologram line is skipped (game extraLabel owns it). */
    default boolean showStockDealer() {
        return true;
    }

    default DisplayPose tablePileSlot(Table table, String pile, int index, int count, boolean faceUp) {
        return TablePileLayout.slot(table, pile, index, count, faceUp);
    }

    default void onTablePilesChanged(Table table) {}

    void onFeltPilesChanged(Table table);

    /**
     * Idle shoe click. True if the click was consumed (no sandbox draw).
     */
    default boolean tryClaimDealer(Table table, Player player) {
        return false;
    }
}
