package net.tfminecraft.games.guild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tfminecraft.games.persistence.DurableFiles;

/** Stamp an old table's guild incarnation once; a deleted guild never adopts a later namesake. */
public final class GuildTableMigration {
    private GuildTableMigration() {}

    public static boolean prepare(Path tableFile, Path migrationFolder) throws IOException {
        JsonObject data = net.tfminecraft.games.ownership.RawJson.read(tableFile);
        if (data.has("ownerGuildMigrated") && !data.get("ownerGuildMigrated").isJsonNull()
                && data.get("ownerGuildMigrated").getAsBoolean()) return true;
        if (!data.has("ownerGuildId") || data.get("ownerGuildId").isJsonNull()
                || data.get("ownerGuildId").getAsString().isBlank()) return true;
        if (!GuildTables.ready()) return false;
        Path backup = migrationFolder.resolve("backup").resolve(tableFile.getFileName());
        Path manifest = migrationFolder.resolve(tableFile.getFileName());
        JsonObject migrated;
        if (Files.exists(manifest)) {
            // A complete plan is written before the table. Replay the exact incarnation, never resolve again.
            migrated = net.tfminecraft.games.ownership.RawJson.read(manifest);
            JsonObject original = net.tfminecraft.games.ownership.RawJson.read(backup);
            if (!data.equals(original) && !data.equals(migrated)) throw new IOException("Table changed after frozen guild planning");
        } else {
            GuildTables.Lookup lookup = GuildTables.resolveIncarnation(data.get("ownerGuildId").getAsString());
            // A failed lookup is not a deleted guild: stamp nothing and let a later pass retry.
            if (lookup.failed()) return false;
            if (!Files.exists(backup)) DurableFiles.write(backup, Files.readAllBytes(tableFile));
            migrated = data.deepCopy();
            UUID incarnation = lookup.incarnation();
            if (incarnation != null) migrated.addProperty("ownerGuildIncarnation", incarnation.toString());
            migrated.addProperty("ownerGuildMigrated", true);
            DurableFiles.write(manifest, migrated.toString());
        }
        DurableFiles.write(tableFile, migrated.toString());
        return true;
    }
}
