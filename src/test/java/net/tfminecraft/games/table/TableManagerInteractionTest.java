package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.gui.GameSelectGui;

class TableManagerInteractionTest extends TableManagerFixture {
    @Test void armedPlacementUsesTheSurfaceTopAndRetainsArmAfterRefusedSideClick() {
        manager.armPlace(player, "freeplay", false);
        Block surface = surface(2, 64, 3, 1, .5, 1);
        assertFalse(interact(surface, BlockFace.NORTH, EquipmentSlot.HAND).isCancelled());
        assertEquals("place.need_surface", player.nextMessage());
        assertTrue(manager.tables().isEmpty());
        assertTrue(interact(surface, BlockFace.UP, EquipmentSlot.HAND).isCancelled());
        Table table = manager.tables().iterator().next();
        assertEquals(2.5, table.getOrigin().getX());
        assertEquals(64.5 + Cache.tableYOffset, table.getOrigin().getY());
        assertEquals(3.5, table.getOrigin().getZ());
        assertEquals(player.getLocation().getYaw(), table.getYaw());
        assertEquals("place.done", player.nextMessage());
    }

    @Test void thinSurfacesAndOffhandClicksDoNotConsumeAnArmedDeck() {
        manager.armPlace(player, "freeplay", true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER, 2));
        assertFalse(interact(surface(0, 64, 0, .1, 1, 1), BlockFace.UP, EquipmentSlot.OFF_HAND).isCancelled());
        assertFalse(interact(surface(0, 64, 0, .1, 1, 1), BlockFace.UP, EquipmentSlot.HAND).isCancelled());
        assertFalse(interact(surface(0, 64, 0, 1, 1, .1), BlockFace.UP, EquipmentSlot.HAND).isCancelled());
        assertTrue(manager.tables().isEmpty());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(interact(surface(0, 64, 0, 1, 1, 1), BlockFace.UP, EquipmentSlot.HAND).isCancelled());
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(1, manager.tables().size());
    }

    @Test void unarmedDeckOpensGameSelectionAtSurfaceButOrdinaryItemsDoNot() {
        Block surface = surface(3, 64, 4, 1, 1, 1);
        try (var gui = mockStatic(GameSelectGui.class)) {
            when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(false);
            assertFalse(interact(surface, BlockFace.UP, EquipmentSlot.HAND).isCancelled());
            gui.verifyNoInteractions();
            when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(true);
            assertTrue(interact(surface, BlockFace.UP, EquipmentSlot.HAND).isCancelled());
            gui.verify(() -> GameSelectGui.open(any(Player.class), eq(true), argThat(at ->
                    at.getWorld().equals(world) && at.getX() == 3.5 && at.getY() == 65 && at.getZ() == 4.5)));
            assertTrue(manager.tables().isEmpty(), "Selecting a game must precede placement");
        }
    }

    @Test void pickupRemovesSavedTableAndDisplaysAndReturnsThePhysicalDeck() throws Exception {
        Table table = place(false);
        manager.dealToPlayer(table, player, 1); tick(3);
        List<UUID> handTokens = table.handOf(player.getUniqueId()).stream().map(HandCard::tokenId).toList();
        var file = data.resolve("Data/tables/" + table.getId() + ".json");
        assertTrue(Files.exists(file));
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(anchor);
        when(event.getDamager()).thenReturn(player);
        manager.onHitEntity(event); tick(5);
        verify(event).setCancelled(true);
        assertTrue(manager.tables().isEmpty());
        assertFalse(Files.exists(file));
        assertTrue(table.getHands().isEmpty());
        for (UUID token : handTokens) verify(display, atLeastOnce()).despawn(token);
        verify(game).onTableRemoved(table);
        List<Item> drops = world.getEntities().stream().filter(Item.class::isInstance).map(Item.class::cast).toList();
        assertEquals(1, drops.size());
        assertEquals(new ItemStack(Material.PAPER), drops.getFirst().getItemStack());
    }

    @Test void unrelatedEntityDamageDoesNotPickUpATable() {
        Table table = place(false);
        Entity anchor = mock(Entity.class);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(anchor);
        when(event.getDamager()).thenReturn(mock(Entity.class));
        manager.onHitEntity(event);
        when(event.getDamager()).thenReturn(player);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn("not-an-id");
        manager.onHitEntity(event);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(UUID.randomUUID().toString());
        manager.onHitEntity(event);
        verify(event, never()).setCancelled(true);
        assertSame(table, manager.table(table.getId()));
    }

    @Test void chunkReloadRebuildsOnlyTablesInsideTheLoadedChunk() {
        Table table = place(false);
        List<UUID> originalTokens = List.copyOf(table.getStackTokens());
        clearInvocations(display);
        Chunk chunk = mock(Chunk.class);
        ChunkLoadEvent event = mock(ChunkLoadEvent.class);
        when(event.getWorld()).thenReturn(world);
        when(event.getChunk()).thenReturn(chunk);
        when(chunk.getX()).thenReturn(1);
        when(chunk.getZ()).thenReturn(0);
        manager.onChunkLoad(event);
        verifyNoInteractions(display);
        when(chunk.getX()).thenReturn(0);
        manager.onChunkLoad(event);
        for (UUID token : originalTokens) verify(display).despawn(token);
        assertEquals(originalTokens, table.getStackTokens());
        verify(display, times(Cache.stackVisibleMax)).spawn(any(), any(), any(), any());
        assertEquals(2, table.getDeck().remaining());
    }

    @Test void dealerQuitClearsSeatAndNotifiesTheGameWithoutRemovingTheTable() {
        Table table = place(false);
        table.setDealerId(player.getUniqueId());
        manager.onQuit(new PlayerQuitEvent(player, "quit"));
        assertNull(table.dealerId());
        verify(game).onDealerGone(table);
        assertSame(table, manager.table(table.getId()));
    }

    private Block surface(int x, int y, int z, double width, double height, double depth) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x); when(block.getZ()).thenReturn(z);
        when(block.getLocation()).thenAnswer(call -> new Location(world, x, y, z));
        when(block.getBoundingBox()).thenReturn(new BoundingBox(x, y, z, x + width, y + height, z + depth));
        return block;
    }

    private PlayerInteractEvent interact(Block block, BlockFace face, EquipmentSlot hand) {
        Player input = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doReturn(null).when(input).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(input, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), block, face, hand);
        manager.onInteractBlock(event);
        return event;
    }
}
