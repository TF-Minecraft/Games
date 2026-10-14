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
        java.util.Map<UUID,CharacterRef> known = new java.util.HashMap<>();
        scope.when(() -> Characters.currentSession(any())).thenAnswer(call -> {
            Player player = call.getArgument(0);
            if(player == null) return Optional.empty();
            CharacterRef ref=ref(player);known.put(ref.characterId(),ref);
            return Optional.of(new CharacterSession(ref, 1L));
        });
        scope.when(() -> Characters.info(any())).thenAnswer(call -> {
            CharacterRef ref=known.get(call.getArgument(0));
            return ref==null?Optional.empty():Optional.of(new net.tfminecraft.tlibs.character.CharacterInfo(ref,"Fixture",
                    net.tfminecraft.tlibs.character.CharacterStatus.ALIVE,null,java.util.Set.of(),0,null));
        });
        scope.when(Characters::legacyHeirsReady).thenReturn(true);
        scope.when(() -> Characters.legacyHeir(any())).thenAnswer(call -> {
            UUID owner=call.getArgument(0);return Optional.of(new CharacterRef(owner,UUID.nameUUIDFromBytes(("character:"+owner).getBytes(StandardCharsets.UTF_8))));
        });
        scope.when(() -> Characters.displayName(any())).thenAnswer(call -> ((Player) call.getArgument(0)).getName());
        scope.when(() -> Characters.isCurrent(any())).thenReturn(true);
        return scope;
    }
}
