package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.tfminecraft.games.support.TestCharacters;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class OutgoingRefundThreadTest {
    @Test void asyncLookupFailsClosedAndCannotOpenAScope() {
        var player = MockBukkit.getMock().addPlayer();
        var ending = new CharacterSession(TestCharacters.ref(player), 1);
        var reader = Executors.newSingleThreadExecutor();
        try (var characters = TestCharacters.mockSessions()) {
            characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
            characters.when(() -> Characters.isCurrent(ending)).thenReturn(false);
            OutgoingRefund.during(player, ending, () -> {
                assertEquals(ending.ref(), OutgoingRefund.recipient(player));
                var result = reader.submit(() -> {
                    assertNull(OutgoingRefund.recipient(player));
                    var ran = new AtomicBoolean();
                    assertThrows(IllegalStateException.class,
                            () -> OutgoingRefund.during(player, ending, () -> ran.set(true)));
                    assertFalse(ran.get());
                });
                assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
            });
            assertNull(OutgoingRefund.recipient(player));
        } finally { reader.shutdownNow(); }
    }
}
