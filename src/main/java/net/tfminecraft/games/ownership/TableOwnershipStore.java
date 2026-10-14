package net.tfminecraft.games.ownership;

import com.google.gson.*;

import net.tfminecraft.games.persistence.DurableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Raw inventory includes tables that cannot currently create a world/card-set runtime body. */
public final class TableOwnershipStore {
    public static final class Conflict extends IOException {
        public Conflict(String message) {
            super(message);
        }
    }

    public record Row(
            Path path, UUID id, UUID account, UUID character, OwnershipKind kind, JsonObject raw) {}

    private record Source(Path path, String hash, JsonObject raw) {}

    private final Path folder;
    private final OwnershipMigration migration;
    private final Map<UUID, Source> sources = new HashMap<>();
    private final Set<UUID> conflicts = new HashSet<>();
    private final Set<UUID> publicationPending = new HashSet<>();
    private final Map<UUID, Set<String>> knownWagers = new HashMap<>();

    public TableOwnershipStore(Path data) {
        folder = data.resolve("Data/tables");
        migration = new OwnershipMigration(data.resolve("migrations/table-ownership"));
    }

    public Path folder() {
        return folder;
    }

    public boolean prepare(Path file) throws IOException {
        UUID existing = null;
        for (var entry : sources.entrySet())
            if (entry.getValue().path().equals(file)) existing = entry.getKey();
        if (existing != null) check(existing);
        boolean usable = migration.prepare(file);
        captureSource(file);
        return usable;
    }

    public void observe(Path file) throws IOException {
        for (var entry : sources.entrySet())
            if (entry.getValue().path().equals(file)) check(entry.getKey());
        captureSource(file);
    }

    private void captureSource(Path file) throws IOException {
        JsonObject raw = RawJson.read(file);
        OwnershipMigration.validateRow(raw);
        UUID id = RawJson.uuid(raw.get("id"));
        if (id == null) throw new IOException("Invalid table ID");
        Source previous = sources.get(id);
        if (previous != null && !previous.path().equals(file)) {
            conflicts.add(id);
            throw new Conflict("Duplicate table ID");
        }
        sources.put(id, new Source(file, OwnershipMigration.hash(Files.readAllBytes(file)), raw));
        rememberWagers(id, raw);
    }

    public Row row(Path file) throws IOException {
        String text = Files.readString(file);
        JsonObject raw;
        try {
            raw = RawJson.parse(text);
            OwnershipMigration.validateRow(raw);
        } catch (IOException ex) {
            throw new Conflict(
                    "Invalid table source: " + file.getFileName() + ": " + ex.getMessage());
        }
        UUID id = RawJson.uuid(raw.get("id"));
        if (id == null) throw new Conflict("Invalid table ID");
        return new Row(
                file,
                id,
                RawJson.uuid(raw.get("ownerPlayer")),
                RawJson.uuid(raw.get("ownerCharacterId")),
                OwnershipKind.of(raw),
                raw);
    }

