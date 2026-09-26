package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.Stake;

/** Placing loot that the table agreed to, once the proposer clicks the felt. */
class TableManagerLootTest extends TableManagerFixture {
    private Map<String, TableLayout> previousLayouts;

    @BeforeEach void rememberLayouts() {
        previousLayouts = new HashMap<>(Cache.tableLayouts);
    }

    @AfterEach void restoreLayouts() {
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
    }

    @Test void lootAimedAtTheShoeIsRefusedButStaysArmedForAnotherSpot() {
        Cache.tableLayouts.put("freeplay", layout(Map.of(), 0.5));
        Table table = armedTable(7);
        PlayerInteractEvent refused = click(player, table.getOrigin().clone().add(0.4, 0, 0));
        assertTrue(refused.isCancelled());
        assertEquals("wager.no_bet_zone", player.nextMessage());
        assertEquals(2, diamonds());
        assertEquals(1, table.ledger().total());
        assertTrue(click(player, table.getOrigin().clone().add(0.75, 0, 0)).isCancelled());
        assertEquals(0, diamonds());
        assertEquals(15, table.ledger().total());
    }

    @Test void aPlayerAimingLootAtTheHouseTrayStakesItOnTheFeltInstead() {
        Cache.tableLayouts.put("freeplay", layout(Map.of("tray", new TableLayout.PileSlot(0, 1.6)), 0.3));
        Table table = armedTable(7);
        Location tray = Cache.layoutOf("freeplay").trayLocation(table);
        assertTrue(click(player, tray).isCancelled());
        assertEquals(0, diamonds());
        assertEquals(0, manager.trayDenars(table));
        assertEquals(15, manager.ownedDenars(table, player.getUniqueId()));
        Stake loot = table.ledger().stakes(player.getUniqueId()).stream()
                .filter(stake -> stake.item().getType() == Material.DIAMOND).findFirst().orElseThrow();
        Location centre = Cache.layoutOf("freeplay").feltCenter(table);
        assertEquals(centre.getX(), loot.x(), 1e-9);
        assertEquals(centre.getZ(), loot.z(), 1e-9);
    }

    @Test void theDealerMayStockAnUnbackedTrayWithLootWithoutTakingASeatForIt() {
        Cache.tableLayouts.put("freeplay", layout(Map.of("tray", new TableLayout.PileSlot(0, 1.6)), 0.3));
        Table table = armedTable(7);
        table.setDealerId(player.getUniqueId());
        clearInvocations(game);
        assertTrue(click(player, Cache.layoutOf("freeplay").trayLocation(table)).isCancelled());
        assertEquals(0, diamonds());
        assertEquals(14, manager.trayDenars(table));
        assertEquals(1, manager.ownedDenars(table, player.getUniqueId()));
        verify(game, never()).onChipIn(any(), any(), anyInt(), any());
        verify(game, never()).onSessionStart(any());
    }

    @Test void aBackedTrayRefusesTheDealersLootAndKeepsTheArm() {
        Cache.tableLayouts.put("freeplay", layout(Map.of("tray", new TableLayout.PileSlot(0, 1.6)), 0.3));
        Table table = armedTable(7);
        table.setDealerId(player.getUniqueId());
        table.setStaffMint(true);
        Location tray = Cache.layoutOf("freeplay").trayLocation(table);
        assertTrue(click(player, tray).isCancelled());
        assertEquals("wager.tray_is_funded", player.nextMessage());
        assertEquals(2, diamonds());
        assertEquals(0, manager.trayDenars(table));
        manager.proposeLoot(player, 9);
        assertEquals("wager.busy", player.nextMessage(), "the refused placement keeps the arm");
    }

    @Test void aDeclaredValueTooLargeToAddUpTakesNothing() {
        Table table = armedTable(2_000_000_000);
        assertTrue(click(player, table.getOrigin().clone().add(0.75, 0, 0)).isCancelled());
        assertEquals("wager.gone", player.nextMessage());
        assertEquals(2, diamonds());
        assertEquals(1, table.ledger().total());
        manager.proposeLoot(player, 9);
        assertEquals("wager.busy", player.nextMessage());
    }

    @Test void clickingOrRemovingAnotherTableLeavesTheLootArmedForItsOwnTable() {
        Table table = armedTable(7);
        manager.armPlace(player, "freeplay", false);
        Location elsewhere = table.getOrigin().clone().add(10, 0, 0);
        assertTrue(manager.tryPlace(player, elsewhere));
        drain(player);
        Table other = manager.tables().stream().filter(t -> t != table).findFirst().orElseThrow();
        // Diamonds are not a deck, so an unclaimed click falls through to the world.
        when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(false);
        player.teleport(other.getOrigin());
        assertFalse(click(player, other.getOrigin().clone().add(0.75, 0, 0)).isCancelled(),
                "loot armed for one table is not taken by another");
        assertEquals(2, diamonds());
        assertTrue(other.ledger().isEmpty());
        pickUp(other);
        assertNull(manager.table(other.getId()));
        player.teleport(table.getOrigin());
        assertTrue(click(player, table.getOrigin().clone().add(0.75, 0, 0)).isCancelled());
        assertEquals(0, diamonds());
        assertEquals(15, table.ledger().total());
    }

