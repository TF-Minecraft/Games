package net.tfminecraft.games;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.command.CommandManager;
import net.tfminecraft.games.command.WagerCommand;
import net.tfminecraft.games.display.DisplayManager;
import net.tfminecraft.games.display.ProtocolLibBridge;
import net.tfminecraft.games.loader.CardLoader;
import net.tfminecraft.games.table.TableManager;

class GamesLifecycleTest {
    @Test
    void firstEnableRecreatesMissingPluginDataDirectoryAndInstallsAllDefaults() throws Exception {
        withLoadedPlugin(true, (plugin, manager, records) -> {
            var folder = plugin.getDataFolder().toPath();
            Files.deleteIfExists(folder);
            assertFalse(Files.exists(folder));
            MockBukkit.getMock().getPluginManager().enablePlugin(plugin);
            assertTrue(plugin.isEnabled());
            assertTrue(Files.isDirectory(folder.resolve("Data/tables")));
            for (String name : List.of("config.yml", "messages.yml", "cards.yml", "games.yml", "help.yml")) {
                assertTrue(Files.size(folder.resolve(name)) > 0, name);
            }
            verify(manager).loadAll();
            verify(manager).startClock();
        });
    }

    @Test
    void existingConfigurationSurvivesStartupAndDebugReportsItsEffectiveSettings() throws Exception {
        withLoadedPlugin(true, (plugin, manager, records) -> {
            var config = plugin.getDataFolder().toPath().resolve("config.yml");
            Files.createDirectories(config.getParent());
            String custom = "debug: true\ncard-scale: 0.37\n";
            Files.writeString(config, custom);
            MockBukkit.getMock().getPluginManager().enablePlugin(plugin);
            assertEquals(custom, Files.readString(config));
            assertEquals(0.37f, Cache.cardScale);
            assertTrue(records.stream().map(LogRecord::getMessage)
                    .anyMatch(message -> message.startsWith("[Games] Debug:") && message.contains("card-scale=0.37")));
            assertInstanceOf(CommandManager.class, plugin.getCommand("games").getExecutor());
            assertInstanceOf(WagerCommand.class, plugin.getCommand("wager").getExecutor());
            verify(manager).startClock();
        });
    }

