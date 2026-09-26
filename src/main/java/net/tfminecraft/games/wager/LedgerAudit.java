package net.tfminecraft.games.wager;

import java.util.UUID;

import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.table.Table;

/**
 * Rules the money on a table must always obey.
 *
 * <p>These only ever complain. Quietly correcting a total would hide the bug that caused it, and
 * a wrong total that gets papered over is how a table ends up inflated with nobody knowing when
 * it started.
 */
public final class LedgerAudit {

    private LedgerAudit() {}

    /** Checked after every movement of money. Cheap enough to leave on. */
    public static void check(Table table, String stage) {
        if (table == null) {
            return;
        }
        TableLedger ledger = table.ledger();
        if (ledger.total() < 0) {
            MoneyLog.mismatch(table, stage + " left the table holding " + ledger.total());
        }
        // Only owners holding a positive balance are listed, so a bucket gone negative shows up in the
        // table total above.
        for (UUID owner : ledger.owners()) {
            for (Stake stake : ledger.stakes(owner)) {
                if (stake.count() < 0) {
                    MoneyLog.mismatch(table, stage + " left a stake counting " + stake.count()
                            + " on " + owner);
                }
            }
        }
    }

    /**
     * What a table read off disk against what the file said it held. A gap here means a stake was
     * dropped on load, which is worth knowing before anyone plays on it.
     */
    public static void checkLoaded(Table table, int expected) {
        checkLoaded(table, expected, 0);
    }

    /** Includes decoded items awaiting ownerless recovery, kept separate from house funds. */
    public static void checkLoaded(Table table, int expected, int unowned) {
        if (table == null) {
            return;
        }
        int held = table.ledger().total() + unowned;
        if (held != expected) {
            MoneyLog.mismatch(table, "loaded holding " + held + " but the file says " + expected);
        } else if (held > 0 && Cache.wagerAuditLog) {
            MoneyLog.note(table, held, "loaded from disk");
        }
    }
}
