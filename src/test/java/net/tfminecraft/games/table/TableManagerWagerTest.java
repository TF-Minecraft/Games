package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.RayTraceResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import com.google.gson.JsonParser;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.WagerEngine;
import net.tfminecraft.games.wager.WagerItemOverride;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.guild.income.Ledger;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Bank;

class TableManagerWagerTest extends TableManagerFixture {
    private Map<String, TableLayout> oldLayouts;

    @BeforeEach void rememberLayouts() {
        oldLayouts = new HashMap<>(Cache.tableLayouts);
    }

    @AfterEach void restoreLayouts() {
        if (oldLayouts != null) {
            Cache.tableLayouts.clear();
            Cache.tableLayouts.putAll(oldLayouts);
        }
    }

    private Table blackjack() {
        games.when(() -> GamesRegistry.of("blackjack")).thenReturn(game);
        player.addAttachment(Games.plugin, TableHouse.STAFF_PERM, true);
        TableHouse house = TableHouse.forPlace(player, null);
        house.setStaffMint(true);
        house.setMinBet(2);
        house.setMaxBet(2);
        house.setMaxBoxes(1);
        manager.armPlace(player, "blackjack", false, house);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table table = manager.tables().iterator().next();
        table.setBetOpen(true);
        return table;
    }

    private ItemStack coins(int count) {
        return new ItemStack(Material.GOLD_NUGGET, count);
    }

    private void restore(Table table, UUID owner, int value) {
        WagerEngine.get().restore(table, owner, coins(1), "gold", 1, value,
                table.street(), null, null);
        manager.syncBucketChips(table, owner);
    }

    private int pockets(PlayerMock owner, Table table) {
        return Accounts.coins(table, owner).available();
    }

    private void clickAt(PlayerMock actor, Location at) {
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(actor));
        doReturn(new RayTraceResult(at.toVector())).when(clicker).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                actor.getInventory().getItemInMainHand(), world.getBlockAt(0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        manager.onInteractBlock(event);
        assertTrue(event.isCancelled());
    }

    private TableLayout trayLayout() {
        return new TableLayout(Cache.pokerCardSet, "Test", "deck", 6,
                Map.of("tray", new TableLayout.PileSlot(0, 0.75)),
                new TableLayout.FeltRing(0.3, 1.2), null, null, 0.15);
    }

    @Test void blackjackAcceptsWholeCoinBetsWithinLimitsAndRefusesFullOrClosedBoxes() {
        Table table = blackjack();
        player.getInventory().setItemInMainHand(coins(3));
        clickFelt(player, table);
        assertFalse(manager.hasLegalBlackjackBox(table));
        assertEquals(List.of(player.getUniqueId()), manager.boxOwners(table));
        clickFelt(player, table);
        assertTrue(manager.hasLegalBlackjackBox(table));
        assertEquals(2, manager.ownedDenars(table, player.getUniqueId()));
        clickFelt(player, table);
        assertEquals(1, pockets(player, table), "Bet over max must remain in inventory");
        PlayerMock other = opponent();
        other.getInventory().setItemInMainHand(coins(1));
        clickFelt(other, table);
        assertEquals(0, manager.ownedDenars(table, other.getUniqueId()));
        assertEquals(1, pockets(other, table), "Full table must not take another player's coin");
        table.setBetOpen(false);
        manager.refundOwnedPiles(table, player);
        clickFelt(player, table);
        assertEquals(3, pockets(player, table));
        assertTrue(table.ledger().isEmpty());
    }

