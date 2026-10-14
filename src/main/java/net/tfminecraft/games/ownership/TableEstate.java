package net.tfminecraft.games.ownership;

import com.google.gson.*;

import net.tfminecraft.games.persistence.DurableFiles;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.wager.WagerJournal;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterRevivedEvent;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.character.estate.*;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Pending tables reject new activity; captured games settle before raw ownership alone moves. */
public final class TableEstate implements EstateParticipant, Listener {
    private final Plugin plugin;
    private final TableManager manager;
    private final Path journals;
    Predicate<EstateDecision> guard = Estates::guardHolds;
    Supplier<List<EstateDecision>> decisions = Estates::decided;
    BooleanSupplier serviceReady = Estates::available;

    public TableEstate(Plugin plugin, TableManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        journals = plugin.getDataFolder().toPath().resolve("estates/tables");
    }

    public static void start(Plugin plugin, TableManager manager) {
        TableEstate participant = new TableEstate(plugin, manager);
        participant.recover();
        Bukkit.getServicesManager()
                .register(EstateParticipant.class, participant, plugin, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(participant, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, participant::reconcile, 1L, 72000L);
    }

    @Override
    public String id() {
        return "games";
    }

    @Override
    public Holdings describe(UUID character) {
        try {
            List<String> ids =
                    manager.ownershipStore().inventory().stream()
                            .filter(
                                    row ->
                                            row.kind() == OwnershipKind.PRIVATE
                                                    && character.equals(row.character()))
                            .map(row -> "Table: " + row.id())
                            .toList();
            return ids.isEmpty()
                    ? Holdings.NONE
                    : new Holdings(Map.of("private-tables", ids.size()), ids);
        } catch (IOException ex) {
            return new Holdings(
                    Map.of("table-review", 1), List.of("Table inventory requires review"));
        }
    }

    @Override
    public Outcome settle(EstateDecision decision) {
        if (!CharacterAccess.heirReady())
            return Outcome.retry("Reviewed table ownership is not ready");
        if (!guard.test(decision))
            return Outcome.retry("The exact table estate decision is not current");
        if (decision.property().kind() != Recipient.Kind.CHARACTER
                && !decision.property().isWaiting()
                && !decision.property().isFrozen())
            return Outcome.blocked(
                    "Private table property cannot be assigned to this recipient kind");
        if (decision.property().character().equals(Optional.of(decision.testator())))
            return Outcome.blocked("Private table property cannot be transferred to its testator");
        try {
            List<TableOwnershipStore.Row> rows = manager.ownershipStore().inventory();
            if (rows.stream()
                    .anyMatch(
                            row ->
                                    decision.testator().ownerId().equals(row.account())
                                            && row.kind() != OwnershipKind.GUILD
                                            && !OwnershipKind.modeRecorded(row.raw())))
                return Outcome.retry(
                        "Legacy table funding mode must be frozen before property planning");
            if (rows.stream()
                    .anyMatch(
                            row ->
                                    row.kind() == OwnershipKind.PRIVATE
                                            && decision.testator().ownerId().equals(row.account())
                                            && row.character() == null))
                return Outcome.blocked(
                        "Legacy private tables for this account need their reviewed heir");
            List<UUID> moved = new ArrayList<>();
            Path file = journals.resolve(decision.deathId() + "-" + decision.revision() + ".json");
            Map<Path, JsonObject> history = new LinkedHashMap<>();
            for (Path older : files()) {
                JsonObject previous = read(older);
                if (!decision.deathId().toString().equals(RawJson.string(previous, "death")))
                    continue;
                if (previous.get("revision").getAsLong() > decision.revision())
                    throw new Invalid("Future table revision evidence forbids decision rollback");
                if (!from(previous).equals(decision.testator()))
                    throw new Invalid(
                            "Historical table receipt testator conflicts with this death");
                history.put(older, previous);
            }
            JsonObject plan = history.get(file);
            if (plan != null && !decision.toString().equals(RawJson.string(plan, "decision"))) {
                pending(plan, true);
                return Outcome.blocked("Table intent conflicts with the exact current decision");
            }
            validateHistory(history.values(), rows, decision.testator(), decision.revision(), plan);
            for (var entry : history.entrySet()) {
                Path older = entry.getKey();
                JsonObject previous = entry.getValue();
                if (previous.get("complete").getAsBoolean()) moved.addAll(ids(previous, "moved"));
                else if (!older.equals(file)) {
                    if (changed(previous, rows)) {
                        pending(previous, true);
                        return Outcome.blocked(
                                "Earlier partial table transfer requires review: "
                                        + older.getFileName());
                    }
                    previous.addProperty("complete", true);
                    previous.addProperty("obsolete", true);
                    previous.add("moved", new JsonArray());
                    DurableFiles.write(older, RawJson.JSON.toJson(previous));
                    pending(previous, false);
                }
            }
            for (var row : rows)
                if (owned(row, decision.testator()) && moved.contains(row.id())) {
                    manager.estatePending(List.of(row.id()), true);
                    return Outcome.blocked(
                            "Completed table reappeared at the testator: " + row.id());
                }
            if (decision.property().character().isEmpty()) {
                if (plan == null) plan = retain(decision, rows, moved);
                DurableFiles.write(file, RawJson.JSON.toJson(plan));
                return done(moved);
            }
            if (plan != null) {
                if (plan.get("complete").getAsBoolean()) {
                    if (rows.stream().anyMatch(row -> owned(row, decision.testator())))
                        return Outcome.blocked(
                                "Completed table receipt has remaining testator property; review a"
                                        + " new revision");
                    return done(moved);
                }
            } else {
                plan = create(decision, rows);
                DurableFiles.write(file, RawJson.JSON.toJson(plan));
            }
            pending(plan, true);
            List<UUID> candidates = ids(plan, "candidates");
            if (rows.stream()
                    .anyMatch(
                            row ->
                                    owned(row, decision.testator())
                                            && !candidates.contains(row.id())))
                throw new Invalid(
                        "New private property appeared outside the frozen candidate inventory");
            if (!plan.get("ready").getAsBoolean()) {
                for (UUID id : candidates) {
                    TableOwnershipStore.Row row = find(rows, id);
                    if (row == null || !owned(row, decision.testator()))
                        return Outcome.blocked("Pending table source changed: " + id);
                    Outcome wait = idle(row);
                    if (wait != null) return wait;
                    Table table = manager.table(id);
                    if (table != null) manager.checkpoint(table);
                }
                rows = manager.ownershipStore().inventory();
                JsonArray snapshots = new JsonArray();
                CharacterRef recipient = decision.property().character().orElseThrow();
                for (UUID id : candidates) {
                    var row = find(rows, id);
                    if (row == null || !owned(row, decision.testator()))
                        throw new Invalid("Table changed before freezing its source: " + id);
                    Outcome wait = idle(row);
                    if (wait != null) return wait;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", id.toString());
                    entry.addProperty("file", row.path().getFileName().toString());
                    entry.add("before", row.raw().deepCopy());
                    JsonObject after = row.raw().deepCopy();
                    after.addProperty("ownerPlayer", recipient.ownerId().toString());
                    after.addProperty("ownerCharacterId", recipient.characterId().toString());
                    after.addProperty("ownerIdentityVersion", 2);
                    entry.add("after", after);
                    snapshots.add(entry);
                }
                if (!guard.test(decision))
                    return Outcome.retry("Estate changed before table source planning");
                plan.add("tables", snapshots);
                plan.addProperty("ready", true);
                DurableFiles.write(file, RawJson.JSON.toJson(plan));
            }
            for (JsonElement item : plan.getAsJsonArray("tables")) {
                JsonObject snapshot = item.getAsJsonObject();
                UUID id = RawJson.uuid(snapshot.get("id"));
                var row =
                        manager.ownershipStore()
                                .row(
                                        manager.ownershipStore()
                                                .folder()
                                                .resolve(RawJson.string(snapshot, "file")));
                if (!row.raw().equals(snapshot.get("before"))
                        && !row.raw().equals(snapshot.get("after")))
                    return Outcome.blocked("Frozen table source changed: " + id);
                Outcome wait = idle(row, true);
                if (wait != null) return wait;
                if (!guard.test(decision))
                    return Outcome.retry("Estate changed before table ownership publication");
                if (row.raw().equals(snapshot.get("before"))
                        || manager.ownershipStore().publicationPending(id))
                    manager.ownershipStore().publish(row, snapshot.getAsJsonObject("after"));
                CharacterRef recipient = to(plan);
                manager.estateOwner(id, recipient.ownerId(), recipient.characterId());
            }
            JsonArray receipt = new JsonArray();
            candidates.forEach(id -> receipt.add(id.toString()));
            plan.add("moved", receipt);
            plan.addProperty("complete", true);
            DurableFiles.write(file, RawJson.JSON.toJson(plan));
            pending(plan, false);
            moved.addAll(candidates);
            return done(moved);
        } catch (Invalid | TableOwnershipStore.Conflict | RuntimeException ex) {
            holdAll();
            plugin.getLogger().severe("Table estate requires review: " + ex.getMessage());
            return Outcome.blocked("Table estate requires review: " + ex.getMessage());
        } catch (IOException ex) {
            return Outcome.retry("Table estate persistence awaits retry: " + ex.getMessage());
        }
    }

    private Outcome idle(TableOwnershipStore.Row row) throws IOException {
        return idle(row, false);
    }

    private Outcome idle(TableOwnershipStore.Row row, boolean replay) throws IOException {
        Table table = manager.table(row.id());
        Path intent =
                plugin.getDataFolder()
                        .toPath()
                        .resolve("Data/wager-intents")
                        .resolve(row.id() + ".json");
        if (Files.exists(intent))
            return table != null && WagerJournal.active(table)
                    ? Outcome.retry("Table " + row.id() + ": captured money operation is active")
                    : Outcome.blocked(
                            "Table " + row.id() + ": retained wager intent requires review");
        if (table != null
                && table.ownershipHeld()
                && !(replay
                        && manager.ownershipStore().publicationPending(row.id())
                        && !table.ownershipReviewHeld()))
            return Outcome.blocked(
                    "Table " + row.id() + ": ownership persistence is held for review");
        if (table != null && table.moneyBlocked())
            return Outcome.blocked("Table " + row.id() + ": money is held for review");
        if (table != null
                && (table.live()
                        || table.isPaying()
                        || !table.payoutFlying().isEmpty()
                        || table.isRecycling()))
            return Outcome.retry("Table " + row.id() + ": captured game/payout must finish");
        JsonObject raw = row.raw();
        for (String key : List.of("ownershipLive", "ownershipPaying"))
            if (raw.has(key) && !raw.get(key).isJsonNull()) {
                if (!raw.get(key).isJsonPrimitive()
                        || !raw.get(key).getAsJsonPrimitive().isBoolean())
                    throw new Invalid("Invalid saved activity flag for table " + row.id());
                if (raw.get(key).getAsBoolean())
                    return Outcome.blocked(
                            "Table " + row.id() + ": saved activity requires review");
            }
        for (String key : List.of("ledger", "piles"))
            if (raw.has(key) && !raw.get(key).isJsonNull()) {
                if (!raw.get(key).isJsonArray())
                    throw new Invalid("Invalid saved money rows for table " + row.id());
                if (!raw.getAsJsonArray(key).isEmpty())
                    return Outcome.blocked("Table " + row.id() + ": unsettled saved money remains");
            }
        if ((raw.has("houseFloat")
                        && !raw.get("houseFloat").isJsonNull()
                        && moneyInteger(raw.get("houseFloat")) != 0)
                || (raw.has("floatOwner") && !raw.get("floatOwner").isJsonNull()))
            return Outcome.blocked("Table " + row.id() + ": dealer/house float remains");
        if (table != null
                && (!table.ledger().isEmpty()
                        || table.houseFloat() != 0
                        || table.floatOwner() != null
                        || !table.getHands().isEmpty()
                        || !table.tablePilesEmpty()))
            return Outcome.blocked("Table " + row.id() + ": runtime stakes, float or cards remain");
        if (table == null
                && raw.has("actives")
                && (!raw.get("actives").isJsonArray() || !raw.getAsJsonArray("actives").isEmpty()))
            return Outcome.blocked("Table " + row.id() + ": unloaded saved seats require review");
        return null;
    }

    private static long moneyInteger(JsonElement value) throws Invalid {
        try {
            return RawJson.integer(value);
        } catch (IOException ex) {
            throw new Invalid("Invalid saved money value");
        }
    }

    private JsonObject create(EstateDecision decision, List<TableOwnershipStore.Row> rows) {
        JsonObject plan = new JsonObject();
        plan.addProperty("death", decision.deathId().toString());
        plan.addProperty("revision", decision.revision());
        plan.addProperty("decision", decision.toString());
        plan.addProperty("fromAccount", decision.testator().ownerId().toString());
        plan.addProperty("fromCharacter", decision.testator().characterId().toString());
        var recipient = decision.property().character().orElseThrow();
        plan.addProperty("toAccount", recipient.ownerId().toString());
        plan.addProperty("toCharacter", recipient.characterId().toString());
        JsonArray candidates = new JsonArray();
        rows.stream()
                .filter(row -> owned(row, decision.testator()))
                .forEach(row -> candidates.add(row.id().toString()));
        plan.add("candidates", candidates);
        plan.add("tables", new JsonArray());
        plan.add("moved", new JsonArray());
        plan.addProperty("ready", false);
        plan.addProperty("complete", false);
        return plan;
    }

    private JsonObject retain(
            EstateDecision decision, List<TableOwnershipStore.Row> rows, List<UUID> moved) {
        JsonObject plan = new JsonObject();
        plan.addProperty("death", decision.deathId().toString());
        plan.addProperty("revision", decision.revision());
        plan.addProperty("decision", decision.toString());
        plan.addProperty("fromAccount", decision.testator().ownerId().toString());
        plan.addProperty("fromCharacter", decision.testator().characterId().toString());
        plan.addProperty("propertyKind", decision.property().kind().name());
        plan.add("toAccount", JsonNull.INSTANCE);
        plan.add("toCharacter", JsonNull.INSTANCE);
        JsonArray candidates = new JsonArray(),
                retained = new JsonArray(),
                receipt = new JsonArray();
        rows.stream()
                .filter(row -> owned(row, decision.testator()))
                .forEach(
                        row -> {
                            candidates.add(row.id().toString());
                            JsonObject ref = new JsonObject();
                            ref.addProperty("id", row.id().toString());
                            ref.addProperty("account", row.account().toString());
                            ref.addProperty("character", row.character().toString());
                            retained.add(ref);
                        });
        moved.stream().distinct().sorted().forEach(id -> receipt.add(id.toString()));
        plan.add("candidates", candidates);
        plan.add("retained", retained);
        plan.add("tables", new JsonArray());
        plan.add("moved", receipt);
        plan.addProperty("ready", true);
        plan.addProperty("complete", true);
        return plan;
    }

    private static void validateHistory(
            Collection<JsonObject> history,
            List<TableOwnershipStore.Row> rows,
            CharacterRef source,
            long revision,
            JsonObject current)
            throws IOException {
        Set<UUID> cumulative = new HashSet<>(), pool = null;
        JsonObject latest = null;
        List<JsonObject> ordered =
                history.stream()
                        .sorted(Comparator.comparingLong(plan -> plan.get("revision").getAsLong()))
                        .toList();
        for (JsonObject plan : ordered) {
            if (!plan.get("complete").getAsBoolean()
                    || (plan.has("obsolete") && plan.get("obsolete").getAsBoolean())) continue;
            Set<UUID> candidates = Set.copyOf(ids(plan, "candidates"));
            if (retained(plan)) {
                if (!cumulative.equals(Set.copyOf(ids(plan, "moved"))))
                    throw new Invalid("Retained completion lost cumulative moved IDs");
                pool = candidates;
            } else {
                if (!Collections.disjoint(cumulative, ids(plan, "moved")))
                    throw new Invalid("Historical table was completed twice");
                cumulative.addAll(ids(plan, "moved"));
                pool = Set.of();
            }
            latest = plan;
        }
        if (latest == null || latest.get("revision").getAsLong() != revision) return;
        for (var row : rows)
            if (owned(row, source) && !pool.contains(row.id()))
                throw new Invalid(
                        "New or restored table appeared after completed inventory: " + row.id());
        if (retained(latest))
            for (UUID id : pool) {
                var row = find(rows, id);
                if (row == null
                        || !source.ownerId().equals(row.account())
                        || !source.characterId().equals(row.character())) {
                    if (!publishedBy(current, row, id))
                        throw new Invalid("Retained table owner changed after completion: " + id);
                }
            }
    }

    private static boolean publishedBy(JsonObject plan, TableOwnershipStore.Row row, UUID id) {
        if (plan == null
                || row == null
                || retained(plan)
                || plan.get("complete").getAsBoolean()
                || !plan.get("ready").getAsBoolean()) return false;
        for (JsonElement item : plan.getAsJsonArray("tables")) {
            JsonObject snapshot = item.getAsJsonObject();
            if (id.equals(RawJson.uuid(snapshot.get("id")))
                    && row.raw().equals(snapshot.get("after"))) return true;
        }
        return false;
    }

    private static boolean retained(JsonObject plan) {
        return plan.has("propertyKind")
                && !"CHARACTER".equals(RawJson.string(plan, "propertyKind"));
    }

    private List<Path> files() throws IOException {
        if (!Files.isDirectory(journals)) return List.of();
        try (var files = Files.list(journals)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private JsonObject read(Path file) throws IOException {
        String text = Files.readString(file);
        JsonObject plan;
        try {
            plan = RawJson.parse(text);
            validate(plan, file);
        } catch (IOException | RuntimeException ex) {
            throw new Invalid(
                    "Invalid table journal " + file.getFileName() + ": " + ex.getMessage());
        }
        return plan;
    }

    private static void validate(JsonObject plan, Path file) throws IOException {
        UUID death = RawJson.uuid(plan.get("death"));
        long revision = RawJson.integer(plan.get("revision"));
        CharacterRef source = from(plan), recipient = null;
        String kind = plan.has("propertyKind") ? RawJson.string(plan, "propertyKind") : "CHARACTER";
        Recipient property;
        if ("CHARACTER".equals(kind)) {
            recipient = to(plan);
            property = Recipient.character(recipient);
        } else if ("WAITING".equals(kind) || "FROZEN".equals(kind)) {
            property = "WAITING".equals(kind) ? Recipient.WAITING : Recipient.FROZEN;
            for (String key : List.of("toAccount", "toCharacter"))
                if (!plan.has(key) || !plan.get(key).isJsonNull())
                    throw new Invalid("Retained completion has a character recipient");
        } else throw new Invalid("Unsupported saved property recipient");
        String decision = RawJson.string(plan, "decision");
        if (death == null
                || revision < 0
                || !file.getFileName().toString().equals(death + "-" + revision + ".json")
                || decision == null
                || !decision.startsWith(
                        "EstateDecision[deathId=" + death + ", testator=" + source + ", diedAt=")
                || !decision.contains(", property=" + property + ", guildLocks=")
                || !decision.endsWith(", revision=" + revision + "]"))
            throw new Invalid("Decision envelope mismatch");
        for (String key : List.of("ready", "complete"))
            if (!plan.has(key)
                    || !plan.get(key).isJsonPrimitive()
                    || !plan.get(key).getAsJsonPrimitive().isBoolean())
                throw new Invalid("Invalid " + key);
        if (plan.has("obsolete")
                && (!plan.get("obsolete").isJsonPrimitive()
                        || !plan.get("obsolete").getAsJsonPrimitive().isBoolean()
                        || (plan.get("obsolete").getAsBoolean()
                                && !plan.get("complete").getAsBoolean())))
            throw new Invalid("Invalid obsolete receipt marker");
        List<UUID> candidates = ids(plan, "candidates"), moved = ids(plan, "moved");
        if (!plan.has("tables") || !plan.get("tables").isJsonArray())
            throw new Invalid("Missing table snapshots");
        if (retained(plan)) {
            if (!plan.get("ready").getAsBoolean()
                    || !plan.get("complete").getAsBoolean()
                    || !plan.getAsJsonArray("tables").isEmpty()
                    || (plan.has("obsolete") && plan.get("obsolete").getAsBoolean())
                    || !plan.has("retained")
                    || !plan.get("retained").isJsonArray())
                throw new Invalid("Invalid retained completion");
            Set<UUID> refs = new HashSet<>();
            for (JsonElement item : plan.getAsJsonArray("retained")) {
                JsonObject ref = item.getAsJsonObject();
                UUID id = RawJson.uuid(ref.get("id"));
                if (id == null || !refs.add(id) || !source.equals(ref(ref, "account", "character")))
                    throw new Invalid("Retained inventory reference mismatch");
            }
            if (!refs.equals(Set.copyOf(candidates)) || !Collections.disjoint(candidates, moved))
                throw new Invalid("Retained inventory IDs disagree");
            return;
        }
        if (plan.has("retained")
                && (!plan.get("retained").isJsonArray()
                        || !plan.getAsJsonArray("retained").isEmpty()))
            throw new Invalid("Character completion retained unexpected property");
        Set<UUID> snapshots = new HashSet<>();
        for (JsonElement item : plan.getAsJsonArray("tables")) {
            JsonObject entry = item.getAsJsonObject();
            UUID id = RawJson.uuid(entry.get("id"));
            String name = RawJson.string(entry, "file");
            if (id == null
                    || !snapshots.add(id)
                    || name == null
                    || !Path.of(name).getFileName().toString().equals(name)
                    || !name.endsWith(".json"))
                throw new Invalid("Invalid table snapshot envelope");
            JsonObject before = entry.getAsJsonObject("before"),
                    after = entry.getAsJsonObject("after");
            OwnershipMigration.validateRow(before);
            OwnershipMigration.validateRow(after);
            if (!id.equals(RawJson.uuid(before.get("id")))
                    || OwnershipKind.of(before) != OwnershipKind.PRIVATE
                    || !source.ownerId().equals(RawJson.uuid(before.get("ownerPlayer")))
                    || !source.characterId().equals(RawJson.uuid(before.get("ownerCharacterId"))))
                throw new Invalid("Invalid source snapshot");
            JsonObject expected = before.deepCopy();
            expected.addProperty("ownerPlayer", recipient.ownerId().toString());
            expected.addProperty("ownerCharacterId", recipient.characterId().toString());
            expected.addProperty("ownerIdentityVersion", 2);
            if (!expected.equals(after))
                throw new Invalid("Table snapshot changed beyond ownership");
        }
        if (plan.get("ready").getAsBoolean()
                ? !snapshots.equals(Set.copyOf(candidates))
                : !snapshots.isEmpty()) throw new Invalid("Snapshot candidate IDs disagree");
        if (plan.get("complete").getAsBoolean()
                && (plan.has("obsolete") && plan.get("obsolete").getAsBoolean()
                        ? !moved.isEmpty()
                        : !plan.get("ready").getAsBoolean()
                                || !Set.copyOf(moved).equals(Set.copyOf(candidates))))
            throw new Invalid("Completed receipt IDs disagree");
    }

    private static List<UUID> ids(JsonObject plan, String key) throws IOException {
        if (!plan.has(key) || !plan.get(key).isJsonArray())
            throw new Invalid("Invalid " + key + " list");
        List<UUID> ids = new ArrayList<>();
        for (JsonElement item : plan.getAsJsonArray(key)) {
            UUID id = RawJson.uuid(item);
            if (id == null || ids.contains(id)) throw new Invalid("Invalid/duplicate holding ID");
            ids.add(id);
        }
        return ids;
    }

    private static CharacterRef from(JsonObject plan) throws IOException {
        return ref(plan, "fromAccount", "fromCharacter");
    }

    private static CharacterRef to(JsonObject plan) throws IOException {
        return ref(plan, "toAccount", "toCharacter");
    }

    private static CharacterRef ref(JsonObject plan, String account, String character)
            throws IOException {
        UUID owner = RawJson.uuid(plan.get(account)), id = RawJson.uuid(plan.get(character));
        if (owner == null || id == null) throw new Invalid("Invalid character reference");
        return new CharacterRef(owner, id);
    }

    private static boolean owned(TableOwnershipStore.Row row, CharacterRef owner) {
        return row.kind() == OwnershipKind.PRIVATE
                && owner.ownerId().equals(row.account())
                && owner.characterId().equals(row.character());
    }

    private static TableOwnershipStore.Row find(List<TableOwnershipStore.Row> rows, UUID id) {
        return rows.stream().filter(row -> id.equals(row.id())).findFirst().orElse(null);
    }

    private static boolean changed(JsonObject plan, List<TableOwnershipStore.Row> rows)
            throws IOException {
        if (plan.get("ready").getAsBoolean()) {
            for (JsonElement item : plan.getAsJsonArray("tables")) {
                JsonObject entry = item.getAsJsonObject();
                var row = find(rows, RawJson.uuid(entry.get("id")));
                if (row == null || !row.raw().equals(entry.get("before"))) return true;
            }
        } else
            for (UUID id : ids(plan, "candidates")) {
                var row = find(rows, id);
                if (row == null || !owned(row, from(plan))) return true;
            }
        return false;
    }

    private void pending(JsonObject plan, boolean pending) throws IOException {
        manager.estatePending(ids(plan, "candidates"), pending);
    }

    void recover() {
        try {
            for (Path file : files()) {
                JsonObject plan = read(file);
                if (!plan.get("complete").getAsBoolean()) pending(plan, true);
            }
        } catch (IOException | RuntimeException ex) {
            holdAll();
            plugin.getLogger().severe("Table estate journals need review: " + ex.getMessage());
        }
    }

    private void holdAll() {
        manager.tables()
                .forEach(
                        table -> {
                            table.setOwnershipHeld(true);
                            table.setEstatePending(true);
                        });
        try {
            manager.estatePending(
                    manager.ownershipStore().inventory().stream()
                            .map(TableOwnershipStore.Row::id)
                            .toList(),
                    true);
        } catch (IOException ignored) {
            /* Broken raw files remain unloaded and unchanged. */
        }
    }

    void reconcile() {
        releaseCancelled();
        for (EstateDecision decision : decisions.get())
            if (guard.test(decision))
                Estates.report(decision.deathId(), id(), decision.revision(), settle(decision));
    }

    void releaseCancelled() {
        if (!CharacterAccess.heirReady() || !serviceReady.getAsBoolean()) return;
        try {
            List<TableOwnershipStore.Row> rows = manager.ownershipStore().inventory();
            for (Path file : files()) {
                JsonObject plan = read(file);
                if (plan.get("complete").getAsBoolean()) continue;
                CharacterRef source = from(plan);
                UUID death = RawJson.uuid(plan.get("death"));
                var info =
                        Characters.info(source.characterId())
                                .filter(value -> value.ref().equals(source));
                var currentDeath = Characters.deathId(source.characterId());
                if (info.isEmpty()
                        || (!info.get().alive()
                                && (currentDeath.isEmpty() || currentDeath.get().equals(death))))
                    continue;
                if (changed(plan, rows)) {
                    pending(plan, true);
                    continue;
                }
                plan.addProperty("complete", true);
                plan.addProperty("obsolete", true);
                plan.add("moved", new JsonArray());
                DurableFiles.write(file, RawJson.JSON.toJson(plan));
                pending(plan, false);
            }
        } catch (Invalid | TableOwnershipStore.Conflict | RuntimeException ex) {
            holdAll();
            plugin.getLogger().severe("Cancelled table intent requires review: " + ex.getMessage());
        } catch (IOException ex) {
            plugin.getLogger()
                    .warning("Cancelled table intent awaits persistence retry: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onEstate(EstateDecidedEvent event) {
        Bukkit.getScheduler().runTask(plugin, this::reconcile);
    }

    @EventHandler
    public void onRevive(CharacterRevivedEvent event) {
        Bukkit.getScheduler().runTask(plugin, this::reconcile);
    }

    private static Outcome done(List<UUID> ids) {
        List<String> moved = ids.stream().map(UUID::toString).distinct().sorted().toList();
        return Outcome.done(Map.of("private-tables", moved.size()), moved);
    }

    private static final class Invalid extends IOException {
        Invalid(String message) {
            super(message);
        }
    }
}
