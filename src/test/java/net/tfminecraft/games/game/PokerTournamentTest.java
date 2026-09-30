package net.tfminecraft.games.game;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PokerTournamentTest {
    @Test void chipPotsAndAbortedHandsConserveStacksWithoutCreatingDenars() {
        PokerTournament tournament = new PokerTournament();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        tournament.configure(10, 100, 1, 2, 5);
        tournament.buy(alice); tournament.buy(bob);
        tournament.start(1000);
        assertEquals(100, tournament.bet(alice, 500));
        assertEquals(30, tournament.bet(bob, 30));
        tournament.nextStreet();
        assertEquals(0, tournament.contribution(alice));
        assertEquals(100, tournament.invested().get(alice));
        tournament.abortHand();
        assertEquals(100, tournament.stack(alice));
        assertEquals(100, tournament.stack(bob));
        assertTrue(tournament.invested().isEmpty());
    }

    @Test void rebuysAreLimitedAndNewEntrantsCloseWhenPlayStarts() {
        PokerTournament tournament = new PokerTournament();
        UUID alice = UUID.randomUUID();
        tournament.configure(10, 100, 1, 0, 0);
        tournament.buy(alice);
        assertFalse(tournament.canBuy(alice));
        tournament.start(1000);
        assertFalse(tournament.canBuy(UUID.randomUUID()));
        tournament.bet(alice, 100);
        tournament.finishHand();
        assertTrue(tournament.canBuy(alice));
        tournament.buy(alice);
        tournament.bet(alice, 100);
        tournament.finishHand();
        assertFalse(tournament.canBuy(alice));
        assertThrows(IllegalStateException.class, () -> tournament.buy(alice));
        assertThrows(IllegalArgumentException.class, () -> tournament.configure(20, 100, 2, 0, 0));
    }

    @Test void blindLevelsUseElapsedIntervalsAndStayBounded() {
        PokerTournament tournament = new PokerTournament();
        tournament.configure(10, 100, 0, 0, 5);
        tournament.start(1000);
        assertEquals(10, tournament.blind(10, 300999));
        assertEquals(20, tournament.blind(10, 301000));
        assertEquals(40, tournament.blind(10, 601000));
        assertEquals(1_000_000, tournament.blind(10, Long.MAX_VALUE));
        assertEquals(0, tournament.blind(0, Long.MAX_VALUE));
    }
}
