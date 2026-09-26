package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.WagerEngine;
import net.tfminecraft.games.wager.WagerItemOverride;

/** What a right click on the felt does with whatever the player is holding. */
class TableManagerChipPlaceTest extends TableManagerFixture {
    private Map<String, TableLayout> previousLayouts;

    @BeforeEach void rememberLayouts() {
        previousLayouts = new HashMap<>(Cache.tableLayouts);
        // Nothing held in these tests is a deck, so unclaimed clicks fall through to the world.
        when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(false);
    }

    @AfterEach void restoreLayouts() {
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
    }

    @Test void blackjackLeavesOrdinaryItemsAloneAndRefusesStakeableItemsThatAreNotCoins() {
        Table table = blackjack();
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        assertFalse(click(player, felt(table)).isCancelled());
        Cache.wagerItems.add(new WagerItemOverride("DIAMOND", 5, null, null, null, null,
                null, null, false, null));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        assertTrue(click(player, felt(table)).isCancelled());
        assertEquals("wager.coins_only", player.nextMessage());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(table.ledger().isEmpty());
    }

    @Test void aLiveBlackjackRoundRefusesLateCoinsEvenWithBetsFlaggedOpen() {
        Table table = blackjack();
        manager.beginSession(table);
        assertTrue(table.betOpen());
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 3));
        assertTrue(click(player, felt(table)).isCancelled());
        assertEquals("bet.closed", player.nextMessage());
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(table.ledger().isEmpty());
    }

    @Test void coinsCannotBeStakedOnTheShoe() {
        Cache.tableLayouts.put("freeplay", new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6, Map.of(),
                new TableLayout.FeltRing(0.3, 1.2), null, null, 0.5));
        Table table = placeQuietly();
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 3));
        assertTrue(click(player, table.getOrigin().clone().add(0.4, 0, 0)).isCancelled());
        assertEquals("wager.no_bet_zone", player.nextMessage());
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(table.ledger().isEmpty());
    }

    @Test void coinsClickedWhileAPayoutIsInTheAirAreKept() {
        Cache.wagerPayoutTicks = 5;
        Table table = placeQuietly();
        PlayerMock other = opponent();
        stakeCoin(other, table);
        List<PayoutFlight> flights = new ArrayList<>();
        WagerEngine.get().refund(table, other.getUniqueId(), other, 0, flights, "cancelled bet");
        manager.flushPiles(table, flights, null);
        assertTrue(table.isPaying());
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 3));
        assertTrue(click(player, felt(table)).isCancelled());
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(table.ledger().isEmpty());
        assertFalse(table.actives().contains(player.getUniqueId()));
        tick(30);
        assertFalse(table.isPaying());
        assertEquals(1, Accounts.coins(table, other).available());
    }

    @Test void anEmptyHandOnTheFeltIsSwallowedWithoutStakingAnything() {
        Table table = placeQuietly();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        assertTrue(click(player, felt(table)).isCancelled());
        assertTrue(table.ledger().isEmpty());
        assertTrue(table.actives().isEmpty());
        verify(game, never()).onChipIn(any(), any(), anyInt(), any());
    }

    @Test void itemsNeedingADeclaredValueWaitForTheWagerCommandAndOrdinaryItemsPassThrough() {
        Table table = placeQuietly();
        Cache.wagerItems.add(new WagerItemOverride("DIAMOND", null, null, null, null, null,
                null, null, false, null));
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        assertTrue(click(player, felt(table)).isCancelled());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(table.ledger().isEmpty());
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE, 2));
        assertFalse(click(player, felt(table)).isCancelled());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(table.ledger().isEmpty());
        assertTrue(table.actives().isEmpty());
    }

    private Table placeQuietly() {
        Table table = place(false);
        assertEquals("place.done", player.nextMessage());
        return table;
    }

    private Table blackjack() {
        games.when(() -> GamesRegistry.of("blackjack")).thenReturn(game);
        var permission = player.addAttachment(Games.plugin, TableHouse.STAFF_PERM, true);
        TableHouse house = TableHouse.forPlace(player, null);
        house.setStaffMint(true);
        manager.armPlace(player, "blackjack", false, house);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table table = manager.tables().iterator().next();
        player.removeAttachment(permission);
        table.setBetOpen(true);
        while (player.nextMessage() != null) { }
        return table;
    }

    private static Location felt(Table table) {
        return table.getOrigin().clone().add(0.75, 0, 0);
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
}
