package net.tfminecraft.games.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.utils.Keys;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class GameSelectGuiTest {
    private Games previousPlugin;
    private PlayerMock player;
    private TableManager manager;
    private ItemAPI items;
    private MockedStatic<TableManager> managers;
    private MockedStatic<TLibs> libs;

    @BeforeEach
    void setUp() {
        previousPlugin = Games.plugin;
        Games.plugin = mock(Games.class);
        when(Games.plugin.getName()).thenReturn("Games");
        when(Games.plugin.namespace()).thenReturn("games");
        when(Games.plugin.isEnabled()).thenReturn(true);
        player = MockBukkit.getMock().addPlayer();
        manager = mock(TableManager.class);
        managers = mockStatic(TableManager.class);
        managers.when(TableManager::get).thenReturn(manager);
        items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        when(items.getCreator().getItemFromPath(anyString())).thenReturn(null);
        libs = mockStatic(TLibs.class);
        libs.when(TLibs::getItemAPI).thenReturn(items);
    }

    @AfterEach
    void tearDown() {
        libs.close();
        managers.close();
        Games.plugin = previousPlugin;
    }

    @Test
    void opensFourChoicesWithPaperFallbackAndCopiesPendingHit() {
        Location pending = player.getLocation();
        Location original = pending.clone();
        GameSelectGui.open(player, true, pending);
        Inventory inventory = player.getOpenInventory().getTopInventory();
        GameSelectHolder holder = (GameSelectHolder) inventory.getHolder();
        assertSame(inventory, holder.getInventory());
        assertEquals(27, inventory.getSize());
        assertTrue(holder.requireDeck());
        pending.add(10, 0, 0);
        assertEquals(original, holder.pendingHit());
        assertChoice(inventory, 10, "poker");
        assertChoice(inventory, 12, "draw");
        assertChoice(inventory, 14, "freeplay");
        assertChoice(inventory, 16, "blackjack");
    }

    @Test
    void configuredIconIsClonedBeforeMenuMetadataIsAdded() {
        ItemStack source = new ItemStack(Material.DIAMOND);
        when(items.getCreator().getItemFromPath(Cache.iconOf("poker"))).thenReturn(source);
        GameSelectGui.open(player, false, null);
        ItemStack shown = player.getOpenInventory().getTopInventory().getItem(10);
        assertEquals(Material.DIAMOND, shown.getType());
        assertFalse(source.getItemMeta().getPersistentDataContainer().has(Keys.guiGame()));
        assertEquals("poker", shown.getItemMeta().getPersistentDataContainer()
                .get(Keys.guiGame(), PersistentDataType.STRING));
    }

    @ParameterizedTest
    @CsvSource({"12, draw", "14, freeplay"})
    void directChoicesSelectGameWithOriginalPlacementContext(int slot, String game) {
        Location pending = player.getLocation();
        GameSelectGui.open(player, true, pending);
        InventoryClickEvent event = click(slot);
        assertTrue(event.isCancelled());
        verify(manager).selectGame(player, game, pending, true);
        assertNull(player.getOpenInventory().getTopInventory());
        assertEquals(1, player.getHeardSounds().size());
    }

    @ParameterizedTest
    @CsvSource({"10, poker", "16, blackjack"})
    void configuredGamesNavigateToOptionsKeepingPlacementContext(int slot, String game) {
        Location pending = player.getLocation();
        GameSelectGui.open(player, true, pending);
        click(slot);
        TableOptionsHolder options = (TableOptionsHolder) player.getOpenInventory().getTopInventory().getHolder();
        assertEquals(game, options.gameId());
        assertEquals(pending, options.pendingHit());
        assertTrue(options.requireDeck());
        verifyNoInteractions(manager);
    }

    @Test
    void menuCancelsBottomInventoryBlankSlotAndDragWithoutSelection() {
        GameSelectGui.open(player, false, null);
        assertTrue(click(27).isCancelled());
        assertTrue(click(0).isCancelled());
        InventoryDragEvent drag = new InventoryDragEvent(player.getOpenInventory(), new ItemStack(Material.STONE),
                new ItemStack(Material.STONE, 2), false, Map.of(0, new ItemStack(Material.STONE)));
        GameSelectGui.INSTANCE.onDrag(drag);
        assertTrue(drag.isCancelled());
        verifyNoInteractions(manager);
        assertInstanceOf(GameSelectHolder.class, player.getOpenInventory().getTopInventory().getHolder());
    }

    @Test
    void clicksOutsideTheMenuOrOnAnEmptySlotReportedAsAirSelectNothing() {
        GameSelectGui.open(player, false, null);
        assertTrue(click(InventoryView.OUTSIDE).isCancelled());
        // Paper hands back an AIR stack rather than null for an empty slot in the clicked view.
        InventoryView view = player.getOpenInventory();
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(view.getTopInventory());
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(view.getTopInventory());
        when(event.getView()).thenReturn(view);
        when(event.getCurrentItem()).thenReturn(new ItemStack(Material.AIR));
        GameSelectGui.INSTANCE.onClick(event);
        verify(event).setCancelled(true);
        verifyNoInteractions(manager);
        assertTrue(player.getHeardSounds().isEmpty());
        assertInstanceOf(GameSelectHolder.class, player.getOpenInventory().getTopInventory().getHolder());
    }

    @Test
    void iconConfiguredAsAirLeavesThatChoiceEmpty() {
        when(items.getCreator().getItemFromPath(Cache.iconOf("draw"))).thenReturn(new ItemStack(Material.AIR));
        GameSelectGui.open(player, false, null);
        Inventory inventory = player.getOpenInventory().getTopInventory();
        ItemStack draw = inventory.getItem(12);
        assertTrue(draw == null || draw.getType().isAir(), "an air icon cannot be labelled or chosen");
        assertTrue(click(12).isCancelled());
        verifyNoInteractions(manager);
    }

    @Test
    void ordinaryInventoriesAreNotCancelled() {
        player.openInventory(MockBukkit.getMock().createInventory(null, 27));
        InventoryClickEvent click = click(0);
        assertFalse(click.isCancelled());
        InventoryDragEvent drag = new InventoryDragEvent(player.getOpenInventory(), new ItemStack(Material.STONE),
                new ItemStack(Material.STONE, 2), false, Map.of(0, new ItemStack(Material.STONE)));
        GameSelectGui.INSTANCE.onDrag(drag);
        assertFalse(drag.isCancelled());
    }

    private InventoryClickEvent click(int rawSlot) {
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                rawSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        GameSelectGui.INSTANCE.onClick(event);
        return event;
    }

    private static void assertChoice(Inventory inventory, int slot, String game) {
        ItemStack item = inventory.getItem(slot);
        assertEquals(Material.PAPER, item.getType());
        assertEquals(game, item.getItemMeta().getPersistentDataContainer()
                .get(Keys.guiGame(), PersistentDataType.STRING));
    }
}
