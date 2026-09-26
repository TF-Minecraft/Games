package net.tfminecraft.games.select;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayManager;
import net.tfminecraft.games.table.HandCard;

class CardSelectorTest {
    @Test
    void selectionRequiresPlayerAndNonemptyHand() {
        assertNull(CardSelector.closestOnRay(null, List.of()));
        Player player = mock(Player.class);
        assertNull(CardSelector.closestOnRay(player, null));
        assertNull(CardSelector.closestOnRay(player, List.of()));
        verifyNoInteractions(player);
    }

    @Test
    void selectsSmallestRayOffsetAndKeepsFirstCardOnTie() {
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0, 0, 0, 0, 0));
        DisplayManager display = mock(DisplayManager.class);
        HandCard near = new HandCard(null, UUID.randomUUID());
        HandCard centered = new HandCard(null, UUID.randomUUID());
        HandCard tied = new HandCard(null, UUID.randomUUID());
        double distance = Cache.handSelectRange / 2;
        when(display.worldLocation(near.tokenId())).thenReturn(new Location(world, Cache.handSelectRadius / 2, 0, distance));
        when(display.worldLocation(centered.tokenId())).thenReturn(new Location(world, 0, 0, distance));
        when(display.worldLocation(tied.tokenId())).thenReturn(new Location(world, 0, 0, distance / 2));
        try (MockedStatic<DisplayManager> displays = mockStatic(DisplayManager.class)) {
            displays.when(DisplayManager::get).thenReturn(display);
            assertSame(centered, CardSelector.closestOnRay(player, List.of(near, centered, tied)));
            assertSame(near, CardSelector.closestOnRay(player, List.of(near)));
        }
    }

    @Test
    void rejectsInvisibleWrongWorldBehindOutOfReachAndOffRayCards() {
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0, 0, 0, 0, 0));
        DisplayManager display = mock(DisplayManager.class);
        HandCard card = new HandCard(null, UUID.randomUUID());
        try (MockedStatic<DisplayManager> displays = mockStatic(DisplayManager.class)) {
            displays.when(DisplayManager::get).thenReturn(display);
            assertNull(CardSelector.closestOnRay(player, List.of(card)));
            for (Location invalid : List.of(new Location(null, 0, 0, 1),
                    new Location(mock(World.class), 0, 0, 1), new Location(world, 0, 0, -1),
                    new Location(world, 0, 0, Cache.handSelectRange + 1),
                    new Location(world, Cache.handSelectRadius + .1, 0, Cache.handSelectRange / 2))) {
                when(display.worldLocation(card.tokenId())).thenReturn(invalid);
                assertNull(CardSelector.closestOnRay(player, List.of(card)));
            }
            when(display.worldLocation(card.tokenId())).thenReturn(new Location(world, 0, 0, Cache.handSelectRange));
            assertSame(card, CardSelector.closestOnRay(player, List.of(card)), "Range endpoint remains selectable");
        }
    }
}
