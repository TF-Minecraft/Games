package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.table.Table;

class MoneyAuditTest {
    private Games previous;
    private boolean previousAudit;
    private WagerItemOverride previousGold;
    private Logger logger;
    private Table table;

    @BeforeEach void setUp() {
        previous = Games.plugin;
        previousAudit = Cache.wagerAuditLog;
        previousGold = Cache.wagerGold;
        Cache.wagerGold = new WagerItemOverride("GOLD_NUGGET", 1, null, null, null, null, null, null, false, null);
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
        table = new Table(UUID.randomUUID(), "poker", null, 0, null);
    }
    @AfterEach void tearDown() {
        Games.plugin = previous;
        Cache.wagerAuditLog = previousAudit;
        Cache.wagerGold = previousGold;
    }

    @Test void routineTransfersRespectAuditSettingAndKeepTableAndReasonContext() {
        Cache.wagerAuditLog = false;
        MoneyLog.move(table, "player", "felt", 5, "bet");
        verifyNoInteractions(logger);
        Cache.wagerAuditLog = true;
        MoneyLog.move(table, "player", "felt", 0, "bet");
        verifyNoInteractions(logger);
        MoneyLog.move(table, "player", "felt", 5, "bet");
        verify(logger).info("[Money] " + table.getId() + " player -> felt 5 (bet)");
        MoneyLog.note(null, 1, null);
        verify(logger).info("[Money] no-table note 1");
        MoneyLog.note(table, 2, " ");
        verify(logger).info("[Money] " + table.getId() + " note 2");
        Games.plugin = null;
        MoneyLog.note(table, 3, "shutting down");
        verify(logger, times(3)).info(anyString());
    }

    @Test void loadedBalanceMismatchIsAlwaysReportedWithoutChangingMoney() {
        UUID owner = UUID.randomUUID();
        table.ledger().add(owner, new ItemStack(Material.GOLD_NUGGET), "coin", 1, 10, 1);
        Cache.wagerAuditLog = false;
        LedgerAudit.check(table, "settled");
        LedgerAudit.checkLoaded(table, 10);
        verifyNoInteractions(logger);
        LedgerAudit.checkLoaded(table, 12);
        verify(logger).warning("[Money] " + table.getId() + " DOES NOT BALANCE: loaded holding 10 but the file says 12");
        assertEquals(10, table.ledger().total());
        Cache.wagerAuditLog = true;
        LedgerAudit.checkLoaded(table, 10);
        verify(logger).info("[Money] " + table.getId() + " note 10 (loaded from disk)");
        LedgerAudit.checkLoaded(new Table(UUID.randomUUID(), "", null, 0, null), 0);
        LedgerAudit.checkLoaded(null, 0);
        LedgerAudit.check(null, "gone");
        verify(logger, times(1)).info(anyString());
    }

    @Test void mismatchLoggingHandlesUnplacedTablesAndPluginShutdown() {
        Cache.wagerAuditLog = false;
        MoneyLog.mismatch(null, "unplaced transfer");
        verify(logger).warning("[Money] no-table DOES NOT BALANCE: unplaced transfer");
        Games.plugin = null;
        MoneyLog.mismatch(table, "shutdown");
        verify(logger, times(1)).warning(anyString());
    }

    @Test void corruptSavedCountsThatOverflowAreReportedAfterTheNextMovement() {
        UUID hoarder = UUID.randomUUID();
        UUID bettor = UUID.randomUUID();
        // Two saved heaps of the same kind merge on load, so a tampered file can push a count past the int range.
        table.ledger().add(hoarder, new ItemStack(Material.GOLD_NUGGET), "coin", 1, Integer.MAX_VALUE, 1);
        table.ledger().add(hoarder, new ItemStack(Material.GOLD_NUGGET), "coin", 1, 1, 1);
        table.ledger().add(bettor, new ItemStack(Material.GOLD_NUGGET), "coin", 1, 5, 1);
        assertEquals(2, settle(bettor));
        verify(logger).warning("[Money] " + table.getId() + " DOES NOT BALANCE: settle left the table holding "
                + table.ledger().total());
        assertTrue(table.ledger().total() < 0);
        assertEquals(7, table.ledger().total(bettor));
    }

    @Test void negativeHeapHiddenInsideAPositiveBucketIsStillReported() {
        UUID hoarder = UUID.randomUUID();
        table.ledger().add(hoarder, new ItemStack(Material.GOLD_NUGGET), "coin", 1, Integer.MAX_VALUE, 1);
        table.ledger().add(hoarder, new ItemStack(Material.GOLD_NUGGET), "coin", 1, 1, 1);
        table.ledger().add(hoarder, new ItemStack(Material.DIAMOND), "gem", 1, Integer.MAX_VALUE, 1);
        table.ledger().add(hoarder, new ItemStack(Material.EMERALD), "emerald", 1, 5, 1);
        assertEquals(4, table.ledger().total(hoarder));
        assertEquals(2, settle(hoarder));
        assertEquals(6, table.ledger().total(hoarder));
        verify(logger).warning("[Money] " + table.getId() + " DOES NOT BALANCE: settle left a stake counting "
                + Integer.MIN_VALUE + " on " + hoarder);
        verify(logger, never()).warning(contains("left the table holding"));
    }

    /** Staff money onto the felt: a movement that leaves the existing heaps as they were loaded. */
    private int settle(UUID owner) {
        table.setStaffMint(true);
        return new MoneyTx(mock(WagerHost.class), table, "settle")
                .move(Accounts.mint(table), Accounts.bucket(table, owner), 2, new ItemStack(Material.GOLD_NUGGET))
                .commit()
                .moved();
    }
}
