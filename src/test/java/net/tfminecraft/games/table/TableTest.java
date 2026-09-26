package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.deck.Deck;
import net.tfminecraft.games.wager.WagerVote;

class TableTest {
    private final UUID id = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final Table table = new Table(id, "poker", new Location(null, 1, 2, 3), 90, null);

    @Test
    void handAndNamedPileAccessRetainsCardsAndNormalizesNames() {
        Card card = new Card("ace", "oseni", 1, false, "ace-item");
        UUID token = UUID.randomUUID();
        HandCard hand = new HandCard(card, token);
        assertSame(card, hand.card());
        assertSame(token, hand.tokenId());
        assertFalse(hand.faceUp());
        assertFalse(hand.isSelected());
        hand.setSelected(true);
        hand.setFaceUp(true);
        hand.setSlot(2);
        assertTrue(hand.faceUp());
        assertTrue(hand.isSelected());
        assertEquals(2, hand.slot());
        hand.setSlot(-1);
        assertEquals(0, hand.slot());
        table.handOf(player).add(hand);
        assertSame(table.handOf(player), table.getHands().get(player));
        assertEquals(List.of(hand), table.handOf(player));
        assertTrue(table.handOf(UUID.randomUUID()).isEmpty());
        assertTrue(table.tablePilesEmpty());
        table.tablePile("BOARD");
        assertTrue(table.tablePilesEmpty());
        table.tablePile("board").add(new HandCard(card, token, true));
        assertFalse(table.tablePilesEmpty());
        assertSame(table.tablePile("BOARD"), table.tablePiles().get("board"));
        assertTrue(table.tablePile("board").getFirst().faceUp());
        assertSame(table.tablePile(""), table.tablePile(null));
    }

    @Test
    void payoutsDeduplicateDestinationsAndConsumeCompletionOnlyOnce() {
        Runnable done = mock(Runnable.class);
        PayoutFlight first = new PayoutFlight(null, player);
        PayoutFlight duplicate = new PayoutFlight(null, player);
        PayoutFlight tray = PayoutFlight.toTray(null);
        List<PayoutFlight> flights = new ArrayList<>(List.of(first, duplicate, tray));
        assertFalse(table.isPaying());
        assertEquals(0, table.payoutGen());
        assertEquals(1, table.beginPayout(flights, done));
        flights.clear();
        assertTrue(table.isPaying());
        assertEquals(3, table.payoutFlying().size());
        assertEquals(List.of(player), new ArrayList<>(table.payoutDests()));
        table.takePayoutOnDone().run();
        assertNull(table.takePayoutOnDone());
        verify(done).run();
        table.endPayout();
        assertFalse(table.isPaying());
        assertTrue(table.payoutFlying().isEmpty());
        assertTrue(table.payoutDests().isEmpty());
        assertNull(table.takePayoutOnDone());
        assertEquals(2, table.beginPayout(null, done));
        assertTrue(table.payoutFlying().isEmpty());
        table.clearPayoutOnDone();
        assertNull(table.takePayoutOnDone());
        assertEquals(3, table.bumpPayoutGen());
        assertEquals(3, table.payoutGen());
    }

    @Test
    void replacingDealerClosesBetWindowButRetainingDealerDoesNot() {
        table.setDealerId(player);
        table.setBetOpen(true);
        table.setAutoCountdown(10);
        table.setDealerId(player);
        assertTrue(table.betOpen());
        assertEquals(10, table.autoCountdown());
        table.setDealerId(UUID.randomUUID());
        assertFalse(table.betOpen());
        assertEquals(0, table.autoCountdown());
        table.setDealerId(null);
        assertNull(table.dealerId());
        table.setAutoCountdown(-1);
        assertEquals(0, table.autoCountdown());
    }

    @Test
    void staffMintRequiresAutomaticDealerAndIsClearedWithIt() {
        assertFalse(table.staffMint());
        assertFalse(table.autoDealer());
        table.setStaffMint(true);
        assertTrue(table.staffMint());
        assertTrue(table.autoDealer());
        table.setAutoDealer(false);
        assertFalse(table.staffMint());
        table.setAutoDealer(true);
        assertFalse(table.staffMint());
        table.setStaffMint(false);
        assertTrue(table.autoDealer());
    }

