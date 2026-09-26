package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;

/** Picking cards out of a hand, looking at one, and sending picked cards back to the shoe. */
class TableManagerSelectTest extends TableManagerHandFixture {

    /** Two poses a visible distance apart, allowing for float rounding in the animation maths. */
    private static boolean apart(DisplayPose a, DisplayPose b) {
        return a.translation().distance(b.translation()) > 1e-4f;
    }

    private void quit() {
        manager.onQuit(new PlayerQuitEvent(player, "quit"));
        player.disconnect();
    }

    // ------------------------------------------------------------------------------ selecting

    @Test void clickingAPickedCardAgainPutsItBackInTheFan() throws InterruptedException {
        Table table = dealt(2);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        DisplayPose resting = poses.get(held.tokenId());
        select(held);
        assertTrue(held.isSelected());
        assertTrue(apart(resting, poses.get(held.tokenId())), "a picked card stands out");
        select(held);
        assertFalse(held.isSelected());
        assertFalse(apart(resting, poses.get(held.tokenId())));
    }

    @Test void withSelectionAnimationOffAPickedCardMovesAtOnce() throws InterruptedException {
        Cache.handSelectTicks = 0;
        Table table = dealt(2);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        DisplayPose resting = poses.get(held.tokenId());
        waitOutClickCooldown();
        aimAt(held);
        handClick(Action.RIGHT_CLICK_AIR);
        assertTrue(held.isSelected());
        assertTrue(apart(resting, poses.get(held.tokenId())), "moved without any ticks");
    }

    @Test void pointingAwayFromEveryCardSelectsNothing() {
        Table table = dealt(2);
        handClick(Action.RIGHT_CLICK_AIR);
        assertTrue(table.handOf(player.getUniqueId()).stream().noneMatch(HandCard::isSelected));
    }

