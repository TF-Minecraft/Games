package net.tfminecraft.games.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.table.ShufflePolicy;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableHouse;
import net.tfminecraft.games.table.TableManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings("deprecation")
class TableOptionsGuiTest {
    private Games previousPlugin;
    private Map<String, TableLayout> previousLayouts;
    private ServerMock server;
    private PlayerMock player;
    private TableManager manager;
    private MockedStatic<TableManager> managers;
    private MockedStatic<GuildTables> guilds;

    @BeforeEach
    void setUp() {
        server = MockBukkit.getMock();
        player = server.addPlayer();
        player.setOp(false);
        previousPlugin = Games.plugin;
        previousLayouts = new HashMap<>(Cache.tableLayouts);
        Games.plugin = mock(Games.class);
        when(Games.plugin.getName()).thenReturn("Games");
        when(Games.plugin.namespace()).thenReturn("games");
        when(Games.plugin.isEnabled()).thenReturn(true);
        Cache.tableLayouts.put("blackjack", layout());
        Cache.tableLayouts.put("poker", layout());
        manager = mock(TableManager.class);
        managers = mockStatic(TableManager.class);
        managers.when(TableManager::get).thenReturn(manager);
        guilds = mockStatic(GuildTables.class);
        doAnswer(call -> {
            TableHouse house = call.getArgument(1);
            house.apply(call.getArgument(0));
            return null;
        }).when(manager).applyHouse(any(Table.class), any(TableHouse.class));
    }

    @AfterEach
    void tearDown() {
        TableOptionsGui.INSTANCE.onQuit(new PlayerQuitEvent(player, ""));
        server.getScheduler().cancelTasks(Games.plugin);
        guilds.close();
        managers.close();
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
        Games.plugin = previousPlugin;
    }

    @Test
    void placementDefaultsFollowLayoutAndMintControlsRequireStaff() {
        TableOptionsGui.openPlace(player, true, null);
        TableOptionsHolder holder = holder();
        assertEquals("blackjack", holder.gameId());
        assertTrue(holder.requireDeck());
        assertNull(holder.editTableId());
        assertNull(holder.pendingHit());
        assertEquals(player.getUniqueId(), holder.house().ownerPlayer());
        assertEquals(5, holder.house().minBet());
        assertEquals(100, holder.house().maxBet());
        assertEquals(4, holder.house().maxBoxes());
        assertFalse(holder.house().autoDealer());
        assertFalse(holder.house().staffMint());
        assertNull(holder.getInventory().getItem(11));
        staff();
        TableOptionsGui.openPlace(player, false, null);
        assertTrue(holder().house().autoDealer());
        assertTrue(holder().house().staffMint());
        assertEquals(Material.GOLD_INGOT, holder().getInventory().getItem(11).getType());
    }

    @Test
    void togglesKeepAutomaticAndMintSettingsConsistentAndCycleShuffle() {
        staff();
        TableOptionsGui.openPlace(player, false, null);
        TableHouse house = holder().house();
        click(10);
        assertFalse(house.autoDealer());
        assertFalse(house.staffMint());
        click(11);
        assertTrue(house.autoDealer());
        assertTrue(house.staffMint());
        click(11);
        assertTrue(house.autoDealer());
        assertFalse(house.staffMint());
        assertEquals(ShufflePolicy.SHOE, house.shufflePolicy());
        click(15);
        assertEquals(ShufflePolicy.ROUND, house.shufflePolicy());
        click(15);
        assertEquals(ShufflePolicy.SHOE, house.shufflePolicy());
        assertEquals(5, player.getHeardSounds().size());
    }

    @ParameterizedTest
    @CsvSource({"12, 0, 5", "12, 200, 100", "13, 0, 5", "13, 200, 100", "14, -1, 0", "14, 20, 4"})
    void ordinaryPlayersChatValuesRespectServerLimits(int slot, String input, int expected) {
        TableOptionsGui.openPlace(player, false, null);
        click(slot);
        assertTrue(chat(input).isCancelled());
        TableHouse house = holder().house();
        int actual = slot == 12 ? house.minBet() : slot == 13 ? house.maxBet() : house.maxBoxes();
        assertEquals(expected, actual);
    }

    @Test
    void staffCanGoBeyondServerLimitsButMinimumNeverExceedsMaximum() {
        staff();
        TableOptionsGui.openPlace(player, false, null);
        click(13);
        chat("250");
        assertEquals(250, holder().house().maxBet());
        click(12);
        chat("200");
        assertEquals(200, holder().house().minBet());
        click(13);
        chat("10");
        assertEquals(200, holder().house().maxBet());
        click(14);
        chat("12");
        assertEquals(12, holder().house().maxBoxes());
        click(12);
        chat("-1");
        assertEquals(1, holder().house().minBet());
    }

