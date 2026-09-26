package net.tfminecraft.games.game;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.voice.RpNames;

class HandTalkTest {
    @Test
    void showdownNamesEveryTiedWinnerWithTheFiveCardsAndOmitsLosingOrIncompleteHands() {
        UUID weak = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID(), absent = UUID.randomUUID();
        Map<UUID, List<Card>> hands = Map.of(weak, hand("oseni", 14, 10, 8, 5, 2),
                first, hand("cerrith", 9, 10, 11, 12, 13),
                second, hand("mitlan", 9, 10, 11, 12, 13), absent, List.of());
        try (MockedStatic<Messages> messages = mockStatic(Messages.class);
                MockedStatic<RpNames> names = mockStatic(RpNames.class)) {
            names.when(() -> RpNames.of(first)).thenReturn("First");
            names.when(() -> RpNames.of(second)).thenReturn("Second");
            messages.when(() -> Messages.getRaw("hand.card")).thenReturn("{rank} of {suit}");
            messages.when(() -> Messages.getRaw("hand.card_join")).thenReturn(", ");
            messages.when(() -> Messages.get(eq("hand.best"), eq("name"), anyString()))
                    .thenAnswer(call -> "Winner: " + call.getArgument(2));
            messages.when(() -> Messages.get(eq("hand.best_cards"), eq("cards"), anyString()))
                    .thenAnswer(call -> call.getArgument(2));
            List<String> lines = HandTalk.bestHand(null, List.of(weak, first, absent, second), hands::get);
            assertEquals(4, lines.size());
            assertEquals("Winner: First", lines.get(0));
            assertEquals("Winner: Second", lines.get(2));
            assertEquals("#ffffffKing of #55ff55Cerrith, #ffffffQueen of #55ff55Cerrith, #ffffffJack of #55ff55Cerrith, #ffffff10 of #55ff55Cerrith, #ffffff9 of #55ff55Cerrith", lines.get(1));
            assertTrue(lines.get(3).contains("#5555ffMitlan"));
            assertTrue(HandTalk.bestHand(null, List.of(absent), hands::get).isEmpty());
            assertTrue(HandTalk.bestHand(null, List.of(), hands::get).isEmpty());
            // A weaker hand encountered after the winner must not replace it.
            assertEquals(lines.subList(0, 2), HandTalk.bestHand(null, List.of(first, weak), hands::get));
        }
    }

    private List<Card> hand(String suit, int... ranks) {
        List<Card> cards = new ArrayList<>();
        for (int rank : ranks) cards.add(new Card(suit + rank, suit, rank, false, ""));
        return cards;
    }
}
