package net.tfminecraft.games.wager;

import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;

/** Validated guild ledger account; compound postings belong to MoneyTx. */
final class GuildBank {
    private static boolean loggedFail;
    private GuildBank() {}

    static String account(Table table) {
        return table.ownerGuildIncarnation() == null ? null : Denars.guild(table.ownerGuildIncarnation());
    }

    static int balance(Table table) {
        try {
            if (!canHold(table)) return 0;
            return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Denars.balance(account(table)) / 100));
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return 0;
        }
    }

    static boolean canHold(Table table) {
        try {
            return GuildTables.owns(table.ownerGuildId(), table.ownerGuildIncarnation()) && Denars.ready();
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    static boolean declareProfit(Table table, String operationId, int denars) {
        if (denars < 1) return true;
        try {
            if (!canHold(table)) return false;
            Guild guild = FactionManager.getGuildByString(table.ownerGuildId());
            return guild != null && guild.getLedger().addCasinoProfitEntry(operationId, denars);
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    private static void warn(Throwable ex) {
        if (!loggedFail && Games.plugin != null) {
            loggedFail = true;
            Games.plugin.getLogger().warning("[Games] SimpleFactions guild bank unavailable: " + ex.getMessage());
        }
    }
}
