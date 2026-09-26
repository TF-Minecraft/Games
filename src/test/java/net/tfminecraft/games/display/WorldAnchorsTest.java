package net.tfminecraft.games.display;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import net.tfminecraft.games.Games;

class WorldAnchorsTest {
    private Games previousPlugin;
    private WorldMock world;
    private Location origin;

    @BeforeEach
    void setUp() {
        previousPlugin = Games.plugin;
        Games.plugin = mock(Games.class);
        when(Games.plugin.getName()).thenReturn("Games");
        when(Games.plugin.namespace()).thenReturn("games");
        world = MockBukkit.getMock().addSimpleWorld("anchors-" + UUID.randomUUID());
        origin = new Location(world, 10, 65, 20);
    }

    @AfterEach
    void tearDown() {
        for (var entity : world.getEntities()) entity.remove();
        Games.plugin = previousPlugin;
    }

    @Test
    void interactionStoresClickIdentityAndDoesNotPersistAcrossWorldSaves() {
        Interaction anchor = WorldAnchors.spawnInteraction(origin, 0.75f, 0.5f, "token", "table");
        assertNotNull(anchor);
        assertEquals(origin, anchor.getLocation());
        assertEquals(0.75f, anchor.getInteractionWidth());
        assertEquals(0.5f, anchor.getInteractionHeight());
        assertTrue(anchor.isResponsive());
        assertFalse(anchor.isPersistent());
        assertEquals("token", WorldAnchors.tokenId(anchor));
        assertEquals("table", WorldAnchors.tableId(anchor));
    }

    @Test
    void standaloneAndUnidentifiedAnchorsKeepOptionalIdentifiersAbsent() {
        Interaction standalone = WorldAnchors.spawnInteraction(origin, 1, 1, "standalone");
        assertEquals("standalone", WorldAnchors.tokenId(standalone));
        assertNull(WorldAnchors.tableId(standalone));
        Interaction anonymous = WorldAnchors.spawnInteraction(origin, 1, 1, null);
        assertNull(WorldAnchors.tokenId(anonymous));
        assertNull(WorldAnchors.tableId(anonymous));
    }

    @Test
    void labelsAreSmallCenteredAndLegibleWithoutSeeingThroughWalls() {
        // MockBukkit does not implement Display.setBillboard; retain the spawn initializer
        // and verify the server-bound setters instead of skipping the contract.
        World labelWorld = mock(World.class);
        TextDisplay label = mock(TextDisplay.class);
        Location labelLocation = new Location(labelWorld, 10, 65, 20);
        when(labelWorld.spawn(eq(labelLocation), eq(TextDisplay.class), org.mockito.ArgumentMatchers.<Consumer<TextDisplay>>any())).thenAnswer(call -> {
            Consumer<TextDisplay> initialize = call.getArgument(2);
            initialize.accept(label);
            return label;
        });
        assertSame(label, WorldAnchors.spawnLabel(labelLocation, "Table 1"));
        verify(label).setText("Table 1");
        verify(label).setBillboard(Display.Billboard.CENTER);
        verify(label).setPersistent(false);
        verify(label).setSeeThrough(false);
        verify(label).setShadowed(true);
        ArgumentCaptor<Transformation> transform = ArgumentCaptor.forClass(Transformation.class);
        verify(label).setTransformation(transform.capture());
        assertEquals(new Vector3f(0, 0.4f, 0), transform.getValue().getTranslation());
        assertEquals(new Vector3f(0.4f), transform.getValue().getScale());
        UUID labelId = UUID.randomUUID();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getEntity(labelId)).thenReturn(label);
            WorldAnchors.setText(labelId, "Next hand");
            verify(label).setText("Next hand");
            verify(label, times(2)).setSeeThrough(false);
            WorldAnchors.setText(labelId, null);
            verify(label).setText("");
            WorldAnchors.spawnLabel(labelLocation, null);
            verify(label, times(2)).setText("");
        }
    }

    @Test
    void moveAndRemoveFollowEntityLifecycleAndStaleIdsAreHarmless() {
        Interaction anchor = WorldAnchors.spawnInteraction(origin, 1, 1, "token");
        UUID id = anchor.getUniqueId();
        Location destination = origin.clone().add(2, 1, 3);
        WorldAnchors.move(id, destination);
        assertEquals(destination, anchor.getLocation());
        WorldAnchors.setText(id, "not a label");
        assertEquals("token", WorldAnchors.tokenId(anchor));
        WorldAnchors.remove(id);
        assertTrue(anchor.isDead());
        assertDoesNotThrow(() -> {
            WorldAnchors.remove(id);
            WorldAnchors.move(id, origin);
            WorldAnchors.setText(id, "stale");
        });
        assertEquals(destination, anchor.getLocation());
    }

    @Test
    void absentPlacementOrEntityReferencesDoNotCreateAnchors() {
        assertNull(WorldAnchors.spawnInteraction(null, 1, 1, "token"));
        assertNull(WorldAnchors.spawnInteraction(new Location(null, 0, 0, 0), 1, 1, "token"));
        assertNull(WorldAnchors.spawnLabel(null, "label"));
        assertNull(WorldAnchors.spawnLabel(new Location(null, 0, 0, 0), "label"));
        assertNull(WorldAnchors.tokenId(null));
        assertNull(WorldAnchors.tableId(null));
        assertDoesNotThrow(() -> {
            WorldAnchors.setText(null, "text");
            WorldAnchors.move(null, origin);
            WorldAnchors.move(UUID.randomUUID(), null);
            WorldAnchors.remove(null);
            WorldAnchors.remove(UUID.randomUUID());
        });
        assertTrue(world.getEntities().isEmpty());
    }
}
