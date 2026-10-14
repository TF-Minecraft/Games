package net.tfminecraft.games.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.*;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.table.*;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class TableEstateTest {
    @TempDir Path data;
    Games plugin, previous;
    TableManager manager;
    TableOwnershipStore store;
    TableEstate estate;
    MockedStatic<CharacterAccess> characters;
    CharacterRef from = new CharacterRef(UUID.randomUUID(), UUID.randomUUID()),
            to = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
    UUID death = UUID.randomUUID(), tableId = UUID.randomUUID();
    Set<UUID> pending = new HashSet<>();

    @BeforeEach
    void prepare() {
        previous = Games.plugin;
        plugin = mock(Games.class);
        Games.plugin = plugin;
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
        manager = mock(TableManager.class);
        store = new TableOwnershipStore(data);
        when(manager.ownershipStore()).thenReturn(store);
        when(manager.tables()).thenReturn(List.of());
        doAnswer(
                        call -> {
                            Collection<UUID> ids = call.getArgument(0);
                            if (call.getArgument(1)) pending.addAll(ids);
                            else pending.removeAll(ids);
                            return null;
                        })
                .when(manager)
                .estatePending(anyCollection(), anyBoolean());
        characters = mockStatic(CharacterAccess.class, CALLS_REAL_METHODS);
        characters.when(CharacterAccess::heirReady).thenReturn(true);
        estate = new TableEstate(plugin, manager);
        estate.guard = d -> true;
    }

    @AfterEach
    void finish() {
        characters.close();
        Games.plugin = previous;
    }

    EstateDecision decision(long revision, Recipient property) {
        return new EstateDecision(
                death,
                from,
                Optional.of(Instant.EPOCH),
                Instant.EPOCH,
                Optional.of(to),
                Optional.empty(),
                Optional.empty(),
                Recipient.FROZEN,
                property,
                Recipient.FROZEN,
                Recipient.SENDER,
                revision);
    }

    JsonObject raw(UUID id) throws Exception {
        JsonObject row =
                RawJson.parse(
                        "{\"ownerIdentityVersion\":2,\"unknown\":{\"nullable\":null},\"world\":\"unloaded\",\"ledger\":[],\"piles\":[],\"houseFloat\":0,\"actives\":[],\"autoDealer\":false,\"staffMint\":false}");
        row.addProperty("id", id.toString());
        row.addProperty("ownerPlayer", from.ownerId().toString());
        row.addProperty("ownerCharacterId", from.characterId().toString());
        return row;
    }

    Path write(JsonObject row) throws Exception {
        Path path = data.resolve("Data/tables/" + row.get("id").getAsString() + ".json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, RawJson.JSON.toJson(row));
        return path;
    }

    Path journal(long revision) {
        return data.resolve("estates/tables/" + death + "-" + revision + ".json");
    }

    @Test
    void unloadedPrivatePropertyMovesOnceWithUnknownNullsAndWagerRefsPreserved() throws Exception {
        JsonObject before = raw(tableId), refs = new JsonObject();
        refs.addProperty(from.ownerId().toString(), from.characterId().toString());
        before.add("wagerCharacters", refs);
        Path file = write(before);
        var first = decision(1, Recipient.character(to));
        Outcome result = estate.settle(first);
        assertInstanceOf(Outcome.Done.class, result);
        JsonObject expected = before.deepCopy();
        expected.addProperty("ownerPlayer", to.ownerId().toString());
        expected.addProperty("ownerCharacterId", to.characterId().toString());
        assertEquals(expected, RawJson.read(file));
        assertTrue(pending.isEmpty());
        assertEquals(result, estate.settle(first));
        var second =
                decision(
                        2,
                        Recipient.character(
                                new CharacterRef(UUID.randomUUID(), UUID.randomUUID())));
        assertEquals(result, estate.settle(second));
        estate = new TableEstate(plugin, manager);
        estate.guard = d -> true;
        estate.recover();
        assertEquals(result, estate.settle(second));
        assertEquals(Holdings.NONE, estate.describe(from.characterId()));
    }

    @Test
    void guildMetadataAndStaffMintRemainOutsidePrivateInheritanceAcrossAllModes() throws Exception {
        JsonObject guild = raw(UUID.randomUUID());
        guild.addProperty("ownerGuildId", "guild");
        guild.addProperty("staffMint", true);
        guild.addProperty("ownerGuildIncarnation", UUID.randomUUID().toString());
        JsonObject mint = raw(UUID.randomUUID());
        mint.addProperty("staffMint", true);
        JsonObject auto = raw(tableId);
        auto.addProperty("autoDealer", true);
        Path guildFile = write(guild), mintFile = write(mint), privateFile = write(auto);
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1, Recipient.character(to))));
        assertEquals(guild, RawJson.read(guildFile));
        assertEquals(mint, RawJson.read(mintFile));
        assertEquals(
                to.characterId(), RawJson.uuid(RawJson.read(privateFile).get("ownerCharacterId")));
    }

    @Test
    void providerReadinessAndUnresolvedSlotsCannotCreateAnEmptyReceipt() throws Exception {
        var row = raw(tableId);
        row.add("ownerCharacterId", JsonNull.INSTANCE);
        write(row);
        characters.when(CharacterAccess::heirReady).thenReturn(false);
        assertInstanceOf(Outcome.Retry.class, estate.settle(decision(1, Recipient.character(to))));
        assertFalse(Files.exists(journal(1)));
        characters.when(CharacterAccess::heirReady).thenReturn(true);
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(to))));
        assertFalse(Files.exists(journal(1)));
        row.addProperty("ownerCharacterId", from.characterId().toString());
        write(row);
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1, Recipient.character(to))));
    }

    @Test
    void completedRestoredSourceBlocksCharacterWaitingAndFrozenRevisionsAcrossRestart()
            throws Exception {
        Path file = write(raw(tableId));
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1, Recipient.character(to))));
        Files.writeString(file, RawJson.JSON.toJson(raw(tableId)));
        String source = Files.readString(file), receipt = Files.readString(journal(1));
        for (Recipient property :
                List.of(Recipient.character(to), Recipient.WAITING, Recipient.FROZEN)) {
            estate = new TableEstate(plugin, manager);
            estate.guard = d -> true;
            estate.recover();
            assertInstanceOf(Outcome.Blocked.class, estate.settle(decision(2, property)));
            assertEquals(source, Files.readString(file));
            assertEquals(receipt, Files.readString(journal(1)));
            assertFalse(Files.exists(journal(2)));
        }
    }

    @Test
    void unsupportedAccountSenderAndSelfRecipientsBlockWithoutFictitiousMovesOrIntent()
            throws Exception {
        Path file = write(raw(tableId));
        String source = Files.readString(file);
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(from))));
        for (Recipient property :
                List.of(
                        Recipient.account("char:" + to.characterId() + ":bank"),
                        Recipient.SENDER)) {
            EstateDecision invalid = mock(EstateDecision.class);
            when(invalid.property()).thenReturn(property);
            when(invalid.testator()).thenReturn(from);
            assertInstanceOf(Outcome.Blocked.class, estate.settle(invalid));
            assertEquals(source, Files.readString(file));
            assertFalse(Files.exists(journal(1)));
        }
    }

    @Test
    void exactCurrentReceiptDecisionIsCheckedBeforeWaitingFrozenOrSelfReturns() throws Exception {
        write(raw(tableId));
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1, Recipient.character(to))));
        String receipt = Files.readString(journal(1));
        for (Recipient property :
                List.of(Recipient.WAITING, Recipient.FROZEN, Recipient.character(from))) {
            assertInstanceOf(Outcome.Blocked.class, estate.settle(decision(1, property)));
            assertEquals(receipt, Files.readString(journal(1)));
        }
    }

    @Test
    void futureRevisionEvidenceBlocksBeforeObsoletingPlansOrChangingOwnership() throws Exception {
        Path file = write(raw(tableId));
        Table live = mock(Table.class);
        when(live.live()).thenReturn(true);
        when(manager.table(tableId)).thenReturn(live);
        assertInstanceOf(Outcome.Retry.class, estate.settle(decision(2, Recipient.character(to))));
        String intent = Files.readString(journal(2)), source = Files.readString(file);
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(to))));
        assertEquals(intent, Files.readString(journal(2)));
        assertEquals(source, Files.readString(file));
        assertFalse(Files.exists(journal(1)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CHARACTER", "WAITING", "FROZEN"})
    void emptyNoncharacterCompletionBlocksSameRevisionGrowthAndAcceptsUntouchedNewerProperty(
            String kind) throws Exception {
        Recipient property =
                kind.equals("CHARACTER")
                        ? Recipient.character(to)
                        : kind.equals("WAITING") ? Recipient.WAITING : Recipient.FROZEN;
        var first = decision(1, property);
        assertEquals(Outcome.done(Map.of("private-tables", 0), List.of()), estate.settle(first));
        String receipt = Files.readString(journal(1));
        var envelope = RawJson.read(journal(1));
        assertEquals(
                kind,
                envelope.has("propertyKind")
                        ? RawJson.string(envelope, "propertyKind")
                        : "CHARACTER");
        estate = new TableEstate(plugin, manager);
        estate.guard = d -> true;
        estate.recover();
        assertEquals(Outcome.done(Map.of("private-tables", 0), List.of()), estate.settle(first));
        assertEquals(receipt, Files.readString(journal(1)));
        assertInstanceOf(
                Outcome.Blocked.class,
                estate.settle(
                        decision(
                                1,
                                Recipient.character(
                                        new CharacterRef(UUID.randomUUID(), UUID.randomUUID())))));
        assertEquals(receipt, Files.readString(journal(1)));
        Path file = write(raw(tableId));
        String source = Files.readString(file);
        assertInstanceOf(Outcome.Blocked.class, estate.settle(first));
        assertEquals(source, Files.readString(file));
        assertEquals(receipt, Files.readString(journal(1)));
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(2, Recipient.character(to))));
        assertEquals(to.characterId(), RawJson.uuid(RawJson.read(file).get("ownerCharacterId")));
    }

    @Test
    void newlyResolvedOwnershipRequiresANewerRevisionAfterEmptyCompletion() throws Exception {
        JsonObject row = raw(tableId);
        row.addProperty("ownerCharacterId", UUID.randomUUID().toString());
        Path file = write(row);
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1, Recipient.FROZEN)));
        String receipt = Files.readString(journal(1));
        row.addProperty("ownerCharacterId", from.characterId().toString());
        write(row);
        String source = Files.readString(file);
        estate = new TableEstate(plugin, manager);
        estate.guard = d -> true;
        estate.recover();
        assertInstanceOf(Outcome.Blocked.class, estate.settle(decision(1, Recipient.FROZEN)));
        assertEquals(source, Files.readString(file));
        assertEquals(receipt, Files.readString(journal(1)));
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(2, Recipient.character(to))));
        assertEquals(to.characterId(), RawJson.uuid(RawJson.read(file).get("ownerCharacterId")));
    }

    @Test
    void untouchedRetainedPropertyTransfersOnlyOnNewRevisionWithCumulativeCompletionEvidence()
            throws Exception {
        Path file = write(raw(tableId));
        String source = Files.readString(file);
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1, Recipient.WAITING)));
        assertEquals(source, Files.readString(file));
        var held = RawJson.read(journal(1));
        assertEquals(
                tableId,
                RawJson.uuid(held.getAsJsonArray("retained").get(0).getAsJsonObject().get("id")));
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(to))));
        assertEquals(source, Files.readString(file));
        assertEquals(
                Outcome.done(Map.of("private-tables", 1), List.of(tableId.toString())),
                estate.settle(decision(2, Recipient.character(to))));
        assertEquals(to.characterId(), RawJson.uuid(RawJson.read(file).get("ownerCharacterId")));
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(3, Recipient.FROZEN)));
        JsonObject completion = RawJson.read(journal(3));
        assertTrue(completion.getAsJsonArray("retained").isEmpty());
        assertEquals(1, completion.getAsJsonArray("moved").size());
        completion.add("moved", new JsonArray());
        Files.writeString(journal(3), RawJson.JSON.toJson(completion));
        String movedSource = Files.readString(file);
        assertInstanceOf(
                Outcome.Blocked.class,
                estate.settle(
                        decision(
                                4,
                                Recipient.character(
                                        new CharacterRef(UUID.randomUUID(), UUID.randomUUID())))));
        assertEquals(movedSource, Files.readString(file));
        assertFalse(Files.exists(journal(4)));
    }

    @Test
    void guardedPublicationCutAfterRetainedCompletionReplaysItsExactCurrentSnapshot()
            throws Exception {
        Path file = write(raw(tableId));
        assertTrue(store.prepare(file));
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1, Recipient.FROZEN)));
        var cut = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var writer =
                mockStatic(
                        net.tfminecraft.games.persistence.DurableFiles.class, CALLS_REAL_METHODS)) {
            writer.when(
                            () ->
                                    net.tfminecraft.games.persistence.DurableFiles.write(
                                            eq(file), anyString()))
                    .thenAnswer(
                            call -> {
                                call.callRealMethod();
                                if (cut.getAndSet(false))
                                    throw new java.io.IOException(
                                            "retained-to-character acknowledgement cut");
                                return null;
                            });
            assertInstanceOf(
                    Outcome.Retry.class, estate.settle(decision(2, Recipient.character(to))));
            assertTrue(store.publicationPending(tableId));
            assertInstanceOf(
                    Outcome.Done.class, estate.settle(decision(2, Recipient.character(to))));
            assertFalse(store.publicationPending(tableId));
            assertEquals(
                    to.characterId(), RawJson.uuid(RawJson.read(file).get("ownerCharacterId")));
        }
    }

    @Test
    void retainedReferenceAndCoherentlyEmptiedInventoryCorruptionCannotAuthorizeEffects()
            throws Exception {
        Path file = write(raw(tableId));
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1, Recipient.FROZEN)));
        String receipt = Files.readString(journal(1)), source = Files.readString(file);
        for (boolean clear : List.of(false, true)) {
            JsonObject plan = RawJson.parse(receipt);
            if (clear) {
                plan.add("candidates", new JsonArray());
                plan.add("retained", new JsonArray());
            } else
                plan.getAsJsonArray("retained")
                        .get(0)
                        .getAsJsonObject()
                        .addProperty("character", UUID.randomUUID().toString());
            Files.writeString(journal(1), RawJson.JSON.toJson(plan));
            assertInstanceOf(
                    Outcome.Blocked.class,
                    estate.settle(
                            decision(
                                    clear ? 1 : 2,
                                    clear ? Recipient.FROZEN : Recipient.character(to))));
            assertEquals(source, Files.readString(file));
            assertFalse(Files.exists(journal(2)));
        }
    }

    @Test
    void unloadedLegacyModeMustBeFrozenAndItsClassificationCannotDriftWithLayout()
            throws Exception {
        var layouts = net.tfminecraft.games.cache.Cache.tableLayouts;
        var previous =
                layouts.put(
                        "estate-mode",
                        new net.tfminecraft.games.layout.TableLayout(
                                "cards", "fixture", "icon", 6, Map.of(), null, null, null, 0, false,
                                true, 5, 80, 10));
        try {
            JsonObject legacy = raw(tableId);
            legacy.addProperty("gameId", "estate-mode");
            legacy.remove("staffMint");
            legacy.add("autoDealer", JsonNull.INSTANCE);
            legacy.addProperty("houseFloat", 17);
            Path file = write(legacy);
            String original = Files.readString(file);
            assertEquals(OwnershipKind.STAFF, store.row(file).kind());
            assertInstanceOf(
                    Outcome.Retry.class, estate.settle(decision(1, Recipient.character(to))));
            assertFalse(Files.exists(journal(1)));
            assertEquals(original, Files.readString(file));
            assertTrue(store.prepare(file));
            var frozen = RawJson.read(file);
            assertTrue(frozen.get("staffMint").getAsBoolean());
            assertEquals(17, frozen.get("houseFloat").getAsInt());
            assertEquals(from.characterId(), RawJson.uuid(frozen.get("ownerCharacterId")));
            layouts.remove("estate-mode");
            assertEquals(OwnershipKind.STAFF, store.row(file).kind());
            assertEquals(
                    Outcome.done(Map.of("private-tables", 0), List.of()),
                    estate.settle(decision(1, Recipient.character(to))));
            assertEquals(frozen, RawJson.read(file));
            death = UUID.randomUUID();
            JsonObject privateAuto = raw(UUID.randomUUID());
            privateAuto.addProperty("gameId", "estate-mode");
            privateAuto.add("autoDealer", JsonNull.INSTANCE);
            Path privateFile = write(privateAuto);
            assertEquals(OwnershipKind.PRIVATE, store.row(privateFile).kind());
            assertInstanceOf(
                    Outcome.Done.class, estate.settle(decision(2, Recipient.character(to))));
            assertEquals(
                    to.characterId(),
                    RawJson.uuid(RawJson.read(privateFile).get("ownerCharacterId")));
            assertEquals(frozen, RawJson.read(file));
        } finally {
            if (previous == null) layouts.remove("estate-mode");
            else layouts.put("estate-mode", previous);
        }
    }

    @Test
    void receiptIdsAndHistoricalRecipientEnvelopesRejectIndependentCorruption() throws Exception {
        Path file = write(raw(tableId));
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(1, Recipient.character(to))));
        String receipt = Files.readString(journal(1));
        for (String defect : List.of("moved", "recipient", "revision")) {
            JsonObject plan = RawJson.parse(receipt);
            switch (defect) {
                case "moved" -> plan.add("moved", new JsonArray());
                case "recipient" -> plan.addProperty("toCharacter", UUID.randomUUID().toString());
                default -> plan.addProperty("revision", 1.5);
            }
            Files.writeString(journal(1), RawJson.JSON.toJson(plan));
            Files.writeString(file, RawJson.JSON.toJson(raw(tableId)));
            assertInstanceOf(
                    Outcome.Blocked.class, estate.settle(decision(2, Recipient.character(to))));
            assertEquals(
                    from.characterId(), RawJson.uuid(RawJson.read(file).get("ownerCharacterId")));
            assertFalse(Files.exists(journal(2)));
        }
    }

    @Test
    void durablePendingSurvivesRestartWhileLiveWorkRetriesAndSavedMoneyBlocks() throws Exception {
        JsonObject raw = raw(tableId);
        Path file = write(raw);
        Table live = mock(Table.class);
        when(live.live()).thenReturn(true);
        when(manager.table(tableId)).thenReturn(live);
        assertInstanceOf(Outcome.Retry.class, estate.settle(decision(1, Recipient.character(to))));
        assertTrue(Files.exists(journal(1)));
        assertTrue(pending.contains(tableId));
        pending.clear();
        estate = new TableEstate(plugin, manager);
        estate.guard = d -> true;
        estate.recover();
        assertTrue(pending.contains(tableId));
        assertEquals(raw, RawJson.read(file));
        when(manager.table(tableId)).thenReturn(null);
        raw.addProperty("houseFloat", 17);
        write(raw);
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(to))));
        assertEquals(17, RawJson.read(file).get("houseFloat").getAsInt());
    }

    @Test
    void retainedWagerIntentAndUnloadedSavedSeatsCannotBeWaivedByOwnership() throws Exception {
        JsonObject raw = raw(tableId);
        Path file = write(raw);
        Path wager = data.resolve("Data/wager-intents/" + tableId + ".json");
        Files.createDirectories(wager.getParent());
        Files.writeString(wager, "retained original money intent");
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(to))));
        assertEquals("retained original money intent", Files.readString(wager));
        assertEquals(raw, RawJson.read(file));
        Files.delete(wager);
        JsonArray seats = new JsonArray();
        seats.add(from.ownerId().toString());
        raw.add("actives", seats);
        write(raw);
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(to))));
        assertEquals(raw, RawJson.read(file));
    }

    @Test
    void malformedDuplicateAndExternalRawConflictsBlockWithoutWritingAnEstateOrSource()
            throws Exception {
        JsonObject raw = raw(tableId);
        Path file = write(raw);
        assertTrue(store.prepare(file));
        String original = Files.readString(file);
        Files.writeString(file, "{\"ownerPlayer\":");
        String malformed = Files.readString(file);
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(to))));
        assertEquals(malformed, Files.readString(file));
        assertFalse(Files.exists(journal(1)));
        Files.writeString(file, original);
        Path duplicate = file.resolveSibling("duplicate.json");
        Files.writeString(duplicate, original);
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(to))));
        assertEquals(original, Files.readString(file));
        assertEquals(original, Files.readString(duplicate));
        assertFalse(Files.exists(journal(1)));
        Files.delete(duplicate);
        store = new TableOwnershipStore(data);
        when(manager.ownershipStore()).thenReturn(store);
        assertTrue(store.prepare(file));
        Files.writeString(file, original + " ");
        String outside = Files.readString(file);
        assertInstanceOf(
                Outcome.Blocked.class, estate.settle(decision(1, Recipient.character(to))));
        assertEquals(outside, Files.readString(file));
        assertTrue(store.held(tableId));
        assertFalse(Files.exists(journal(1)));
    }

    @Test
    void currentIntentReflushesExactPublishedBytesAfterAnAcknowledgementFailure() throws Exception {
        Path file = write(raw(tableId));
        assertTrue(store.prepare(file));
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var writer =
                mockStatic(
                        net.tfminecraft.games.persistence.DurableFiles.class, CALLS_REAL_METHODS)) {
            writer.when(
                            () ->
                                    net.tfminecraft.games.persistence.DurableFiles.write(
                                            eq(file), anyString()))
                    .thenAnswer(
                            call -> {
                                call.callRealMethod();
                                if (first.getAndSet(false))
                                    throw new java.io.IOException(
                                            "after bytes before acknowledgement");
                                return null;
                            });
            assertInstanceOf(
                    Outcome.Retry.class, estate.settle(decision(1, Recipient.character(to))));
            assertTrue(store.publicationPending(tableId));
            assertTrue(store.held(tableId));
            assertEquals(
                    to.characterId(), RawJson.uuid(RawJson.read(file).get("ownerCharacterId")));
            assertFalse(RawJson.read(journal(1)).get("complete").getAsBoolean());
            assertInstanceOf(
                    Outcome.Done.class, estate.settle(decision(1, Recipient.character(to))));
            assertFalse(store.publicationPending(tableId));
            assertFalse(store.held(tableId));
            assertEquals(1, RawJson.read(journal(1)).getAsJsonArray("moved").size());
        }
    }

    @Test
    void revivalReleasesOnlyAnUntouchedIntentWithExactReadyProviderEvidence() throws Exception {
        Path file = write(raw(tableId));
        Table live = mock(Table.class);
        when(live.live()).thenReturn(true);
        when(manager.table(tableId)).thenReturn(live);
        assertInstanceOf(Outcome.Retry.class, estate.settle(decision(1, Recipient.character(to))));
        String source = Files.readString(file);
        estate = new TableEstate(plugin, manager);
        estate.recover();
        estate.serviceReady = () -> true;
        try (var identity = mockStatic(net.tfminecraft.tlibs.character.Characters.class)) {
            estate.releaseCancelled();
            assertTrue(pending.contains(tableId));
            assertFalse(RawJson.read(journal(1)).get("complete").getAsBoolean());
            var alive =
                    new net.tfminecraft.tlibs.character.CharacterInfo(
                            from,
                            "fixture",
                            net.tfminecraft.tlibs.character.CharacterStatus.ALIVE,
                            null,
                            Set.of(),
                            0,
                            null);
            identity.when(() -> net.tfminecraft.tlibs.character.Characters.info(from.characterId()))
                    .thenReturn(Optional.of(alive));
            identity.when(
                            () ->
                                    net.tfminecraft.tlibs.character.Characters.deathId(
                                            from.characterId()))
                    .thenReturn(Optional.of(death));
            characters.when(CharacterAccess::heirReady).thenReturn(false);
            estate.releaseCancelled();
            assertTrue(pending.contains(tableId));
            characters.when(CharacterAccess::heirReady).thenReturn(true);
            estate.serviceReady = () -> false;
            estate.releaseCancelled();
            assertTrue(pending.contains(tableId));
            estate.serviceReady = () -> true;
            estate.releaseCancelled();
            assertTrue(pending.isEmpty());
            assertEquals(source, Files.readString(file));
            JsonObject receipt = RawJson.read(journal(1));
            assertTrue(receipt.get("obsolete").getAsBoolean());
            assertTrue(receipt.getAsJsonArray("moved").isEmpty());
            estate.recover();
            assertTrue(pending.isEmpty());
        }
    }

    @Test
    void revivalCannotReleaseAPartiallyPublishedFrozenPlan() throws Exception {
        Path file = write(raw(tableId));
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (var writer =
                mockStatic(
                        net.tfminecraft.games.persistence.DurableFiles.class, CALLS_REAL_METHODS)) {
            writer.when(
                            () ->
                                    net.tfminecraft.games.persistence.DurableFiles.write(
                                            eq(file), anyString()))
                    .thenAnswer(
                            call -> {
                                call.callRealMethod();
                                if (first.getAndSet(false))
                                    throw new java.io.IOException(
                                            "publication acknowledgement cut");
                                return null;
                            });
            assertInstanceOf(
                    Outcome.Retry.class, estate.settle(decision(1, Recipient.character(to))));
        }
        String published = Files.readString(file);
        estate.serviceReady = () -> true;
        try (var identity = mockStatic(net.tfminecraft.tlibs.character.Characters.class)) {
            var alive =
                    new net.tfminecraft.tlibs.character.CharacterInfo(
                            from,
                            "fixture",
                            net.tfminecraft.tlibs.character.CharacterStatus.ALIVE,
                            null,
                            Set.of(),
                            0,
                            null);
            identity.when(() -> net.tfminecraft.tlibs.character.Characters.info(from.characterId()))
                    .thenReturn(Optional.of(alive));
            estate.releaseCancelled();
            assertTrue(pending.contains(tableId));
            assertEquals(published, Files.readString(file));
            assertFalse(RawJson.read(journal(1)).get("complete").getAsBoolean());
        }
    }
}
