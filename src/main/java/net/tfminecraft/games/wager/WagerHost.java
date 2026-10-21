package net.tfminecraft.games.wager;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;

import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.Table;

/**
 * What the money engine needs from the table it is moving money on: where chips are drawn, and
 * a single place to tell once a transaction is done.
 *
 * <p>Redrawing and saving used to happen inside each money method, which is why two money
 * methods could not be composed into one operation. Now it happens once, after the money has
 * finished moving.
 */
public interface WagerHost {

    /** Where this owner's chips sit, or the middle of the felt when they have no spot. */
    Location anchorFor(Table table, UUID owner);

    /** Redraw the buckets that changed, save the table, and refresh its label. */
    void moneyMoved(Table table, Collection<UUID> buckets);

    /**
     * Throwaway chips for an animation. They hold no value and are dropped when they land.
     * {@code sourceOwner} is the bucket they leave, so they can start where its heaps are drawn.
     */
    List<PayoutFlight> flights(Table table, List<Stake> stakes, Location from, UUID sourceOwner, UUID destId,
            boolean toTray);
}
