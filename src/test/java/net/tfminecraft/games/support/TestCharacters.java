package net.tfminecraft.games.support;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;

/** Deterministic character service boundary for gameplay fixtures. */
public final class TestCharacters {
    private TestCharacters() {}
    public static CharacterRef ref(Player player) {
        UUID owner = player.getUniqueId();
        return new CharacterRef(owner, UUID.nameUUIDFromBytes(("character:" + owner).getBytes(StandardCharsets.UTF_8)));
    }
    public static MockedStatic<Characters> mockSessions() {
        MockedStatic<Characters> scope = mockStatic(Characters.class);
        scope.when(() -> Characters.currentSession(any())).thenAnswer(call -> {
            Player player = call.getArgument(0);
            return player == null ? Optional.empty() : Optional.of(new CharacterSession(ref(player), 1L));
        });
        scope.when(() -> Characters.displayName(any())).thenAnswer(call -> ((Player) call.getArgument(0)).getName());
        scope.when(() -> Characters.isCurrent(any())).thenReturn(true);
        return scope;
    }
}