    @Test void clickingOffTheFeltLeavesTheLootArmedAndInHand() {
        Table table = armedTable(7);
        when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(false);
        assertFalse(click(player, table.getOrigin().clone().add(3, 0, 0)).isCancelled());
        assertEquals(2, diamonds());
        assertEquals(1, table.ledger().total());
        manager.proposeLoot(player, 9);
        assertEquals("wager.busy", player.nextMessage());
    }

    @Test void lootAimedAtTheTrayLandsWhereThePlayerIsLookingOnTheFelt() {
        Cache.tableLayouts.put("freeplay", layout(Map.of("tray", new TableLayout.PileSlot(0, 1.6)), 0.3));
        Table table = armedTable(7);
        // Facing along the table's forward axis, looking down at the felt a block ahead.
        player.teleport(new Location(world, 0, 65, 0, 90, 58));
        Location gaze = gazeOnFelt(table);
        assertTrue(click(player, Cache.layoutOf("freeplay").trayLocation(table)).isCancelled());
        assertEquals(0, diamonds());
        assertEquals(15, manager.ownedDenars(table, player.getUniqueId()));
        Stake loot = lootStake(table);
        assertEquals(gaze.getX(), loot.x(), 1e-6);
        assertEquals(gaze.getZ(), loot.z(), 1e-6);
    }

    @Test void lootIsNeverStakedOnTheNeighbouringTableThePlayerIsLookingAt() {
        Cache.tableLayouts.put("freeplay", layout(Map.of("tray", new TableLayout.PileSlot(0, 1.6)), 0.3));
        Table table = armedTable(7);
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, new Location(world, -3, 65, 0)));
        drain(player);
        Table neighbour = manager.tables().stream().filter(t -> t != table).findFirst().orElseThrow();
        // Stood at this table's shoe, but looking down at the neighbour's felt.
        player.teleport(new Location(world, 0, 65, 0, 90, 35.4f));
        assertTrue(Math.abs(gazeOnFelt(table).getX() - neighbour.getOrigin().getX()) < 1.2);
        assertTrue(click(player, Cache.layoutOf("freeplay").trayLocation(table)).isCancelled());
        assertEquals(0, diamonds());
        assertEquals(15, table.ledger().total());
        assertTrue(neighbour.ledger().isEmpty());
        Location centre = Cache.layoutOf("freeplay").feltCenter(table);
        assertEquals(centre.getX(), lootStake(table).x(), 1e-9);
        assertEquals(centre.getZ(), lootStake(table).z(), 1e-9);
    }

    @Test void aPlayerLookingAtTheTrayTheyClickedCannotStakeLootInIt() {
        Cache.tableLayouts.put("freeplay", layout(Map.of("tray", new TableLayout.PileSlot(0, 1.6)), 0.3));
        Table table = armedTable(7);
        Location tray = Cache.layoutOf("freeplay").trayLocation(table);
        player.teleport(new Location(world, 0, 65, 0, 0, 45));
        assertTrue(gazeOnFelt(table).distance(tray) < 0.3);
        assertTrue(click(player, tray).isCancelled());
        assertEquals("wager.no_bet_zone", player.nextMessage());
        assertEquals(2, diamonds());
        assertEquals(0, manager.trayDenars(table));
        assertEquals(1, table.ledger().total());
    }

    /** Where the player's line of sight meets the felt's surface. */
    private Location gazeOnFelt(Table table) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection();
        double t = (table.getOrigin().getY() - eye.getY()) / dir.getY();
        Location hit = eye.clone().add(dir.multiply(t));
        hit.setY(table.getOrigin().getY());
        return hit;
    }

    private Stake lootStake(Table table) {
        return table.ledger().stakes(player.getUniqueId()).stream()
                .filter(stake -> stake.item().getType() == Material.DIAMOND).findFirst().orElseThrow();
    }

    /** A seated player whose loot proposal nobody else had to approve. */
    private Table armedTable(int denars) {
        Table table = place(false);
        stakeCoin(player, table);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, denars);
        assertNull(table.getVote());
        drain(player);
        return table;
    }

    private PlayerInteractEvent click(PlayerMock actor, Location at) {
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(actor));
        doReturn(new RayTraceResult(at.toVector())).when(clicker).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                actor.getInventory().getItemInMainHand(), world.getBlockAt(0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        manager.onInteractBlock(event);
        return event;
    }

    private void pickUp(Table table) {
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(player);
        manager.onHitEntity(hit);
    }

    private int diamonds() {
        return player.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    private static TableLayout layout(Map<String, TableLayout.PileSlot> piles, double noBetRadius) {
        return new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6, piles,
                new TableLayout.FeltRing(0.3, 1.2), null, null, noBetRadius);
    }

    private static void drain(PlayerMock target) {
        while (target.nextMessage() != null) { }
    }
}
