package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.game.FreePlayGame;
import net.tfminecraft.games.game.GamesRegistry;

/** Walking away from, or logging out of, the tables a player is involved with. */
class TableManagerLeaveTest extends TableManagerFixture {

    @BeforeEach void realGame() {
        games.when(() -> GamesRegistry.of("freeplay")).thenReturn(new FreePlayGame());
    }

    private Table placeAt(double x) {
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, new Location(world, x, 65, 0)));
        return manager.tables().stream().filter(t -> t.getOrigin().getX() == x).findFirst().orElseThrow();
    }

    private int pocketGold(PlayerMock who) {
        return who.getInventory().all(Material.GOLD_NUGGET).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    /** Stake one coin at each of two tables 8 blocks apart, standing between them. */
    private Table[] stakedAtTwoTables() {
        Table west = placeAt(0);
        Table east = placeAt(8);
        player.teleport(new Location(world, 4, 65, 0, 90, 0));
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 2));
        clickFelt(player, west);
        clickFelt(player, east);
        assertEquals(1, manager.ownedDenars(west, player.getUniqueId()));
        assertEquals(1, manager.ownedDenars(east, player.getUniqueId()));
        assertEquals(0, pocketGold(player));
        return new Table[] {west, east};
    }

    @Test void quittingReturnsTheStakeAtEveryTableThePlayerBetAt() {
        Table[] both = stakedAtTwoTables();
        manager.onQuit(new PlayerQuitEvent(player, "quit"));
        for (Table table : both) {
            assertEquals(0, manager.ownedDenars(table, player.getUniqueId()));
            assertFalse(table.actives().contains(player.getUniqueId()), "no seat is kept for a player who left");
        }
        assertEquals(2, pocketGold(player));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void walkingAwayFromOneTableReturnsOnlyThatStake(boolean walkEast) {
        Table[] both = stakedAtTwoTables();
        Table left = walkEast ? both[0] : both[1];
        Table kept = walkEast ? both[1] : both[0];
        manager.startClock();
        player.teleport(new Location(world, walkEast ? 12 : -4, 65, 0, 90, 0));
        tick(2);
        manager.stopClock();
        assertEquals(0, manager.ownedDenars(left, player.getUniqueId()), "the table left behind refunds");
        assertFalse(left.actives().contains(player.getUniqueId()));
        assertEquals(1, manager.ownedDenars(kept, player.getUniqueId()), "the nearby table keeps the bet");
        assertTrue(kept.actives().contains(player.getUniqueId()));
        assertEquals(1, pocketGold(player));
    }

    @Test void travellingToAnotherWorldReturnsTheHand() {
        Table table = placeAt(0);
        manager.dealToPlayer(table, player, 1);
        tick(2);
        while (player.nextMessage() != null) {
            // Placement notices are not under test here.
        }
        WorldMock elsewhere = new WorldMock();
        elsewhere.setName("elsewhere-" + java.util.UUID.randomUUID());
        MockBukkit.getMock().addWorld(elsewhere);
        player.teleport(new Location(elsewhere, 0, 65, 0));
        manager.startClock();
        tick(2);
        manager.stopClock();
        assertTrue(table.getHands().isEmpty());
        assertEquals("hand.returned", player.nextMessage());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void aBystanderLoggingOutLeavesOtherPlayersSeatsAlone() {
        Table table = placeAt(0);
        PlayerMock dealer = opponent();
        table.setDealerId(dealer.getUniqueId());
        stakeCoin(dealer, table);
        manager.onQuit(new PlayerQuitEvent(player, "quit"));
        assertEquals(dealer.getUniqueId(), table.dealerId());
        assertEquals(1, manager.ownedDenars(table, dealer.getUniqueId()));
    }

    @Test void loggingOutOfAFeltLeavesAnotherPlayersHandUntouched() {
        Table table = placeAt(0);
        PlayerMock holder = opponent();
        manager.dealToPlayer(table, holder, 1);
        tick(2);
        List<HandCard> held = List.copyOf(table.handOf(holder.getUniqueId()));
        stakeCoin(player, table);
        manager.onQuit(new PlayerQuitEvent(player, "quit"));
        assertEquals(held, table.handOf(holder.getUniqueId()));
        assertEquals(1, table.getDeck().remaining());
        assertEquals(1, pocketGold(player));
        assertFalse(table.actives().contains(player.getUniqueId()));
    }

    @Test void muckingAPlayerWhoHoldsNoCardsLeavesTheShoeAndOtherHandsAlone() {
        Table table = placeAt(0);
        PlayerMock holder = opponent();
        manager.dealToPlayer(table, holder, 1);
        tick(2);
        manager.muckPlayer(table, player);
        assertEquals(1, table.handOf(holder.getUniqueId()).size());
        assertEquals(1, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
    }

    // ------------------------------------------------------------ a table of an unknown game

    /** A table whose game is no longer registered, for example after its config was removed. */
    private Table unknownGameTable() {
        manager.armPlace(player, "retired-game", false);
        assertTrue(manager.tryPlace(player, new Location(world, 0, 65, 0)));
        return manager.tables().iterator().next();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void walkingAwayFromATableOfAnUnknownGameStillRefundsTheStake(boolean holdingCards) {
        Table table = unknownGameTable();
        stakeCoin(player, table);
        assertTrue(table.actives().contains(player.getUniqueId()));
        if (holdingCards) {
            manager.dealToPlayer(table, player, 1);
            tick(2);
        }
        manager.startClock();
        player.teleport(new Location(world, 50, 65, 0));
        tick(2);
        manager.stopClock();
        assertEquals(1, pocketGold(player));
        assertTrue(table.ledger().isEmpty());
        assertFalse(table.actives().contains(player.getUniqueId()));
        assertTrue(table.getHands().isEmpty());
    }
}
