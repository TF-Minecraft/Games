package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.gui.GameSelectGui;

/** Clicks, swaps and sneaks that should or should not reach a table. */
class TableManagerInputTest extends TableManagerFixture {
    private final Map<UUID, DisplayPose> poses = new HashMap<>();
    private final Map<UUID, ItemStack> publicItems = new HashMap<>();
    private int oldFlip, oldStagger, oldSelect;

    @BeforeEach void trackDisplays() {
        oldFlip = Cache.handRevealFlip;
        oldStagger = Cache.handRevealStagger;
        oldSelect = Cache.handSelectTicks;
        Cache.handRevealFlip = 2;
        Cache.handRevealStagger = 1;
        Cache.handSelectTicks = 3;
        when(items.getCreator().getItemFromPath("face")).thenReturn(new ItemStack(Material.DIAMOND));
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            poses.put(call.getArgument(0), call.getArgument(3));
            publicItems.put(call.getArgument(0), call.getArgument(2));
            return true;
        });
        when(display.poseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        when(display.otherPoseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        doAnswer(call -> { poses.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(display).setTransform(any(), any(), anyInt());
        doAnswer(call -> { poses.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(display).setTransformSplit(any(), any(), any(), anyInt());
        doAnswer(call -> { publicItems.computeIfPresent(call.getArgument(0), (k, v) -> call.getArgument(1)); return null; })
                .when(display).setItem(any(), any());
        doAnswer(call -> { poses.remove(call.getArgument(0)); publicItems.remove(call.getArgument(0)); return null; })
                .when(display).despawn(any());
    }

    @AfterEach void restoreHandTiming() {
        Cache.handRevealFlip = oldFlip;
        Cache.handRevealStagger = oldStagger;
        Cache.handSelectTicks = oldSelect;
    }

    @Test void leftClickingABlockWithNoCardInViewIsLeftToTheWorld() {
        PlayerInteractEvent click = press(Action.LEFT_CLICK_BLOCK, world.getBlockAt(0, 64, 0));
        assertFalse(click.isCancelled());
        assertNull(player.nextMessage());
    }

    @Test void pressurePlatesNeitherPlaceAnArmedTableNorSpendTheArm() {
        manager.armPlace(player, "freeplay", false);
        PlayerInteractEvent step = press(Action.PHYSICAL, world.getBlockAt(0, 64, 0));
        assertFalse(step.isCancelled());
        assertTrue(manager.tables().isEmpty());
        assertTrue(manager.tryPlace(player, new Location(world, 0, 65, 0)), "The arm is still live");
    }

    @Test void rightClickingTheAirWhileArmedWaitsForASurface() {
        manager.armPlace(player, "freeplay", false);
        press(Action.RIGHT_CLICK_AIR);
        assertTrue(manager.tables().isEmpty());
        assertNull(player.nextMessage(), "No surface complaint for a click at nothing");
        assertTrue(manager.tryPlace(player, new Location(world, 0, 65, 0)));
    }

    @Test void holdingCardsStopsADeckFromOpeningGameSelection() {
        Table table = place(false);
        manager.dealToPlayer(table, player, 1);
        tick(3);
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER));
        Block surface = mock(Block.class);
        when(surface.getX()).thenReturn(20);
        when(surface.getZ()).thenReturn(20);
        when(surface.getLocation()).thenAnswer(call -> new Location(world, 20, 64, 20));
        when(surface.getBoundingBox()).thenReturn(new BoundingBox(20, 64, 20, 21, 65, 21));
        try (var gui = mockStatic(GameSelectGui.class)) {
            Player input = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
            doReturn(null).when(input).rayTraceBlocks(anyDouble());
            PlayerInteractEvent click = new PlayerInteractEvent(input, Action.RIGHT_CLICK_BLOCK,
                    player.getInventory().getItemInMainHand(), surface, BlockFace.UP, EquipmentSlot.HAND);
            manager.onInteractBlock(click);
            assertFalse(click.isCancelled());
            gui.verifyNoInteractions();
        }
        assertEquals(1, table.handOf(player.getUniqueId()).size());
    }

    @Test void sneakingWithoutAnArmSaysNothing() {
        manager.onSneak(new PlayerToggleSneakEvent(player, true));
        assertNull(player.nextMessage());
    }

    @Test void swappingWithoutCardsKeepsTheNormalOffhandSwap() {
        Table table = place(false);
        assertFalse(swap().isCancelled());
        manager.relayoutHand(table, player);
        assertTrue(table.getHands().containsKey(player.getUniqueId()));
        assertFalse(swap().isCancelled(), "An empty hand has nothing to reveal");
    }

    @Test void aGameThatLocksRevealsSwallowsTheSwapButKeepsCardsPrivate() {
        Table table = place(false);
        manager.dealToPlayer(table, player, 2);
        tick(3);
        List<HandCard> hand = table.handOf(player.getUniqueId());
        assertTrue(swap().isCancelled());
        tick(10);
        for (HandCard held : hand) {
            assertEquals(Material.PAPER, publicItems.get(held.tokenId()).getType());
            assertFalse(held.faceUp());
        }
    }

    @Test void entitiesThatAreNotTablesAreIgnored() {
        place(false);
        Entity stranger = mock(Entity.class);
        PlayerInteractAtEntityEvent click = new PlayerInteractAtEntityEvent(player, stranger, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(click);
        assertFalse(click.isCancelled());
    }

    @Test void aLiveTableOfARetiredGameIgnoresShoeClicks() throws Exception {
        Table table = loadRetired();
        manager.beginSession(table);
        assertTrue(table.live());
        assertTrue(shoeClick(table).isCancelled());
        tick(3);
        assertEquals(2, table.getDeck().remaining());
        assertFalse(table.getHands().containsKey(player.getUniqueId()));
    }

    @Test void anIdleRetiredGameTableStillDealsAFreeCard() throws Exception {
        Table table = loadRetired();
        assertTrue(shoeClick(table).isCancelled());
        tick(3);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(1, table.getDeck().remaining());
    }

    @Test void aGameClaimingTheDealerSeatConsumesTheShoeClick() {
        Table table = place(false);
        when(game.tryClaimDealer(table, player)).thenReturn(true);
        when(game.allowFreeDraw(any(), any())).thenReturn(true);
        assertTrue(shoeClick(table).isCancelled());
        tick(3);
        assertEquals(2, table.getDeck().remaining());
        verify(game, never()).allowFreeDraw(any(), any());
    }

    @Test void anIdleTableWhoseGameAllowsNeitherReturnsNorDrawsDealsNothing() {
        Table table = place(false);
        assertTrue(shoeClick(table).isCancelled());
        tick(3);
        assertEquals(2, table.getDeck().remaining());
        assertFalse(table.getHands().containsKey(player.getUniqueId()));
    }

    @Test void onALiveTableWithNothingSelectedTheShoeClickGoesToTheGame() {
        when(game.allowReturnSelected(any(), any())).thenReturn(true);
        Table table = place(false);
        manager.dealToPlayer(table, player, 1);
        tick(3);
        manager.beginSession(table);
        assertTrue(shoeClick(table).isCancelled());
        verify(game).onShoeClick(table, player);
        verify((net.tfminecraft.games.game.LiveCardReturns) game, never()).onReturnedSelected(any(), any(), anyInt());
        assertEquals(1, table.handOf(player.getUniqueId()).size());
    }

    @Test void onALiveTableTheShoeReturnsSelectedCardsOnlyOnceTheRevealHasSettled() {
        when(game.allowReturnSelected(any(), any())).thenReturn(true);
        when(game.allowRevealToggle(any(), any())).thenReturn(true);
        Table table = place(false);
        manager.dealToPlayer(table, player, 2);
        tick(3);
        HandCard chosen = table.handOf(player.getUniqueId()).getFirst();
        Location aim = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.5));
        when(display.worldLocation(chosen.tokenId())).thenReturn(aim);
        assertTrue(press(Action.RIGHT_CLICK_AIR).isCancelled());
        assertTrue(chosen.isSelected());
        tick(4);
        manager.beginSession(table);
        assertTrue(swap().isCancelled());
        shoeClick(table);
        verify(game).onShoeClick(table, player);
        assertEquals(2, table.handOf(player.getUniqueId()).size(), "Nothing leaves the hand mid-reveal");
        tick(10);
        shoeClick(table);
        verify((net.tfminecraft.games.game.LiveCardReturns) game).onReturnedSelected(table, player, 1);
        verify(game, times(1)).onShoeClick(table, player);
        tick(10);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(2, table.handOf(player.getUniqueId()).size() + table.getDeck().remaining()
                + table.getDeck().discarded());
    }

    private Table loadRetired() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = new JsonObject();
        saved.addProperty("id", id.toString());
        saved.addProperty("gameId", "retired");
        saved.addProperty("world", world.getName());
        saved.addProperty("y", 65);
        saved.addProperty("setName", Cache.pokerCardSet);
        saved.add("remaining", new Gson().toJsonTree(List.of("one", "two")));
        saved.add("discarded", new JsonArray());
        var folder = data.resolve("Data/tables");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(id + ".json"), saved.toString());
        manager.loadAll();
        return manager.table(id);
    }

    private PlayerInteractEvent press(Action action) {
        return press(action, null);
    }

    private PlayerInteractEvent press(Action action, Block block) {
        Player input = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doReturn(null).when(input).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(input, action, null, block,
                block != null ? BlockFace.UP : null, EquipmentSlot.HAND);
        manager.onInteractBlock(event);
        return event;
    }

    private PlayerSwapHandItemsEvent swap() {
        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player, new ItemStack(Material.AIR),
                new ItemStack(Material.AIR));
        manager.onSwapHands(event);
        return event;
    }

    private PlayerInteractAtEntityEvent shoeClick(Table table) {
        Entity shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
        PlayerInteractAtEntityEvent event = new PlayerInteractAtEntityEvent(player, shoe, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(event);
        return event;
    }
}
