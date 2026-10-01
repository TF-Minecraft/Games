package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.cache.Cache;

class TableManagerHandLimitTest extends TableManagerHandFixture {
    private Map<String, Integer> previousLimits;

    @BeforeEach void saveLimits() {
        previousLimits = new HashMap<>(Cache.gameHandCardLimits);
        Cache.gameHandCardLimits.clear();
    }

    @AfterEach void restoreLimits() {
        Cache.gameHandCardLimits.clear();
        Cache.gameHandCardLimits.putAll(previousLimits);
    }

    @Test void shoeClicksCannotDrawPastTheLimitOrConsumeAnotherCard() {
        Cache.gameHandCardLimits.put("freeplay", 1);
        Table table = placeAndClearMessage();
        shoeClick(table);
        tick(5);
        shoeClick(table);
        tick(5);
        assertEquals("hand.limit", player.nextMessage());
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(1, table.getDeck().remaining());
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void rapidClicksCountCardsStillInFlight() {
        Cache.gameHandCardLimits.put("freeplay", 1);
        Cache.handDealTicks = 3;
        Table table = placeAndClearMessage();
        shoeClick(table);
        shoeClick(table);
        // A second automatic request waits for the first flight, then checks the cap again.
        manager.dealToPlayer(table, player, 1);
        tick(20);
        assertEquals("hand.limit", player.nextMessage());
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(1, table.getDeck().remaining());
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void oversizedAutomaticDealCompletesItsCallbackOnceAtTheLimit() {
        Cache.gameHandCardLimits.put("freeplay", 1);
        Table table = placeAndClearMessage();
        Runnable done = mock(Runnable.class);
        manager.dealToPlayer(table, player, 2, done);
        tick(10);
        verify(done, times(1)).run();
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertEquals(1, table.getDeck().remaining());
    }

    @Test void returningACardMakesRoomToDrawAgain() throws InterruptedException {
        Cache.gameHandCardLimits.put("freeplay", 1);
        Table table = dealt(1);
        select(table.handOf(player.getUniqueId()).getFirst());
        shoeClick(table);
        tick(10);
        assertEquals("hand.returned_selected", player.nextMessage());
        shoeClick(table);
        tick(10);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertAllCardsAccountedFor(table, 2);
    }

    @Test void anOmittedLimitLeavesDrawingUnrestricted() {
        Table table = placeAndClearMessage();
        shoeClick(table);
        tick(5);
        shoeClick(table);
        tick(5);
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertAllCardsAccountedFor(table, 2);
    }
}
