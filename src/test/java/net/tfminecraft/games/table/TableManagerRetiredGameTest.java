package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.layout.TablePileLayout;
import net.tfminecraft.games.wager.Accounts;

/**
 * An admin can place a table for any game id, and a saved table can outlive the game it was
 * made for. Such a table has no rules behind it and must behave as plain free play.
 */
class TableManagerRetiredGameTest extends TableManagerFixture {
    @BeforeEach void faceArt() {
        when(items.getCreator().getItemFromPath("face")).thenReturn(new ItemStack(Material.DIAMOND));
    }

    @Test void stakesNeverStartARoundAndAnIdleTableCanBeFlushedByHand() {
        Table table = retired();
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        assertFalse(table.live(), "without a game nothing decides when a round begins");
        drain(player);
        player.setSneaking(true);
        shoe(table, player);
        assertTrue(table.ledger().isEmpty());
        assertEquals(2, Accounts.coins(table, player).available());
        assertEquals(0, Accounts.coins(table, other).available());
        assertEquals("wager.paid", player.nextMessage());
    }

    @Test void aSessionStartedByCommandBlocksTheManualFlushUntilItEnds() {
        Table table = retired();
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        manager.beginSession(table);
        assertTrue(table.live());
        drain(player);
        player.setSneaking(true);
        shoe(table, player);
        assertEquals("wager.no_flush", player.nextMessage());
        assertEquals(2, table.ledger().total());
        manager.endSession(table);
        assertFalse(table.live());
        shoe(table, player);
        assertTrue(table.ledger().isEmpty());
        assertEquals(2, Accounts.coins(table, player).available());
    }

    @Test void playersDrawFreelyAndMayShowTheirCards() {
        Table table = retired();
        shoe(table, player);
        tick(2);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(player,
                new ItemStack(Material.AIR), new ItemStack(Material.AIR));
        manager.onSwapHands(swap);
        assertTrue(swap.isCancelled());
        tick(10);
        verify(display).setItem(eq(held.tokenId()), argThat(item -> item.getType() == Material.DIAMOND));
        assertEquals(1, table.getDeck().remaining());
    }

    @Test void publicPilesUseTheStandardLayout() {
        Table table = retired();
        clearInvocations(display);
        manager.dealToTable(table, "board", 1, true);
        tick(2);
        assertEquals(1, table.tablePile("board").size());
        ArgumentCaptor<DisplayPose> pose = ArgumentCaptor.forClass(DisplayPose.class);
        verify(display).spawn(eq(table.tablePile("board").getFirst().tokenId()), any(), any(), pose.capture());
        assertEquals(TablePileLayout.slot(table, "board", 0, 1, true).translation(), pose.getValue().translation());
        manager.revealTablePile(table, "board");
        manager.muckTable(table, "board");
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void revealedCardsSparkleUnlessTheGameTurnsThatOff() {
        Table retired = retired();
        assertEquals(1, dustAt(retired), "a table without rules shows reveal dust");
        manager.wipeHands();
        player.teleport(new Location(world, 20, 65, 0));
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table ruled = manager.tables().stream().filter(t -> t != retired).findFirst().orElseThrow();
        assertEquals(0, dustAt(ruled), "this game has switched reveal dust off");
    }

    /** Deal one card, make it public the way a showdown does, and count dust over one clock cycle. */
    private int dustAt(Table table) {
        manager.dealToPlayer(table, player, 1);
        tick(2);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        manager.publishHand(table, player);
        when(display.worldLocation(held.tokenId())).thenReturn(table.getOrigin().clone().add(0, 1, 0));
        Player recipient = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doNothing().when(recipient).spawnParticle(eq(Particle.DUST), anyDouble(), anyDouble(), anyDouble(),
                anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(Particle.DustOptions.class));
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(recipient);
            manager.startClock();
            tick(3);
            manager.stopClock();
        }
        return mockingDetails(recipient).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("spawnParticle")).toList().size();
    }

    private Table retired() {
        manager.armPlace(player, "retired", false);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        drain(player);
        Table table = manager.tables().iterator().next();
        assertEquals("retired", table.getGameId());
        return table;
    }

    private void shoe(Table table, PlayerMock actor) {
        Entity shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
        PlayerInteractAtEntityEvent event = new PlayerInteractAtEntityEvent(actor, shoe, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(event);
        assertTrue(event.isCancelled());
    }

    private static void drain(PlayerMock target) {
        while (target.nextMessage() != null) { }
    }
}
