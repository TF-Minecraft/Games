package net.tfminecraft.games.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.*;

import net.tfminecraft.games.persistence.DurableFiles;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

class OwnershipMigrationTest {
    @TempDir Path root;
    UUID account = UUID.randomUUID(), character = UUID.randomUUID(), id = UUID.randomUUID();

    Path source() {
        return root.resolve("table.json");
    }

    JsonObject original() throws IOException {
        JsonObject row =
                RawJson.parse(
                        "{\"unknown\":{\"null\":null,\"array\":[1,null,{\"future\":true}]},\"ledger\":[],\"houseFloat\":0}");
        row.addProperty("id", id.toString());
        row.addProperty("ownerPlayer", account.toString());
        return row;
    }

    OwnershipMigration migration(java.util.function.Function<UUID, Optional<UUID>> heir) {
        return new OwnershipMigration(root.resolve("migrations"), () -> true, heir);
    }

    @Test
    void legacyEffectiveModeIsFrozenBeforePublicationWithoutRecapturingOwnerOrMoney()
            throws Exception {
        var layouts = net.tfminecraft.games.cache.Cache.tableLayouts;
        var previous =
                layouts.put(
                        "mode-fixture",
                        new net.tfminecraft.games.layout.TableLayout(
                                "cards", "fixture", "icon", 6, Map.of(), null, null, null, 0, false,
                                true, 5, 80, 10));
        try {
            var raw = original();
            raw.addProperty("gameId", "mode-fixture");
            raw.addProperty("ownerIdentityVersion", 2);
            raw.addProperty("ownerCharacterId", character.toString());
            raw.addProperty("houseFloat", 17);
            String before = RawJson.JSON.toJson(raw);
            Files.writeString(source(), before);
            var mode =
                    new OwnershipMigration(
                            root.resolve("migrations"),
                            () -> false,
                            a -> {
                                throw new AssertionError("Existing character cannot be recaptured");
                            });
            try (var writer = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
                writer.when(() -> DurableFiles.write(eq(source()), anyString()))
                        .thenThrow(new IOException("mode publication cut"));
                assertThrows(IOException.class, () -> mode.prepare(source()));
            }
            layouts.put(
                    "mode-fixture",
                    new net.tfminecraft.games.layout.TableLayout(
                            "cards", "fixture", "icon", 6, Map.of(), null, null, null, 0));
            assertTrue(mode.prepare(source()));
            var expected = raw.deepCopy();
            expected.addProperty("staffMint", true);
            assertEquals(expected, RawJson.read(source()));
            try (var files = Files.walk(root.resolve("migrations"))) {
                assertEquals(
                        before,
                        Files.readString(
                                files.filter(path -> path.toString().endsWith(".original"))
                                        .findFirst()
                                        .orElseThrow()));
            }
            assertTrue(mode.prepare(source()));
            assertEquals(expected, RawJson.read(source()));
            assertEquals(OwnershipKind.STAFF, OwnershipKind.of(expected));
        } finally {
            if (previous == null) layouts.remove("mode-fixture");
            else layouts.put("mode-fixture", previous);
        }
    }

    @Test
    void reviewedIdentityChangesOnlyItsEnvelopeAndKeepsExactImmutableSource() throws Exception {
        String before = RawJson.JSON.toJson(original());
        Files.writeString(source(), before);
        assertTrue(migration(a -> Optional.of(character)).prepare(source()));
        JsonObject after = RawJson.read(source());
        assertEquals(character, RawJson.uuid(after.get("ownerCharacterId")));
        assertEquals(account, RawJson.uuid(after.get("ownerPlayer")));
        assertEquals(original().get("unknown"), after.get("unknown"));
        assertTrue(after.getAsJsonObject("unknown").has("null"));
        try (var files = Files.walk(root.resolve("migrations"))) {
            Path archive =
                    files.filter(p -> p.toString().endsWith(".original")).findFirst().orElseThrow();
            assertEquals(before, Files.readString(archive));
        }
        assertTrue(migration(a -> Optional.of(UUID.randomUUID())).prepare(source()));
        assertEquals(after, RawJson.read(source()));
    }

    @Test
    void missingHeirRetainsAccountThenLaterResolutionPreservesInterveningGameplay()
            throws Exception {
        Files.writeString(source(), RawJson.JSON.toJson(original()));
        assertTrue(migration(a -> Optional.empty()).prepare(source()));
        var held = RawJson.read(source());
        assertTrue(held.get("ownerCharacterId").isJsonNull());
        held.addProperty("ordinaryProgress", 77);
        Files.writeString(source(), RawJson.JSON.toJson(held));
        assertTrue(migration(a -> Optional.of(character)).prepare(source()));
        assertEquals(77, RawJson.read(source()).get("ordinaryProgress").getAsInt());
        assertEquals(character, RawJson.uuid(RawJson.read(source()).get("ownerCharacterId")));
    }

