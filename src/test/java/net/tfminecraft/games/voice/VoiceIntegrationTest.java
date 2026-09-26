package net.tfminecraft.games.voice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.layout.TableLayout.VoiceLines;
import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.chat.ChatManager;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.loaders.ChatLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class VoiceIntegrationTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<DisplayIdentityService> identity;
    private MockedStatic<ChatLoader> channels;
    private MockedStatic<PlayerManager> players;
    private MockedStatic<ChatManager> chat;
    private PluginManager plugins;
    private Plugin rpc;
    private Player player;
    private PlayerData data;
    private ChatChannel channel;
    private UUID id;

    private net.tfminecraft.games.Games previousPlugin;
    private java.util.logging.Logger logger;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        previousPlugin = net.tfminecraft.games.Games.plugin;
        net.tfminecraft.games.Games.plugin = mock(net.tfminecraft.games.Games.class);
        logger = mock(java.util.logging.Logger.class);
        when(net.tfminecraft.games.Games.plugin.getLogger()).thenReturn(logger);
        // Each warning is once per server start; begin each test from a fresh start.
        for (Class<?> type : List.of(RpNames.class, RpVoice.class)) {
            var loggedFail = type.getDeclaredField("loggedFail");
            loggedFail.setAccessible(true);
            loggedFail.setBoolean(null, false);
        }
        plugins = mock(PluginManager.class);
        rpc = mock(Plugin.class);
        when(rpc.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("RPCharacters")).thenReturn(rpc);
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        id = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("AccountName");
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        identity = mockStatic(DisplayIdentityService.class);
        channels = mockStatic(ChatLoader.class);
        players = mockStatic(PlayerManager.class);
        chat = mockStatic(ChatManager.class);
        channel = mock(ChatChannel.class);
        channels.when(() -> ChatLoader.getChannel("rp")).thenReturn(channel);
        data = mock(PlayerData.class);
        when(data.getActiveCharacter()).thenReturn(mock(RPCharacter.class));
        players.when(() -> PlayerManager.get(player)).thenReturn(data);
    }

    @AfterEach
    void closeMocks() {
        if (chat != null) chat.close();
        if (players != null) players.close();
        if (channels != null) channels.close();
        if (identity != null) identity.close();
        if (bukkit != null) bukkit.close();
        net.tfminecraft.games.Games.plugin = previousPlugin;
    }

    @Test
    void namesPreferCharacterDisplayThenCharacterNameThenAccountName() {
        identity.when(() -> DisplayIdentityService.resolveCharacterName(player)).thenReturn("Character Name");
        identity.when(() -> DisplayIdentityService.resolveDisplay(player)).thenReturn("The Dealer");
        assertEquals("The Dealer", RpNames.of(id));
        identity.when(() -> DisplayIdentityService.resolveDisplay(player)).thenReturn(" ");
        assertEquals("Character Name", RpNames.of(id));
        identity.when(() -> DisplayIdentityService.resolveDisplay(player)).thenReturn(null);
        assertEquals("Character Name", RpNames.of(id));
        identity.when(() -> DisplayIdentityService.resolveCharacterName(player)).thenReturn(" ");
        assertEquals("AccountName", RpNames.of(id));
        identity.when(() -> DisplayIdentityService.resolveCharacterName(player)).thenReturn(null);
        assertEquals("AccountName", RpNames.of(id));
    }

    @Test
    void offlineNamesAndUnknownIdentityHaveUsefulFallbacks() {
        OfflinePlayer offline = mock(OfflinePlayer.class);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(null);
        bukkit.when(() -> Bukkit.getOfflinePlayer(id)).thenReturn(offline);
        when(offline.getName()).thenReturn("LastKnownName");
        assertEquals("LastKnownName", RpNames.of(id));
        when(offline.getName()).thenReturn(" ");
        assertEquals("-", RpNames.of(id));
        when(offline.getName()).thenReturn(null);
        assertEquals("-", RpNames.of(id));
        assertEquals("-", RpNames.of(null));
    }

    @Test
    void missingOrDisabledRoleplayPluginFallsBackWithoutDispatchingVoice() {
        when(rpc.isEnabled()).thenReturn(false);
        assertEquals("AccountName", RpNames.of(id));
        RpVoice.say(player, layout(VoiceLines.defaults()), "hit");
        when(plugins.getPlugin("RPCharacters")).thenReturn(null);
        assertEquals("AccountName", RpNames.of(id));
        RpVoice.say(player, layout(VoiceLines.defaults()), "hit");
        identity.verifyNoInteractions();
        chat.verifyNoInteractions();
    }

    @Test
    void configuredVoiceLineDispatchesThroughRoleplayChannelWithActiveCharacter() {
        TableLayout layout = layout(new VoiceLines("rp", "Another card.", "Enough.", "Double.", "Split."));
        RpVoice.say(player, layout, "HIT");
        chat.verify(() -> ChatManager.dispatch(player, channel, "Another card.", true));
        RpVoice.say(player, layout, "stand");
        chat.verify(() -> ChatManager.dispatch(player, channel, "Enough.", true));
    }

    @Test
    void unknownChannelOrMissingActiveCharacterDoesNotSpeak() {
        RpVoice.say(player, layout(new VoiceLines("missing", "Hit.", null, null, null)), "hit");
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        RpVoice.say(player, layout(VoiceLines.defaults()), "hit");
        players.when(() -> PlayerManager.get(player)).thenReturn(data);
        when(data.getActiveCharacter()).thenReturn(null);
        RpVoice.say(player, layout(VoiceLines.defaults()), "hit");
        chat.verifyNoInteractions();
    }

    @Test
    void blankConfigurationAndUnknownActionsNeverDispatchChat() {
        RpVoice.say(player, layout(new VoiceLines("rp", " ", null, null, null)), "hit");
        RpVoice.say(player, layout(new VoiceLines("rp", null, null, null, null)), "hit");
        RpVoice.say(player, layout(new VoiceLines("", "Hit.", null, null, null)), "hit");
        RpVoice.say(player, layout(new VoiceLines(null, "Hit.", null, null, null)), "hit");
        RpVoice.say(player, layout(VoiceLines.defaults()), "unknown");
        RpVoice.say(player, layout(VoiceLines.defaults()), " ");
        RpVoice.say(player, layout(VoiceLines.defaults()), null);
        RpVoice.say(null, layout(VoiceLines.defaults()), "hit");
        RpVoice.say(player, null, "hit");
        chat.verifyNoInteractions();
        channels.verifyNoInteractions();
    }

    @Test
    void integrationFailuresFallBackToAccountNameAndDoNotBreakGameplay() {
        identity.when(() -> DisplayIdentityService.resolveCharacterName(player))
                .thenThrow(new NoClassDefFoundError("incompatible identity API"));
        assertEquals("AccountName", RpNames.of(id));
        chat.when(() -> ChatManager.dispatch(player, channel, "Hit.", true))
                .thenThrow(new IllegalStateException("chat unavailable"));
        assertDoesNotThrow(() -> RpVoice.say(player, layout(VoiceLines.defaults()), "hit"));
        assertEquals("AccountName", RpNames.of(id));
        assertDoesNotThrow(() -> RpVoice.say(player, layout(VoiceLines.defaults()), "hit"));
        verify(logger, times(1)).warning("[Games] RPCharacters names skipped: incompatible identity API");
        verify(logger, times(1)).warning("[Games] RPCharacters voice skipped: chat unavailable");
    }

    private static TableLayout layout(VoiceLines voice) {
        return new TableLayout(null, null, null, 0, null, null, null, null, 0,
                false, false, 0, 0, 10, voice);
    }
}
