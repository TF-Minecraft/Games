package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.Games;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.guild.income.Ledger;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Bank;

class GuildBankTest {
    private MockedStatic<Bukkit> bukkit;
    private Games previousPlugin;
    private Logger logger;
    private MockedStatic<FactionManager> factions;
    private Plugin plugin;
    private PluginManager plugins;
    private Guild guild;
    private Bank bank;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        previousPlugin = Games.plugin;
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
        // The warning is once per server start; begin each test from a fresh start.
        var loggedFail = GuildBank.class.getDeclaredField("loggedFail");
        loggedFail.setAccessible(true);
        loggedFail.setBoolean(null, false);
        plugins = mock(PluginManager.class);
        plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("SimpleFactions")).thenReturn(plugin);
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        factions = mockStatic(FactionManager.class);
        guild = mock(Guild.class);
        bank = mock(Bank.class);
        when(guild.getBank()).thenReturn(bank);
        when(bank.getWealth()).thenReturn(10.75);
        factions.when(() -> FactionManager.getGuildByString("guild")).thenReturn(guild);
    }

    @AfterEach
    void closeMocks() {
        factions.close();
        bukkit.close();
        Games.plugin = previousPlugin;
    }

    @Test
    void missingOrDisabledIntegrationRefusesToMoveMoney() {
        assertFalse(GuildBank.exists(null));
        assertFalse(GuildBank.exists(" "));
        assertFalse(GuildBank.exists("deleted"));
        when(plugin.isEnabled()).thenReturn(false);
        assertFalse(GuildBank.exists("guild"));
        assertEquals(0, GuildBank.balance("guild"));
        assertFalse(GuildBank.withdraw("guild", 1));
        assertFalse(GuildBank.deposit("guild", 1));
        assertFalse(GuildBank.canHold("guild"));
        GuildBank.declareProfit("guild", 2);
        when(plugins.getPlugin("SimpleFactions")).thenReturn(null);
        assertFalse(GuildBank.exists("guild"));
        verifyNoInteractions(bank);
    }

    @Test
    void balancesAreWholeDenarsAndWithdrawalsNeverExceedAvailableWealth() {
        assertTrue(GuildBank.exists("guild"));
        assertTrue(GuildBank.canHold("guild"));
        assertEquals(10, GuildBank.balance("guild"));
        assertFalse(GuildBank.withdraw("guild", 11));
        verify(bank, never()).withdraw(anyDouble());
        assertTrue(GuildBank.withdraw("guild", 10));
        verify(bank).withdraw(10.0);
        when(bank.getWealth()).thenReturn(-5.0);
        assertEquals(0, GuildBank.balance("guild"));
        when(bank.getWealth()).thenReturn((double) Integer.MAX_VALUE + 100);
        assertEquals(Integer.MAX_VALUE, GuildBank.balance("guild"));
    }

    @Test
    void bankruptGuildCannotWithdrawButCanReceiveMoneyToRecover() {
        when(guild.isBankrupt()).thenReturn(true);
        assertEquals(0, GuildBank.balance("guild"));
        assertFalse(GuildBank.withdraw("guild", 1));
        assertTrue(GuildBank.canHold("guild"));
        assertTrue(GuildBank.deposit("guild", 12));
        verify(bank).deposit(12.0);
        verify(bank, never()).withdraw(anyDouble());
    }

    @Test
    void missingBankOrUninitializedWealthCannotFundTable() {
        when(bank.getWealth()).thenReturn(null);
        assertEquals(0, GuildBank.balance("guild"));
        assertFalse(GuildBank.withdraw("guild", 1));
        when(guild.getBank()).thenReturn(null);
        assertFalse(GuildBank.canHold("guild"));
        assertEquals(0, GuildBank.balance("guild"));
        assertFalse(GuildBank.withdraw("guild", 1));
        assertFalse(GuildBank.deposit("guild", 1));
        verify(bank, never()).withdraw(anyDouble());
        verify(bank, never()).deposit(anyDouble());
    }

    @Test
    void zeroTransfersAreNoOpsAndProfitIsDeclaredSeparatelyFromDeposits() {
        Ledger ledger = mock(Ledger.class);
        when(guild.getLedger()).thenReturn(ledger);
        assertTrue(GuildBank.withdraw("guild", 0));
        assertTrue(GuildBank.deposit("guild", 0));
        GuildBank.declareProfit("guild", 0);
        verifyNoInteractions(bank, ledger);
        assertTrue(GuildBank.deposit("guild", 20));
        verify(bank).deposit(20.0);
        verifyNoInteractions(ledger);
        GuildBank.declareProfit("guild", 7);
        verify(ledger).addCasinoProfitEntry(7.0);
        verify(bank, times(1)).deposit(anyDouble());
    }

    @Test
    void integrationFailuresFailClosedForEveryMoneyOperation() {
        factions.when(() -> FactionManager.getGuildByString("guild"))
                .thenThrow(new IllegalStateException("Guild data unavailable"));
        assertFalse(GuildBank.exists("guild"));
        assertFalse(GuildBank.canHold("guild"));
        assertEquals(0, GuildBank.balance("guild"));
        assertFalse(GuildBank.withdraw("guild", 5));
        assertFalse(GuildBank.deposit("guild", 5));
        assertDoesNotThrow(() -> GuildBank.declareProfit("guild", 5));
        verifyNoInteractions(bank);
        verify(logger, times(1)).warning("[Games] SimpleFactions guild bank skipped: Guild data unavailable");
    }
}
