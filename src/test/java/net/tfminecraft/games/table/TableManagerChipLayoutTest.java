package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.PotPile;
import net.tfminecraft.games.wager.WagerEngine;
import net.tfminecraft.games.wager.WagerItemOverride;

class TableManagerChipLayoutTest extends TableManagerFixture {
    private Map<String, TableLayout> previousLayouts;
    private double previousMergeRange;

    @BeforeEach void configureChipLayout() {
        previousLayouts = new HashMap<>(Cache.tableLayouts);
        previousMergeRange = Cache.wagerMergeRange;
        Cache.wagerMergeRange = 0.13;
        Cache.wagerGold = styledCoin("GOLD_NUGGET", 1, "gold-display");
        Cache.wagerSilver = styledCoin("IRON_NUGGET", 5, "silver-display");
        Cache.tableLayouts.put("freeplay", new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6,
                Map.of("tray", new TableLayout.PileSlot(-0.6, 0.0)), null,
                new TableLayout.FeltBox(0, 0, 3, 3), null, 0.5));
    }

    @AfterEach void restoreChipLayout() {
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
        Cache.wagerMergeRange = previousMergeRange;
    }

    private static WagerItemOverride styledCoin(String material, int value, String model) {
        return new WagerItemOverride(material, value, model, 3, 0.025f, 0.2f, false,
                null, false, null);
    }

    private void restore(Table table, UUID owner, Material material, int unit, int count,
            Double x, Double z) {
        WagerEngine.get().restore(table, owner, new ItemStack(material), material.name(), unit,
                count, 2, x, z);
    }

    private List<PotPile> piles(Table table, UUID owner) {
        return table.getPiles().stream().filter(pile -> owner.equals(pile.ownerId())).toList();
    }

    private List<List<Double>> positions(List<PotPile> piles) {
        return piles.stream().map(pile -> List.of(pile.x(), pile.z())).toList();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,0.5", "false,0.5", "true,0.0"})
    void tableSoundOverrideUsesConfiguredVolumeAndPitchAndHonorsMute(boolean enabled, float volume) {
        Cache.tableLayouts.put("freeplay", new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6,
                Map.of(), null, new TableLayout.FeltBox(0, 0, 3, 3), null, 0.5,
                false, false, 0, 0, 10, TableLayout.VoiceLines.defaults(), null, 0, 1,
                new TableLayout.SoundFx(enabled ? org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL : null, volume, 1.25f)));
        Table table = place(false);
        org.bukkit.World audio = mock(org.bukkit.World.class);
        Location at = new Location(audio, 4, 65, 2);
        manager.playChipSound(table, at);
        if (enabled && volume > 0) {
            verify(audio).playSound(at, org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, volume, 1.25f);
        } else {
            verifyNoInteractions(audio);
        }
    }

    @Test
    void personalBetPadAcceptsChipsOutsideTheFeltAndRemembersTheirPositionAfterRefund() {
        Cache.tableLayouts.put("freeplay", new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6,
                Map.of(), null, new TableLayout.FeltBox(0, 0, 0.4, 0.4), null, 0.1,
                false, false, 0, 0, 10, TableLayout.VoiceLines.defaults(),
                new TableLayout.BetZone(0.35), 0, 1));
        Table table = place(false);
        player.teleport(new Location(world, 2.5, 65, 0, 90, 0));
        Location pad = manager.boxLocation(table, player.getUniqueId());
        assertEquals(2.5 - Cache.handDistance - 0.35, pad.getX(), 0.000001);
        assertEquals(0, pad.getZ(), 0.000001);
        assertFalse(Cache.layoutOf("freeplay").onFelt(table, pad));
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 2));
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doReturn(new org.bukkit.util.RayTraceResult(pad.toVector())).when(clicker).rayTraceBlocks(anyDouble());
        PlayerInteractEvent bet = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), world.getBlockAt(1, 64, 0), BlockFace.UP, EquipmentSlot.HAND);
        manager.onInteractBlock(bet);
        assertTrue(bet.isCancelled());
        assertEquals(1, WagerEngine.get().owned(table, player.getUniqueId()));
        assertEquals(1, Accounts.coins(table, player).available());
        PotPile placed = piles(table, player.getUniqueId()).getFirst();
        assertEquals(pad.getX(), placed.x(), 0.000001);
        assertEquals(pad.getZ(), placed.z(), 0.000001);
        assertEquals(1, WagerEngine.get().refund(table, player.getUniqueId(), player, 0, null, "return bet").moved());
        player.teleport(player.getLocation().add(0, 0, 1));
        assertEquals(pad, manager.boxLocation(table, player.getUniqueId()));
        assertEquals(2, Accounts.coins(table, player).available());
        assertTrue(table.ledger().isEmpty());
    }

    @Test
    void trayDenominationsOccupySeparateGridSlotsWhilePlayerChipsKeepTheirAnchor() {
        Table table = place(false);
        restore(table, table.getId(), Material.GOLD_NUGGET, 1, 13, null, null);
        restore(table, table.getId(), Material.IRON_NUGGET, 5, 3, null, null);
        restore(table, player.getUniqueId(), Material.GOLD_NUGGET, 1, 2, -0.75, 0.4);
        manager.syncAllChips(table);
        List<PotPile> tray = piles(table, table.getId());
        assertEquals(6, tray.size());
        assertEquals(13, tray.stream().filter(p -> p.item().getType() == Material.GOLD_NUGGET)
                .mapToInt(PotPile::pieces).sum());
        assertEquals(3, tray.stream().filter(p -> p.item().getType() == Material.IRON_NUGGET)
                .mapToInt(PotPile::pieces).sum());
        TableLayout layout = Cache.layoutOf("freeplay");
        for (int i = 0; i < tray.size(); i++) {
            PotPile pile = tray.get(i);
            assertTrue(layout.inTrayZone(table, new Location(world, pile.x(), 65, pile.z())));
            assertTrue(manager.isTrayPile(table, pile));
            for (int j = i + 1; j < tray.size(); j++) {
                PotPile other = tray.get(j);
                assertTrue(Math.hypot(pile.x() - other.x(), pile.z() - other.z())
                        > Cache.wagerMergeRange, "Tray piles must not overlap their merge radius");
            }
        }
        PotPile own = piles(table, player.getUniqueId()).getFirst();
        assertEquals(-0.75, own.x());
        assertEquals(0.4, own.z());
        assertFalse(manager.isTrayPile(table, own));
        assertEquals(30, WagerEngine.get().total(table));
        List<List<Double>> idlePositions = positions(table.getPiles());
        manager.beginSession(table);
        assertTrue(table.live());
        manager.syncAllChips(table);
        assertEquals(idlePositions, positions(table.getPiles()));
        assertEquals(30, WagerEngine.get().total(table));
    }

    @Test
    void placedHeapOverflowsBesideItsSpotWithCappedLayersAndConfiguredVerticalSpacing() {
        Table table = place(false);
        WagerEngine.get().restore(table, player.getUniqueId(), new ItemStack(Material.GOLD_NUGGET),
                "gold", 1, 8, 2, -0.75, 0.4, -0.85, 0.45);
        List<Location> rendered = new ArrayList<>();
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            rendered.add(((Location) call.getArgument(1)).clone());
            return true;
        });
        manager.syncBucketChips(table, player.getUniqueId());
        List<PotPile> heaps = piles(table, player.getUniqueId());
        assertEquals(List.of(3, 3, 2), heaps.stream().map(PotPile::pieces).toList());
        assertEquals(-0.85, heaps.getFirst().x());
        assertEquals(0.45, heaps.getFirst().z());
        assertEquals(3, positions(heaps).stream().distinct().count());
        assertTrue(heaps.stream().allMatch(p -> p.streetId() == 2 && p.tokens().size() <= 3));
        assertEquals(8, rendered.size());
        assertEquals(0.025, rendered.get(1).getY() - rendered.getFirst().getY(), 0.000001);
        List<UUID> tokens = heaps.stream().flatMap(p -> p.tokens().stream()).toList();
        manager.syncBucketChips(table, player.getUniqueId());
        for (UUID token : tokens) verify(display).despawn(token);
        assertEquals(8, WagerEngine.get().owned(table, player.getUniqueId()));
        ItemStack template = manager.feltItem(table, player.getUniqueId());
        assertEquals(Material.GOLD_NUGGET, template.getType());
        assertEquals(1, template.getAmount());
        template.setAmount(40);
        assertEquals(8, WagerEngine.get().owned(table, player.getUniqueId()));
    }

    @Test
    void hiddenChipsKeepAmountLabelsCurrentThroughRefundsAndRestoreWithoutLosingMoney() {
        Table table = place(false);
        restore(table, player.getUniqueId(), Material.GOLD_NUGGET, 1, 4, -0.75, 0.4);
        restore(table, table.getId(), Material.GOLD_NUGGET, 1, 3, null, null);
        manager.syncAllChips(table);
        List<UUID> tokens = table.getPiles().stream().flatMap(p -> p.tokens().stream()).toList();
        Map<UUID, TextDisplay> entities = new LinkedHashMap<>();
        Map<UUID, String> text = new LinkedHashMap<>();
        messages.when(() -> Messages.get(eq("label.stake"), any(String[].class))).thenAnswer(call ->
                ((String[]) call.getRawArguments()[1])[1]);
        anchors.when(() -> WorldAnchors.spawnLabel(any(Location.class), anyString())).thenAnswer(call -> {
            TextDisplay label = mock(TextDisplay.class);
            UUID id = UUID.randomUUID();
            when(label.getUniqueId()).thenReturn(id);
            entities.put(id, label);
            text.put(id, call.getArgument(1));
            return label;
        });
        anchors.when(() -> WorldAnchors.setText(any(UUID.class), anyString())).thenAnswer(call -> {
            text.put(call.getArgument(0), call.getArgument(1));
            return null;
        });
        anchors.when(() -> WorldAnchors.remove(any(UUID.class))).thenAnswer(call -> {
            entities.remove(call.getArgument(0));
            text.remove(call.getArgument(0));
            return null;
        });
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getEntity(any(UUID.class))).thenAnswer(call -> entities.get(call.getArgument(0)));
            Cache.wagerShowChips = false;
            manager.redrawAllChips();
            assertTrue(table.getPiles().isEmpty());
            assertEquals(List.of("4", "3"), new ArrayList<>(text.values()));
            for (UUID token : tokens) verify(display).despawn(token);
            UUID playerLabel = text.keySet().iterator().next();
            assertEquals(2, WagerEngine.get().refund(table, player.getUniqueId(), player, 2,
                    null, "partial return").moved());
            assertEquals("2", text.get(playerLabel));
            assertEquals(2, text.size());
            assertEquals(2, WagerEngine.get().refund(table, player.getUniqueId(), player, 0,
                    null, "remaining return").moved());
            assertFalse(text.containsKey(playerLabel));
            assertEquals(List.of("3"), new ArrayList<>(text.values()));
            Cache.wagerShowChips = true;
            manager.redrawAllChips();
            assertTrue(text.isEmpty());
            assertEquals(3, table.getPiles().stream().mapToInt(PotPile::pieces).sum());
            assertEquals(7, Accounts.coins(table, player).available() + WagerEngine.get().total(table));
        }
    }

    @Test
    void externalHighDenominationCoinsRenderSeveralPiecesWithoutMultiplyingTheirValue() {
        Table table = place(false);
        ItemStack pouch = new ItemStack(Material.EMERALD);
        PluginManager plugins = mock(PluginManager.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("DenarEconomy")).thenReturn(plugin);
        MoneyManager money = mock(MoneyManager.class);
        Coin coin = mock(Coin.class);
        when(coin.getId()).thenReturn("three-denar");
        when(coin.getValue()).thenReturn(3.0);
        when(money.getCoin(any(ItemStack.class))).thenAnswer(call ->
                ((ItemStack) call.getArgument(0)).getType() == Material.EMERALD ? coin : null);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                MockedStatic<DenarEconomy> economy = mockStatic(DenarEconomy.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            economy.when(DenarEconomy::getMoneyManager).thenReturn(money);
            WagerEngine.get().restore(table, player.getUniqueId(), pouch, "pouch", 3, 2, 1, -0.75, 0.4);
            manager.syncBucketChips(table, player.getUniqueId());
            assertEquals(2, table.getPiles().size());
            assertTrue(table.getPiles().stream().allMatch(p -> p.pieces() == 3 && p.tokens().size() == 3));
            assertEquals(6, WagerEngine.get().owned(table, player.getUniqueId()));
            assertEquals(2, table.ledger().stakes(player.getUniqueId()).getFirst().count());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void fractionalExternalCoinsAreRefusedWithoutConsumingItemsWithOrWithoutSilverDecoration(boolean silverStyle) {
        if (!silverStyle) Cache.wagerSilver = null;
        Table table = place(false);
        assertEquals("place.done", player.nextMessage());
        PluginManager plugins = mock(PluginManager.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("DenarEconomy")).thenReturn(plugin);
        MoneyManager money = mock(MoneyManager.class);
        Coin halfDenar = mock(Coin.class);
        when(halfDenar.getId()).thenReturn("half-denar");
        when(halfDenar.getValue()).thenReturn(0.5);
        when(money.getCoin(any(ItemStack.class))).thenAnswer(call ->
                ((ItemStack) call.getArgument(0)).getType() == Material.EMERALD ? halfDenar : null);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                MockedStatic<DenarEconomy> economy = mockStatic(DenarEconomy.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            economy.when(DenarEconomy::getMoneyManager).thenReturn(money);
            ItemStack coins = new ItemStack(Material.EMERALD, 3);
            player.getInventory().setItemInMainHand(coins);
            Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
            Location target = table.getOrigin().clone().add(-0.75, 0, 0.4);
            doReturn(new org.bukkit.util.RayTraceResult(target.toVector())).when(clicker).rayTraceBlocks(anyDouble());
            PlayerInteractEvent click = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                    coins, world.getBlockAt(-1, 64, 0), BlockFace.UP, EquipmentSlot.HAND);
            manager.onInteractBlock(click);
            assertTrue(click.isCancelled());
            assertEquals("wager.not_whole", player.nextMessage());
            assertEquals(new ItemStack(Material.EMERALD, 3), player.getInventory().getItemInMainHand());
            assertTrue(table.ledger().isEmpty());
            assertTrue(table.getPiles().isEmpty());
            assertFalse(table.actives().contains(player.getUniqueId()));
        }
    }

    @Test
    void trayWithoutConfiguredSlotUsesTableOriginAndStillPaysItsRealBalance() {
        Cache.tableLayouts.remove("freeplay");
        Table table = place(false);
        restore(table, table.getId(), Material.GOLD_NUGGET, 1, 2, null, null);
        manager.syncBucketChips(table, table.getId());
        PotPile pile = table.getPiles().getFirst();
        assertEquals(table.getOrigin().getX(), pile.x());
        assertEquals(table.getOrigin().getZ(), pile.z());
        assertEquals(2, WagerEngine.get().refund(table, table.getId(), player, 0, null, "return tray").moved());
        assertTrue(table.getPiles().isEmpty());
        assertNull(manager.feltItem(table, table.getId()));
        assertEquals(2, Accounts.coins(table, player).available());
    }

    @Test
    void failedLayerSpawnRemovesPartialDecorationAndRetryRebuildsUnchangedStake() {
        Table table = place(false);
        restore(table, player.getUniqueId(), Material.GOLD_NUGGET, 1, 3, -0.75, 0.4);
        List<UUID> attempts = new ArrayList<>();
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            attempts.add(call.getArgument(0));
            return attempts.size() != 2;
        });
        manager.syncBucketChips(table, player.getUniqueId());
        assertTrue(table.getPiles().isEmpty());
        assertEquals(2, attempts.size());
        verify(display).despawn(attempts.getFirst());
        assertEquals(3, WagerEngine.get().owned(table, player.getUniqueId()));
        when(display.spawn(any(), any(), any(), any())).thenReturn(true);
        manager.syncBucketChips(table, player.getUniqueId());
        assertEquals(3, table.getPiles().getFirst().tokens().size());
        assertEquals(3, WagerEngine.get().owned(table, player.getUniqueId()));
    }

    @Test
    void lookingDownAtFeltWithoutABlockHitPlacesCoinWhileLookingAwayPreservesIt() {
        Table table = place(false);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doReturn(null).when(clicker).rayTraceBlocks(anyDouble());
        Location eye = player.getEyeLocation();
        eye.setPitch(0);
        doReturn(eye).when(clicker).getEyeLocation();
        PlayerInteractEvent away = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND);
        manager.onInteractBlock(away);
        assertEquals(1, Accounts.coins(table, player).available());
        assertEquals(0, WagerEngine.get().total(table));
        Location target = table.getOrigin().clone().add(-0.8, 0, 0.2);
        eye.setDirection(target.toVector().subtract(eye.toVector()));
        PlayerInteractEvent aimed = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND);
        manager.onInteractBlock(aimed);
        assertTrue(aimed.isCancelled());
        assertEquals(0, Accounts.coins(table, player).available());
        assertEquals(1, WagerEngine.get().owned(table, player.getUniqueId()));
        PotPile pile = table.getPiles().getFirst();
        assertEquals(target.getX(), pile.x(), 0.000001);
        assertEquals(target.getZ(), pile.z(), 0.000001);
        assertEquals(pile.x(), manager.boxLocation(table, player.getUniqueId()).getX(), 0.000001);
        assertEquals(pile.z(), manager.boxLocation(table, player.getUniqueId()).getZ(), 0.000001);
    }
}
