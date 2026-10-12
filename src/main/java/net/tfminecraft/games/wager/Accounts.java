package net.tfminecraft.games.wager;

import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.table.Table;

/** Handles on the places a table's money can sit. */
public final class Accounts {

    private Accounts() {}

    /** One owner's chips on the felt. */
    public static BucketAccount bucket(Table table, UUID owner) {
        return new BucketAccount(table, owner);
    }

    /** The house tray, which is just the bucket keyed by the table itself. */
    public static BucketAccount tray(Table table) {
        return new BucketAccount(table, table.getId());
    }

    /** A player's pockets, any kind of chip. */
    public static PlayerAccount pockets(Table table, Player player) {
        return new PlayerAccount(player, player == null ? null : player.getUniqueId(),
                ChipItems::isChip, null, table.street());
    }

    /** A player's pockets, coins only, which is what a blackjack box takes. */
    public static PlayerAccount coins(Table table, Player player) {
        return new PlayerAccount(player, player == null ? null : player.getUniqueId(),
                ChipItems::isMoneyCoin, null, table.street());
    }

    /** A player's pockets with a rule of your own about what counts. */
    public static PlayerAccount pockets(Table table, Player player, Predicate<ItemStack> allowed) {
        return new PlayerAccount(player, player == null ? null : player.getUniqueId(), allowed,
                null, table.street());
    }

    /**
     * Items in a player's hands that are worth what they were wagered for rather than what any
     * coin table says, which is how loot gets onto the felt.
     */
    public static PlayerAccount declared(Table table, Player player, ItemStack sample, int unit) {
        ItemStack one = sample.clone();
        one.setAmount(1);
        return new PlayerAccount(player, player == null ? null : player.getUniqueId(),
                one::isSimilar, null, table.street(), unit);
    }

    /**
     * Somewhere to pay money out to. Falls back to dropping the coins at the table when the
     * player is gone, so a payout is never simply deleted.
     */
    public static PlayerAccount payee(Table table, Player dest, UUID owner) {
        Location dropAt = dest != null && dest.isOnline() ? dest.getLocation()
                : (table.getOrigin() != null ? table.getOrigin().clone() : null);
        UUID id = dest != null ? dest.getUniqueId() : owner;
        return new PlayerAccount(dest, id, null, dropAt, table.street());
    }

    /** Coins on the ground at the table, for when there is nobody and no bank to take them. */
    public static PlayerAccount ground(Table table, Location at) {
        Location dropAt = at != null ? at
                : (table.getOrigin() != null ? table.getOrigin().clone() : null);
        return new PlayerAccount(null, null, null, dropAt, table.street());
    }

    /** The guild bank behind the table. */
    public static BankAccount bank(Table table) {
        return new BankAccount(table, table.street());
    }

    /** Money from nothing. Refuses on anything but a staff table. */
    public static MintAccount mint(Table table) {
        return new MintAccount(table, table.street());
    }

    /**
     * Wherever this table's house money comes from: the mint on a staff table, the guild bank
     * on a real one. Saves every caller repeating that branch and getting it slightly different.
     */
    public static MoneyAccount house(Table table) {
        return table.staffMint() ? mint(table) : bank(table);
    }

    /** The player behind a bucket, online or not. */
    static Player online(UUID owner) {
        return owner == null ? null : Bukkit.getPlayer(owner);
    }
}
