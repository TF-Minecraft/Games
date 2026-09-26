package net.tfminecraft.games.layout;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.tfminecraft.games.cache.Cache;

class HandAnchorTest {
    @Test
    void standingHandsFollowPlayerAtConfiguredLift() {
        WorldMock world = MockBukkit.getMock().addSimpleWorld("standing");
        Player player = mock(Player.class);
        Location at = new Location(world, 1, 65, 3);
        when(player.getLocation()).thenReturn(at);
        when(player.getWorld()).thenReturn(world);
        var anchor = HandAnchor.resolve(player, new Location(world, 0, 66, 0));
        assertFalse(anchor.sitting());
        assertEquals(at.clone().add(0, Cache.handLift, 0), anchor.location());
        assertEquals(65, at.getY(), "Resolving an anchor must not mutate player location");
        assertEquals(0, anchor.placeYaw());
        when(player.isInsideVehicle()).thenReturn(true);
        WorldMock other = MockBukkit.getMock().addSimpleWorld("other");
        assertFalse(HandAnchor.resolve(player, new Location(other, 0, 66, 0)).sitting());
    }

    @Test
    void seatedHandsAnchorToNearEdgeOfSolidTableFromEverySide() {
        WorldMock world = MockBukkit.getMock().addSimpleWorld("rim");
        world.getBlockAt(0, 65, 0).setType(Material.STONE);
        Location shoe = new Location(world, .5, 66, .5);
        double[][] cases = {{-.5, .5, .5 - Cache.handSitEdge, .5, 270},
                {1.5, .5, .5 + Cache.handSitEdge, .5, 90},
                {.5, -.5, .5, .5 - Cache.handSitEdge, 0},
                {.5, 1.5, .5, .5 + Cache.handSitEdge, 180}};
        for (double[] sample : cases) {
            Player player = seated(world, sample[0], sample[1]);
            var anchor = HandAnchor.resolve(player, shoe);
            assertTrue(anchor.sitting());
            assertEquals(sample[2], anchor.location().getX(), 1e-7);
            assertEquals(sample[3], anchor.location().getZ(), 1e-7);
            assertEquals(66, anchor.location().getY());
            assertEquals((float) sample[4], anchor.placeYaw());
        }
    }

    @Test
    void seatsWithoutNearbySolidRimUsePlayerPositionAtShoeHeight() {
        WorldMock world = MockBukkit.getMock().addSimpleWorld("no-rim");
        Player player = seated(world, -.5, .5);
        Location shoe = new Location(world, .5, 66, .5, 37, 0);
        var fallback = HandAnchor.resolve(player, shoe);
        assertTrue(fallback.sitting());
        assertEquals(new Location(world, -.5, 66, .5), fallback.location());
        assertEquals(-90, fallback.placeYaw());
        Player centered = seated(world, .5, .5);
        var coincident = HandAnchor.resolve(centered, shoe);
        assertTrue(coincident.sitting());
        assertEquals(37, coincident.placeYaw());
        assertEquals(66, coincident.location().getY());
    }

    @Test
    void solidSeatBlockIsNotMistakenForTheTableRim() {
        WorldMock world = MockBukkit.getMock().addSimpleWorld("chair");
        world.getBlockAt(1, 65, 0).setType(Material.STONE);
        world.getBlockAt(0, 65, 0).setType(Material.OAK_STAIRS);
        Player player = seated(world, -.5, .5);
        // The seat entity sits inside the stair block beside the player, between them and the table.
        when(player.getVehicle().getLocation()).thenReturn(new Location(world, .3, 65, .5));
        var anchor = HandAnchor.resolve(player, new Location(world, 1.5, 66, .5));
        assertTrue(anchor.sitting());
        assertEquals(1.5 - Cache.handSitEdge, anchor.location().getX(), 1e-7);
        assertEquals(270f, anchor.placeYaw());
    }

    private Player seated(WorldMock world, double x, double z) {
        Player player = mock(Player.class);
        Entity seat = mock(Entity.class);
        Location at = new Location(world, x, 65, z);
        when(player.getLocation()).thenReturn(at);
        when(player.getWorld()).thenReturn(world);
        when(player.isInsideVehicle()).thenReturn(true);
        when(player.getVehicle()).thenReturn(seat);
        when(seat.getLocation()).thenReturn(at);
        return player;
    }
}