    @Test
    void invalidOrCancelledChatReopensOptionsWithoutChangingValue() {
        TableOptionsGui.openPlace(player, true, null);
        click(12);
        chat("not a number");
        assertEquals(5, holder().house().minBet());
        click(12);
        chat("2147483648");
        assertEquals(5, holder().house().minBet());
        click(12);
        chat(" CaNcEl ");
        assertEquals(5, holder().house().minBet());
        assertTrue(holder().requireDeck());
        verifyNoInteractions(manager);
    }

    @Test
    void pokerShowsBlindsAndRejectsInvertedOrNegativeBlinds() {
        TableOptionsGui.openPlace(player, true, null, "poker");
        assertNull(holder().getInventory().getItem(10));
        assertNull(holder().getInventory().getItem(11));
        assertNull(holder().getInventory().getItem(14));
        assertEquals(2, holder().house().smallBlind());
        assertEquals(4, holder().house().bigBlind());
        click(12);
        chat("5");
        assertEquals(2, holder().house().smallBlind());
        click(13);
        chat("1");
        assertEquals(4, holder().house().bigBlind());
        click(13);
        chat("-1");
        assertEquals(4, holder().house().bigBlind());
        click(13);
        chat("10");
        click(12);
        chat("5");
        assertEquals(5, holder().house().smallBlind());
        assertEquals(10, holder().house().bigBlind());
        click(12);
        chat("0");
        click(13);
        chat("0");
        assertEquals(0, holder().house().smallBlind());
        assertEquals(0, holder().house().bigBlind());
    }

    @Test
    void confirmationArmsPlacementAndUsesCopiedPendingHit() {
        Location pending = player.getLocation();
        Location original = pending.clone();
        TableOptionsGui.openPlace(player, true, pending);
        pending.add(10, 0, 0);
        click(15);
        click(16);
        ArgumentCaptor<TableHouse> house = ArgumentCaptor.forClass(TableHouse.class);
        verify(manager).armPlace(eq(player), eq("blackjack"), eq(true), house.capture());
        assertEquals(player.getUniqueId(), house.getValue().ownerPlayer());
        assertEquals(ShufflePolicy.ROUND, house.getValue().shufflePolicy());
        assertFalse(house.getValue().staffMint());
        verify(manager).tryPlace(player, original);
    }

    @ParameterizedTest
    @CsvSource({"true, place.armed", "false, place.armed_admin"})
    void commandPlacementArmsWithoutImmediatelyPlacing(boolean requireDeck, String messageKey) {
        TableOptionsGui.openPlace(player, requireDeck, null);
        click(16);
        verify(manager).armPlace(eq(player), eq("blackjack"), eq(requireDeck), any(TableHouse.class));
        verify(manager, never()).tryPlace(any(), any());
        assertEquals(Messages.get(messageKey), player.nextMessage());
    }

    @Test
    void guildRefusalClosesMenuWithoutPlacement() {
        TableOptionsGui.openPlace(player, false, null);
        TableHouse house = holder().house();
        guilds.when(() -> GuildTables.refuseKey(player, house, null)).thenReturn("denied");
        click(16);
        guilds.verify(() -> GuildTables.tellRefuse(player, "denied", house));
        verify(manager, never()).armPlace(any(), anyString(), anyBoolean(), any());
        assertNull(player.getOpenInventory().getTopInventory());
        assertEquals(2, player.getHeardSounds().size());
    }

    @Test
    void pokerConfirmationSkipsBlackjackGuildFundingGate() {
        TableOptionsGui.openPlace(player, false, null, "poker");
        click(16);
        verify(manager).armPlace(eq(player), eq("poker"), eq(false), any(TableHouse.class));
        guilds.verifyNoInteractions();
    }

    @Test
    void authorizedEditAppliesSnapshotOnlyOnConfirmation() {
        Table table = editableTable();
        TableOptionsGui.openEdit(player, table);
        TableOptionsHolder holder = holder();
        assertEquals(table.getId(), holder.editTableId());
        assertFalse(holder.requireDeck());
        assertEquals("guild", holder.house().ownerGuildId());
        assertEquals(player.getUniqueId(), holder.house().ownerPlayer());
        click(12);
        chat("20");
        assertEquals(5, table.minBet(), "Editing the snapshot must not alter the table before confirm");
        click(15);
        click(16);
        assertEquals(20, table.minBet());
        assertEquals(ShufflePolicy.ROUND, table.shufflePolicy());
        assertEquals("guild", table.ownerGuildId());
        verify(manager).applyHouse(eq(table), any(TableHouse.class));
    }

