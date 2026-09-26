package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.denareconomy.managers.MoneyManager;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.PotPile;
import net.tfminecraft.games.wager.WagerEngine;
import net.tfminecraft.games.wager.WagerItemOverride;

/** Proposals, street commits, table lookup and where each bucket's chips are drawn. */
class TableManagerMoneyTest extends TableManagerFixture {
    private Map<String, TableLayout> previousLayouts;
    private double previousMergeRange;

    @BeforeEach void rememberLayouts() {
        previousLayouts = new HashMap<>(Cache.tableLayouts);
        previousMergeRange = Cache.wagerMergeRange;
    }

    @AfterEach void restoreLayouts() {
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
        Cache.wagerMergeRange = previousMergeRange;
    }

    @Test void loadingLootWhileRefundChipsAreStillFlyingIsRefused() {
        Cache.wagerPayoutTicks = 3;
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        manager.refundOwnedPiles(table, other);
        assertTrue(table.isPaying());
        drain(player);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 10);
        assertEquals("wager.paying", player.nextMessage());
        assertNull(table.getVote());
        manager.commitStreet(player);
        assertEquals("wager.paying", player.nextMessage());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(1, table.ledger().total(player.getUniqueId()));
    }

    @Test void anEmptyHandHasNothingToPropose() {
        Table table = place(false);
        stakeCoin(player, table);
        drain(player);
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        manager.proposeLoot(player, 10);
        assertEquals("wager.need_item", player.nextMessage());
        assertNull(table.getVote());
    }

    @Test void coinsAreStakedByClickingNotProposedAsLoot() {
        Table table = place(false);
        stakeCoin(player, table);
        drain(player);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 3));
        manager.proposeLoot(player, 10);
        assertEquals("wager.use_click", player.nextMessage());
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
        assertNull(table.getVote());
    }

    @Test void aCoinWorthLessThanADenarIsStillACoinAndCannotBeProposed() {
        Cache.wagerSilver = null;
        Table table = place(false);
        stakeCoin(player, table);
        drain(player);
        PluginManager plugins = mock(PluginManager.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("DenarEconomy")).thenReturn(plugin);
        MoneyManager money = mock(MoneyManager.class);
        Coin halfDenar = mock(Coin.class);
        when(halfDenar.getValue()).thenReturn(0.5);
        when(money.getCoin(any(ItemStack.class))).thenAnswer(call ->
                ((ItemStack) call.getArgument(0)).getType() == Material.EMERALD ? halfDenar : null);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                MockedStatic<DenarEconomy> economy = mockStatic(DenarEconomy.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            economy.when(DenarEconomy::getMoneyManager).thenReturn(money);
            player.getInventory().setItemInMainHand(new ItemStack(Material.EMERALD, 4));
            manager.proposeLoot(player, 10);
        }
        assertEquals("wager.use_click", player.nextMessage());
        assertNull(table.getVote());
        assertEquals(4, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test void aConfiguredWagerItemWithoutAFixedValueIsProposedAtItsDeclaredValue() {
        Cache.wagerItems.add(new WagerItemOverride("GOLD_INGOT", null, null, null, null, null,
                null, null, false, null));
        Table table = place(false);
        stakeCoin(player, table);
        drain(player);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_INGOT, 2));
        manager.proposeLoot(player, 12);
        assertEquals("wager.accepted", player.nextMessage());
        assertEquals(0, manager.chipUnitDenars(new ItemStack(Material.GOLD_INGOT)),
                "Its worth is whatever the table agreed, not a unit of its own");
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount(), "Nothing moves until it is placed");
    }

    @Test void aMinimumPlayerCountHoldsBackLootUntilEnoughHaveBet() {
        Cache.wagerMinPlayers = 2;
        Table table = place(false);
        stakeCoin(player, table);
        drain(player);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 10);
        assertEquals("wager.need_players", player.nextMessage());
        assertNull(table.getVote());
        PlayerMock other = opponent();
        stakeCoin(other, table);
        manager.proposeLoot(player, 10);
        assertNotNull(table.getVote(), "With a second player betting the proposal goes to a vote");
    }

    @Test void committingAStreetNeedsATableAndNoOpenVote() {
        manager.commitStreet(player);
        assertEquals("wager.no_table", player.nextMessage());
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        drain(player);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 10);
        assertNotNull(table.getVote());
        drain(other);
        manager.commitStreet(other);
        assertEquals("wager.busy", other.nextMessage());
        verify(game, never()).onStreetCommit(any(), any(), anyInt(), anyBoolean());
    }

    @Test void aRetiredGameAsksForNothingSoAnyStreetCommits() throws Exception {
        Table table = loadRetired();
        stakeCoin(player, table);
        drain(player);
        manager.commitStreet(player);
        assertEquals("wager.committed", player.nextMessage());
        assertEquals(1, table.ledger().total(player.getUniqueId()));
    }

    @Test void lookingAtTheFeltFindsThatTable() {
        Table table = place(false);
        // Stand two blocks off and look down at the felt about three quarters of a block from the shoe.
        player.teleport(new Location(world, 2, 65, 0, 90, 52));
        assertSame(table, manager.tableNear(player));
        assertSame(table, manager.tableNearby(player));
        player.teleport(new Location(world, 2, 65, 0, 90, -90));
        assertNull(manager.tableNear(player), "Looking at the sky is not looking at a table");
        assertSame(table, manager.tableNearby(player), "Close enough to count as seated");
    }

    @Test void withoutAFeltInViewTheClosestTableWins() {
        Table near = place(false);
        Table farWest = placeAt(-1, 0);
        Table farSouth = placeAt(0, 3);
        player.teleport(new Location(world, 1, 65, 1, 0, -90));
        // Two equally distant tables make sure the "not closer" comparison is always exercised.
        assertTrue(near.getOrigin().distance(player.getLocation()) < farWest.getOrigin().distance(player.getLocation()));
        assertEquals(farWest.getOrigin().distance(player.getLocation()), farSouth.getOrigin().distance(player.getLocation()), 1e-9);
        assertSame(near, manager.tableNearby(player));
    }

    @Test void boxOwnersAreThePlayersNotTheTrayOrTheDealersOwnChips() {
        Table table = place(false);
        PlayerMock other = opponent();
        table.setDealerId(player.getUniqueId());
        restore(table, table.getId(), 2);
        restore(table, player.getUniqueId(), 3);
        assertFalse(manager.hasNonDealerOwnedPile(table));
        assertTrue(manager.boxOwners(table).isEmpty());
        restore(table, other.getUniqueId(), 1);
        assertTrue(manager.hasNonDealerOwnedPile(table));
        assertEquals(List.of(other.getUniqueId()), manager.boxOwners(table));
    }

    @Test void anOfflineOwnerWithoutASavedSpotIsDrawnAtTheTable() {
        Table table = place(false);
        UUID absent = UUID.randomUUID();
        restore(table, absent, 2);
        assertFalse(table.ledger().hasAnchor(absent));
        Location at = manager.boxLocation(table, absent);
        assertEquals(table.getOrigin().getX(), at.getX());
        assertEquals(table.getOrigin().getZ(), at.getZ());
        manager.syncBucketChips(table, absent);
        assertEquals(2, table.getPiles().stream().filter(p -> absent.equals(p.ownerId()))
                .mapToInt(PotPile::pieces).sum());
    }

    @Test void aTrayTooSmallForItsStacksPilesTheRestOnItsCentreRatherThanSpilling() {
        Cache.wagerMergeRange = 0.13;
        Cache.wagerGold = new WagerItemOverride("GOLD_NUGGET", 1, "gold", 3, 0.025f, 0.2f, false,
                null, false, null);
        Cache.tableLayouts.put("freeplay", new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6,
                Map.of("tray", new TableLayout.PileSlot(-0.6, 0.0)), null,
                new TableLayout.FeltBox(0, 0, 3, 3), null, 0.2));
        Table table = place(false);
        restore(table, table.getId(), 30);
        manager.syncBucketChips(table, table.getId());
        Location tray = Cache.layoutOf("freeplay").trayLocation(table);
        List<PotPile> piles = table.getPiles();
        assertEquals(10, piles.size());
        assertEquals(30, piles.stream().mapToInt(PotPile::pieces).sum());
        for (PotPile pile : piles) {
            assertTrue(Math.hypot(pile.x() - tray.getX(), pile.z() - tray.getZ()) < 0.2, "Every stack stays in the tray");
        }
        // Only the centre and its four diagonals are clear of each other inside so small a tray.
        assertEquals(5, piles.stream().map(p -> List.of(p.x(), p.z())).distinct().count());
        assertEquals(6, piles.stream().filter(p -> p.x() == tray.getX() && p.z() == tray.getZ()).count(),
                "Once the free spots run out the remaining stacks share the centre");
        assertEquals(30, manager.trayDenars(table));
    }

    @Test void aWholeDenarCoinIsWorthItsConfiguredUnit() {
        assertEquals(1, manager.chipUnitDenars(new ItemStack(Material.GOLD_NUGGET)));
    }

    private void restore(Table table, UUID owner, int count) {
        WagerEngine.get().restore(table, owner, new ItemStack(Material.GOLD_NUGGET), "GOLD_NUGGET", 1,
                count, 1, null, null);
    }

    private void drain(PlayerMock who) {
        while (who.nextMessage() != null) {
            // Earlier messages are not what these scenarios check.
        }
    }

    private Table placeAt(double x, double z) {
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, new Location(world, x, 65, z)));
        return manager.tables().stream()
                .filter(t -> t.getOrigin().getX() == x && t.getOrigin().getZ() == z)
                .findFirst().orElseThrow();
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
}
