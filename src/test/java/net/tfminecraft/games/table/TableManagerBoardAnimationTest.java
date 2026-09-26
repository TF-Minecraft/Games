package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;

class TableManagerBoardAnimationTest extends TableManagerFixture {
    private final Set<UUID> visible = new HashSet<>();

    @BeforeEach void trackDisplays() {
        Cache.handDealTicks = 4;
        doAnswer(call -> visible.add(call.getArgument(0))).when(display).spawn(any(), any(), any(), any());
        doAnswer(call -> { visible.remove(call.getArgument(0)); return null; }).when(display).despawn(any());
    }

    @Test void failedCourierSpawnReturnsItsCardAndDoesNotBlockTheNextBoardDeal() {
        Table table = place(false);
        ItemStack face = new ItemStack(Material.DIAMOND);
        when(items.getCreator().getItemFromPath("face")).thenReturn(face);
        doReturn(false).when(display).spawn(any(), any(), eq(face), any());
        Runnable failed = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, failed);
        tick(8);
        assertTrue(table.tablePile("board").isEmpty());
        assertEquals(1, table.getDeck().remaining());
        assertEquals(1, table.getDeck().discarded());
        verify(failed).run();
        assertOnlyStacksVisible(table);
        doAnswer(call -> visible.add(call.getArgument(0))).when(display).spawn(any(), any(), eq(face), any());
        Runnable next = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, next);
        tick(12);
        assertEquals(1, table.tablePile("board").size());
        assertEquals(1, table.getDeck().discarded());
        assertEquals(0, table.getDeck().remaining());
        verify(next).run();
    }

    @Test void muckingBoardDuringFlightCancelsItsArrivalAndKeepsTheCardRecoverable() {
        Table table = place(false);
        Runnable cancelled = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, cancelled);
        tick(2);
        manager.muckTable(table, "board");
        tick(12);
        assertTrue(table.tablePilesEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
        assertOnlyStacksVisible(table);
        verifyNoInteractions(cancelled);
        Runnable next = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, next);
        tick(12);
        assertEquals(1, table.tablePile("board").size());
        assertEquals(1, table.getDeck().remaining() + table.getDeck().discarded());
        verify(next).run();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void pickupBeforeOrDuringFlightRemovesEveryDisplayAndPreventsLateArrival(int elapsedTicks) {
        Table table = place(false);
        Runnable cancelled = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, cancelled);
        tick(elapsedTicks);
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(player);
        manager.onHitEntity(hit);
        tick(12);
        assertTrue(manager.tables().isEmpty());
        assertTrue(table.tablePilesEmpty());
        var drops = world.getEntities().stream().filter(org.bukkit.entity.Item.class::isInstance)
                .map(org.bukkit.entity.Item.class::cast).toList();
        assertEquals(1, drops.size());
        assertEquals(new ItemStack(Material.PAPER), drops.getFirst().getItemStack());
        assertTrue(visible.isEmpty(), "No travelling card may outlive its table");
        verifyNoInteractions(cancelled);
    }

    private void assertOnlyStacksVisible(Table table) {
        Set<UUID> stacks = new HashSet<>(table.getStackTokens());
        stacks.addAll(table.getDiscardTokens());
        assertEquals(stacks, visible);
    }
}