    @Test
    void malformedStartupConfigurationIsReportedWithoutOverwritingItOrDisablingCommands() throws Exception {
        withLoadedPlugin(true, (plugin, manager, records) -> {
            var config = plugin.getDataFolder().toPath().resolve("config.yml");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "broken: [\n");
            MockBukkit.getMock().getPluginManager().enablePlugin(plugin);
            assertTrue(plugin.isEnabled());
            assertEquals("broken: [\n", Files.readString(config));
            assertTrue(records.stream().map(LogRecord::getMessage)
                    .anyMatch("Games loaded with config errors."::equals));
            assertInstanceOf(CommandManager.class, plugin.getCommand("games").getExecutor());
            assertInstanceOf(WagerCommand.class, plugin.getCommand("wager").getExecutor());
            verify(manager).loadAll();
            verify(manager).startClock();
        });
    }

    @Test
    void restartKeepsSavedTablesAndExistingDefaults() throws Exception {
        withLoadedPlugin(true, (plugin, manager, records) -> {
            var folder = plugin.getDataFolder().toPath();
            var saved = folder.resolve("Data/tables/table.json");
            Files.createDirectories(saved.getParent());
            Files.writeString(saved, "{}");
            MockBukkit.getMock().getPluginManager().enablePlugin(plugin);
            assertEquals("{}", Files.readString(saved));
            assertTrue(Files.size(folder.resolve("messages.yml")) > 0);
        });
    }

    @Test
    void dataFolderBlockedByAFileReportsEachDefaultThatCannotBeInstalled() throws Exception {
        withLoadedPlugin(true, (plugin, manager, records) -> {
            var folder = plugin.getDataFolder().toPath();
            Files.createDirectories(folder.getParent());
            Files.deleteIfExists(folder);
            Files.writeString(folder, "not a directory");
            try {
                MockBukkit.getMock().getPluginManager().enablePlugin(plugin);
                assertTrue(plugin.isEnabled());
                List<String> severe = records.stream()
                        .filter(record -> record.getLevel() == java.util.logging.Level.SEVERE)
                        .map(LogRecord::getMessage).toList();
                for (String name : List.of("config.yml", "messages.yml", "cards.yml", "games.yml", "help.yml")) {
                    assertTrue(severe.stream().anyMatch(message ->
                            message.startsWith("Failed to copy default resource " + name + ": ")), name);
                }
                assertTrue(records.stream().map(LogRecord::getMessage)
                        .anyMatch("Games loaded with config errors."::equals));
                verify(manager).loadAll();
            } finally {
                Files.deleteIfExists(folder);
            }
        });
    }

    @Test
    void incompletePluginDescriptorReportsBothMissingCommandsAndStillStartsTableServices() throws Exception {
        withLoadedPlugin(false, (plugin, manager, records) -> {
            MockBukkit.getMock().getPluginManager().enablePlugin(plugin);
            assertTrue(plugin.isEnabled());
            assertNull(plugin.getCommand("games"));
            assertNull(plugin.getCommand("wager"));
            List<String> messages = records.stream().map(LogRecord::getMessage).toList();
            assertTrue(messages.contains("Command 'games' missing from plugin.yml"));
            assertTrue(messages.contains("Command 'wager' missing from plugin.yml"));
            verify(manager).loadAll();
            verify(manager).startClock();
        });
    }

    @Test
    void startupInstallsDefaultsRegistersCommandsAndOnlySuccessfulReloadRefreshesTables() throws Exception {
        Map<Field, Object> saved = saveStaticState(Cache.class, CardLoader.class, Messages.class, net.tfminecraft.games.wager.WagerEngine.class);
        Games previous = Games.plugin;
        TableManager manager = mock(TableManager.class);
        DisplayManager display = mock(DisplayManager.class);
        Games plugin = null;
        try (MockedStatic<TableManager> tables = mockStatic(TableManager.class);
                MockedStatic<DisplayManager> displays = mockStatic(DisplayManager.class);
                MockedStatic<ProtocolLibBridge> protocol = mockStatic(ProtocolLibBridge.class)) {
            tables.when(TableManager::get).thenReturn(manager);
            displays.when(DisplayManager::get).thenReturn(display);
            try {
                plugin = MockBukkit.load(Games.class);
                assertSame(plugin, Games.plugin);
                assertTrue(plugin.isEnabled());
                for (String filename : List.of("config.yml", "messages.yml", "cards.yml", "games.yml", "help.yml")) {
                    assertTrue(Files.size(plugin.getDataFolder().toPath().resolve(filename)) > 0, filename);
                }
                assertTrue(Files.isDirectory(plugin.getDataFolder().toPath().resolve("Data/tables")));
                assertInstanceOf(CommandManager.class, plugin.getCommand("games").getExecutor());
                assertInstanceOf(WagerCommand.class, plugin.getCommand("wager").getExecutor());
                assertSame(plugin.getCommand("games").getExecutor(), plugin.getCommand("games").getTabCompleter());
                assertSame(plugin.getCommand("wager").getExecutor(), plugin.getCommand("wager").getTabCompleter());
                verify(manager).loadAll();
                verify(manager).startClock();
                protocol.verify(() -> ProtocolLibBridge.init(Games.plugin));
                assertTrue(CardLoader.cardCount() > 0);
                assertFalse(Cache.helpBooks.isEmpty());
                assertTrue(plugin.reloadAll());
                verify(manager).wipeHands();
                verify(manager).redrawAllChips();
                var config = plugin.getDataFolder().toPath().resolve("config.yml");
                Files.writeString(config, "bad: [\n");
                assertFalse(plugin.reloadAll());
                verify(manager, times(1)).wipeHands();
                verify(manager, times(1)).redrawAllChips();
            } finally {
                try {
                    if (plugin != null) {
                        MockBukkit.getMock().getPluginManager().disablePlugin(plugin);
                        verify(manager).stopClock();
                        verify(manager).despawnWorldAll();
                        verify(display).shutdown();
                        protocol.verify(ProtocolLibBridge::shutdown);
                    }
                } finally {
                    try {
                        restoreStaticState(saved);
                    } finally {
                        Games.plugin = previous;
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface StartupScenario {
        void run(Games plugin, TableManager manager, List<LogRecord> records) throws Exception;
    }

    /** Load without enabling so scenarios can prepare real startup files and descriptors. */
    private void withLoadedPlugin(boolean commands, StartupScenario scenario) throws Exception {
        Map<Field, Object> saved = saveStaticState(Cache.class, CardLoader.class, Messages.class,
                net.tfminecraft.games.wager.WagerEngine.class);
        Games previous = Games.plugin;
        Games loaded = null;
        TableManager manager = mock(TableManager.class);
        DisplayManager display = mock(DisplayManager.class);
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        try (MockedStatic<TableManager> tables = mockStatic(TableManager.class);
                MockedStatic<DisplayManager> displays = mockStatic(DisplayManager.class);
                MockedStatic<ProtocolLibBridge> protocol = mockStatic(ProtocolLibBridge.class)) {
            tables.when(TableManager::get).thenReturn(manager);
            displays.when(DisplayManager::get).thenReturn(display);
            try {
                String yaml = "name: GamesStartup" + UUID.randomUUID().toString().replace("-", "")
                        + "\nversion: test\nmain: " + Games.class.getName() + "\napi-version: '1.21.10'\n"
                        + (commands ? "commands:\n  games: {}\n  wager: {}\n" : "");
                PluginDescriptionFile description = new PluginDescriptionFile(new StringReader(yaml));
                loaded = (Games) MockBukkit.getMock().getPluginManager()
                        .loadPlugin(Games.class, description, new Object[0]);
                loaded.getLogger().addHandler(capture);
                scenario.run(loaded, manager, records);
            } finally {
                Games current = loaded;
                assertAll("Restore lifecycle fixture",
                        () -> {
                            if (current != null) {
                                try { MockBukkit.getMock().getPluginManager().disablePlugin(current); }
                                finally { current.getLogger().removeHandler(capture); }
                            }
                        },
                        () -> restoreStaticState(saved),
                        () -> Games.plugin = previous);
            }
        }
    }

    /** Preserve process-wide configuration; this does not inject test scenarios. */
    private static Map<Field, Object> saveStaticState(Class<?>... types) throws Exception {
        Map<Field, Object> saved = new LinkedHashMap<>();
        for (Class<?> type : types) for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Map<?, ?> map) saved.put(field, new LinkedHashMap<>(map));
            else if (value instanceof List<?> list) saved.put(field, new ArrayList<>(list));
            else if (!Modifier.isFinal(field.getModifiers())) saved.put(field, value);
        }
        return saved;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restoreStaticState(Map<Field, Object> saved) throws Exception {
        for (var entry : saved.entrySet()) {
            Object current = entry.getKey().get(null);
            if (current instanceof Map map) { map.clear(); map.putAll((Map) entry.getValue()); }
            else if (current instanceof List list) { list.clear(); list.addAll((List) entry.getValue()); }
            else entry.getKey().set(null, entry.getValue());
        }
    }
}