    @Test void blackjackRejectsDeclaredLootWithoutRemovingItemsOrStartingVote() {
        Table table = blackjack();
        stakeCoin(player, table);
        Cache.wagerItems.add(new WagerItemOverride("DIAMOND", null, null, null, null, null,
                null, null, false, null));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 10);
        assertNull(table.getVote());
        clickFelt(player, table);
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(1, table.ledger().total());
    }

    @Test void trayStockBelongsToDealerAndReturnsToThatDealerRatherThanOtherPlayers() {
        Cache.tableLayouts.put("freeplay", trayLayout());
        Table table = place(false);
        table.setDealerId(player.getUniqueId());
        Location tray = Cache.layoutOf("freeplay").trayLocation(table);
        PlayerMock other = opponent();
        other.getInventory().setItemInMainHand(coins(1));
        clickAt(other, tray);
        assertEquals(1, pockets(other, table));
        assertEquals(0, manager.trayDenars(table));
        player.getInventory().setItemInMainHand(coins(1));
        clickAt(player, tray);
        assertEquals(table.getId(), manager.trayOwner(table));
        assertEquals(1, manager.trayDenars(table));
        assertTrue(table.getPiles().stream().allMatch(pile -> manager.isTrayPile(table, pile)));
        assertFalse(manager.hasNonDealerOwnedPile(table));
        assertEquals(tray, manager.boxLocation(table, table.getId()));
        manager.bankAutoTray(table);
        assertEquals(1, pockets(player, table));
        assertEquals(1, pockets(other, table));
        assertTrue(table.ledger().isEmpty());
    }

    @Test void backedTrayRefusesDealersPersonalCoinsAndMintSettlementPreservesPlayerBets() {
        Cache.tableLayouts.put("freeplay", trayLayout());
        Table table = place(false);
        table.setDealerId(player.getUniqueId());
        table.setStaffMint(true);
        player.getInventory().setItemInMainHand(coins(1));
        clickAt(player, Cache.layoutOf("freeplay").trayLocation(table));
        assertEquals(1, pockets(player, table));
        assertEquals(0, manager.trayDenars(table));
        PlayerMock other = opponent();
        stakeCoin(other, table);
        assertEquals(5, WagerEngine.get().fundFromHouse(table, table.getId(), coins(1), 5,
                table.getOrigin(), "staff float").moved());
        manager.bankAutoTray(table);
        assertEquals(0, manager.trayDenars(table));
        assertEquals(1, manager.ownedDenars(table, other.getUniqueId()));
        assertEquals(1, table.ledger().total());
    }

    @Test void guildTrayDepositRepaysFloatAndDeclaresOnlyRemainingProfit() {
        Table table = place(false);
        table.setOwnerGuildId("guild");
        table.setHouseFloat(5);
        restore(table, table.getId(), 8);
        PluginManager plugins = mock(PluginManager.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("SimpleFactions")).thenReturn(plugin);
        Guild guild = mock(Guild.class);
        Bank bank = mock(Bank.class);
        Ledger ledger = mock(Ledger.class);
        when(guild.getBank()).thenReturn(bank);
        when(guild.getLedger()).thenReturn(ledger);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                MockedStatic<FactionManager> factions = mockStatic(FactionManager.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            factions.when(() -> FactionManager.getGuildByString("guild")).thenReturn(guild);
            manager.bankAutoTray(table);
        }
        verify(bank).deposit(8.0);
        verify(ledger).addCasinoProfitEntry(3.0);
        assertEquals(0, table.houseFloat());
        assertTrue(table.ledger().isEmpty());
        assertTrue(table.getPiles().isEmpty());
    }

    @Test void missingGuildBankDropsTrayCoinsAtTableInsteadOfDeletingThem() {
        Table table = place(false);
        table.setOwnerGuildId("deleted-guild");
        table.setHouseFloat(5);
        restore(table, table.getId(), 5);
        manager.bankAutoTray(table);
        int dropped = world.getEntitiesByClass(Item.class).stream()
                .filter(item -> item.getItemStack().getType() == Material.GOLD_NUGGET)
                .mapToInt(item -> item.getItemStack().getAmount()).sum();
        assertEquals(5, dropped);
        assertTrue(table.ledger().isEmpty());
        assertEquals(5, table.houseFloat(), "A refused bank transfer cannot retire its float");
    }

    @Test void houseEditsRequireOwnershipOrPermissionAndGuildChangeResetsOldFloat() throws Exception {
        Table table = place(false);
        PlayerMock other = opponent();
        assertTrue(manager.canEditHouse(player, table));
        assertFalse(manager.canEditHouse(other, table));
        other.addAttachment(Games.plugin, "games.admin", true);
        assertTrue(manager.canEditHouse(other, table));
        other.addAttachment(Games.plugin, "games.admin", false);
        other.addAttachment(Games.plugin, TableHouse.STAFF_PERM, true);
        assertTrue(manager.canEditHouse(other, table));
        TableHouse first = TableHouse.from(table);
        first.setOwnerGuildId("first-guild");
        first.setMinBet(2);
        first.setMaxBet(10);
        manager.applyHouse(table, first);
        table.setHouseFloat(7);
        TableHouse sameOwner = TableHouse.from(table);
        sameOwner.setMaxBet(20);
        manager.applyHouse(table, sameOwner);
        assertEquals(7, table.houseFloat());
        TableHouse newOwner = TableHouse.from(table);
        newOwner.setOwnerGuildId("second-guild");
        manager.applyHouse(table, newOwner);
        assertEquals(0, table.houseFloat());
        assertEquals("second-guild", table.ownerGuildId());
        var saved = JsonParser.parseString(Files.readString(
                data.resolve("Data/tables/" + table.getId() + ".json"))).getAsJsonObject();
        assertEquals("second-guild", saved.get("ownerGuildId").getAsString());
        assertEquals(0, saved.get("houseFloat").getAsInt());
        assertEquals(20, saved.get("maxBet").getAsInt());
    }

    @Test void voterLeavingCountsAsNoAndReturnsTheirStakeWithoutDestroyingProposedLoot() {
        Table table = place(false);
        PlayerMock yes = opponent();
        PlayerMock leaving = opponent();
        stakeCoin(player, table);
        stakeCoin(yes, table);
        stakeCoin(leaving, table);
        doCallRealMethod().when(game).onLeave(any(), any());
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 10);
        assertEquals(2, table.getVote().eligible().size());
        manager.voteWager(yes, true);
        assertNotNull(table.getVote(), "One of two eligible voters is not a majority");
        manager.onQuit(new PlayerQuitEvent(leaving, "left"));
        assertNull(table.getVote());
        assertEquals(1, pockets(leaving, table));
        assertFalse(table.actives().contains(leaving.getUniqueId()));
        assertEquals(2, table.ledger().total());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test void proposerCanCancelOrQuitWithoutCommittingLootOrLeavingVoteTimerAlive() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        doCallRealMethod().when(game).onLeave(any(), any());
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 10);
        manager.voteWager(player, true);
        assertNotNull(table.getVote(), "Proposer cannot approve their own wager");
        manager.voteWager(player, false);
        assertNull(table.getVote());
        manager.proposeLoot(player, 10);
        assertNotNull(table.getVote());
        manager.onQuit(new PlayerQuitEvent(player, "left"));
        assertNull(table.getVote());
        assertEquals(1, pockets(player, table));
        assertEquals(2, player.getInventory().all(Material.DIAMOND).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
        tick(25);
        assertNull(table.getVote());
        assertEquals(1, table.ledger().total());
    }

    @Test void worldShutdownRefundsPlayersAndHumanDealerFloatAndLeavesAnIdleTable() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        table.setDealerId(player.getUniqueId());
        restore(table, table.getId(), 3);
        manager.beginSession(table);
        manager.despawnWorldAll();
        assertEquals(4, pockets(player, table));
        assertEquals(1, pockets(other, table));
        assertTrue(table.ledger().isEmpty());
        assertTrue(table.getPiles().isEmpty());
        assertTrue(table.actives().isEmpty());
        assertNull(table.dealerId());
        assertFalse(table.live());
        assertFalse(table.isPaying());
        assertEquals(1, table.street());
        assertSame(table, manager.table(table.getId()));
        verify(game).onTableRemoved(table);
    }
}
