package net.tfminecraft.games.guild;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuildTableMigrationTest {
    @TempDir Path data;
    private Path source() throws Exception {
        Path source = data.resolve("table.json");
        Files.writeString(source, "{\"ownerGuildId\":\"guild\",\"houseFloat\":17}");
        return source;
    }
    private static GuildTables.Lookup found(UUID incarnation) {
        return new GuildTables.Lookup(incarnation, false);
    }
    @Test void waitsWithoutBackupOrMutationUntilFactionMigrationIsReady() throws Exception {
        Path table = source();
        String before = Files.readString(table);
        try (var guilds = mockStatic(GuildTables.class)) {
            assertFalse(GuildTableMigration.prepare(table, data.resolve("migration")));
            assertEquals(before, Files.readString(table));
            assertFalse(Files.exists(data.resolve("migration")));
            guilds.verify(() -> GuildTables.resolveIncarnation(anyString()), never());
        }
    }
    @Test void failedLookupStampsNothingAndALaterPassStillMigrates() throws Exception {
        Path table = source();
        String before = Files.readString(table);
        UUID incarnation = UUID.randomUUID();
        try (var guilds = mockStatic(GuildTables.class)) {
            guilds.when(GuildTables::ready).thenReturn(true);
            guilds.when(() -> GuildTables.resolveIncarnation("guild"))
                    .thenReturn(new GuildTables.Lookup(null, true));
            assertFalse(GuildTableMigration.prepare(table, data.resolve("migration")));
            assertEquals(before, Files.readString(table));
            assertFalse(Files.exists(data.resolve("migration")), "a transient failure writes no manifest");
            guilds.when(() -> GuildTables.resolveIncarnation("guild")).thenReturn(found(incarnation));
            assertTrue(GuildTableMigration.prepare(table, data.resolve("migration")));
            assertTrue(Files.readString(table).contains(incarnation.toString()));
        }
    }
    @Test void recordsImmutableBackupAndNeverAdoptsAReplacementGuild() throws Exception {
        Path table = source();
        String before = Files.readString(table);
        UUID incarnation = UUID.randomUUID();
        try (var guilds = mockStatic(GuildTables.class)) {
            guilds.when(GuildTables::ready).thenReturn(true);
            guilds.when(() -> GuildTables.resolveIncarnation("guild")).thenReturn(found(incarnation));
            assertTrue(GuildTableMigration.prepare(table, data.resolve("migration")));
            String migrated = Files.readString(table);
            assertEquals(before, Files.readString(data.resolve("migration/backup/table.json")));
            assertTrue(migrated.contains(incarnation.toString()));
            assertEquals(17, JsonParser.parseString(migrated).getAsJsonObject().get("houseFloat").getAsInt());
            guilds.when(() -> GuildTables.resolveIncarnation("guild")).thenReturn(found(UUID.randomUUID()));
            assertTrue(GuildTableMigration.prepare(table, data.resolve("migration")));
            assertEquals(migrated, Files.readString(table));
            guilds.verify(() -> GuildTables.resolveIncarnation("guild"), times(1));
        }
    }
    @Test void interruptedPlanReplaysOriginalIncarnationAndNeverResolvesAgain() throws Exception {
        Path table = source();
        String before = Files.readString(table);
        UUID incarnation = UUID.randomUUID();
        try (var guilds = mockStatic(GuildTables.class)) {
            guilds.when(GuildTables::ready).thenReturn(true);
            guilds.when(() -> GuildTables.resolveIncarnation("guild")).thenReturn(found(incarnation));
            assertTrue(GuildTableMigration.prepare(table, data.resolve("migration")));
            String migrated = Files.readString(table);
            Files.writeString(table, before);
            guilds.when(() -> GuildTables.resolveIncarnation("guild")).thenReturn(found(UUID.randomUUID()));
            assertTrue(GuildTableMigration.prepare(table, data.resolve("migration")));
            assertEquals(migrated, Files.readString(table));
            guilds.verify(() -> GuildTables.resolveIncarnation("guild"), times(1));
        }
    }
    @Test void missingGuildIsPermanentlyUnresolvedAndPersonalTablesNeedNoMigration() throws Exception {
        Path table = source();
        try (var guilds = mockStatic(GuildTables.class)) {
            guilds.when(GuildTables::ready).thenReturn(true);
            guilds.when(() -> GuildTables.resolveIncarnation("guild"))
                    .thenReturn(new GuildTables.Lookup(null, false));
            assertTrue(GuildTableMigration.prepare(table, data.resolve("migration")));
            var migrated = JsonParser.parseString(Files.readString(table)).getAsJsonObject();
            assertTrue(migrated.get("ownerGuildMigrated").getAsBoolean());
            assertFalse(migrated.has("ownerGuildIncarnation"));
            guilds.when(() -> GuildTables.resolveIncarnation("guild")).thenReturn(found(UUID.randomUUID()));
            assertTrue(GuildTableMigration.prepare(table, data.resolve("migration")));
            assertEquals(migrated, JsonParser.parseString(Files.readString(table)));
            guilds.verify(() -> GuildTables.resolveIncarnation("guild"), times(1));
            Files.writeString(table, "{}");
            guilds.when(GuildTables::ready).thenReturn(false);
            assertTrue(GuildTableMigration.prepare(table, data.resolve("unused")));
            assertFalse(Files.exists(data.resolve("unused")));
        }
    }
}
