package net.tfminecraft.games.wager;

import java.util.List;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;

/**
 * Somewhere money can sit: a player's pockets, a bucket of chips on the felt, a guild bank,
 * or the mint on a staff table.
 *
 * <p>An account plans a movement before any of it happens. That is the whole point: a
 * transaction can then refuse cleanly instead of moving half the money and trying to undo the
 * rest, which is how a bet could be taken while the action it paid for was refused.
 */
public interface MoneyAccount {

    /** A detached source used to reserve coins while a compound transaction is planned. */
    default MoneyAccount planningCopy() { return this; }

    /** Ledger destination/source, or null for physical chips. */
    default String ledgerAccount() { return null; }

    /** Short name for the audit line, like "felt" or "guild bank". */
    String label();

    /** Most that could come out right now, before asking whether it can be made exactly. */
    int available();

    /**
     * Plan handing over exactly this many denars, or null when the money here cannot make that
     * amount. Nothing is mutated.
     *
     * @param template coin to shape new money like, for accounts holding a balance instead of
     *                 items. Accounts that hold real coins ignore it.
     */
    Withdrawal planTake(int denars, ItemStack template);

    /** Plan handing over everything, or everything staked on one betting street. */
    Withdrawal planTakeAll(Integer street);

    /** Largest value not over {@code denars} that this account can hand over exactly. */
    int largestTakeUpTo(int denars, ItemStack template);

    /**
     * Whether this account will take this value, checked before anything moves. A guild bank
     * whose guild has been deleted says no here, so the caller can fall back to paying the
     * coins out instead of finding out once they have already left the tray.
     */
    default boolean canAccept(int denars) {
        return true;
    }

    /** Take money in. Returns the denars absorbed, which the transaction checks. */
    int accept(List<Stake> stakes);

    /** True when this account is chips on this table's felt, so the felt total changes with it. */
    default boolean onFelt() {
        return false;
    }

    /** The bucket chips are drawn from or to, so an animation knows where to start. */
    default UUID feltOwner() {
        return null;
    }

    /** Who chips should fly to when this account receives them. Null for no animation. */
    default UUID flightTarget() {
        return null;
    }

    /** True when this is the house tray, which chips fly into rather than out to a player. */
    default boolean isTray() {
        return false;
    }
}
