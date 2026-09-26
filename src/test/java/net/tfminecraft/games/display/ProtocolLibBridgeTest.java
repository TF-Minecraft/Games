package net.tfminecraft.games.display;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.logging.Logger;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ProtocolLibBridgeTest {
    private Plugin plugin;
    private Logger logger;
    private PluginManager plugins;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<ProtocolLibrary> protocol;

    @BeforeEach
    void setUp() {
        ProtocolLibBridge.shutdown();
        plugin = mock(Plugin.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        plugins = mock(PluginManager.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        protocol = mockStatic(ProtocolLibrary.class);
    }

    @AfterEach
    void tearDown() {
        ProtocolLibBridge.shutdown();
        protocol.close();
        bukkit.close();
    }

    @Test
    void absentProtocolLibDisablesPacketsAndExplainsWhy() {
        ProtocolLibBridge.init(plugin);
        assertFalse(ProtocolLibBridge.isReady());
        assertNull(ProtocolLibBridge.getPackets());
        verify(logger).warning("[Games] ProtocolLib not found - display engine disabled.");
        protocol.verifyNoInteractions();
    }

    @Test
    void installedProtocolLibEnablesPacketsUntilShutdown() {
        when(plugins.getPlugin("ProtocolLib")).thenReturn(mock(Plugin.class));
        protocol.when(ProtocolLibrary::getProtocolManager).thenReturn(mock(ProtocolManager.class));
        ProtocolLibBridge.init(plugin);
        assertTrue(ProtocolLibBridge.isReady());
        assertNotNull(ProtocolLibBridge.getPackets());
        verify(logger).info("[Games] ProtocolLib detected - packet ItemDisplays enabled.");
        ProtocolLibBridge.shutdown();
        assertFalse(ProtocolLibBridge.isReady());
        assertNull(ProtocolLibBridge.getPackets());
    }

    @Test
    void failedReinitializationClearsPreviouslyAvailablePackets() {
        when(plugins.getPlugin("ProtocolLib")).thenReturn(mock(Plugin.class));
        protocol.when(ProtocolLibrary::getProtocolManager).thenReturn(mock(ProtocolManager.class));
        ProtocolLibBridge.init(plugin);
        assertTrue(ProtocolLibBridge.isReady());
        protocol.when(ProtocolLibrary::getProtocolManager).thenThrow(new IllegalStateException("manager unavailable"));
        assertDoesNotThrow(() -> ProtocolLibBridge.init(plugin));
        assertFalse(ProtocolLibBridge.isReady());
        assertNull(ProtocolLibBridge.getPackets());
        verify(logger).warning("[Games] Failed to initialize ProtocolLib: manager unavailable");
    }

    @Test
    void PluginRemovalDuringReloadDisablesPreviouslyAvailablePackets() {
        when(plugins.getPlugin("ProtocolLib")).thenReturn(mock(Plugin.class));
        protocol.when(ProtocolLibrary::getProtocolManager).thenReturn(mock(ProtocolManager.class));
        ProtocolLibBridge.init(plugin);
        when(plugins.getPlugin("ProtocolLib")).thenReturn(null);
        ProtocolLibBridge.init(plugin);
        assertFalse(ProtocolLibBridge.isReady());
        assertNull(ProtocolLibBridge.getPackets());
    }
}