    @Test
    void betLimitsAndRoundIndicesCannotBecomeNegativeOrInvert() {
        table.setMinBet(-1);
        assertEquals(1, table.minBet());
        assertEquals(1, table.maxBet());
        table.setMaxBet(20);
        table.setMinBet(10);
        assertEquals(10, table.minBet());
        assertEquals(20, table.maxBet());
        table.setMaxBet(5);
        assertEquals(10, table.maxBet());
        table.setMinBet(30);
        assertEquals(30, table.maxBet());
        table.setHouseFloat(-1);
        table.setMaxBoxes(-1);
        table.setSmallBlind(-1);
        table.setBigBlind(-1);
        table.setBoxIndex(-1);
        table.setHandIndex(-1);
        table.setStreet(-1);
        assertEquals(0, table.houseFloat());
        assertEquals(0, table.maxBoxes());
        assertEquals(0, table.smallBlind());
        assertEquals(0, table.bigBlind());
        assertEquals(0, table.boxIndex());
        assertEquals(0, table.handIndex());
        assertEquals(1, table.street());
        table.setMaxBoxes(3);
        table.setSmallBlind(5);
        table.setBigBlind(10);
        table.setStreet(2);
        assertEquals(3, table.maxBoxes());
        assertEquals(5, table.smallBlind());
        assertEquals(10, table.bigBlind());
        assertEquals(2, table.street());
    }

    @Test
    void clearingSessionResetsRoundProgressButRetainsTableOwnership() {
        table.setOwnerPlayer(player);
        table.setOwnerGuildId("guild");
        table.startSession();
        table.setActor(player);
        table.setPhase("flop");
        table.boxes().add(player);
        table.setBoxIndex(2);
        table.setHandIndex(1);
        table.setAutoCountdown(5);
        table.roundMoney().recordProfit(player, 10);
        assertTrue(table.live());
        assertEquals(player, table.actor());
        assertEquals("flop", table.phase());
        table.clearSession();
        assertFalse(table.live());
        assertNull(table.actor());
        assertNull(table.phase());
        assertTrue(table.boxes().isEmpty());
        assertEquals(0, table.boxIndex());
        assertEquals(0, table.handIndex());
        assertEquals(0, table.autoCountdown());
        assertTrue(table.roundMoney().wonProfit().isEmpty());
        assertEquals(player, table.ownerPlayer());
        assertEquals("guild", table.ownerGuildId());
    }

    @Test
    void shufflePolicyAcceptsConfigValuesAndFallsBackForInvalidInput() {
        assertEquals(ShufflePolicy.SHOE, table.shufflePolicy());
        table.setShufflePolicy(ShufflePolicy.ROUND);
        assertEquals(ShufflePolicy.ROUND, table.shufflePolicy());
        table.setShufflePolicy(ShufflePolicy.parse("unknown"));
        assertEquals(ShufflePolicy.SHOE, table.shufflePolicy());
        assertEquals(ShufflePolicy.ROUND, ShufflePolicy.parse(" round "));
        assertEquals(ShufflePolicy.SHOE, ShufflePolicy.parse("shoe"));
        for (String invalid : Arrays.asList(null, "", "  ", "unknown")) {
            assertEquals(ShufflePolicy.SHOE, ShufflePolicy.parse(invalid));
        }
        assertEquals(ShufflePolicy.ROUND, ShufflePolicy.SHOE.next());
        assertEquals(ShufflePolicy.SHOE, ShufflePolicy.ROUND.next());
    }

    @Test
    void animationGenerationsInvalidatePreviousWorkIndependently() {
        assertEquals(0, table.recycleGen());
        assertEquals(0, table.tableDealGen());
        assertEquals(1, table.bumpRecycleGen());
        assertEquals(2, table.bumpRecycleGen());
        assertEquals(0, table.tableDealGen());
        assertEquals(1, table.bumpTableDealGen());
        assertEquals(2, table.recycleGen());
        assertEquals(1, table.tableDealGen());
        assertFalse(table.isRecycling());
        table.setRecycling(true);
        assertTrue(table.isRecycling());
        table.setRecycling(false);
        assertFalse(table.isRecycling());
    }
}
