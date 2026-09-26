package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.display.WorldAnchors;

/** A game can switch off the free-play conveniences its rules do not allow. */
class TableManagerGameRulesTest extends TableManagerFixture {

    @Test void aGameThatForbidsFreeDrawingKeepsTheShoeShutToIdleClicks() {
        Table table = place(false);
        when(game.allowFreeDraw(table, player)).thenReturn(false);
        when(game.allowReturnSelected(table, player)).thenReturn(false);
        while (player.nextMessage() != null) { }
        Entity shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
        PlayerInteractAtEntityEvent click = new PlayerInteractAtEntityEvent(player, shoe, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(click);
        tick(5);
        assertTrue(click.isCancelled());
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining());
        assertNull(player.nextMessage());
    }

    @Test void aGameThatForbidsShowingCardsKeepsTheHandPrivateOnTheSwapKey() {
        Table table = place(false);
        when(items.getCreator().getItemFromPath("face")).thenReturn(new ItemStack(Material.DIAMOND));
        manager.dealToPlayer(table, player, 1);
        tick(2);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        when(game.allowRevealToggle(table, player)).thenReturn(false);
        clearInvocations(display);
        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(player,
                new ItemStack(Material.AIR), new ItemStack(Material.AIR));
        manager.onSwapHands(swap);
        tick(10);
        assertTrue(swap.isCancelled(), "the key still belongs to the table, not the offhand");
        assertFalse(held.faceUp());
        verify(display, never()).setItem(eq(held.tokenId()), any());
        assertEquals(1, table.handOf(player.getUniqueId()).size());
    }
}