    @ParameterizedTest
    @CsvSource({"missing", "live", "denied"})
    void staleOrUnauthorizedEditsNeverApplyChanges(String reason) {
        Table table = editableTable();
        TableOptionsGui.openEdit(player, table);
        if (reason.equals("missing")) {
            when(manager.table(table.getId())).thenReturn(null);
        } else if (reason.equals("live")) {
            table.startSession();
        } else {
            when(manager.canEditHouse(player, table)).thenReturn(false);
        }
        click(16);
        verify(manager, never()).applyHouse(any(), any());
        assertEquals(Messages.get(reason.equals("denied") ? "place.options_denied" : "place.options_live"),
                player.nextMessage());
    }

    @Test
    void cancelDiscardsEditedOptionsAndNeverAppliesOrArms() {
        Table table = editableTable();
        TableOptionsGui.openEdit(player, table);
        click(15);
        click(22);
        assertEquals(ShufflePolicy.SHOE, table.shufflePolicy());
        verify(manager, never()).applyHouse(any(), any());
        verify(manager, never()).armPlace(any(), anyString(), anyBoolean(), any());
        assertNull(player.getOpenInventory().getTopInventory());
    }

    @Test
    void chatPromptExpiresAndQuitClearsPromptWithoutConsumingLaterChat() {
        TableOptionsGui.openPlace(player, false, null);
        click(12);
        server.getScheduler().performTicks(1200);
        assertFalse(chat("9").isCancelled());
        TableOptionsGui.openPlace(player, false, null);
        click(12);
        TableOptionsGui.INSTANCE.onQuit(new PlayerQuitEvent(player, ""));
        assertFalse(chat("9").isCancelled());
    }

    @Test
    void reopeningMenuInvalidatesAlreadyScheduledChatAndOldTimeout() {
        TableOptionsGui.openPlace(player, false, null);
        click(12);
        AsyncPlayerChatEvent pending = chatEvent("30");
        TableOptionsGui.INSTANCE.onChat(pending);
        assertTrue(pending.isCancelled());
        TableOptionsGui.openPlace(player, false, null, "poker");
        server.getScheduler().performTicks(1);
        assertEquals("poker", holder().gameId());
        assertEquals(2, holder().house().smallBlind());
        server.getScheduler().performTicks(1200);
        assertEquals("poker", holder().gameId());
    }

    @Test
    void optionsMenuCancelsBottomClicksAndDragsButIgnoresUnrelatedInventories() {
        TableOptionsGui.openPlace(player, false, null);
        assertTrue(click(27).isCancelled());
        assertTrue(click(0).isCancelled());
        InventoryDragEvent drag = drag();
        TableOptionsGui.INSTANCE.onDrag(drag);
        assertTrue(drag.isCancelled());
        player.openInventory(server.createInventory(null, 27));
        assertFalse(click(0).isCancelled());
        drag = drag();
        TableOptionsGui.INSTANCE.onDrag(drag);
        assertFalse(drag.isCancelled());
    }

    @Test
    void automaticDealerCanBeSwitchedOnAndOffAgain() {
        TableOptionsGui.openPlace(player, false, null);
        TableHouse house = holder().house();
        click(10);
        assertTrue(house.autoDealer());
        assertEquals(Messages.get("place.options_on"), holder().getInventory().getItem(10).getItemMeta().getLore().getFirst());
        click(10);
        assertFalse(house.autoDealer());
        assertEquals(Messages.get("place.options_off"), holder().getInventory().getItem(10).getItemMeta().getLore().getFirst());
    }

    @Test
    void mintCannotBeToggledOnceStaffPermissionIsWithdrawn() {
        var attachment = player.addAttachment(Games.plugin, TableHouse.STAFF_PERM, true);
        TableOptionsGui.openPlace(player, false, null);
        TableHouse house = holder().house();
        assertTrue(house.staffMint());
        attachment.remove();
        click(11);
        assertTrue(house.staffMint());
        assertNull(holder().getInventory().getItem(11), "the redrawn menu no longer offers the mint");
    }

    @Test
    void pokerShuffleLabelFollowsThePolicy() {
        TableOptionsGui.openPlace(player, false, null, "poker");
        assertEquals(Messages.get("place.options_shuffle_shoe"), holder().getInventory().getItem(15).getItemMeta().getDisplayName());
        click(15);
        assertEquals(ShufflePolicy.ROUND, holder().house().shufflePolicy());
        assertEquals(Messages.get("place.options_shuffle_round"), holder().getInventory().getItem(15).getItemMeta().getDisplayName());
    }

    @Test
    void clicksOutsideTheMenuOrOnEmptySlotsChangeNothing() {
        TableOptionsGui.openPlace(player, false, null);
        TableHouse house = holder().house();
        assertTrue(click(InventoryView.OUTSIDE).isCancelled());
        assertTrue(click(0).isCancelled());
        assertFalse(house.autoDealer());
        assertTrue(player.getHeardSounds().isEmpty());
    }

