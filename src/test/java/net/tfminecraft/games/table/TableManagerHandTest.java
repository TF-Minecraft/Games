package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.FreePlayGame;
import net.tfminecraft.games.game.GamesRegistry;

class TableManagerHandTest extends TableManagerFixture {
    private final Map<UUID, DisplayPose> poses = new HashMap<>();
    private final Map<UUID, ItemStack> publicItems = new HashMap<>();
    private final Map<UUID, ItemStack> privateItems = new HashMap<>();
    private int oldFlip, oldStagger, oldSelect, oldInterpolation;

    @BeforeEach void configureHands() {
        oldFlip = Cache.handRevealFlip;
        oldStagger = Cache.handRevealStagger;
        oldSelect = Cache.handSelectTicks;
        oldInterpolation = Cache.interpolationTicks;
        Cache.handRevealFlip = 2;
        Cache.handRevealStagger = 1;
        Cache.handSelectTicks = 3;
        games.when(() -> GamesRegistry.of("freeplay")).thenReturn(new FreePlayGame());
        when(items.getCreator().getItemFromPath("face")).thenReturn(new ItemStack(Material.DIAMOND));
        when(items.getCreator().getItemFromPath("back")).thenReturn(new ItemStack(Material.PAPER));
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            UUID token = call.getArgument(0);
            publicItems.put(token, call.getArgument(2));
            poses.put(token, call.getArgument(3));
            return true;
        });
        when(display.poseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        when(display.otherPoseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        doAnswer(call -> { poses.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(display).setTransform(any(), any(), anyInt());
        doAnswer(call -> { poses.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(display).setTransformSplit(any(), any(), any(), anyInt());
        doAnswer(call -> { publicItems.computeIfPresent(call.getArgument(0), (token, previous) -> call.getArgument(1)); return null; })
                .when(display).setItem(any(), any());
        doAnswer(call -> { if (publicItems.containsKey(call.getArgument(0))) privateItems.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(display).setItemFor(any(), eq(player), any());
        doAnswer(call -> { privateItems.remove(call.getArgument(0)); return null; })
                .when(display).clearItemFor(any(), eq(player));
        doAnswer(call -> {
            UUID token = call.getArgument(0);
            poses.remove(token); publicItems.remove(token); privateItems.remove(token);
            return null;
        }).when(display).despawn(any());
    }

    @AfterEach void restoreHands() {
        Cache.handRevealFlip = oldFlip;
        Cache.handRevealStagger = oldStagger;
        Cache.handSelectTicks = oldSelect;
        Cache.interpolationTicks = oldInterpolation;
    }

    @Test void revealAndHideRoundTripPreservesCardsAndRestoresOwnerOnlyFaces() {
        Table table = dealt(2);
        List<HandCard> before = List.copyOf(table.handOf(player.getUniqueId()));
        assertPrivate(before);
        assertTrue(swap().isCancelled());
        tick(8);
        for (HandCard held : before) assertEquals(Material.DIAMOND, publicItems.get(held.tokenId()).getType());
        assertTrue(swap().isCancelled());
        tick(8);
        List<HandCard> after = table.handOf(player.getUniqueId());
        assertEquals(before.stream().map(HandCard::card).toList(), after.stream().map(HandCard::card).toList());
        assertPrivate(after);
        for (HandCard held : before) assertFalse(publicItems.containsKey(held.tokenId()), "Hidden cards replace public tokens");
        assertEquals(0, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
    }

    @Test void selectingThenRevealingOnlyShowsTheSelectedCardAndBlocksAnotherRevealDuringFlip() {
        Table table = dealt(2);
        List<HandCard> hand = table.handOf(player.getUniqueId());
        HandCard selected = hand.getFirst();
        aimAt(selected);
        PlayerInteractEvent click = handClick(Action.RIGHT_CLICK_AIR);
        assertTrue(click.isCancelled());
        assertTrue(selected.isSelected());
        assertFalse(hand.get(1).isSelected());
        tick(4);
        swap();
        swap(); // A repeated keypress while the first animation is busy must not reverse it.
        tick(8);
        assertEquals(Material.DIAMOND, publicItems.get(selected.tokenId()).getType());
        assertEquals(Material.PAPER, publicItems.get(hand.get(1).tokenId()).getType());
        assertEquals(2, hand.size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 3})
    void selectedCardReturnsThroughShoeInteractionWithoutDuplicatingOrLosingCards(int returnTicks) {
        Table table = dealt(2);
        Cache.handDealTicks = returnTicks;
        HandCard selected = table.handOf(player.getUniqueId()).getFirst();
        aimAt(selected);
        handClick(Action.RIGHT_CLICK_AIR);
        tick(4);
        PlayerInteractAtEntityEvent click = shoeClick(table, EquipmentSlot.HAND);
        assertTrue(click.isCancelled());
        tick(10);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertFalse(table.handOf(player.getUniqueId()).stream().anyMatch(held -> held.card().equals(selected.card())));
        assertEquals(1, table.getDeck().remaining() + table.getDeck().discarded());
        assertFalse(publicItems.containsKey(selected.tokenId()));
        assertEquals("hand.returned_selected", player.nextMessage());
    }

    @Test void inspectingACardReportsItsIdentityAndKeepsItInThePrivateHand() {
        Table table = dealt(1);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        aimAt(held);
        assertTrue(handClick(Action.LEFT_CLICK_AIR).isCancelled());
        assertEquals("card.info", player.nextMessage());
        tick(30);
        assertEquals(List.of(held), table.handOf(player.getUniqueId()));
        assertPrivate(List.of(held));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 3})
    void drawingIntoARevealedHandRevealsTheNewCardAfterItsAnimation(int dealTicks) {
        Table table = dealt(1);
        swap(); tick(8);
        Cache.handDealTicks = dealTicks;
        Runnable after = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, after);
        tick(12);
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        for (HandCard held : table.handOf(player.getUniqueId()))
            assertEquals(Material.DIAMOND, publicItems.get(held.tokenId()).getType());
        verify(after).run();
    }

    @Test void aDealRequestedDuringRevealWaitsThenCompletesWithBothCardsPublic() {
        Table table = dealt(1);
        Cache.handDealTicks = 3;
        swap();
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        verifyNoInteractions(complete);
        tick(20);
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertEquals(2, table.handOf(player.getUniqueId()).stream().map(HandCard::card).distinct().count());
        for (HandCard held : table.handOf(player.getUniqueId()))
            assertEquals(Material.DIAMOND, publicItems.get(held.tokenId()).getType());
        assertEquals(0, table.getDeck().remaining());
        verify(complete).run();
    }

    @Test void removalWhileADealWaitsForRevealCompletesWithoutResurrectingTheHand() {
        Table table = dealt(1);
        swap();
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete);
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        var hit = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(player);
        manager.onHitEntity(hit);
        tick(20);
        assertTrue(manager.tables().isEmpty());
        assertTrue(table.getHands().isEmpty());
        assertTrue(privateItems.isEmpty());
        verify(complete).run();
    }

    @Test void returningTheLastCardEndsTheHandAndAllowsDrawingAgain() {
        Table table = dealt(1);
        Cache.handDealTicks = 3;
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        aimAt(held); handClick(Action.RIGHT_CLICK_AIR); tick(4);
        shoeClick(table, EquipmentSlot.HAND); tick(12);
        assertFalse(table.getHands().containsKey(player.getUniqueId()));
        assertEquals(2, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
        assertFalse(publicItems.containsKey(held.tokenId()));
        assertTrue(privateItems.isEmpty());
        manager.dealToPlayer(table, player, 1); tick(12);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(1, table.getDeck().remaining());
        assertPrivate(table.handOf(player.getUniqueId()));
    }

    @Test void failedHandDisplayOnArrivalPreservesTheCardAndAllowsTheNextDeal() {
        Table table = placeAndClearMessage();
        Cache.handDealTicks = 3;
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete);
        // The renderer rejects the arriving hand display; subsequent stack rebuilds can succeed.
        when(display.spawn(any(), any(), any(), any())).thenReturn(false).thenAnswer(call -> {
            UUID token = call.getArgument(0);
            publicItems.put(token, call.getArgument(2));
            poses.put(token, call.getArgument(3));
            return true;
        });
        tick(12);
        assertTrue(table.handOf(player.getUniqueId()).isEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
        assertTrue(privateItems.isEmpty());
        verify(complete).run();
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            UUID token = call.getArgument(0);
            publicItems.put(token, call.getArgument(2));
            poses.put(token, call.getArgument(3));
            return true;
        });
        manager.dealToPlayer(table, player, 1); tick(12);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(1, table.getDeck().remaining() + table.getDeck().discarded());
        assertPrivate(table.handOf(player.getUniqueId()));
    }

    @Test void freeDrawRequiresEmptyMainHandAndIgnoresOffhandClicks() {
        Table table = placeAndClearMessage();
        assertFalse(shoeClick(table, EquipmentSlot.OFF_HAND).isCancelled());
        assertEquals(2, table.getDeck().remaining());
        player.getInventory().setItemInMainHand(new ItemStack(Material.STONE));
        assertTrue(shoeClick(table, EquipmentSlot.HAND).isCancelled());
        assertEquals("hand.need_empty", player.nextMessage());
        assertEquals(2, table.getDeck().remaining());
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        shoeClick(table, EquipmentSlot.HAND); tick(2);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertPrivate(table.handOf(player.getUniqueId()));
    }

    @Test void leavingDuringRevealCancelsDelayedFacesAndReturnsEveryCard() {
        Table table = dealt(2);
        List<UUID> tokens = table.handOf(player.getUniqueId()).stream().map(HandCard::tokenId).toList();
        swap();
        manager.onQuit(new PlayerQuitEvent(player, "quit"));
        tick(20);
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
        for (UUID token : tokens) assertFalse(publicItems.containsKey(token));
    }

    @Test void clockReturnsDistantHandsButKeepsNearbyHandsAndCanBeStopped() {
        Table table = dealt(2);
        manager.startClock(); tick(2);
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        player.teleport(player.getLocation().add(100, 0, 0));
        tick(2);
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
        assertEquals("hand.returned", player.nextMessage());
        manager.stopClock();
        player.teleport(table.getOrigin());
        manager.dealToPlayer(table, player, 1); tick(3);
        player.teleport(player.getLocation().add(100, 0, 0)); tick(3);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
    }

    @Test void instantHandLayoutFollowsNearbyMovementWithoutRevealingOrRedealingCards() {
        Cache.interpolationTicks = 0;
        Table table = dealt(1);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        DisplayPose before = poses.get(held.tokenId());
        manager.startClock();
        player.teleport(player.getLocation().add(1, 0, 0));
        tick(3);
        DisplayPose after = poses.get(held.tokenId());
        assertEquals(before.translation().x + 1, after.translation().x, 0.0001);
        assertEquals(before.translation().y, after.translation().y, 0.0001);
        assertEquals(before.translation().z, after.translation().z, 0.0001);
        assertEquals(List.of(held), table.handOf(player.getUniqueId()));
        assertEquals(1, table.getDeck().remaining());
        assertPrivate(List.of(held));
    }

    @Test void clockShowsSuitDustForTheRevealedCardOnly() {
        Table table = dealt(2);
        HandCard publicCard = table.handOf(player.getUniqueId()).getFirst();
        HandCard privateCard = table.handOf(player.getUniqueId()).get(1);
        aimAt(publicCard); handClick(Action.RIGHT_CLICK_AIR); tick(4);
        swap(); tick(8);
        Location at = table.getOrigin().clone().add(0, 1, 0);
        when(display.worldLocation(publicCard.tokenId())).thenReturn(at);
        when(display.worldLocation(privateCard.tokenId())).thenReturn(at.clone().add(1, 0, 0));
        org.bukkit.entity.Player recipient = mock(org.bukkit.entity.Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doNothing().when(recipient).spawnParticle(eq(org.bukkit.Particle.DUST), anyDouble(), anyDouble(), anyDouble(),
                anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(org.bukkit.Particle.DustOptions.class));
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(player.getUniqueId())).thenReturn(recipient);
            manager.startClock(); tick(3); manager.stopClock();
            verify(recipient).spawnParticle(eq(org.bukkit.Particle.DUST), anyDouble(), eq(at.getY()), anyDouble(),
                    eq(1), eq(0d), eq(0d), eq(0d), eq(0d), argThat((org.bukkit.Particle.DustOptions dust) ->
                            dust.getSize() == .8f && dust.getColor().equals(net.tfminecraft.games.card.CardNames.suitDust(publicCard.card()))));
        }
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertEquals(Material.PAPER, publicItems.get(privateCard.tokenId()).getType());
    }

    @Test void failedAnimatedDealKeepsTheCardRecoverableAndCompletesTheRequest() {
        Table table = placeAndClearMessage();
        Cache.handDealTicks = 3;
        Location courierStart = table.getOrigin().clone().add(0, (Cache.stackVisibleMax - 1) * Cache.stackLayerGap, 0);
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call ->
                !(((Location) call.getArgument(1)).equals(courierStart) && failed.compareAndSet(false, true)));
        Runnable after = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, after); tick(10);
        assertTrue(table.handOf(player.getUniqueId()).isEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
        assertEquals("place.spawn_failed", player.nextMessage());
        verify(after).run();
    }

    @Test void aPlayerCannotReceiveCardsFromTwoTablesAtOnce() {
        Table first = dealt(1);
        List<HandCard> original = List.copyOf(first.handOf(player.getUniqueId()));
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, new Location(world, 10, 65, 0)));
        assertEquals("place.done", player.nextMessage());
        Table second = manager.tables().stream().filter(t -> t != first).findFirst().orElseThrow();
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(second, player, 1, complete); tick(5);
        assertEquals("hand.busy", player.nextMessage());
        assertEquals(original, first.handOf(player.getUniqueId()));
        assertFalse(second.getHands().containsKey(player.getUniqueId()));
        assertEquals(2, second.getDeck().remaining());
        verify(complete).run();
    }

    @Test void anExhaustedShoeStopsAnOversizedDealWithoutRepeatingCardsOrCallbacks() {
        Table table = placeAndClearMessage();
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 10, complete); tick(20);
        List<HandCard> hand = table.handOf(player.getUniqueId());
        assertEquals(2, hand.size());
        assertEquals(2, hand.stream().map(held -> held.card().getId()).distinct().count());
        assertEquals(0, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
        assertEquals("hand.empty", player.nextMessage());
        verify(complete).run();
    }

    @Test void missingBackResourceRefusesDealingWithoutRemovingCards() {
        Table table = placeAndClearMessage();
        when(items.getCreator().getItemFromPath("back")).thenReturn(null);
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 1, complete); tick(5);
        assertEquals("place.spawn_failed", player.nextMessage());
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
        verify(complete).run();
    }

    @Test void cancelledPlacementCannotLaterCreateATable() {
        manager.armPlace(player, "freeplay", false);
        manager.onSneak(new PlayerToggleSneakEvent(player, false));
        manager.onSneak(new PlayerToggleSneakEvent(player, true));
        assertEquals("place.cancelled", player.nextMessage());
        assertFalse(manager.tryPlace(player, player.getLocation()));
        assertTrue(manager.tables().isEmpty());
    }

    @Test void actorChatDispatchesNormalizedActionsOnTheServerTickOnly() {
        Table table = placeAndClearMessage();
        games.when(() -> GamesRegistry.of("freeplay")).thenReturn(game);
        when(game.allowPlayChat(table, player)).thenCallRealMethod();
        manager.beginSession(table);
        table.setPhase("play"); table.setActor(player.getUniqueId());
        for (String action : List.of("hit", "stand", "double", "split", "check", "call", "fold", "raise", "draw")) {
            var chat = new AsyncPlayerChatEvent(true, player, "  " + action.toUpperCase(Locale.ROOT) + "!  ", Set.of(player));
            manager.onPlayChat(chat);
            assertTrue(chat.isCancelled());
        }
        verify(game, never()).onBetHit(any(), any());
        tick(1);
        verify(game).onBetHit(table, player);
        verify(game).onBetStand(table, player);
        verify(game).onBetDouble(table, player);
        verify(game).onBetSplit(table, player);
        for (String word : List.of("check", "call", "fold", "raise", "draw")) verify(game).onPlayWord(table, player, word);
        for (String ordinary : List.of("", "!", "hi there", "hit me", "hit?")) {
            var chat = new AsyncPlayerChatEvent(true, player, ordinary, Set.of(player));
            manager.onPlayChat(chat);
            assertFalse(chat.isCancelled());
        }
    }

    @Test void actorChangingBeforeQueuedChatExecutesPreventsTheStaleAction() {
        Table table = placeAndClearMessage();
        games.when(() -> GamesRegistry.of("freeplay")).thenReturn(game);
        when(game.allowPlayChat(table, player)).thenCallRealMethod();
        manager.beginSession(table); table.setPhase("play"); table.setActor(player.getUniqueId());
        var chat = new AsyncPlayerChatEvent(true, player, "stand.", Set.of(player));
        manager.onPlayChat(chat); assertTrue(chat.isCancelled());
        table.setActor(opponent().getUniqueId()); tick(1);
        verify(game, never()).onBetStand(any(), any());
        var nonActorChat = new AsyncPlayerChatEvent(true, player, "stand", Set.of(player));
        manager.onPlayChat(nonActorChat); assertFalse(nonActorChat.isCancelled());
    }

    @Test void wipingDuringDrawReturnsPendingAndHeldCardsAndCancelsTheCourier() {
        Table table = dealt(1);
        Cache.handDealTicks = 4;
        manager.dealToPlayer(table, player, 1);
        manager.wipeHands(); tick(20);
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
        assertTrue(privateItems.isEmpty());
        manager.rebuildAllStacks();
        assertEquals(Cache.stackVisibleMax, table.getStackTokens().size());
    }

    private Table placeAndClearMessage() {
        Table table = place(false);
        assertEquals("place.done", player.nextMessage());
        return table;
    }

    private Table dealt(int count) {
        Table table = placeAndClearMessage();
        manager.dealToPlayer(table, player, count); tick(5);
        assertEquals(count, table.handOf(player.getUniqueId()).size());
        return table;
    }

    private void aimAt(HandCard held) {
        Location hit = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.5));
        when(display.worldLocation(held.tokenId())).thenReturn(hit);
    }

    private PlayerInteractEvent handClick(Action action) {
        org.bukkit.entity.Player input = mock(org.bukkit.entity.Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doReturn(null).when(input).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(input, action, null, null, null, EquipmentSlot.HAND);
        manager.onInteractBlock(event);
        return event;
    }

    private PlayerSwapHandItemsEvent swap() {
        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player, new ItemStack(Material.AIR), new ItemStack(Material.AIR));
        manager.onSwapHands(event); return event;
    }

    private PlayerInteractAtEntityEvent shoeClick(Table table, EquipmentSlot hand) {
        Entity shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
        PlayerInteractAtEntityEvent event = new PlayerInteractAtEntityEvent(player, shoe, new Vector(), hand);
        manager.onInteractAtEntity(event); return event;
    }

    private void assertPrivate(List<HandCard> hand) {
        for (HandCard held : hand) {
            assertEquals(Material.PAPER, publicItems.get(held.tokenId()).getType());
            assertEquals(Material.DIAMOND, privateItems.get(held.tokenId()).getType());
        }
    }
}