    @Test
    void privateLegacyWaitsWithoutWritingWhileGuildAndStaffAuthorityRemainSeparate()
            throws Exception {
        var migration =
                new OwnershipMigration(
                        root.resolve("migrations"), () -> false, a -> Optional.of(character));
        var raw = original();
        Files.writeString(source(), RawJson.JSON.toJson(raw));
        String before = Files.readString(source());
        assertFalse(migration.prepare(source()));
        assertEquals(before, Files.readString(source()));
        raw.addProperty("staffMint", true);
        Files.writeString(source(), RawJson.JSON.toJson(raw));
        assertTrue(migration.prepare(source()));
        assertEquals(OwnershipKind.STAFF, OwnershipKind.of(RawJson.read(source())));
        raw.addProperty("ownerGuildId", "guild");
        Files.writeString(source(), RawJson.JSON.toJson(raw));
        assertTrue(migration.prepare(source()));
        assertEquals(OwnershipKind.GUILD, OwnershipKind.of(RawJson.read(source())));
    }

    @Test
    void frozenRecipientReplaysAfterPublicationCutsWithoutResolvingAgain() throws Exception {
        Files.writeString(source(), RawJson.JSON.toJson(original()));
        try (var writer = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            writer.when(() -> DurableFiles.write(eq(source()), anyString()))
                    .thenThrow(new IOException("before main publication"));
            assertThrows(
                    IOException.class,
                    () -> migration(a -> Optional.of(character)).prepare(source()));
        }
        assertTrue(
                migration(
                                a -> {
                                    throw new AssertionError(
                                            "Frozen heir must not be resolved again");
                                })
                        .prepare(source()));
        assertEquals(character, RawJson.uuid(RawJson.read(source()).get("ownerCharacterId")));
    }

    @Test
    void changedSourceAfterFrozenPlanningIsHeldAndNeverOverwritten() throws Exception {
        Files.writeString(source(), RawJson.JSON.toJson(original()));
        try (var writer = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            writer.when(() -> DurableFiles.write(eq(source()), anyString()))
                    .thenThrow(new IOException("cut"));
            assertThrows(
                    IOException.class,
                    () -> migration(a -> Optional.of(character)).prepare(source()));
        }
        var changed = original();
        changed.addProperty("progress", 55);
        String current = RawJson.JSON.toJson(changed);
        Files.writeString(source(), current);
        assertThrows(
                IOException.class, () -> migration(a -> Optional.of(character)).prepare(source()));
        assertEquals(current, Files.readString(source()));
    }

    @Test
    void schemaAndDuplicateKeysRejectWithoutChangingBytes() throws Exception {
        for (JsonElement version :
                List.of(
                        new JsonPrimitive("2"),
                        new JsonPrimitive(1.5),
                        new JsonPrimitive(3),
                        JsonNull.INSTANCE)) {
            var row = original();
            row.add("ownerIdentityVersion", version);
            String bytes = RawJson.JSON.toJson(row);
            Files.writeString(source(), bytes);
            assertThrows(
                    IOException.class,
                    () -> migration(a -> Optional.of(character)).prepare(source()));
            assertEquals(bytes, Files.readString(source()));
        }
        String duplicate =
                "{\"ownerPlayer\":\"" + account + "\",\"unknown\":{\"null\":null,\"null\":7}}";
        Files.writeString(source(), duplicate);
        assertThrows(
                IOException.class, () -> migration(a -> Optional.of(character)).prepare(source()));
        assertEquals(duplicate, Files.readString(source()));
        var invalid = original();
        invalid.addProperty("ownerPlayer", "bad");
        Files.writeString(source(), RawJson.JSON.toJson(invalid));
        assertThrows(
                IOException.class, () -> migration(a -> Optional.of(character)).prepare(source()));
    }

    @Test
    void completedArchiveStillRequiresIntegrityBeforeAnyLaterGameplaySave() throws Exception {
        Files.writeString(source(), RawJson.JSON.toJson(original()));
        assertTrue(migration(a -> Optional.of(character)).prepare(source()));
        String current = Files.readString(source());
        try (var files = Files.walk(root.resolve("migrations"))) {
            Files.writeString(
                    files.filter(p -> p.toString().endsWith(".original")).findFirst().orElseThrow(),
                    "corrupt archive");
        }
        assertThrows(
                IOException.class, () -> migration(a -> Optional.of(character)).prepare(source()));
        assertEquals(current, Files.readString(source()));
    }

    @Test
    void invalidGuildAndFloatOwnershipNeverPublishOrNormalizeTheirSource() throws Exception {
        for (String key : List.of("ownerGuildIncarnation", "floatOwner")) {
            var row = original();
            row.addProperty(key, "invalid-owner");
            String bytes = RawJson.JSON.toJson(row);
            Files.writeString(source(), bytes);
            assertThrows(
                    IOException.class,
                    () -> migration(a -> Optional.of(character)).prepare(source()));
            assertEquals(bytes, Files.readString(source()));
        }
        var ambiguous = original();
        ambiguous.addProperty("ownerGuildIncarnation", UUID.randomUUID().toString());
        String bytes = RawJson.JSON.toJson(ambiguous);
        Files.writeString(source(), bytes);
        assertThrows(
                IOException.class, () -> migration(a -> Optional.of(character)).prepare(source()));
        assertEquals(bytes, Files.readString(source()));
    }
}
