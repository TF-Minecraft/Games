package net.tfminecraft.games.display;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;

class DisplayManagerTest {
    private DisplayManager manager;
    private FakeItemDisplayPackets packets;
    private MockedStatic<ProtocolLibBridge> bridge;
    private Games previousPlugin;
    private double previousRange;
    private WorldMock world;
    private WorldMock otherWorld;
    private PlayerMock owner;
    private PlayerMock observer;
    private PlayerMock distant;
    private Location origin;
    private UUID token;
    private ItemStack back;

    @BeforeEach
    void setUp() {
        previousPlugin = Games.plugin;
        Games.plugin = mock(Games.class);
        when(Games.plugin.getName()).thenReturn("Games");
        when(Games.plugin.namespace()).thenReturn("games");
        when(Games.plugin.isEnabled()).thenReturn(true);
        when(Games.plugin.getLogger()).thenReturn(mock(Logger.class));
        previousRange = Cache.displayRange;
        Cache.displayRange = 10;
        packets = mock(FakeItemDisplayPackets.class);
        bridge = mockStatic(ProtocolLibBridge.class);
        bridge.when(ProtocolLibBridge::isReady).thenReturn(true);
        bridge.when(ProtocolLibBridge::getPackets).thenReturn(packets);
        manager = DisplayManager.get();
        manager.shutdown();
        world = MockBukkit.getMock().addSimpleWorld("displays-" + UUID.randomUUID());
        otherWorld = MockBukkit.getMock().addSimpleWorld("elsewhere-" + UUID.randomUUID());
        origin = new Location(world, 0, 65, 0);
        owner = MockBukkit.getMock().addPlayer();
        observer = MockBukkit.getMock().addPlayer();
        distant = MockBukkit.getMock().addPlayer();
        owner.teleport(origin);
        observer.teleport(origin.clone().add(10, 0, 0));
        distant.teleport(origin.clone().add(11, 0, 0));
        token = UUID.randomUUID();
        back = new ItemStack(Material.PAPER, 1);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        bridge.close();
        Cache.displayRange = previousRange;
        Games.plugin = previousPlugin;
    }

    @Test
    void spawnUsesInclusiveRangeAndClonesOriginAndItem() {
        assertTrue(manager.spawn(token, origin, back, null));
        verify(packets).spawn(eq(owner), anyInt(), any(UUID.class), eq(origin), eq(back), any(DisplayPose.class));
        verify(packets).spawn(eq(observer), anyInt(), any(UUID.class), eq(origin), eq(back), any(DisplayPose.class));
        verify(packets, never()).spawn(eq(distant), anyInt(), any(), any(), any(), any());
        back.setAmount(12);
        origin.add(100, 0, 0);
        manager.setTransform(token, DisplayPose.identity(1).withTranslation(1, 2, 3), 4);
        assertEquals(new Location(world, 1, 67, 3), manager.worldLocation(token));
        verify(packets).update(eq(owner), anyInt(), argThat(item -> item.getAmount() == 1), any(), eq(0), eq(4));
        Location returned = manager.worldLocation(token);
        returned.add(20, 0, 0);
        assertEquals(new Location(world, 1, 67, 3), manager.worldLocation(token));
    }

