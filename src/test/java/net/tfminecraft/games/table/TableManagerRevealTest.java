package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import net.tfminecraft.games.cache.Cache;

/** Showing and hiding cards: the flip animation, and what survives interruptions to it. */
class TableManagerRevealTest extends TableManagerHandFixture {

    private Table secondTable() {
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, new Location(world, 10, 65, 0)));
        assertEquals("place.done", player.nextMessage());
        return manager.tables().stream().filter(t -> t.getOrigin().getX() == 10).findFirst().orElseThrow();
    }

    @Test void reloadingWhileCardsAreBeingHiddenLeavesNoPhantomHandBlockingOtherTables() {
        Table table = dealt(2);
        swap();
        tick(8);
        swap();
        tick(1);
        manager.wipeHands();
        tick(10);
        assertTrue(table.getHands().isEmpty(), "the stale hide animation must not recreate the hand");
        assertAllCardsAccountedFor(table, 2);
        Table other = secondTable();
        manager.dealToPlayer(other, player, 1);
        tick(3);
        assertNull(player.nextMessage(), "no hand.busy refusal");
        assertEquals(1, other.handOf(player.getUniqueId()).size());
    }

    private void quit() {
        manager.onQuit(new PlayerQuitEvent(player, "quit"));
        player.disconnect();
    }

    @Test void reloadingMidRevealThenDealingElsewhereLeavesOnlyTheNewHand() {
        Table table = dealt(2);
        swap();
        tick(1);
        manager.wipeHands();
        Table other = secondTable();
        manager.dealToPlayer(other, player, 1);
        tick(10);
        assertTrue(table.getHands().isEmpty(), "the finished reveal must not lay out a hand at the old table");
        assertEquals(1, other.handOf(player.getUniqueId()).size());
        assertPrivate(other.handOf(player.getUniqueId()));
    }

    @Test void reloadingMidHideThenDealingAgainHereLeavesTheNewCardAlone() {
        Table table = dealt(2);
        swap();
        tick(8);
        swap();
        tick(1);
        manager.wipeHands();
        manager.dealToPlayer(table, player, 1);
        tick(1);
        HandCard fresh = table.handOf(player.getUniqueId()).getFirst();
        tick(10);
        assertEquals(List.of(fresh), table.handOf(player.getUniqueId()), "the stale hide must not replace it");
        assertPrivate(List.of(fresh));
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void hidingAHandWherePartIsAlreadyHiddenTurnsOnlyTheShowingCards() throws InterruptedException {
        useCards(3);
        Table table = dealt(1);
        HandCard first = table.handOf(player.getUniqueId()).getFirst();
        select(first);
        swap();
        tick(8);
        manager.dealToPlayer(table, player, 1);
        tick(8);
        HandCard shown = table.handOf(player.getUniqueId()).stream().filter(h -> h != first).findFirst().orElseThrow();
        assertTrue(isPublic(shown), "a card joining a fully shown hand is shown too");
        swap();
        tick(8);
        manager.dealToPlayer(table, player, 1);
        tick(8);
        HandCard hidden = table.handOf(player.getUniqueId()).stream()
                .filter(h -> h.card() != first.card() && h != shown).findFirst().orElseThrow();
        assertFalse(isPublic(hidden));
        // Send the picked card back, leaving one showing and one hidden card and nothing picked.
        shoeClick(table);
        tick(3);
        List<HandCard> hand = table.handOf(player.getUniqueId());
        assertEquals(2, hand.size());
        assertTrue(hand.stream().noneMatch(HandCard::isSelected));
        java.util.UUID hiddenToken = hidden.tokenId();
        swap();
        tick(8);
        assertTrue(hand.stream().noneMatch(this::isPublic));
        assertTrue(hand.stream().anyMatch(h -> h.tokenId().equals(hiddenToken)), "the hidden card was never touched");
        assertPrivate(hand);
    }

    @Test void cardsWithoutAFaceModelStayShowingTheirBackThroughARevealAndHide() {
        when(items.getCreator().getItemFromPath("face")).thenReturn(null);
        Table table = dealt(2);
        swap();
        tick(8);
        for (HandCard held : table.handOf(player.getUniqueId())) {
            assertEquals(Material.PAPER, publicItems.get(held.tokenId()).getType());
        }
        swap();
        tick(8);
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        for (HandCard held : table.handOf(player.getUniqueId())) {
            assertEquals(Material.PAPER, publicItems.get(held.tokenId()).getType());
            assertNull(privateItems.get(held.tokenId()));
        }
    }

    @Test void losingTheCardBackModelAfterDealingStillLetsTheHandBeShownAndHidden() {
        Table table = dealt(2);
        when(items.getCreator().getItemFromPath("back")).thenReturn(null);
        swap();
        tick(8);
        assertTrue(table.handOf(player.getUniqueId()).stream().allMatch(this::isPublic));
        swap();
        tick(8);
        List<HandCard> hand = table.handOf(player.getUniqueId());
        assertEquals(2, hand.size());
        for (HandCard held : hand) {
            assertEquals(Material.DIAMOND, privateItems.get(held.tokenId()).getType(), "the owner still sees the face");
        }
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void losingBothCardModelsBeforeHidingKeepsTheCardsInTheHand() {
        Table table = dealt(2);
        swap();
        tick(8);
        when(items.getCreator().getItemFromPath("back")).thenReturn(null);
        when(items.getCreator().getItemFromPath("face")).thenReturn(null);
        swap();
        tick(8);
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertAllCardsAccountedFor(table, 2);
        manager.muckPlayer(table, player);
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void aCardLandingAfterTheBackModelDisappearsIsStillShownInARevealedHand() {
        Table table = dealt(1);
        swap();
        tick(8);
        Cache.handDealTicks = 3;
        manager.dealToPlayer(table, player, 1);
        when(items.getCreator().getItemFromPath("back")).thenReturn(null);
        tick(15);
        List<HandCard> hand = table.handOf(player.getUniqueId());
        assertEquals(2, hand.size());
        assertTrue(hand.stream().allMatch(this::isPublic));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void quittingPartWayThroughAFlipLeavesNoCardShowingAndLosesNone(int elapsed) {
        Table table = dealt(2);
        List<HandCard> hand = List.copyOf(table.handOf(player.getUniqueId()));
        swap();
        tick(elapsed);
        quit();
        tick(10);
        assertTrue(table.getHands().isEmpty());
        for (HandCard held : hand) assertFalse(publicItems.containsKey(held.tokenId()));
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void quittingWhileANewCardTurnsOverToJoinARevealedHandLosesNothing() {
        Table table = dealt(1);
        swap();
        tick(8);
        manager.dealToPlayer(table, player, 1);
        quit();
        tick(10);
        assertTrue(table.getHands().isEmpty());
        assertAllCardsAccountedFor(table, 2);
    }
}
