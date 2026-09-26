package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;

/** Typing a play word in chat only acts for the seat whose turn it is. */
@SuppressWarnings("deprecation")
class TableManagerPlayChatTest extends TableManagerFixture {

    @Test void playWordsAreOrdinaryChatUnlessTheGameAcceptsThemFromTheActingSeat() {
        Table table = place(false);
        player.teleport(table.getOrigin().clone().add(100, 0, 0));
        assertFalse(chat("hit").isCancelled(), "away from every table");
        player.teleport(table.getOrigin());
        assertFalse(chat("hit").isCancelled(), "at a table with no round running");
        manager.beginSession(table);
        table.setPhase("insurance");
        table.setActor(player.getUniqueId());
        when(game.allowPlayChat(table, player)).thenReturn(false);
        assertFalse(chat("hit").isCancelled(), "the game is not taking play words right now");
        when(game.allowPlayChat(table, player)).thenReturn(true);
        assertTrue(chat("Hit!").isCancelled());
        verify(game, never()).onBetHit(any(), any());
        tick(1);
        verify(game).onBetHit(table, player);
    }

    private AsyncPlayerChatEvent chat(String message) {
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, player, message, Set.of(player));
        manager.onPlayChat(event);
        return event;
    }
}
