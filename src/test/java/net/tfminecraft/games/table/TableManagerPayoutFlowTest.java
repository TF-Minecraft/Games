package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.PotPile;
import net.tfminecraft.games.wager.WagerEngine;

/** Chips animated for money that has already changed hands. */
class TableManagerPayoutFlowTest extends TableManagerFixture {
    private Map<String, TableLayout> previousLayouts;

    @BeforeEach void animatePayouts() {
        previousLayouts = new HashMap<>(Cache.tableLayouts);
        Cache.wagerPayoutTicks = 3;
    }

    @AfterEach void restoreLayouts() {
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
    }

    @Test void chipsLostToTheHouseFlyToTheTray() {
        Cache.tableLayouts.put("freeplay", new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6,
                Map.of("tray", new TableLayout.PileSlot(0, 1.6)), new TableLayout.FeltRing(0.3, 1.2),
                null, null, 0.3));
        Table table = place(false);
        stakeCoin(player, table);
        Location tray = Cache.layoutOf("freeplay").trayLocation(table);
        assertLandsAt(table, tray);
        assertEquals(1, manager.trayDenars(table));
    }

    @Test void withoutATrayChipsLostToTheHouseFlyToTheShoe() {
        Table table = place(false);
        stakeCoin(player, table);
        assertLandsAt(table, table.getOrigin());
        assertEquals(1, manager.trayDenars(table));
    }

    @Test void aTallStackLandsOnceWhenItsTopLayerArrives() {
        Table table = place(false);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 3));
        clickFelt(player, table);
        clickFelt(player, table);
        clickFelt(player, table);
        assertEquals(3, manager.ownedDenars(table, player.getUniqueId()));
        List<PayoutFlight> flights = new ArrayList<>();
        WagerEngine.get().refund(table, player.getUniqueId(), player, 0, flights, "cancelled bet");
        assertEquals(1, flights.size());
        List<UUID> layers = List.copyOf(flights.getFirst().pile().tokens());
        assertEquals(3, layers.size());
        AtomicInteger landed = new AtomicInteger();
        manager.flushPiles(table, flights, landed::incrementAndGet);
        tick(30);
        assertEquals(1, landed.get());
        for (UUID layer : layers) verify(display, atLeastOnce()).despawn(layer);
        assertEquals(3, Accounts.coins(table, player).available());
    }

    @Test void aWaveReplacedAsItTakesOffStopsAnimatingItsChips() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        List<PayoutFlight> first = refund(table, player);
        UUID token = first.getFirst().pile().tokens().getFirst();
        manager.flushPiles(table, first, null);
        tick(1);
        verify(display).setTransform(eq(token), any(), eq(0));
        manager.flushPiles(table, refund(table, other), null);
        verify(display).despawn(token);
        clearInvocations(display);
        tick(30);
        verify(display, never()).setTransform(eq(token), any(), anyInt());
        assertEquals(1, Accounts.coins(table, player).available());
        assertEquals(1, Accounts.coins(table, other).available());
    }

    @Test void aRendererRefusingFlightChipsStillFinishesThePayoutAtOnce() {
        Table table = place(false);
        stakeCoin(player, table);
        when(display.spawn(any(), any(), any(), any())).thenReturn(false);
        List<PayoutFlight> flights = new ArrayList<>();
        assertEquals(1, WagerEngine.get().refund(table, player.getUniqueId(), player, 0, flights,
                "cancelled bet").moved());
        assertTrue(flights.isEmpty());
        AtomicInteger done = new AtomicInteger();
        manager.flushPiles(table, flights, done::incrementAndGet);
        assertEquals(1, done.get());
        assertFalse(table.isPaying());
        assertEquals(1, Accounts.coins(table, player).available());
    }

    @Test void withChipsHiddenNothingFliesAndThePayoutLandsAtOnce() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        Cache.wagerShowChips = false;
        clearInvocations(display);
        drain(player);
        manager.payout(table, player);
        assertFalse(table.isPaying());
        verify(display, never()).spawn(any(), any(), any(), any());
        assertEquals(2, Accounts.coins(table, player).available());
        assertTrue(table.ledger().isEmpty());
        assertEquals("wager.paid", player.nextMessage());
    }

    @Test void askingForAPayoutWhileChipsAreFlyingTellsTheWinnerToWait() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        manager.flushPiles(table, refund(table, other), null);
        drain(player);
        manager.payout(table, player);
        assertEquals("wager.paying", player.nextMessage());
        assertEquals(1, manager.ownedDenars(table, player.getUniqueId()));
        tick(30);
        assertEquals(1, Accounts.coins(table, other).available());
    }

    @Test void payingOutAnEmptyFeltTellsTheSeatsThereIsNothingToPay() {
        Table table = place(false);
        stakeCoin(player, table);
        manager.refundOwnedPiles(table, player);
        tick(30);
        assertTrue(table.actives().contains(player.getUniqueId()));
        drain(player);
        manager.payout(table, player);
        assertEquals("wager.empty", player.nextMessage());
        assertTrue(table.actives().contains(player.getUniqueId()));
    }

    @Test void aWinnerWhoLogsOffMidFlightKeepsTheMoneyWithoutAStaleMessage() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        manager.payout(table, other);
        assertTrue(table.isPaying());
        assertEquals(2, Accounts.coins(table, other).available());
        drain(other);
        other.disconnect();
        tick(30);
        assertFalse(table.isPaying());
        assertNull(other.nextMessage());
        assertEquals(2, other.getInventory().all(Material.GOLD_NUGGET).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
        assertTrue(table.ledger().isEmpty());
    }

    @Test void theAuditStaysQuietWhenARoundEndsWithAnEmptyFelt() {
        boolean previous = Cache.wagerAuditLog;
        try {
            Cache.wagerAuditLog = true;
            Table table = place(false);
            manager.beginSession(table);
            clearInvocations(Games.plugin.getLogger());
            manager.endSession(table);
            verify(Games.plugin.getLogger(), never()).warning(anyString());
        } finally {
            Cache.wagerAuditLog = previous;
        }
    }

    /** Lose the player's stake to the house and follow the chips to where they land. */
    private void assertLandsAt(Table table, Location expected) {
        List<PayoutFlight> flights = new ArrayList<>();
        WagerEngine.get().begin(table, "lost to house").animate(flights)
                .moveAll(Accounts.bucket(table, player.getUniqueId()), Accounts.tray(table)).commit();
        assertEquals(1, flights.size());
        assertTrue(flights.getFirst().stayOnTray());
        PotPile pile = flights.getFirst().pile();
        UUID token = pile.tokens().getFirst();
        AtomicInteger done = new AtomicInteger();
        manager.flushPiles(table, flights, done::incrementAndGet);
        tick(30);
        assertEquals(1, done.get());
        ArgumentCaptor<DisplayPose> poses = ArgumentCaptor.forClass(DisplayPose.class);
        verify(display, atLeastOnce()).setTransform(eq(token), poses.capture(), eq(1));
        DisplayPose last = poses.getAllValues().getLast();
        assertEquals(expected.getX(), pile.x() + last.translation().x, 1e-4);
        assertEquals(expected.getZ(), pile.z() + last.translation().z, 1e-4);
        verify(display, atLeastOnce()).despawn(token);
    }

    private List<PayoutFlight> refund(Table table, PlayerMock owner) {
        List<PayoutFlight> flights = new ArrayList<>();
        assertEquals(1, WagerEngine.get().refund(table, owner.getUniqueId(), owner, 0, flights,
                "cancelled bet").moved());
        assertFalse(flights.isEmpty());
        return flights;
    }

    private static void drain(PlayerMock target) {
        while (target.nextMessage() != null) { }
    }
}
