package net.tfminecraft.games.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class BodyYawTest {
    @Test
    void usesTorsoYawInsteadOfHeadYawWhenServerExposesIt() {
        Player player = mock(Player.class);
        when(player.getBodyYaw()).thenReturn(35f);
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0, 90, 0));
        assertEquals(35f, BodyYaw.of(player));
        verify(player, never()).getLocation();
    }

    @Test
    void unsupportedBodyYawFallsBackToLookDirection() {
        Player player = mock(Player.class);
        when(player.getBodyYaw()).thenThrow(new UnsupportedOperationException("older server adapter"));
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0, 90, 0));
        assertEquals(90f, BodyYaw.of(player));
        assertEquals(0, BodyYaw.of(null));
    }
}
