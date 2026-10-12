package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.guild.income.Ledger;
import net.tfminecraft.simplefactions.managers.FactionManager;
import org.junit.jupiter.api.Test;

class GuildBankTest {
    private final UUID incarnation = UUID.randomUUID();
    private Table table() {
        Table table = new Table(UUID.randomUUID(), "blackjack", null, 0, null);
        table.setOwnerGuildId("guild");
        table.setOwnerGuildIncarnation(incarnation);
        return table;
    }

    @Test
    void missingOrDisabledGuildIntegrationCannotFundTables() {
        Table table = table();
        try (var guilds = mockStatic(GuildTables.class); var denars = mockStatic(Denars.class)) {
            assertFalse(GuildBank.canHold(table));
            assertEquals(0, GuildBank.balance(table));
            assertFalse(GuildBank.declareProfit(table, "op", 10));
            denars.verifyNoInteractions();
        }
    }

    @Test
    void readsWholeDenarsWithoutAllowingDebtToFundAGame() {
        Table table = table();
        try (var guilds = mockStatic(GuildTables.class); var denars = mockStatic(Denars.class, CALLS_REAL_METHODS)) {
            guilds.when(() -> GuildTables.owns("guild", incarnation)).thenReturn(true);
            denars.when(Denars::ready).thenReturn(true);
            denars.when(() -> Denars.balance("guild:" + incarnation)).thenReturn(1075L, -500L, Long.MAX_VALUE);
            assertEquals(10, GuildBank.balance(table));
            assertEquals(0, GuildBank.balance(table));
            assertEquals(Integer.MAX_VALUE, GuildBank.balance(table));
        }
    }

    @Test
    void indebtedGuildCanReceiveMoneyButCannotFundGames() {
        Table table = table();
        try (var guilds = mockStatic(GuildTables.class); var denars = mockStatic(Denars.class, CALLS_REAL_METHODS)) {
            guilds.when(() -> GuildTables.owns("guild", incarnation)).thenReturn(true);
            denars.when(Denars::ready).thenReturn(true);
            denars.when(() -> Denars.balance("guild:" + incarnation)).thenReturn(-100L);
            assertTrue(GuildBank.canHold(table));
            assertEquals(0, GuildBank.balance(table));
        }
    }

    @Test
    void unavailableLedgerCannotAcceptMoney() {
        Table table = table();
        try (var guilds = mockStatic(GuildTables.class); var denars = mockStatic(Denars.class)) {
            guilds.when(() -> GuildTables.owns("guild", incarnation)).thenReturn(true);
            assertFalse(GuildBank.canHold(table));
            assertEquals(0, GuildBank.balance(table));
        }
    }

    @Test
    void profitUsesTheStableOperationIdAndNeverMovesMoneyAgain() {
        Table table = table();
        Guild guild = mock(Guild.class);
        Ledger ledger = mock(Ledger.class);
        when(guild.getLedger()).thenReturn(ledger);
        when(ledger.addCasinoProfitEntry("games:op", 7.0)).thenReturn(true);
        try (var guilds = mockStatic(GuildTables.class); var denars = mockStatic(Denars.class);
             var factions = mockStatic(FactionManager.class)) {
            guilds.when(() -> GuildTables.owns("guild", incarnation)).thenReturn(true);
            denars.when(Denars::ready).thenReturn(true);
            factions.when(() -> FactionManager.getGuildByString("guild")).thenReturn(guild);
            assertTrue(GuildBank.declareProfit(table, "games:op", 0));
            verifyNoInteractions(ledger);
            assertTrue(GuildBank.declareProfit(table, "games:op", 7));
            assertTrue(GuildBank.declareProfit(table, "games:op", 7));
            verify(ledger, times(2)).addCasinoProfitEntry("games:op", 7.0);
            denars.verify(() -> Denars.ready(), times(2));
            denars.verifyNoMoreInteractions();
        }
    }

    @Test
    void bookkeepingFailureDoesNotClaimCompletion() {
        Table table = table();
        try (var guilds = mockStatic(GuildTables.class); var denars = mockStatic(Denars.class);
             var factions = mockStatic(FactionManager.class)) {
            guilds.when(() -> GuildTables.owns("guild", incarnation)).thenReturn(true);
            denars.when(Denars::ready).thenReturn(true);
            factions.when(() -> FactionManager.getGuildByString("guild")).thenThrow(new IllegalStateException("offline"));
            assertFalse(GuildBank.declareProfit(table, "op", 10));
        }
    }
}
