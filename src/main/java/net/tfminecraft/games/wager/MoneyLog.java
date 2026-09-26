package net.tfminecraft.games.wager;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.table.Table;

/**
 * One line per denar movement so a table can be audited after the fact.
 * Every line is a transfer between the guild bank, the tray, the felt, and players.
 */
public final class MoneyLog {

    private MoneyLog() {}

    /** One leg of a transaction, named by where it came from and where it went. */
    public static void move(Table table, String from, String to, int denars, String reason) {
        log(table, from + " -> " + to, denars, reason);
    }

    /** Something worth recording that is not a transfer. */
    public static void note(Table table, int denars, String reason) {
        log(table, "note", denars, reason);
    }

    /** Money did not add up. Always logged, flag or not. */
    public static void mismatch(Table table, String detail) {
        if (Games.plugin == null) {
            return;
        }
        String id = table != null ? table.getId().toString() : "no-table";
        Games.plugin.getLogger().warning("[Money] " + id + " DOES NOT BALANCE: " + detail);
    }

    private static void log(Table table, String move, int denars, String reason) {
        if (!Cache.wagerAuditLog || denars == 0 || Games.plugin == null) {
            return;
        }
        String id = table != null ? table.getId().toString() : "no-table";
        Games.plugin.getLogger().info("[Money] " + id + " " + move + " " + denars
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
    }
}
