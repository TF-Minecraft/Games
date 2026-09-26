package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class StakeTest {
    @Test
    void storesIdentityAndClampsNegativeAmounts() {
        ItemStack item = mock(ItemStack.class);
        Stake stake = new Stake(item, "coin", 5, 3, 2);
        assertSame(item, stake.item());
        assertEquals("coin", stake.typeKey());
        assertEquals(5, stake.unit());
        assertEquals(3, stake.count());
        assertEquals(2, stake.streetId());
        assertEquals(15, stake.value());
        Stake invalid = new Stake(null, null, -1, -2, 0);
        assertEquals(0, invalid.unit());
        assertEquals(0, invalid.count());
        assertEquals(0, invalid.value());
        stake.addCount(2);
        stake.addCount(0);
        stake.addCount(-10);
        assertEquals(5, stake.count());
        stake.setCount(7);
        assertEquals(35, stake.value());
        stake.setCount(-1);
        assertEquals(0, stake.count());
    }

    @Test
    void placementRequiresBothCoordinatesAndResetClearsOldPosition() {
        Stake stake = new Stake(null, null, 1, 2, 0);
        assertFalse(stake.placed());
        stake.setSpot(2.0, 3.0);
        assertTrue(stake.placed());
        assertEquals(2, stake.x());
        assertEquals(3, stake.z());
        assertEquals(25, stake.distanceSq(5, 7));
        stake.setSpot(null, 3.0);
        assertFalse(stake.placed());
        assertEquals(0, stake.x());
        assertEquals(0, stake.z());
        stake.setSpot(2.0, 3.0);
        stake.setSpot(2.0, null);
        assertFalse(stake.placed());
        assertEquals(0, stake.x());
        assertEquals(0, stake.z());
    }

    @Test
    void sameKindRequiresMatchingValueStreetTypeAndActualItem() {
        ItemStack item = mock(ItemStack.class);
        ItemStack similar = mock(ItemStack.class);
        ItemStack different = mock(ItemStack.class);
        when(item.isSimilar(similar)).thenReturn(true);
        Stake stake = new Stake(item, "coin", 5, 3, 2);
        assertTrue(stake.sameKind(similar, "coin", 5, 2));
        assertFalse(stake.sameKind(similar, "coin", 1, 2));
        assertFalse(stake.sameKind(similar, "coin", 5, 1));
        assertFalse(stake.sameKind(similar, "other", 5, 2));
        assertFalse(stake.sameKind(similar, null, 5, 2));
        assertFalse(stake.sameKind(different, "coin", 5, 2));
        Stake untyped = new Stake(item, null, 5, 3, 2);
        assertTrue(untyped.sameKind(similar, null, 5, 2));
        assertFalse(untyped.sameKind(similar, "coin", 5, 2));
        assertFalse(new Stake(null, null, 5, 3, 2).sameKind(similar, null, 5, 2));
    }

    @Test
    void splitConservesValueClampsRequestsAndPreservesPlacement() {
        ItemStack item = mock(ItemStack.class);
        Stake stake = new Stake(item, "coin", 5, 6, 2);
        Stake empty = stake.split(-4);
        assertEquals(0, empty.count());
        assertFalse(empty.placed());
        assertEquals(6, stake.count());
        stake.setSpot(2.0, -3.0);
        Stake part = stake.split(2);
        assertEquals(4, stake.count());
        assertEquals(2, part.count());
        assertSame(item, part.item());
        assertEquals("coin", part.typeKey());
        assertEquals(5, part.unit());
        assertEquals(2, part.streetId());
        assertTrue(part.placed());
        assertEquals(2, part.x());
        assertEquals(-3, part.z());
        assertEquals(30, stake.value() + part.value());
        Stake remainder = stake.split(100);
        assertEquals(4, remainder.count());
        assertEquals(0, stake.count());
    }
}
