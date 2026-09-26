package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.games.cache.Cache;

class TableLedgerTest {
    private final double originalRange = Cache.wagerMergeRange;
    private final TableLedger ledger = new TableLedger();
    private final UUID player = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID house = UUID.randomUUID();

    @AfterEach
    void restoreRange() {
        Cache.wagerMergeRange = originalRange;
    }

    private ItemStack item() {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.isSimilar(item)).thenReturn(true);
        return item;
    }

    @Test
    void emptyAndInvalidDepositsNeverCreateMoney() {
        ItemStack item = item();
        ledger.add(null, item, null, 1, 1, 0);
        ledger.add(player, null, null, 1, 1, 0);
        ledger.add(player, item, null, 0, 1, 0);
        ledger.add(player, item, null, 1, 0, 0);
        ledger.put(player, null, null, null);
        assertTrue(ledger.isEmpty());
        assertEquals(0, ledger.total());
        assertEquals(0, ledger.total(player));
        assertEquals(0, ledger.total(player, 1));
        assertTrue(ledger.owners().isEmpty());
        assertTrue(ledger.stakes(player).isEmpty());
        assertTrue(ledger.liveStakes(player).isEmpty());
        assertNull(ledger.template(player));
        assertFalse(ledger.hasAnchor(player));
        assertEquals(0, ledger.anchorX(player));
        assertEquals(0, ledger.anchorZ(player));
        ledger.forget(player);
        assertEquals(0, TableLedger.valueOf(null));
        assertEquals(0, TableLedger.valueOf(List.of()));
        verify(item, never()).clone();
    }

    @Test
    void depositStoresOneItemCloneWithoutChangingTheIncomingStack() {
        ItemStack incoming = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        when(incoming.clone()).thenReturn(copy);
        ledger.add(player, incoming, "coin", 2, 8, 4);
        Stake stored = ledger.stakes(player).getFirst();
        assertSame(copy, stored.item());
        assertEquals(8, stored.count());
        assertEquals(16, stored.value());
        verify(copy).setAmount(1);
        verify(incoming, never()).setAmount(anyInt());
    }

    @Test
    void depositsPreserveOwnerOrderAndReportStreetAndHouseTotals() {
        ItemStack item = item();
        ledger.add(player, item, "coin", 5, 2, 1);
        ledger.add(player, item, "coin", 5, 1, 2);
        ledger.add(other, item, "coin", 5, 4, 1);
        ledger.add(house, item, "coin", 5, 6, 0);
        UUID anchorOnly = UUID.randomUUID();
        ledger.setAnchor(anchorOnly, 1, 2);
        assertFalse(ledger.isEmpty());
        assertEquals(65, ledger.total());
        assertEquals(15, ledger.total(player));
        assertEquals(10, ledger.total(player, 1));
        assertEquals(5, ledger.total(player, 2));
        assertEquals(0, ledger.total(player, 3));
        assertEquals(35, ledger.totalExcept(house));
        assertEquals(Map.of(player, 15, other, 20), ledger.totalsExcept(house));
        assertEquals(List.of(player, other, house), new ArrayList<>(ledger.owners()));
        assertEquals(15, TableLedger.valueOf(ledger.stakes(player)));
        List<Stake> copy = ledger.stakes(player);
        copy.clear();
        assertEquals(2, ledger.stakes(player).size());
        assertSame(ledger.stakes(player).getFirst(), ledger.liveStakes(player).getFirst());
        assertFalse(ledger.hasAnchor(player));
        ledger.forget(player);
        assertEquals(15, ledger.total(player));
        verify(item, times(4)).setAmount(1);
    }

    @Test
    void unplacedDepositsMergeButPlacedDepositsOnlyMergeNearbyHeaps() {
        Cache.wagerMergeRange = 1;
        ItemStack item = item();
        ledger.add(player, item, "coin", 1, 2, 0);
        ledger.add(player, item, "coin", 1, 3, 0);
        assertEquals(5, ledger.stakes(player).getFirst().count());
        ledger.add(player, item, "coin", 1, 4, 0, 0.0, 0.0);
        ledger.add(player, item, "coin", 1, 5, 0, 1.0, 0.0);
        ledger.add(player, item, "coin", 1, 6, 0, 1.01, 0.0);
        assertEquals(3, ledger.stakes(player).size());
        assertEquals(9, ledger.stakes(player).get(1).count());
        assertEquals(6, ledger.stakes(player).get(2).count());
        assertEquals(0, ledger.stakes(player).get(1).x());
        assertEquals(1.01, ledger.stakes(player).get(2).x());
        assertEquals(20, ledger.total());
        ledger.add(other, item, "coin", 1, 2, 0, 4.0, 5.0);
        ledger.add(other, item, "coin", 1, 3, 0);
        assertEquals(1, ledger.stakes(other).size());
        assertEquals(5, ledger.stakes(other).getFirst().count());
        assertTrue(ledger.stakes(other).getFirst().placed());
    }

    @Test
    void partialCoordinatesAreUnplacedAndNegativeMergeRangeClampsToZero() {
        Cache.wagerMergeRange = -1;
        ItemStack item = item();
        ledger.add(player, item, null, 1, 1, 0, null, 2.0);
        ledger.add(other, item, null, 1, 1, 0, 2.0, null);
        assertFalse(ledger.stakes(player).getFirst().placed());
        assertFalse(ledger.stakes(other).getFirst().placed());
        ledger.add(house, item, null, 1, 1, 0, 2.0, 2.0);
        ledger.add(house, item, null, 1, 1, 0, 2.0, 2.0);
        ledger.add(house, item, null, 1, 1, 0, 2.001, 2.0);
        assertEquals(2, ledger.stakes(house).size());
        assertEquals(2, ledger.stakes(house).getFirst().count());
    }

    @Test
    void putUsesDestinationPlacementAndTemplateSelectsSmallestLiveCoin() {
        ItemStack large = item();
        ItemStack small = item();
        ItemStack medium = item();
        Stake source = new Stake(large, "large", 10, 2, 3);
        source.setSpot(9.0, 8.0);
        ledger.put(player, source, 1.0, 2.0);
        Stake deposited = ledger.stakes(player).getFirst();
        assertNotSame(source, deposited);
        assertEquals(1, deposited.x());
        assertEquals(2, deposited.z());
        assertEquals(3, deposited.streetId());
        assertSame(large, ledger.template(player));
        ledger.add(player, small, "small", 1, 1, 3);
        ledger.add(player, medium, "medium", 5, 1, 3);
        assertSame(small, ledger.template(player));
        ledger.liveStakes(player).get(1).split(1);
        assertSame(medium, ledger.template(player));
        ledger.liveStakes(player).forEach(stake -> stake.split(stake.count()));
        assertNull(ledger.template(player));
        assertTrue(ledger.owners().isEmpty());
        assertTrue(ledger.totalsExcept(house).isEmpty());
    }

    @Test
    void tidyRetainsAnchorsAndLiveBucketsButForgetDropsEmptyAnchors() {
        ItemStack item = item();
        ledger.add(player, item, null, 1, 1, 0);
        ledger.add(other, item, null, 1, 1, 0);
        ledger.setAnchor(other, 2, -3);
        ledger.add(house, item, null, 1, 1, 0);
        ledger.liveStakes(player).getFirst().split(1);
        ledger.liveStakes(other).getFirst().split(1);
        ledger.tidy();
        assertTrue(ledger.liveStakes(player).isEmpty());
        assertTrue(ledger.liveStakes(other).isEmpty());
        assertEquals(1, ledger.total(house));
        assertTrue(ledger.hasAnchor(other));
        assertEquals(2, ledger.anchorX(other));
        assertEquals(-3, ledger.anchorZ(other));
        ledger.forget(other);
        assertFalse(ledger.hasAnchor(other));
        assertEquals(0, ledger.anchorX(other));
        assertEquals(0, ledger.anchorZ(other));
        ledger.setAnchor(house, 4, 5);
        ledger.forget(house);
        assertTrue(ledger.hasAnchor(house));
        assertEquals(1, ledger.total(house));
    }
}