    @Test
    void negativeSmallBlindIsRejectedAndSmallBlindIsFreeWhileThereIsNoBigBlind() {
        TableOptionsGui.openPlace(player, false, null, "poker");
        click(12);
        chat("-1");
        assertEquals(Messages.get("place.options_chat_invalid"), lastMessage());
        assertEquals(2, holder().house().smallBlind());
        click(13);
        chat("0");
        click(12);
        chat("7");
        assertEquals(7, holder().house().smallBlind());
        assertEquals(0, holder().house().bigBlind());
    }

    @Test
    void staffConfirmationKeepsTheMint() {
        staff();
        TableOptionsGui.openPlace(player, false, null);
        click(16);
        ArgumentCaptor<TableHouse> house = ArgumentCaptor.forClass(TableHouse.class);
        verify(manager).armPlace(eq(player), eq("blackjack"), eq(false), house.capture());
        assertTrue(house.getValue().staffMint());
    }

    @Test
    void gameWithoutLayoutOrCapsLeavesPlayerLimitsOpen() {
        Cache.tableLayouts.remove("blackjack");
        TableOptionsGui.openPlace(player, false, null);
        click(13);
        chat("500");
        click(12);
        chat("300");
        click(14);
        chat("7");
        TableHouse house = holder().house();
        assertEquals(500, house.maxBet());
        assertEquals(300, house.minBet());
        assertEquals(7, house.maxBoxes());
        Cache.tableLayouts.put("blackjack", new TableLayout("cards", "Game", "icon", 6, Map.of(), null, null, null, 0,
                false, false, 5, 0, 2, TableLayout.VoiceLines.defaults(), null, 0, 2,
                null, 0, 2, 4, 4, false));
        TableOptionsGui.openPlace(player, false, null);
        click(13);
        chat("1000");
        click(12);
        chat("900");
        assertEquals(1000, holder().house().maxBet());
        assertEquals(900, holder().house().minBet());
    }

    @Test
    void emptySlotReportedAsAirByTheServerChangesNothing() {
        // Paper hands back an AIR stack rather than null for an empty slot in the clicked view.
        TableOptionsGui.openPlace(player, false, null);
        InventoryView view = player.getOpenInventory();
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(view.getTopInventory());
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(view.getTopInventory());
        when(event.getView()).thenReturn(view);
        when(event.getCurrentItem()).thenReturn(new ItemStack(Material.AIR));
        TableOptionsGui.INSTANCE.onClick(event);
        verify(event).setCancelled(true);
        assertFalse(holder().house().autoDealer());
        assertTrue(player.getHeardSounds().isEmpty());
    }

    private String lastMessage() {
        String last = null;
        for (String next = player.nextMessage(); next != null; next = player.nextMessage()) {
            last = next;
        }
        return last;
    }

    private Table editableTable() {
        Table table = new Table(UUID.randomUUID(), "blackjack", player.getLocation(), 0, null);
        table.setOwnerPlayer(player.getUniqueId());
        table.setOwnerGuildId("guild");
        table.setMinBet(5);
        table.setMaxBet(100);
        table.setMaxBoxes(4);
        when(manager.table(table.getId())).thenReturn(table);
        when(manager.canEditHouse(player, table)).thenReturn(true);
        return table;
    }

    private void staff() {
        player.addAttachment(Games.plugin, TableHouse.STAFF_PERM, true);
    }

    private TableOptionsHolder holder() {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertInstanceOf(TableOptionsHolder.class, inventory.getHolder());
        return (TableOptionsHolder) inventory.getHolder();
    }

    private InventoryClickEvent click(int rawSlot) {
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                rawSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        TableOptionsGui.INSTANCE.onClick(event);
        return event;
    }

    private AsyncPlayerChatEvent chatEvent(String text) {
        return new AsyncPlayerChatEvent(false, player, text, new HashSet<>());
    }

    private AsyncPlayerChatEvent chat(String text) {
        AsyncPlayerChatEvent event = chatEvent(text);
        TableOptionsGui.INSTANCE.onChat(event);
        server.getScheduler().performTicks(1);
        return event;
    }

    private InventoryDragEvent drag() {
        return new InventoryDragEvent(player.getOpenInventory(), new ItemStack(Material.STONE),
                new ItemStack(Material.STONE, 2), false, Map.of(0, new ItemStack(Material.STONE)));
    }

    private static TableLayout layout() {
        return new TableLayout("cards", "Game", "icon", 6, Map.of(), null, null, null, 0,
                false, true, 5, 100, 2, TableLayout.VoiceLines.defaults(), null, 0, 2,
                null, 4, 2, 4, 4, false);
    }
}
