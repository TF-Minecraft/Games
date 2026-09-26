package net.tfminecraft.games;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {
    @TempDir Path directory;
    private Games previousPlugin;
    private Object previousConfig;
    private Field config;
    private Logger logger;

    @BeforeEach
    void saveState() throws Exception {
        previousPlugin = Games.plugin;
        config = Messages.class.getDeclaredField("config");
        config.setAccessible(true);
        previousConfig = config.get(null);
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
    }

    @AfterEach
    void restoreState() throws Exception {
        Games.plugin = previousPlugin;
        config.set(null, previousConfig);
    }

    @Test
    void messageTemplatesReplaceNamedValuesAndFormatLegacyAndHexColors() throws Exception {
        Path file = directory.resolve("messages.yml");
        Files.writeString(file, "greeting: '&aHello {name}, {n} coins. {optional}'\nhex: '#aabbccHi'\n");
        Messages.load(file.toFile());
        assertEquals("&aHello {name}, {n} coins. {optional}", Messages.getRaw("greeting"));
        assertEquals("§aHello Alice, 10 coins. ", Messages.get("greeting", "name", "Alice", "n", "10", "optional", null));
        assertEquals("§x§a§a§b§b§c§cHi", Messages.get("hex"));
        assertEquals("missing", Messages.get("missing"));
        assertEquals("§aHello Alice, {n} coins. {optional}", Messages.get("greeting", "name", "Alice", "unpaired"));
        verifyNoInteractions(logger);
    }

    @Test
    void reloadReplacesOldMessagesAndFailedLoadsReportFallbackKeys() throws Exception {
        Path file = directory.resolve("messages.yml");
        Files.writeString(file, "old: first\n");
        Messages.load(file.toFile());
        assertEquals("first", Messages.get("old"));
        Files.writeString(file, "new: second\n");
        Messages.load(file.toFile());
        assertEquals("old", Messages.get("old"));
        assertEquals("second", Messages.get("new"));
        Files.writeString(file, "bad: [\n");
        Messages.load(file.toFile());
        assertEquals("new", Messages.get("new"));
        verify(logger).severe(startsWith("[Games] Failed to load messages.yml:"));
        Messages.load(directory.resolve("missing.yml").toFile());
        assertEquals("fallback", Messages.getRaw("fallback"));
        verify(logger, times(2)).severe(startsWith("[Games] Failed to load messages.yml:"));
    }

    @Test
    void keysAreShownAsTheyAreBeforeMessagesAreLoaded() throws Exception {
        config.set(null, null);
        assertEquals("place.armed", Messages.getRaw("place.armed"));
        assertEquals("place.armed", Messages.get("place.armed", "name", "Alice"));
    }
}
