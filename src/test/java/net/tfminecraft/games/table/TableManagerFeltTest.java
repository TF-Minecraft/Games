package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.gui.GameSelectGui;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.PotPile;
import net.tfminecraft.games.wager.Stake;
import net.tfminecraft.games.wager.WagerItemOverride;

/** Where a click on or near a table lands a chip, and what happens when it lands nowhere. */
class TableManagerFeltTest extends TableManagerFixture {
    /** Deterministic ids: a HashMap visits the id ending 1 before the id ending 2. */
    private static final UUID FIRST = new UUID(0L, 1L);
    private static final UUID SECOND = new UUID(0L, 2L);
    private Map<String, TableLayout> previousLayouts;

    @BeforeEach void rememberLayouts() {
        previousLayouts = new HashMap<>(Cache.tableLayouts);
        // Nothing held here is a card deck, so a click that misses every felt is simply ignored.
        when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(false);
    }

    @AfterEach void restoreLayouts() {
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
    }

    private static TableLayout layout(Map<String, TableLayout.PileSlot> piles, TableLayout.FeltBox box,
            double noBetRadius, TableLayout.BetZone pad) {
        return new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6, piles, null, box, null, noBetRadius,
                false, false, 0, 0, 10, TableLayout.VoiceLines.defaults(), pad, 0, 1);
    }

    private PlayerInteractEvent clickAt(Player actor, Location at) {
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(actor));
        doReturn(new RayTraceResult(at.toVector())).when(clicker).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                actor.getInventory().getItemInMainHand(), world.getBlockAt(0, 64, 0), BlockFace.UP, EquipmentSlot.HAND);
        manager.onInteractBlock(event);
        return event;
    }

    /**
     * A click into the air: the block ray found nothing, so the felt is found from the look ray.
     * An event without a block always reports itself cancelled, so tests read the outcome instead.
     */
    private PlayerInteractEvent airClick(Player actor) {
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(actor));
        doReturn(null).when(clicker).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_AIR,
                actor.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND);
        manager.onInteractBlock(event);
        return event;
    }

    private Location onTable(Table table, double dx, double dz) {
        return table.getOrigin().clone().add(dx, 0, dz);
    }

    private int pocketGold(PlayerMock who) {
        return who.getInventory().all(Material.GOLD_NUGGET).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    private void writeTable(UUID id, double x) throws Exception {
        JsonObject saved = new JsonObject();
        saved.addProperty("id", id.toString());
        saved.addProperty("gameId", "freeplay");
        saved.addProperty("world", world.getName());
        saved.addProperty("x", x);
        saved.addProperty("y", 65);
        saved.addProperty("z", 0);
        saved.addProperty("yaw", 90);
        saved.addProperty("setName", Cache.pokerCardSet);
        JsonArray remaining = new JsonArray();
        remaining.add("one");
        remaining.add("two");
        saved.add("remaining", remaining);
        Path folder = data.resolve("Data/tables");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(id + ".json"), saved.toString());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.1, 2.0})
    void coinsClickedOffTheDefaultFeltRingStayInThePocket(double distance) {
        Table table = place(false);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 2));
        PlayerInteractEvent click = clickAt(player, onTable(table, distance, 0));
        assertFalse(click.isCancelled());
        assertEquals(2, pocketGold(player));
        assertTrue(table.ledger().isEmpty());
        assertTrue(table.actives().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 0})
    void clicksBesideASmallFeltWithoutAWorkingBetPadStakeNothing(double padRadius) {
        TableLayout.BetZone pad = padRadius < 0 ? null : new TableLayout.BetZone(padRadius);
        Cache.tableLayouts.put("freeplay", layout(Map.of(), new TableLayout.FeltBox(0, 0, 0.4, 0.4), 0, pad));
        Table table = place(false);
        player.teleport(new Location(world, 2.5, 65, 0, 90, 0));
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        // Where a pad would be, in front of the player and well off the felt.
        Location beside = onTable(table, 2.5 - Cache.handDistance - 0.35, 0);
        assertFalse(clickAt(player, beside).isCancelled());
        assertEquals(1, pocketGold(player));
        assertTrue(table.ledger().isEmpty());
    }

    @Test void aClickIntoTheAirStakesOnlyWhereTheLookRayMeetsTheFeltWithinReach() {
        Table table = place(false);
        double drop = player.getEyeHeight() + 65 - table.getOrigin().getY();
        // Looking 60 degrees down from here meets the felt 0.75 blocks from the shoe.
        double standX = 0.75 + drop / Math.tan(Math.toRadians(60));
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 3));
        player.teleport(new Location(world, standX, 65, 0, 90, -30));
        airClick(player);
        assertEquals(3, pocketGold(player), "looking up never meets the felt");
        player.teleport(new Location(world, standX, 65, 0, 90, 1));
        airClick(player);
        assertEquals(3, pocketGold(player), "a glancing look meets the felt far out of reach");
        assertTrue(table.ledger().isEmpty());
        player.teleport(new Location(world, standX, 65, 0, 90, 60));
        airClick(player);
        assertEquals(2, pocketGold(player));
        Stake placed = table.ledger().stakes(player.getUniqueId()).getFirst();
        assertEquals(0.75, placed.x() - table.getOrigin().getX(), 0.01);
        assertEquals(0, placed.z(), 0.01);
    }

    @Test void chipsDroppedOnTheShoeAreRefusedWithANotice() {
        Cache.tableLayouts.put("freeplay", layout(Map.of(), new TableLayout.FeltBox(0, 0, 3, 3), 0.5, null));
        Table table = place(false);
        assertEquals("place.done", player.nextMessage());
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        assertTrue(clickAt(player, onTable(table, 0.2, 0)).isCancelled());
        assertEquals("wager.no_bet_zone", player.nextMessage());
        assertEquals(1, pocketGold(player));
        assertTrue(table.ledger().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void whereTwoFeltsOverlapTheChipGoesToTheTableWhoseShoeIsNearerTheClick(boolean firstIsNearer)
            throws Exception {
        writeTable(FIRST, firstIsNearer ? 0 : 2);
        writeTable(SECOND, firstIsNearer ? 2 : 0);
        manager.loadAll();
        Table near = manager.table(firstIsNearer ? FIRST : SECOND);
        Table far = manager.table(firstIsNearer ? SECOND : FIRST);
        // Both rings cover this spot; the player also stands nearer the same table.
        player.teleport(new Location(world, 0.2, 65, 1, 90, 0));
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        assertTrue(clickAt(player, new Location(world, 0.9, near.getOrigin().getY(), 0)).isCancelled());
        assertEquals(1, manager.ownedDenars(near, player.getUniqueId()));
        assertEquals(0, manager.ownedDenars(far, player.getUniqueId()));
        assertEquals(0, pocketGold(player));
    }

    @Test void onlyTheNearestTableOffersThePlayerTheirPersonalBetPad() {
        Cache.tableLayouts.put("freeplay", layout(Map.of(), new TableLayout.FeltBox(0, 0, 0.4, 0.4), 0,
                new TableLayout.BetZone(0.35)));
        Table nearest = place(false);
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, new Location(world, 4, 65, 0)));
        Table farther = manager.tables().stream().filter(t -> t != nearest).findFirst().orElseThrow();
        player.teleport(new Location(world, 1.5, 65, 0, 90, 0));
        Location farPad = manager.boxLocation(farther, player.getUniqueId());
        Location nearPad = manager.boxLocation(nearest, player.getUniqueId());
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 2));
        assertFalse(clickAt(player, farPad).isCancelled());
        assertEquals(2, pocketGold(player));
        assertTrue(clickAt(player, nearPad).isCancelled());
        assertEquals(1, manager.ownedDenars(nearest, player.getUniqueId()));
        assertEquals(0, manager.ownedDenars(farther, player.getUniqueId()));
    }

    @Test void atBlackjackWagerItemsAreRefusedAsCoinsOnlyWhileOrdinaryItemsAreIgnored() {
        games.when(() -> GamesRegistry.of("blackjack")).thenReturn(game);
        player.addAttachment(Games.plugin, TableHouse.STAFF_PERM, true);
        TableHouse house = TableHouse.forPlace(player, null);
        house.setStaffMint(true);
        house.setMaxBet(10);
        manager.armPlace(player, "blackjack", false, house);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table table = manager.tables().iterator().next();
        table.setBetOpen(true);
        while (player.nextMessage() != null) {
            // Placement notices are not under test here.
        }
        Cache.wagerItems.add(new WagerItemOverride("DIAMOND", 5, null, null, null, null, null, null, false, null));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND));
        assertTrue(clickAt(player, onTable(table, 0.75, 0)).isCancelled());
        assertEquals("wager.coins_only", player.nextMessage());
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        assertFalse(clickAt(player, onTable(table, 0.75, 0)).isCancelled());
        assertNull(player.nextMessage());
        assertTrue(table.ledger().isEmpty());
    }

    // ------------------------------------------------------------------ loot on the wrong spot

    private static final TableLayout.PileSlot TRAY = new TableLayout.PileSlot(1.5, 0);

    /**
     * A non-dealer who clicks the tray with armed loot has it put down on their own spot instead.
     * The tray sits 1.5 blocks behind the shoe, off every felt used here.
     */
    private Stake lootPlacedFromTrayClick(TableLayout layout, Location stand) {
        Cache.tableLayouts.put("freeplay", layout);
        Table table = place(false);
        player.teleport(stand);
        stakeCoin(player, table);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 7);
        Location tray = layout.trayLocation(table);
        assertTrue(clickAt(player, tray).isCancelled());
        assertTrue(player.getInventory().getItemInMainHand().getType().isAir(), "the loot was staked");
        assertEquals(15, table.ledger().total());
        return table.ledger().stakes(player.getUniqueId()).stream()
                .filter(stake -> stake.item().getType() == Material.DIAMOND).findFirst().orElseThrow();
    }

    @Test void lootClickedOntoTheTrayLandsOnThePlayersBetPad() {
        TableLayout layout = layout(Map.of("tray", TRAY), new TableLayout.FeltBox(-0.9, 0, 1, 1), 0.3,
                new TableLayout.BetZone(0.35));
        Stake loot = lootPlacedFromTrayClick(layout, new Location(world, 2.5, 65, 0, 90, 0));
        assertEquals(2.5 - Cache.handDistance - 0.35, loot.x(), 0.0001);
        assertEquals(0, loot.z(), 0.0001);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 0})
    void lootClickedOntoTheTrayLandsOnTheFeltCentreWithoutAWorkingBetPad(double padRadius) {
        TableLayout.BetZone pad = padRadius < 0 ? null : new TableLayout.BetZone(padRadius);
        TableLayout layout = layout(Map.of("tray", TRAY), new TableLayout.FeltBox(-0.9, 0, 1, 1), 0.3, pad);
        Stake loot = lootPlacedFromTrayClick(layout, new Location(world, 2.5, 65, 0, 90, 0));
        // Forward is -X for a table facing yaw 90, so the felt centre is 0.9 blocks along +X.
        assertEquals(0.9, loot.x(), 0.0001);
        assertEquals(0, loot.z(), 0.0001);
    }

    @ParameterizedTest
    @ValueSource(doubles = {2.5, 0})
    void lootClickedOntoTheTrayOfAnUnsizedBoxFeltLandsOnTheRingTowardsThePlayer(double standX) {
        // "felt: type: box" with no size falls back to the ring, which the box centre is not on.
        TableLayout layout = layout(Map.of("tray", TRAY), new TableLayout.FeltBox(0, 0, 0, 0), 0.2, null);
        Stake loot = lootPlacedFromTrayClick(layout, new Location(world, standX, 65, 0, 90, 0));
        double ring = (Cache.wagerMinRange + Cache.wagerMaxRange) * 0.5;
        // Straight towards the player, or along +X when they stand right over the shoe.
        assertEquals(ring, loot.x(), 0.0001);
        assertEquals(0, loot.z(), 0.0001);
    }

    // ---------------------------------------------------------------------- placing a table

    @Test void rightClickingTheAirWithADeckDoesNotOpenGameSelection() {
        when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(true);
        try (var gui = mockStatic(GameSelectGui.class)) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER));
            airClick(player);
            gui.verifyNoInteractions();
        }
        assertTrue(manager.tables().isEmpty());
    }

    @Test void anArmedPlacementFromABlockClickWithoutABlockAsksForASurface() {
        // Other plugins can fire block clicks that carry no block.
        manager.armPlace(player, "freeplay", false);
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doReturn(null).when(clicker).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK, null,
                (Block) null, BlockFace.UP, EquipmentSlot.HAND);
        manager.onInteractBlock(event);
        assertEquals("place.need_surface", player.nextMessage());
        assertTrue(manager.tables().isEmpty());
    }

    // ------------------------------------------------------------------------- chip displays

    private List<PotPile> stakeThree(WagerItemOverride coin) {
        Cache.wagerGold = coin;
        Table table = place(false);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 3));
        for (int i = 0; i < 3; i++) {
            clickFelt(player, table);
        }
        assertEquals(3, manager.ownedDenars(table, player.getUniqueId()));
        return table.getPiles();
    }

    @Test void chipStacksWithRandomYawTurnEachLayerFreelyWhileFixedStacksFaceTheTable() {
        List<PotPile> random = stakeThree(new WagerItemOverride("GOLD_NUGGET", 1, null, 5, null, null,
                true, null, false, null));
        float tableYaw = manager.tables().iterator().next().getYaw();
        PotPile heap = random.getFirst();
        assertEquals(3, heap.layerYaws().size());
        for (float yaw : heap.layerYaws()) {
            assertTrue(yaw >= tableYaw && yaw < tableYaw + 360f, "a random turn stays within one revolution");
        }
    }

    @Test void chipStacksWithoutRandomYawAllFaceTheTable() {
        List<PotPile> fixed = stakeThree(new WagerItemOverride("GOLD_NUGGET", 1, null, 5, null, null,
                false, null, false, null));
        float tableYaw = manager.tables().iterator().next().getYaw();
        assertEquals(List.of(tableYaw, tableYaw, tableYaw), fixed.getFirst().layerYaws());
    }

    @Test void aChipModelThatCannotBeResolvedIsDrawnAsTheCoinItself() {
        when(items.getCreator().getItemFromPath("missing-chip-model")).thenReturn(null);
        List<ItemStack> drawn = new java.util.ArrayList<>();
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            drawn.add(call.getArgument(2));
            return true;
        });
        stakeThree(new WagerItemOverride("GOLD_NUGGET", 1, "missing-chip-model", 5, null, null,
                false, null, false, null));
        assertTrue(drawn.stream().anyMatch(item -> item.getType() == Material.GOLD_NUGGET && item.getAmount() == 1));
        assertEquals(0, Accounts.coins(manager.tables().iterator().next(), player).available());
    }
}
