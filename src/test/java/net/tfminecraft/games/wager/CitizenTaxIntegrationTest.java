package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.TaxLeg;
import net.tfminecraft.denareconomy.api.TaxPolicy;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import net.tfminecraft.tlibs.character.CharacterRef;

class CitizenTaxIntegrationTest {
    @Test
    void profitTaxQuotesTheCapturedCharacterWithoutCreditingAnything() {
        CharacterRef character = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
        TaxPolicy policy = mock(TaxPolicy.class);
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(TaxPolicy.class)).thenReturn(policy);
        when(policy.tax(character, 2500L)).thenReturn(Optional.of(new TaxLeg("guildtax:realm", 260)));
        try (var bukkit = mockStatic(Bukkit.class); var denars = mockStatic(Denars.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            denars.when(Denars::taxReady).thenReturn(true);
            assertEquals(new CitizenTax.Levy(3, 2.6, character, "guildtax:realm"), CitizenTax.quote(character, 25));
            verify(policy).tax(character, 2500L);
            denars.verify(Denars::taxReady);
            denars.verifyNoMoreInteractions();
        }
    }

    @Test
    void absentCharacterOrTaxProviderReadinessDefersWithoutGuessing() {
        CharacterRef character = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
        try (var denars = mockStatic(Denars.class)) {
            assertNull(CitizenTax.quote(null, 10));
            assertSame(CitizenTax.Levy.NONE, CitizenTax.quote(null, 0));
            assertSame(CitizenTax.Levy.NONE, CitizenTax.quote(character, -1));
            assertNull(CitizenTax.quote(character, 10));
            denars.verify(Denars::taxReady);
        }
    }

    @Test
    void taxNotificationOnlyReachesOnlinePlayersWithPositiveTax() {
        Player player = mock(Player.class);
        try (var messages = mockStatic(MessageLoader.class)) {
            CitizenTax.tell(null, 2);
            CitizenTax.tell(player, 2);
            when(player.isOnline()).thenReturn(true);
            CitizenTax.tell(player, 0);
            CitizenTax.tell(player, -1);
            messages.verifyNoInteractions();
            CitizenTax.tell(player, 2.6);
            messages.verify(() -> MessageLoader.send(player, "money.tax", "tax", 2.6));
        }
    }
}