    @Test
    void privateCardOverridesStayPrivateAcrossPublicItemChangesAndCanBeCleared() {
        manager.spawn(token, origin, back, null);
        ItemStack face = new ItemStack(Material.DIAMOND);
        manager.setItemFor(token, owner, face);
        face.setAmount(12);
        clearInvocations(packets);
        ItemStack newBack = new ItemStack(Material.BOOK);
        manager.setItem(token, newBack);
        verify(packets).update(eq(owner), anyInt(), argThat(item -> item.getType() == Material.DIAMOND
                && item.getAmount() == 1), any(), eq(0), eq(0));
        verify(packets).update(eq(observer), anyInt(), eq(newBack), any(), eq(0), eq(0));
        clearInvocations(packets);
        manager.clearItemFor(token, owner);
        verify(packets).update(eq(owner), anyInt(), eq(newBack), any(), eq(0), eq(0));
        verify(packets, never()).update(eq(observer), anyInt(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void splitLayoutSendsOwnerAndObserverTheirOwnPoseAndSuppressesUnchangedUpdates() {
        DisplayPose initial = DisplayPose.identity(1);
        manager.spawn(token, origin, back, initial);
        manager.setLayoutOwner(token, owner.getUniqueId());
        DisplayPose privatePose = initial.withTranslation(1, 2, 3);
        DisplayPose publicPose = initial.withTranslation(4, 5, 6);
        manager.setTransformSplit(token, privatePose, publicPose, 6);
        verify(packets).update(eq(owner), anyInt(), any(), argThat(pose -> pose.matches(privatePose)), eq(0), eq(6));
        verify(packets).update(eq(observer), anyInt(), any(), argThat(pose -> pose.matches(publicPose)), eq(0), eq(6));
        assertTrue(manager.poseOf(token).matches(privatePose));
        assertTrue(manager.otherPoseOf(token).matches(publicPose));
        clearInvocations(packets);
        manager.setTransformSplit(token, privatePose, publicPose, 6);
        verifyNoInteractions(packets);
        manager.setTransform(token, privatePose, -1);
        verify(packets).update(eq(observer), anyInt(), any(), argThat(pose -> pose.matches(privatePose)), eq(0), eq(0));
        assertTrue(manager.otherPoseOf(token).matches(privatePose));
        clearInvocations(packets);
        manager.setTransform(token, privatePose, 4);
        verifyNoInteractions(packets);
        manager.setTransformSplit(token, publicPose, null, 3);
        assertTrue(manager.otherPoseOf(token).matches(publicPose));
    }

    @Test
    void respawnReplacesEntityAndDespawnAndShutdownDestroyAllTokens() {
        manager.spawn(token, origin, back, null);
        ArgumentCaptor<Integer> ids = ArgumentCaptor.forClass(Integer.class);
        verify(packets).spawn(eq(owner), ids.capture(), any(), any(), any(), any());
        int first = ids.getValue();
        manager.spawn(token, origin, back, null);
        verify(packets).destroy(owner, List.of(first));
        verify(packets, times(2)).spawn(eq(owner), ids.capture(), any(), any(), any(), any());
        int replacement = ids.getValue();
        assertNotEquals(first, replacement);
        manager.despawn(token);
        verify(packets).destroy(owner, List.of(replacement));
        assertNull(manager.worldLocation(token));
        assertNull(manager.poseOf(token));
        assertNull(manager.otherPoseOf(token));
        clearInvocations(packets);
        manager.despawn(token);
        verifyNoInteractions(packets);
        manager.spawn(token, origin, back, null);
        manager.shutdown();
        assertNull(manager.worldLocation(token));
        verify(packets).destroy(eq(owner), anyList());
    }

    @Test
    void worldChangesHideOldWorldTokensAndShowEligibleDestinationTokens() {
        manager.spawn(token, origin, back, null);
        UUID elsewhere = UUID.randomUUID();
        Location destination = new Location(otherWorld, 0, 65, 0);
        manager.spawn(elsewhere, destination, back, null);
        clearInvocations(packets);
        owner.teleport(destination);
        manager.onWorldChange(new PlayerChangedWorldEvent(owner, world));
        verify(packets).destroy(eq(owner), anyList());
        verify(packets).spawn(eq(owner), anyInt(), any(), eq(destination), any(), any());
        verify(packets, never()).spawn(eq(owner), anyInt(), any(), eq(origin), any(), any());
    }

    @Test
    void joinShowsNearbyTokensNextTickAndQuitClearsPrivateOverride() {
        manager.spawn(token, origin, back, null);
        manager.setItemFor(token, distant, new ItemStack(Material.DIAMOND));
        distant.teleport(origin);
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(distant);
        clearInvocations(packets);
        manager.onJoin(join);
        verifyNoInteractions(packets);
        MockBukkit.getMock().getScheduler().performOneTick();
        verify(packets).spawn(eq(distant), anyInt(), any(), any(), eq(new ItemStack(Material.DIAMOND)), any());
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(distant);
        manager.onQuit(quit);
        verify(packets).destroy(eq(distant), anyList());
        clearInvocations(packets);
        manager.onJoin(join);
        MockBukkit.getMock().getScheduler().performOneTick();
        verify(packets).spawn(eq(distant), anyInt(), any(), any(), eq(back), any());
    }

    @Test
    void loadedChunkRefreshesExistingViewersAndShowsNewNearbyViewersOnlyForThatChunk() {
        manager.spawn(token, origin, back, null);
        manager.spawn(UUID.randomUUID(), origin.clone().add(32, 0, 0), back, null);
        manager.spawn(UUID.randomUUID(), origin.clone().add(0, 0, 32), back, null);
        manager.spawn(UUID.randomUUID(), new Location(otherWorld, 0, 65, 0), back, null);
        distant.teleport(origin);
        ChunkLoadEvent event = new ChunkLoadEvent(world.getChunkAt(0, 0), false);
        clearInvocations(packets);
        manager.onChunkLoad(event);
        verify(packets).update(eq(owner), anyInt(), eq(back), any(), eq(0), eq(0));
        verify(packets).update(eq(observer), anyInt(), eq(back), any(), eq(0), eq(0));
        verify(packets).spawn(eq(distant), anyInt(), any(), eq(origin), eq(back), any());
        verify(packets, times(1)).spawn(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void unavailableProtocolPreventsSpawnAndAllowsLocalCleanup() {
        bridge.when(ProtocolLibBridge::isReady).thenReturn(false);
        assertFalse(manager.spawn(token, origin, back, null));
        assertNull(manager.worldLocation(token));
        verifyNoInteractions(packets);
        bridge.when(ProtocolLibBridge::isReady).thenReturn(true);
        manager.spawn(token, origin, back, null);
        clearInvocations(packets);
        bridge.when(ProtocolLibBridge::isReady).thenReturn(false);
        manager.setItem(token, null);
        manager.shutdown();
        assertNull(manager.worldLocation(token));
        verifyNoInteractions(packets);
    }

    @Test
    void failedSpawnDestroysAlreadyShownEntityAndDiscardsToken() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("packet failure");
            return null;
        }).when(packets).spawn(any(Player.class), anyInt(), any(), any(), any(), any());
        assertFalse(manager.spawn(token, origin, back, null));
        assertNull(manager.worldLocation(token));
        verify(packets).destroy(any(Player.class), anyList());
        verify(Games.plugin.getLogger()).warning(contains("packet failure"));
    }

    @Test
    void quitDuringProtocolOutageClearsViewersSoReconnectCanShowTheTokenAgain() {
        manager.spawn(token, origin, back, null);
        manager.setItemFor(token, owner, new ItemStack(Material.DIAMOND));
        clearInvocations(packets);
        bridge.when(ProtocolLibBridge::isReady).thenReturn(false);
        manager.onQuit(new PlayerQuitEvent(owner, "quit"));
        verifyNoInteractions(packets);
        bridge.when(ProtocolLibBridge::isReady).thenReturn(true);
        manager.onJoin(new PlayerJoinEvent(owner, "join"));
        MockBukkit.getMock().getScheduler().performOneTick();
        verify(packets).spawn(eq(owner), anyInt(), any(), eq(origin), eq(back), any());
        assertEquals(origin, manager.worldLocation(token));
    }

    @Test
    void tokenSpawnedByALaterQuitListenerIsShownAgainWhenThatPlayerReconnects() {
        manager.spawn(token, origin, back, null);
        manager.onQuit(new PlayerQuitEvent(owner, "quit"));
        clearInvocations(packets);
        // Another quit listener, such as a table mucking the leaving player's hand, rebuilds a pile while
        // the player is still listed as online.
        UUID pile = UUID.randomUUID();
        manager.spawn(pile, origin, back, null);
        verify(packets).spawn(eq(owner), anyInt(), any(), eq(origin), eq(back), any());
        owner.disconnect();
        clearInvocations(packets);
        manager.setItem(pile, new ItemStack(Material.BOOK));
        manager.setTransform(pile, DisplayPose.identity(1).withTranslation(0, 1, 0), 2);
        verify(packets, never()).update(eq(owner), anyInt(), any(), any(), anyInt(), anyInt());
        verify(packets).update(eq(observer), anyInt(), eq(new ItemStack(Material.BOOK)), any(), eq(0), eq(0));
        owner.reconnect();
        owner.teleport(origin);
        clearInvocations(packets);
        manager.onJoin(new PlayerJoinEvent(owner, "join"));
        MockBukkit.getMock().getScheduler().performOneTick();
        verify(packets).spawn(eq(owner), anyInt(), any(), eq(origin), eq(back), any());
        verify(packets).spawn(eq(owner), anyInt(), any(), eq(origin), eq(new ItemStack(Material.BOOK)), any());
    }

    @Test
    void departedViewerIsSkippedWhenTheTokenIsDestroyed() {
        manager.onQuit(new PlayerQuitEvent(owner, "quit"));
        manager.spawn(token, origin, back, null);
        ArgumentCaptor<Integer> entity = ArgumentCaptor.forClass(Integer.class);
        verify(packets).spawn(eq(observer), entity.capture(), any(), any(), any(), any());
        owner.disconnect();
        clearInvocations(packets);
        manager.despawn(token);
        verify(packets, never()).destroy(eq(owner), anyList());
        verify(packets).destroy(observer, List.of(entity.getValue()));
    }

    @Test
    void tokenShownBeforeTheDelayedJoinRefreshIsNotSentTwice() {
        distant.teleport(origin);
        manager.onJoin(new PlayerJoinEvent(distant, "join"));
        manager.spawn(token, origin, back, null);
        verify(packets).spawn(eq(distant), anyInt(), any(), eq(origin), eq(back), any());
        clearInvocations(packets);
        MockBukkit.getMock().getScheduler().performOneTick();
        verifyNoInteractions(packets);
    }

    @Test
    void joinRefreshOnlyShowsTokensWithinRangeInThePlayersWorld() {
        UUID far = UUID.randomUUID();
        UUID elsewhere = UUID.randomUUID();
        manager.spawn(far, origin.clone().add(40, 0, 0), back, null);
        manager.spawn(elsewhere, new Location(otherWorld, 0, 65, 0), back, null);
        Location near = origin.clone().add(11, 0, 5);
        manager.spawn(token, near, back, null);
        clearInvocations(packets);
        manager.onJoin(new PlayerJoinEvent(distant, "join"));
        MockBukkit.getMock().getScheduler().performOneTick();
        verify(packets).spawn(eq(distant), anyInt(), any(), eq(near), any(), any());
        verify(packets, times(1)).spawn(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void spawnAtAnOriginWithoutALoadedWorldIsRefused() {
        assertFalse(manager.spawn(token, new Location(null, 0, 65, 0), back, null));
        assertNull(manager.worldLocation(token));
        verifyNoInteractions(packets);
    }

    @Test
    void missingPoseLeavesTheTokenWhereItWas() {
        DisplayPose start = DisplayPose.identity(1).withTranslation(1, 0, 0);
        manager.spawn(token, origin, back, start);
        clearInvocations(packets);
        manager.setTransform(token, null, 3);
        manager.setTransformSplit(token, null, DisplayPose.identity(1), 3);
        verifyNoInteractions(packets);
        assertTrue(manager.poseOf(token).matches(start));
        assertTrue(manager.otherPoseOf(token).matches(start));
    }

    @Test
    void chunkLoadDuringProtocolOutageDefersShowingUntilPacketsReturn() {
        manager.spawn(token, origin, back, null);
        distant.teleport(origin);
        ChunkLoadEvent event = new ChunkLoadEvent(world.getChunkAt(0, 0), false);
        clearInvocations(packets);
        bridge.when(ProtocolLibBridge::isReady).thenReturn(false);
        manager.onChunkLoad(event);
        verifyNoInteractions(packets);
        bridge.when(ProtocolLibBridge::isReady).thenReturn(true);
        manager.onChunkLoad(event);
        verify(packets).spawn(eq(distant), anyInt(), any(), eq(origin), eq(back), any());
    }

    @Test
    void pendingJoinDoesNotSendPacketsAfterDisconnectOrProtocolShutdown() {
        manager.spawn(token, origin, back, null);
        distant.teleport(origin);
        manager.onJoin(new PlayerJoinEvent(distant, "join"));
        distant.disconnect();
        clearInvocations(packets);
        MockBukkit.getMock().getScheduler().performOneTick();
        verifyNoInteractions(packets);
        manager.onJoin(new PlayerJoinEvent(owner, "join"));
        bridge.when(ProtocolLibBridge::isReady).thenReturn(false);
        MockBukkit.getMock().getScheduler().performOneTick();
        verifyNoInteractions(packets);
        assertEquals(origin, manager.worldLocation(token));
    }

    @Test
    void queuedChangesAfterDespawnCannotResurrectAnEntityOrSendUpdates() {
        manager.spawn(token, origin, back, null);
        manager.despawn(token);
        clearInvocations(packets);
        manager.setItem(token, new ItemStack(Material.BOOK));
        manager.setItemFor(token, owner, new ItemStack(Material.DIAMOND));
        manager.setLayoutOwner(token, owner.getUniqueId());
        manager.setTransform(token, DisplayPose.identity(1), 3);
        manager.setTransformSplit(token, DisplayPose.identity(1), DisplayPose.identity(2), 3);
        assertNull(manager.worldLocation(token));
        assertNull(manager.poseOf(token));
        assertNull(manager.otherPoseOf(token));
        verifyNoInteractions(packets);
    }

    @Test
    void changingOnlyObserverPoseStillUpdatesAndClearingOwnerUsesSharedPose() {
        DisplayPose privatePose = DisplayPose.identity(1).withTranslation(1, 0, 0);
        DisplayPose publicPose = DisplayPose.identity(1).withTranslation(2, 0, 0);
        manager.spawn(token, origin, back, privatePose);
        manager.setLayoutOwner(token, owner.getUniqueId());
        manager.setTransformSplit(token, privatePose, publicPose, 2);
        clearInvocations(packets);
        DisplayPose movedPublic = publicPose.withTranslation(3, 0, 0);
        manager.setTransformSplit(token, privatePose, movedPublic, 2);
        verify(packets).update(eq(observer), anyInt(), any(), argThat(pose -> pose.matches(movedPublic)), eq(0), eq(2));
        verify(packets).update(eq(owner), anyInt(), any(), argThat(pose -> pose.matches(privatePose)), eq(0), eq(2));
        clearInvocations(packets);
        manager.setLayoutOwner(token, null);
        manager.setItem(token, new ItemStack(Material.BOOK));
        verify(packets).update(eq(owner), anyInt(), any(), argThat(pose -> pose.matches(privatePose)), eq(0), eq(0));
        verify(packets).update(eq(observer), anyInt(), any(), argThat(pose -> pose.matches(privatePose)), eq(0), eq(0));
    }

    @Test
    void emptyDisplayCanReceiveAnItemAndClearingItPreservesPrivateOverrides() {
        assertTrue(manager.spawn(token, origin, null, null));
        verify(packets).spawn(eq(owner), anyInt(), any(), eq(origin), isNull(), any(DisplayPose.class));
        assertNotNull(manager.poseOf(token));
        ItemStack face = new ItemStack(Material.DIAMOND);
        manager.setItemFor(token, owner, face);
        manager.setItem(token, back);
        clearInvocations(packets);
        manager.setItem(token, null);
        verify(packets).update(eq(observer), anyInt(), isNull(), any(), eq(0), eq(0));
        verify(packets).update(eq(owner), anyInt(), eq(face), any(), eq(0), eq(0));
        assertEquals(origin, manager.worldLocation(token));
    }

    @Test
    void onlyStandaloneMainHandAnchorClicksAreConsumed() {
        try (MockedStatic<Messages> messages = mockStatic(Messages.class, call -> "clicked")) {
            Interaction standalone = WorldAnchors.spawnInteraction(origin, 1, 1, "token");
            PlayerInteractEntityEvent click = new PlayerInteractEntityEvent(owner, standalone, EquipmentSlot.HAND);
            manager.onInteract(click);
            assertTrue(click.isCancelled());
            assertEquals("clicked", owner.nextMessage());
            Interaction tableAnchor = WorldAnchors.spawnInteraction(origin, 1, 1, "token", "table");
            PlayerInteractEntityEvent tableClick = new PlayerInteractEntityEvent(owner, tableAnchor, EquipmentSlot.HAND);
            manager.onInteract(tableClick);
            assertFalse(tableClick.isCancelled());
            PlayerInteractEntityEvent offHand = new PlayerInteractEntityEvent(owner, standalone, EquipmentSlot.OFF_HAND);
            manager.onInteract(offHand);
            assertFalse(offHand.isCancelled());
            Interaction anonymous = WorldAnchors.spawnInteraction(origin, 1, 1, null);
            PlayerInteractEntityEvent anonymousClick = new PlayerInteractEntityEvent(owner, anonymous, EquipmentSlot.HAND);
            manager.onInteract(anonymousClick);
            assertFalse(anonymousClick.isCancelled());
            manager.onInteract(click);
            assertNull(owner.nextMessage(), "already cancelled events must not send a second message");
        }
    }
}
