package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.table.Table;

class BucketAccountTest {
    private final Table table = new Table(UUID.randomUUID(), "test", null, 0, null);
    private final UUID owner = UUID.randomUUID();
    private final BucketAccount account = Accounts.bucket(table, owner);

    private Stake coin(int unit, int count, int street) {
        return new Stake(new ItemStack(Material.GOLD_NUGGET), "coin", unit, count, street);
    }

    @Test
    void ownerMetadataDistinguishesPlayerFeltAndHouseTray() {
        assertEquals(owner, account.owner());
        assertEquals(owner, account.feltOwner());
        assertEquals(owner, account.flightTarget());
        assertEquals("felt", account.label());
        assertTrue(account.onFelt());
        assertFalse(account.isTray());
        assertTrue(account.canAccept(10));
        BucketAccount tray = Accounts.tray(table);
        assertEquals(table.getId(), tray.owner());
        assertEquals("tray", tray.label());
        assertTrue(tray.isTray());
        assertNull(tray.flightTarget());
    }

    @Test
    void emptyDepositsAndUnavailableWithdrawalsDoNotChangeLedger() {
        assertEquals(0, account.accept(null));
        assertEquals(0, account.accept(List.of()));
        assertEquals(0, account.accept(List.of(coin(1, 0, 0))));
        assertEquals(0, account.available());
        assertNull(account.planTake(0, null));
        assertNull(account.planTake(1, null));
        assertNull(account.planTakeAll(null));
        assertEquals(0, account.largestTakeUpTo(0, null));
        assertEquals(0, account.largestTakeUpTo(1, null));
        assertTrue(table.ledger().isEmpty());
    }

    @Test
    void exactPlanUsesSmallerCoinsWhenGreedyChoiceCannotPayAndPreservesSourceSpot() {
        account.placedAt(new Location(null, 2, 0, 3));
        assertEquals(11, account.accept(List.of(coin(5, 1, 1), coin(3, 2, 1))));
        assertEquals(6, account.largestTakeUpTo(7, null));
        Withdrawal plan = account.planTake(6, null);
        assertNotNull(plan);
        assertEquals(6, plan.denars());
        assertEquals(11, account.available());
        List<Stake> preview = plan.preview();
        assertEquals(1, preview.size());
        assertEquals(3, preview.getFirst().unit());
        assertEquals(2, preview.getFirst().count());
        assertEquals(1, preview.getFirst().streetId());
        preview.getFirst().setCount(0);
        assertEquals(6, TableLedger.valueOf(plan.preview()));
        List<Stake> taken = plan.take();
        assertEquals(6, TableLedger.valueOf(taken));
        assertTrue(taken.getFirst().placed());
        assertEquals(2, taken.getFirst().x());
        assertEquals(3, taken.getFirst().z());
        assertEquals(5, account.available());
    }

    @Test
    void allPlanFiltersStreetsAndDrainsOnlyMeasuredStakes() {
        account.accept(List.of(coin(2, 3, 1), coin(5, 2, 2)));
        assertNull(account.planTakeAll(3));
        Withdrawal street = account.planTakeAll(1);
        assertNotNull(street);
        assertEquals(6, street.denars());
        assertEquals(6, TableLedger.valueOf(street.preview()));
        assertEquals(16, account.available());
        assertEquals(6, TableLedger.valueOf(street.take()));
        assertEquals(10, account.available());
        Withdrawal rest = account.planTakeAll(null);
        assertNotNull(rest);
        assertEquals(10, rest.denars());
        assertEquals(10, TableLedger.valueOf(rest.take()));
        assertTrue(table.ledger().isEmpty());
        assertNull(account.planTakeAll(null));
    }

    @Test
    void alreadyDrainedSourceCannotBeWithdrawnTwice() {
        account.accept(List.of(coin(2, 2, 0)));
        Withdrawal first = account.planTake(4, null);
        Withdrawal stale = account.planTake(4, null);
        assertEquals(4, TableLedger.valueOf(first.take()));
        assertTrue(stale.take().isEmpty());
        assertEquals(0, account.available());
    }

    @Test
    void placementKeepsFirstBucketAnchorAndAtResetsExplicitPlacement() {
        assertSame(account, account.at(new Location(null, 1, 0, 2)));
        account.accept(List.of(coin(1, 1, 0)));
        assertEquals(1, table.ledger().anchorX(owner));
        assertEquals(2, table.ledger().anchorZ(owner));
        assertFalse(table.ledger().stakes(owner).getFirst().placed());
        assertSame(account, account.placedAt(new Location(null, 8, 0, 9)));
        account.accept(List.of(coin(1, 1, 1)));
        Stake placed = table.ledger().stakes(owner).get(1);
        assertTrue(placed.placed());
        assertEquals(8, placed.x());
        assertEquals(9, placed.z());
        assertEquals(1, table.ledger().anchorX(owner));
        assertEquals(2, table.ledger().anchorZ(owner));
        account.at(new Location(null, 4, 0, 5));
        account.accept(List.of(coin(1, 1, 2)));
        assertFalse(table.ledger().stakes(owner).get(2).placed());
        account.placedAt(null);
        account.accept(List.of(coin(1, 1, 3)));
        assertFalse(table.ledger().stakes(owner).get(3).placed());
    }
}
