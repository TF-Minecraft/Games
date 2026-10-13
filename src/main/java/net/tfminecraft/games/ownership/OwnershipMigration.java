package net.tfminecraft.games.ownership;

import com.google.gson.JsonObject;

import net.tfminecraft.games.persistence.DurableFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Per-file expand migration: archive and frozen intent precede owner-envelope publication. */
public final class OwnershipMigration {
    private final Path journals;
    private final BooleanSupplier ready;
    private final Function<UUID, Optional<UUID>> heir;

    public OwnershipMigration(Path journals) {
        this(journals, CharacterAccess::heirReady, CharacterAccess::heir);
    }

    public OwnershipMigration(
            Path journals, BooleanSupplier ready, Function<UUID, Optional<UUID>> heir) {
        this.journals = journals;
        this.ready = ready;
        this.heir = heir;
    }

    public boolean prepare(Path source) throws IOException {
        Path folder =
                journals.resolve(
                        hash(
                                source.getFileName()
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (Files.isDirectory(folder)) {
            try (var files = Files.list(folder)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                    JsonObject plan = RawJson.read(file);
                    validate(plan, source, file);
                    if (!plan.get("complete").getAsBoolean()) publish(source, file, plan);
                }
            }
            try (var files = Files.list(folder)) {
                for (Path archive :
                        files.filter(path -> path.toString().endsWith(".original")).toList()) {
                    String name = archive.getFileName().toString().replace(".original", "");
                    if (!Files.exists(archive.resolveSibling(name + ".json"))
                            && !hash(Files.readAllBytes(source)).equals(name))
                        throw new IOException("Orphan ownership archive needs review");
                }
            }
        }
        byte[] before = Files.readAllBytes(source);
        JsonObject raw = RawJson.parse(new String(before, java.nio.charset.StandardCharsets.UTF_8));
        validateRow(raw);
        UUID account = RawJson.uuid(raw.get("ownerPlayer"));
        UUID character = RawJson.uuid(raw.get("ownerCharacterId"));
        boolean needsOwner = account != null && character == null,
                mode = !OwnershipKind.modeRecorded(raw);
        boolean ownerChange = false;
        Optional<UUID> reviewed = Optional.empty();
        if (needsOwner) {
            if (!ready.getAsBoolean()) {
                if (OwnershipKind.of(raw) == OwnershipKind.PRIVATE) return false;
            } else {
                reviewed = heir.apply(account);
                ownerChange =
                        reviewed.isPresent()
                                || !raw.has("ownerIdentityVersion")
                                || RawJson.integer(raw.get("ownerIdentityVersion")) != 2;
            }
        }
        if (!ownerChange && !mode) return true;
        JsonObject next = raw.deepCopy();
        if (ownerChange) {
            next.addProperty("ownerIdentityVersion", 2);
            if (reviewed.isPresent())
                next.addProperty("ownerCharacterId", reviewed.get().toString());
            else next.add("ownerCharacterId", com.google.gson.JsonNull.INSTANCE);
        }
        if (mode) next.addProperty("staffMint", OwnershipKind.effectiveMint(raw));
        String beforeHash = hash(before), output = RawJson.JSON.toJson(next);
        Path archive = folder.resolve(beforeHash + ".original");
        Path file = folder.resolve(beforeHash + ".json");
        if (Files.exists(archive) && !hash(Files.readAllBytes(archive)).equals(beforeHash))
            throw new IOException("Ownership archive conflict");
        if (!Files.exists(archive)) DurableFiles.write(archive, before);
        JsonObject plan = new JsonObject();
        plan.addProperty("source", source.getFileName().toString());
        plan.addProperty("before", beforeHash);
        plan.addProperty("after", hash(output.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (account == null) plan.add("account", com.google.gson.JsonNull.INSTANCE);
        else plan.addProperty("account", account.toString());
        plan.add(
                "character",
                next.has("ownerCharacterId")
                        ? next.get("ownerCharacterId")
                        : com.google.gson.JsonNull.INSTANCE);
        plan.addProperty("ownerChange", ownerChange);
        if (mode) plan.add("staffMint", next.get("staffMint"));
        plan.add("planned", next);
        plan.addProperty("complete", false);
        DurableFiles.write(file, RawJson.JSON.toJson(plan));
        publish(source, file, plan);
        return true;
    }

    private static void validate(JsonObject plan, Path source, Path file) throws IOException {
        String before = RawJson.string(plan, "before"), after = RawJson.string(plan, "after");
        if (before == null
                || !before.matches("[a-f0-9]{64}")
                || after == null
                || !after.matches("[a-f0-9]{64}")
                || !source.getFileName().toString().equals(RawJson.string(plan, "source"))
                || !file.getFileName().toString().equals(before + ".json")
                || !plan.has("complete")
                || !plan.get("complete").isJsonPrimitive()
                || !plan.get("complete").getAsJsonPrimitive().isBoolean()
                || !plan.has("planned")
                || !plan.get("planned").isJsonObject())
            throw new IOException("Invalid ownership intent envelope");
        Path archive = file.resolveSibling(before + ".original");
        byte[] bytes = Files.readAllBytes(archive);
        if (!before.equals(hash(bytes))) throw new IOException("Ownership archive changed");
        JsonObject original =
                RawJson.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        validateRow(original);
        UUID account = RawJson.uuid(plan.get("account")),
                character = RawJson.uuid(plan.get("character"));
        boolean ownerChange = !plan.has("ownerChange") || booleanValue(plan, "ownerChange");
        if (ownerChange && RawJson.uuid(original.get("ownerCharacterId")) != null)
            throw new IOException("Recorded character cannot be recaptured");
        if (!plan.has("account")
                || (!plan.get("account").isJsonNull() && account == null)
                || !java.util.Objects.equals(account, RawJson.uuid(original.get("ownerPlayer")))
                || (ownerChange && account == null)
                || (plan.get("character") == null
                        || (!plan.get("character").isJsonNull() && character == null)))
            throw new IOException("Invalid frozen owner mapping");
        JsonObject expected = original.deepCopy();
        if (ownerChange) {
            expected.addProperty("ownerIdentityVersion", 2);
            expected.add("ownerCharacterId", plan.get("character"));
        } else if (!java.util.Objects.equals(
                character, RawJson.uuid(original.get("ownerCharacterId"))))
            throw new IOException("Mode-only plan changed its owner");
        if (plan.has("staffMint")) {
            if (OwnershipKind.modeRecorded(original))
                throw new IOException("Mode plan overwrote explicit funding mode");
            expected.addProperty("staffMint", booleanValue(plan, "staffMint"));
        } else if (!ownerChange) throw new IOException("Empty table migration plan");
        if (!expected.equals(plan.getAsJsonObject("planned"))
                || !after.equals(
                        hash(
                                RawJson.JSON
                                        .toJson(expected)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            throw new IOException("Frozen ownership output changed");
    }

    private static boolean booleanValue(JsonObject plan, String key) throws IOException {
        if (!plan.has(key)
                || !plan.get(key).isJsonPrimitive()
                || !plan.get(key).getAsJsonPrimitive().isBoolean())
            throw new IOException("Invalid frozen " + key);
        return plan.get(key).getAsBoolean();
    }

    private static void publish(Path source, Path file, JsonObject plan) throws IOException {
        validate(plan, source, file);
        String current = hash(Files.readAllBytes(source));
        if (current.equals(plan.get("before").getAsString()))
            DurableFiles.write(source, RawJson.JSON.toJson(plan.get("planned")));
        else if (!current.equals(plan.get("after").getAsString()))
            throw new IOException("Table changed after frozen ownership planning");
        plan.addProperty("complete", true);
        DurableFiles.write(file, RawJson.JSON.toJson(plan));
    }

    public static void validateRow(JsonObject raw) throws IOException {
        if (raw.has("ownerIdentityVersion")) {
            long version = RawJson.integer(raw.get("ownerIdentityVersion"));
            if (version != 1 && version != 2)
                throw new IOException("Unsupported owner identity version");
        }
        for (String key :
                java.util.List.of(
                        "ownerPlayer", "ownerCharacterId", "ownerGuildIncarnation", "floatOwner")) {
            if (raw.has(key) && !raw.get(key).isJsonNull() && RawJson.uuid(raw.get(key)) == null)
                throw new IOException("Invalid " + key);
        }
        if (RawJson.uuid(raw.get("ownerCharacterId")) != null
                && RawJson.uuid(raw.get("ownerPlayer")) == null)
            throw new IOException("Character owner has no account");
        if (RawJson.uuid(raw.get("ownerCharacterId")) != null
                && (!raw.has("ownerIdentityVersion")
                        || RawJson.integer(raw.get("ownerIdentityVersion")) != 2))
            throw new IOException("Character owner lacks schema 2");
        if (raw.has("ownerGuildId")
                && !raw.get("ownerGuildId").isJsonNull()
                && RawJson.string(raw, "ownerGuildId") == null)
            throw new IOException("Invalid guild association");
        if (RawJson.uuid(raw.get("ownerGuildIncarnation")) != null
                && (RawJson.string(raw, "ownerGuildId") == null
                        || RawJson.string(raw, "ownerGuildId").isBlank()))
            throw new IOException("Guild incarnation lacks its guild association");
        for (String key :
                java.util.List.of(
                        "staffMint",
                        "autoDealer",
                        "ownerGuildMigrated",
                        "ownershipLive",
                        "ownershipPaying")) {
            if (raw.has(key)
                    && !raw.get(key).isJsonNull()
                    && (!raw.get(key).isJsonPrimitive()
                            || !raw.get(key).getAsJsonPrimitive().isBoolean()))
                throw new IOException("Invalid " + key);
        }
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
