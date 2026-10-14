package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;

/** Typing a play word in chat only acts for the seat whose turn it is. */
@SuppressWarnings("deprecation")
class TableManagerPlayChatTest extends TableManagerFixture {

    @Test void asyncChatSeesCompletePreviousSnapshotDuringBothPublicationPaths() throws Exception {
        Table table = place(false);
        manager.beginSession(table);
        table.setActor(player.getUniqueId());
        when(game.allowPlayChat(table, player)).thenReturn(true);
        manager.publishChatActors();
        var field = TableManager.class.getDeclaredField("chatActors");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var before = (java.util.Map<java.util.UUID, java.util.UUID>) field.get(manager);
        assertThrows(UnsupportedOperationException.class, before::clear);
        var reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            when(game.allowPlayChat(table, player)).thenAnswer(call -> {
                var event = reader.submit(() -> chat("hit")).get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(event.isCancelled(), "the async reader must retain the complete published actor");
                return true;
            });
            manager.publishChatActors();
            manager.chatActorChanged(table);
        } finally {
            reader.shutdownNow();
        }
        verify(game, never()).onBetHit(any(), any());
        table.setActor(null);
        assertEquals(java.util.Map.of(player.getUniqueId(), table.getId()), before,
                "old readers retain an immutable snapshot after a later publication");
        tick(1);
        verify(game, never()).onBetHit(any(), any());
    }

    @Test void queuedChatCannotMoveToAnotherTableOrRenewedCharacterSession() {
        Table table = place(false);
        manager.beginSession(table);
        when(game.allowPlayChat(table, player)).thenReturn(true);
        table.setActor(player.getUniqueId());
        assertTrue(chat("hit").isCancelled());
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, table.getOrigin().clone().add(20, 0, 0)));
        Table other = manager.tables().stream().filter(candidate -> candidate != table).findFirst().orElseThrow();
        player.teleport(other.getOrigin());
        manager.beginSession(other);
        when(game.allowPlayChat(other, player)).thenReturn(true);
        other.setActor(player.getUniqueId());
        tick(1);
        verify(game, never()).onBetHit(any(), any());
        assertTrue(chat("hit").isCancelled());
        try (var access = mockStatic(net.tfminecraft.games.ownership.CharacterAccess.class, CALLS_REAL_METHODS)) {
            access.when(() -> net.tfminecraft.games.ownership.CharacterAccess.capture(player))
                    .thenReturn(new net.tfminecraft.games.ownership.OwnerSession(player.getUniqueId(),
                            table.ownerCharacterId(), 2));
            tick(1);
        }
        verify(game, never()).onBetHit(any(), any());
    }

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
        manager.publishChatActors(); // The main-thread phase boundary publishes immutable chat eligibility.
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
