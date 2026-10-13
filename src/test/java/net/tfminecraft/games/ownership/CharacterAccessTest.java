package net.tfminecraft.games.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.tfminecraft.games.table.Table;
import net.tfminecraft.tlibs.character.*;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class CharacterAccessTest {
    @Test
    void sameAccountCharactersAndRenewedSessionsNeverSharePrivateAuthority() {
        var player = MockBukkit.getMock().addPlayer();
        UUID account = player.getUniqueId();
        var a = new CharacterRef(account, UUID.randomUUID());
        var b = new CharacterRef(account, UUID.randomUUID());
        var current = new AtomicReference<>(new CharacterSession(a, 1));
        Table table = mock(Table.class);
        when(table.ownerPlayer()).thenReturn(account);
        when(table.ownerCharacterId()).thenReturn(a.characterId());
        try (var characters = mockStatic(Characters.class)) {
            characters
                    .when(() -> Characters.currentSession(player))
                    .thenAnswer(call -> Optional.of(current.get()));
            characters
                    .when(() -> Characters.info(any()))
                    .thenAnswer(
                            call ->
                                    Optional.of(
                                            new CharacterInfo(
                                                    current.get().ref(),
                                                    "Fixture",
                                                    CharacterStatus.ALIVE,
                                                    null,
                                                    Set.of(),
                                                    0,
                                                    null)));
            characters.when(() -> Characters.isCurrent(any())).thenReturn(true);
            assertTrue(CharacterAccess.available());
            assertTrue(CharacterAccess.owns(table, player));
            OwnerSession old = CharacterAccess.capture(player);
            current.set(new CharacterSession(b, 2));
            assertFalse(CharacterAccess.owns(table, player));
            assertFalse(CharacterAccess.current(old));
            current.set(new CharacterSession(a, 3));
            assertTrue(CharacterAccess.owns(table, player));
            assertFalse(CharacterAccess.current(old));
            when(table.ownershipHeld()).thenReturn(true);
            assertFalse(CharacterAccess.owns(table, player));
        }
    }

    @Test
    void wrongAccountStaleDeadAndMismatchedInfoProvidersFailBeforePromptCapture() {
        var player = MockBukkit.getMock().addPlayer();
        var ref = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
        var token = new AtomicReference<>(new CharacterSession(ref, 1));
        var info =
                new AtomicReference<>(
                        new CharacterInfo(
                                ref, "Fixture", CharacterStatus.ALIVE, null, Set.of(), 0, null));
        try (var characters = mockStatic(Characters.class)) {
            characters
                    .when(() -> Characters.currentSession(player))
                    .thenAnswer(call -> Optional.of(token.get()));
            characters
                    .when(() -> Characters.info(any()))
                    .thenAnswer(call -> Optional.of(info.get()));
            characters.when(() -> Characters.isCurrent(any())).thenReturn(true);
            assertNull(CharacterAccess.capture(player));
            var own = new CharacterRef(player.getUniqueId(), ref.characterId());
            token.set(new CharacterSession(own, 1));
            assertNull(CharacterAccess.capture(player));
            info.set(
                    new CharacterInfo(
                            own, "Fixture", CharacterStatus.DEAD, null, Set.of(), 0, null));
            assertNull(CharacterAccess.capture(player));
            info.set(
                    new CharacterInfo(
                            own, "Fixture", CharacterStatus.ALIVE, null, Set.of(), 0, null));
            characters.when(() -> Characters.isCurrent(any())).thenReturn(false);
            assertNull(CharacterAccess.capture(player));
            characters.when(() -> Characters.isCurrent(any())).thenReturn(true);
            assertNotNull(CharacterAccess.capture(player));
        }
    }
}