    @Test void cardsCannotBePickedWhileTurningOverOrOnceAnyIsShowing() throws InterruptedException {
        Table table = dealt(2);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        swap();
        aimAt(held);
        handClick(Action.RIGHT_CLICK_AIR);
        assertFalse(held.isSelected(), "busy turning the hand over");
        tick(8);
        assertTrue(isPublic(held));
        waitOutClickCooldown();
        handClick(Action.RIGHT_CLICK_AIR);
        assertFalse(held.isSelected(), "a showing hand keeps its selection");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aTableEntityInFrontOfTheCardTakesTheClickInsteadOfTheCard(boolean shoeInTheWay) {
        List<Entity> inTheWay = new ArrayList<>();
        WorldMock room = new WorldMock() {
            @Override public RayTraceResult rayTraceEntities(Location start, Vector direction, double distance,
                    Predicate<? super Entity> filter) {
                for (Entity entity : inTheWay) {
                    if (filter.test(entity)) {
                        return new RayTraceResult(start.toVector(), entity);
                    }
                }
                return null;
            }
        };
        room.setName("select-room-" + UUID.randomUUID());
        MockBukkit.getMock().addWorld(room);
        player.teleport(new Location(room, 0, 65, 0, 90, 0));
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, new Location(room, 0, 65, 0)));
        Table table = manager.tables().iterator().next();
        manager.dealToPlayer(table, player, 1);
        tick(3);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        // A stray entity never blocks the pick. The table's own anchor does.
        Entity stray = mock(Entity.class);
        inTheWay.add(stray);
        if (shoeInTheWay) {
            Entity shoe = mock(Entity.class);
            anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
            inTheWay.add(shoe);
        }
        aimAt(held);
        handClick(Action.RIGHT_CLICK_AIR);
        assertEquals(!shoeInTheWay, held.isSelected());
    }

    // ----------------------------------------------------------------------------- inspecting

    @Test void inspectingLiftsOnlyThatCardAndThenLaysItBack() {
        Table table = dealt(2);
        HandCard looked = table.handOf(player.getUniqueId()).getFirst();
        HandCard other = table.handOf(player.getUniqueId()).get(1);
        Map<UUID, DisplayPose> before = new HashMap<>(poses);
        aimAt(looked);
        handClick(Action.LEFT_CLICK_AIR);
        assertEquals("card.info", player.nextMessage());
        assertTrue(apart(before.get(looked.tokenId()), poses.get(looked.tokenId())));
        assertFalse(apart(before.get(other.tokenId()), poses.get(other.tokenId())));
        tick(10);
        assertFalse(apart(before.get(looked.tokenId()), poses.get(looked.tokenId())));
    }

    @Test void inspectingNeedsAHandAndWaitsForTurningOverToFinish() {
        Table table = placeAndClearMessage();
        handClick(Action.LEFT_CLICK_AIR);
        assertNull(player.nextMessage(), "nothing to inspect without a hand");
        manager.dealToPlayer(table, player, 1);
        tick(3);
        swap();
        aimAt(table.handOf(player.getUniqueId()).getFirst());
        handClick(Action.LEFT_CLICK_AIR);
        assertNull(player.nextMessage(), "no inspection while the card turns over");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aHandThatGoesWhileBeingInspectedIsNotRecreated(boolean quits) {
        Table table = dealt(1);
        aimAt(table.handOf(player.getUniqueId()).getFirst());
        handClick(Action.LEFT_CLICK_AIR);
        if (quits) {
            quit();
        } else {
            manager.muckPlayer(table, player);
        }
        tick(10);
        assertTrue(table.getHands().isEmpty());
        assertAllCardsAccountedFor(table, 2);
    }

    // ------------------------------------------------------------------------------ returning

    @Test void clickingTheShoeWithNothingPickedDrawsInsteadOfReturning() {
        Table table = dealt(1);
        assertTrue(shoeClick(table).isCancelled());
        tick(3);
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertEquals(0, table.getDeck().discarded());
    }

    @Test void theShoeIgnoresClicksWhileTheHandIsTurningOver() throws InterruptedException {
        Table table = dealt(1);
        select(table.handOf(player.getUniqueId()).getFirst());
        swap();
        assertTrue(shoeClick(table).isCancelled());
        tick(10);
        assertEquals(1, table.handOf(player.getUniqueId()).size(), "neither returned nor drawn");
        assertEquals(1, table.getDeck().remaining());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void severalPickedCardsFlyBackOneAfterAnotherAndTheHandEndsWithTheLast(int stagger)
            throws InterruptedException {
        useCards(3);
        Table table = dealt(3);
        List<HandCard> hand = table.handOf(player.getUniqueId());
        for (HandCard held : List.copyOf(hand)) {
            select(held);
        }
        Cache.handDealTicks = 2;
        Cache.handRevealStagger = stagger;
        shoeClick(table);
        tick(30);
        assertFalse(table.getHands().containsKey(player.getUniqueId()));
        assertEquals(3, table.getDeck().remaining(), "the emptied hand lets the discards shuffle back");
        assertEquals(0, table.getDeck().discarded());
        assertTrue(privateItems.isEmpty());
    }

    @Test void leavingWhilePickedCardsFlyBackStillReturnsEveryCard() throws InterruptedException {
        Table table = dealt(2);
        for (HandCard held : List.copyOf(table.handOf(player.getUniqueId()))) {
            select(held);
        }
        Cache.handDealTicks = 2;
        Cache.handRevealStagger = 5;
        shoeClick(table);
        tick(2);
        quit();
        tick(20);
        assertTrue(table.getHands().isEmpty());
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void returningALastCardWhileAnotherPlayerHoldsCardsLeavesTheDiscardsAlone()
            throws InterruptedException {
        Table table = dealt(1);
        var other = opponent();
        manager.dealToPlayer(table, other, 1);
        tick(3);
        select(table.handOf(player.getUniqueId()).getFirst());
        shoeClick(table);
        tick(5);
        assertFalse(table.getHands().containsKey(player.getUniqueId()));
        assertEquals(1, table.getDeck().discarded(), "no shuffle while someone still holds cards");
        assertEquals(1, table.handOf(other.getUniqueId()).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aReturnWhoseCourierCannotBeShownStillDiscardsTheCard(boolean backMissing) throws InterruptedException {
        Table table = dealt(2);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        select(held);
        Cache.handDealTicks = 3;
        if (backMissing) {
            when(items.getCreator().getItemFromPath("back")).thenReturn(null);
        } else {
            failNextSpawn();
        }
        shoeClick(table);
        tick(2);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(1, table.getDeck().discarded());
        assertFalse(publicItems.containsKey(held.tokenId()));
    }

    @Test void aPickedCardWhoseDisplayWasLostStaysInTheHandUntilItIsMucked() throws InterruptedException {
        Table table = dealt(2);
        HandCard lost = table.handOf(player.getUniqueId()).getFirst();
        select(lost);
        swap();
        tick(8);
        assertTrue(isPublic(lost));
        // Turning it face-down again needs a fresh display, which the renderer refuses.
        failNextSpawn();
        swap();
        tick(8);
        assertTrue(table.handOf(player.getUniqueId()).contains(lost));
        assertFalse(publicItems.containsKey(lost.tokenId()), "its old display is gone");
        shoeClick(table);
        tick(5);
        assertTrue(table.handOf(player.getUniqueId()).contains(lost), "nothing to fly back, so it is kept");
        assertAllCardsAccountedFor(table, 2);
        manager.muckPlayer(table, player);
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void aHandOfCardsWithoutAFaceModelIsDealtFaceDownToItsOwnerToo() {
        when(items.getCreator().getItemFromPath("face")).thenReturn(null);
        Table table = dealt(1);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        assertEquals(Material.PAPER, publicItems.get(held.tokenId()).getType());
        assertNull(privateItems.get(held.tokenId()));
    }

    @Test void mutedOrMissingCardSoundsPlayNothing() {
        org.bukkit.Sound previous = Cache.cardSound;
        float previousVolume = Cache.cardSoundVolume;
        try {
            Table table = dealt(1);
            player.clearSounds();
            Cache.cardSound = null;
            shoeClick(table);
            tick(3);
            Cache.cardSound = org.bukkit.Sound.ITEM_BOOK_PAGE_TURN;
            Cache.cardSoundVolume = 0f;
            manager.muckPlayer(table, player);
            manager.dealToPlayer(table, player, 1);
            tick(3);
            assertTrue(player.getHeardSounds().isEmpty());
            Cache.cardSoundVolume = 1f;
            shoeClick(table);
            tick(3);
            player.assertSoundHeard(org.bukkit.Sound.ITEM_BOOK_PAGE_TURN);
        } finally {
            Cache.cardSound = previous;
            Cache.cardSoundVolume = previousVolume;
        }
    }
}
