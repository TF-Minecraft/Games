package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.WagerEngine;

class TableManagerPayoutTest extends TableManagerFixture {
    @BeforeEach void animatePayouts() {
        Cache.wagerPayoutTicks = 5;
    }

    @Test void mixedOnlineAndOfflineRefundsMoveMoneyBeforeCompletingOneCallback() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        other.disconnect();
        List<PayoutFlight> flights = new ArrayList<>();
        assertEquals(2, WagerEngine.get().returnStakes(table, flights, "hand cancelled").moved());
        assertEquals(1, pockets(table, player));
        assertEquals(1, droppedCoins());
        assertTrue(table.ledger().isEmpty());
        List<UUID> tokens = tokens(flights);
        AtomicInteger done = new AtomicInteger();
        manager.flushPiles(table, flights, done::incrementAndGet);
        assertEquals(0, done.get(), "online flight must finish before the shared callback");
        assertTrue(table.isPaying());
        assertTrue(table.payoutFlying().stream().allMatch(f -> player.getUniqueId().equals(f.destId())));
        tick(30);
        assertEquals(1, done.get());
        assertFalse(table.isPaying());
        assertEquals(1, pockets(table, player));
        assertEquals(1, droppedCoins());
        assertRetired(tokens);
        tick(30);
        assertEquals(1, done.get());
    }

    @Test void secondWaveLandsFirstWaveAndRunsEachCallbackExactlyOnce() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        List<PayoutFlight> first = refund(table, player);
        List<UUID> firstTokens = tokens(first);
        List<String> callbacks = new ArrayList<>();
        manager.flushPiles(table, first, () -> callbacks.add("first"));
        tick(2);
        assertTrue(callbacks.isEmpty());
        List<PayoutFlight> second = refund(table, other);
        List<UUID> secondTokens = tokens(second);
        manager.flushPiles(table, second, () -> callbacks.add("second"));
        assertEquals(List.of("first"), callbacks);
        assertRetired(firstTokens);
        assertEquals(1, pockets(table, player));
        assertEquals(1, pockets(table, other));
        assertTrue(table.ledger().isEmpty());
        tick(30);
        assertEquals(List.of("first", "second"), callbacks);
        assertRetired(secondTokens);
        assertFalse(table.isPaying());
        tick(30);
        assertEquals(List.of("first", "second"), callbacks);
        assertEquals(2, pockets(table, player) + pockets(table, other));
        assertEquals(0, droppedCoins());
    }

    @Test void completionThatStartsAnotherWaveDoesNotLoseTheAlreadyPendingWave() {
        Table table = place(false);
        PlayerMock secondPlayer = opponent();
        PlayerMock thirdPlayer = opponent();
        stakeCoin(player, table);
        stakeCoin(secondPlayer, table);
        stakeCoin(thirdPlayer, table);
        List<PayoutFlight> first = refund(table, player);
        List<String> callbacks = new ArrayList<>();
        List<UUID> allTokens = new ArrayList<>(tokens(first));
        manager.flushPiles(table, first, () -> {
            callbacks.add("first");
            List<PayoutFlight> third = refund(table, thirdPlayer);
            allTokens.addAll(tokens(third));
            manager.flushPiles(table, third, () -> callbacks.add("third"));
        });
        List<PayoutFlight> second = refund(table, secondPlayer);
        allTokens.addAll(tokens(second));
        manager.flushPiles(table, second, () -> callbacks.add("second"));
        assertEquals(List.of("first", "second"), callbacks);
        assertTrue(table.isPaying());
        tick(30);
        assertEquals(List.of("first", "second", "third"), callbacks);
        assertEquals(3, pockets(table, player) + pockets(table, secondPlayer) + pockets(table, thirdPlayer));
        assertTrue(table.ledger().isEmpty());
        assertFalse(table.isPaying());
        assertRetired(allTokens);
    }

    @Test void emptyFlushCompletesItsCallbackWithoutCancellingAnExistingFlight() {
        Table table = place(false);
        stakeCoin(player, table);
        List<String> callbacks = new ArrayList<>();
        manager.flushPiles(table, refund(table, player), () -> callbacks.add("flight"));
        manager.flushPiles(table, List.of(), () -> callbacks.add("empty"));
        assertEquals(List.of("empty"), callbacks);
        assertTrue(table.isPaying());
        tick(30);
        assertEquals(List.of("empty", "flight"), callbacks);
        assertEquals(1, pockets(table, player));
    }

    @Test void disconnectAfterCommittedRefundDoesNotDropOrPayTheMoneyAgain() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(other, table);
        List<PayoutFlight> flights = refund(table, other);
        List<UUID> tokens = tokens(flights);
        AtomicInteger done = new AtomicInteger();
        manager.flushPiles(table, flights, done::incrementAndGet);
        assertEquals(1, pockets(table, other));
        other.disconnect();
        tick(30);
        assertEquals(1, done.get());
        assertEquals(1, other.getInventory().all(Material.GOLD_NUGGET).values().stream()
                .mapToInt(item -> item.getAmount()).sum());
        assertEquals(0, droppedCoins());
        assertTrue(table.ledger().isEmpty());
        assertRetired(tokens);
    }

    @Test void shutdownDuringFlightCancelsContinuationAndRetiresItsDisplays() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        List<PayoutFlight> flights = refund(table, player);
        List<UUID> tokens = tokens(flights);
        AtomicInteger done = new AtomicInteger();
        manager.flushPiles(table, flights, done::incrementAndGet);
        tick(2);
        manager.despawnWorldAll();
        assertEquals(1, pockets(table, player));
        assertEquals(1, pockets(table, other), "unpaid bucket must be refunded during shutdown");
        assertTrue(table.ledger().isEmpty());
        assertFalse(table.isPaying());
        assertRetired(tokens);
        clearInvocations(display);
        tick(30);
        verifyNoInteractions(display);
        assertEquals(0, done.get(), "a cancelled hand must not continue after shutdown");
        assertEquals(2, pockets(table, player) + pockets(table, other));
        assertEquals(0, droppedCoins());
    }

    @Test void pickupDuringFlightKeepsCommittedMoneyAndRemovesAllFlightDisplays() {
        Table table = place(false);
        stakeCoin(player, table);
        List<PayoutFlight> flights = refund(table, player);
        List<UUID> tokens = tokens(flights);
        AtomicInteger done = new AtomicInteger();
        manager.flushPiles(table, flights, done::incrementAndGet);
        tick(2);
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(player);
        manager.onHitEntity(hit);
        verify(hit).setCancelled(true);
        assertNull(manager.table(table.getId()));
        assertFalse(Files.exists(data.resolve("Data/tables/" + table.getId() + ".json")));
        assertEquals(1, pockets(table, player));
        assertTrue(table.ledger().isEmpty());
        assertRetired(tokens);
        clearInvocations(display);
        tick(30);
        verifyNoInteractions(display);
        assertEquals(0, done.get());
        assertEquals(1, pockets(table, player));
        assertEquals(0, droppedCoins());
    }

    private List<PayoutFlight> refund(Table table, PlayerMock owner) {
        List<PayoutFlight> flights = new ArrayList<>();
        assertEquals(1, WagerEngine.get().refund(table, owner.getUniqueId(), owner, 0,
                flights, "cancelled bet").moved());
        assertFalse(flights.isEmpty(), "real transfer must create a visible payout wave");
        return flights;
    }

    private static List<UUID> tokens(List<PayoutFlight> flights) {
        List<UUID> tokens = flights.stream().flatMap(f -> f.pile().tokens().stream()).toList();
        assertFalse(tokens.isEmpty());
        return tokens;
    }

    private void assertRetired(List<UUID> tokens) {
        for (UUID token : tokens) verify(display, atLeastOnce()).despawn(token);
    }

    private int pockets(Table table, PlayerMock owner) {
        return Accounts.coins(table, owner).available();
    }

    private int droppedCoins() {
        return world.getEntities().stream().filter(Item.class::isInstance).map(Item.class::cast)
                .map(Item::getItemStack).filter(item -> item.getType() == Material.GOLD_NUGGET)
                .mapToInt(item -> item.getAmount()).sum();
    }
}
