package net.tfminecraft.games.wager;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Bank;
import net.tfminecraft.games.Games;

/**
 * The guild bank behind a table, and the only place in the plugin that moves guild money.
 * Package private on purpose: everything goes through {@link BankAccount} so a withdrawal
 * cannot happen without a matching leg on the felt.
 */
final class GuildBank {

    private static boolean loggedFail;

    private GuildBank() {}

    static boolean exists(String guildId) {
        try {
            return guild(guildId) != null;
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    /** Denars the guild can pay out, or 0 when there is no usable bank. */
    static int balance(String guildId) {
        try {
            Guild guild = guild(guildId);
            if (guild == null || guild.isBankrupt()) {
                return 0;
            }
            Bank bank = guild.getBank();
            if (bank == null || bank.getWealth() == null) {
                return 0;
            }
            double wealth = bank.getWealth();
            return wealth <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.floor(wealth));
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return 0;
        }
    }

    static boolean canHold(String guildId) {
        try {
            Guild guild = guild(guildId);
            return guild != null && guild.getBank() != null;
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    static boolean withdraw(String guildId, int denars) {
        if (denars < 1) {
            return true;
        }
        try {
            Guild guild = guild(guildId);
            if (guild == null || guild.isBankrupt()) {
                return false;
            }
            Bank bank = guild.getBank();
            if (bank == null || bank.getWealth() == null || bank.getWealth() < denars) {
                return false;
            }
            bank.withdraw((double) denars);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    static boolean deposit(String guildId, int denars) {
        if (denars < 1) {
            return true;
        }
        try {
            Guild guild = guild(guildId);
            if (guild == null) {
                return false;
            }
            Bank bank = guild.getBank();
            if (bank == null) {
                return false;
            }
            bank.deposit((double) denars);
            return true;
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
            return false;
        }
    }

    /**
     * Tell the guild's ledger that this much of what was banked was won rather than handed back,
     * so the day's tax is worked out on the profit only. The money itself already moved.
     */
    static void declareProfit(String guildId, int denars) {
        if (denars < 1) {
            return;
        }
        try {
            Guild guild = guild(guildId);
            if (guild == null) {
                return;
            }
            guild.getLedger().addCasinoProfitEntry((double) denars);
        } catch (RuntimeException | LinkageError ex) {
            warn(ex);
        }
    }

    private static Guild guild(String guildId) {
        if (guildId == null || guildId.isBlank() || !ready()) {
            return null;
        }
        return FactionManager.getGuildByString(guildId);
    }

    private static boolean ready() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("SimpleFactions");
        return plugin != null && plugin.isEnabled();
    }

    private static void warn(Throwable ex) {
        if (!loggedFail && Games.plugin != null) {
            loggedFail = true;
            Games.plugin.getLogger().warning("[Games] SimpleFactions guild bank skipped: " + ex.getMessage());
        }
    }
}