    public List<Row> inventory() throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        List<Row> rows = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        try (var files = Files.list(folder)) {
            for (Path file :
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .toList()) {
                Row row = row(file);
                if (!ids.add(row.id())) {
                    conflicts.add(row.id());
                    throw new Conflict("Duplicate table ID: " + row.id());
                }
                if (sources.containsKey(row.id())) check(row.id());
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    public boolean held(UUID id) {
        return conflicts.contains(id) || publicationPending.contains(id);
    }

    public boolean publicationPending(UUID id) {
        return publicationPending.contains(id);
    }

    public void hold(UUID id) {
        conflicts.add(id);
    }

    public void check(UUID id) throws IOException {
        Source source = sources.get(id);
        try {
            if (conflicts.contains(id)
                    || (source != null
                            && (!Files.isRegularFile(source.path())
                                    || !source.hash()
                                            .equals(
                                                    OwnershipMigration.hash(
                                                            Files.readAllBytes(source.path())))))) {
                throw new Conflict("Table source changed outside its writer: " + id);
            }
        } catch (IOException ex) {
            conflicts.add(id);
            throw ex;
        }
    }

    public void save(UUID id, JsonObject known) throws IOException {
        if (publicationPending.contains(id))
            throw new Conflict("Ownership publication still awaits durable acknowledgement: " + id);
        check(id);
        Source previous = sources.get(id);
        Path file = previous == null ? folder.resolve(id + ".json") : previous.path();
        if (previous == null && Files.exists(file)) {
            conflicts.add(id);
            throw new Conflict("Unexpected existing table source: " + id);
        }
        JsonObject next = previous == null ? known.deepCopy() : overlay(id, previous.raw(), known);
        OwnershipMigration.validateRow(next);
        String text = RawJson.JSON.toJson(next);
        DurableFiles.write(file, text);
        sources.put(
                id,
                new Source(
                        file,
                        OwnershipMigration.hash(
                                text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        next));
        rememberWagers(id, next);
    }

    public void publish(Row before, JsonObject next) throws IOException {
        if (!before.raw().equals(row(before.path()).raw())) {
            conflicts.add(before.id());
            throw new Conflict("Estate source changed after planning");
        }
        check(before.id());
        OwnershipMigration.validateRow(next);
        String text = RawJson.JSON.toJson(next);
        String afterHash =
                OwnershipMigration.hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            DurableFiles.write(before.path(), text);
        } catch (IOException ex) {
            // Exact attempted bytes are a replay candidate, never acknowledgement or general write
            // authority.
            if (Files.isRegularFile(before.path())
                    && afterHash.equals(
                            OwnershipMigration.hash(Files.readAllBytes(before.path())))) {
                sources.put(before.id(), new Source(before.path(), afterHash, next));
                publicationPending.add(before.id());
            }
            throw ex;
        }
        publicationPending.remove(before.id());
        sources.put(
                before.id(),
                new Source(
                        before.path(),
                        OwnershipMigration.hash(
                                text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        next));
        rememberWagers(before.id(), next);
    }

    private JsonObject overlay(UUID id, JsonObject old, JsonObject known) {
        JsonObject next = RawJson.overlay(old, known).getAsJsonObject();
        if (old.has("ledger")
                && old.get("ledger").isJsonArray()
                && known.has("ledger")
                && known.get("ledger").isJsonArray()) {
            JsonArray ledger = new JsonArray();
            for (JsonElement entry : known.getAsJsonArray("ledger")) {
                JsonElement merged = entry;
                for (JsonElement prior : old.getAsJsonArray("ledger"))
                    if (stakeKey(prior).equals(stakeKey(entry))) {
                        merged = RawJson.overlay(prior, entry);
                        break;
                    }
                ledger.add(merged);
            }
            next.add("ledger", ledger);
        }
        if (old.has("wagerCharacters") && old.get("wagerCharacters").isJsonObject()) {
            JsonObject refs =
                    known.has("wagerCharacters") && known.get("wagerCharacters").isJsonObject()
                            ? known.getAsJsonObject("wagerCharacters").deepCopy()
                            : new JsonObject();
            Set<String> consumed = knownWagers.getOrDefault(id, Set.of());
            old.getAsJsonObject("wagerCharacters")
                    .entrySet()
                    .forEach(
                            entry -> {
                                if (!consumed.contains(entry.getKey()))
                                    refs.add(entry.getKey(), entry.getValue().deepCopy());
                            });
            next.add("wagerCharacters", refs);
        }
        return next;
    }

    private static JsonElement stakeKey(JsonElement row) {
        if (!row.isJsonObject()) return row;
        JsonObject key = row.getAsJsonObject().deepCopy();
        key.remove("count");
        JsonObject identity = new JsonObject();
        for (String field :
                List.of(
                        "owner",
                        "item",
                        "typeKey",
                        "unit",
                        "streetId",
                        "anchorX",
                        "anchorZ",
                        "x",
                        "z"))
            identity.add(field, key.has(field) ? key.get(field) : JsonNull.INSTANCE);
        return identity;
    }

    private void rememberWagers(UUID id, JsonObject raw) {
        Set<String> consumed = knownWagers.computeIfAbsent(id, ignored -> new HashSet<>());
        if (raw.has("wagerCharacters") && raw.get("wagerCharacters").isJsonObject())
            raw.getAsJsonObject("wagerCharacters")
                    .entrySet()
                    .forEach(
                            entry -> {
                                if (RawJson.uuid(new JsonPrimitive(entry.getKey())) != null
                                        && RawJson.uuid(entry.getValue()) != null)
                                    consumed.add(entry.getKey());
                            });
    }
}
