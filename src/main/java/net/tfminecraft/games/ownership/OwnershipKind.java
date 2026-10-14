package net.tfminecraft.games.ownership;

import com.google.gson.JsonObject;

import net.tfminecraft.games.table.Table;

/** Funding metadata is explicit; guild association survives staff mint and human takeover. */
public enum OwnershipKind {
    PRIVATE,
    GUILD,
    STAFF;

    public static OwnershipKind of(JsonObject row) {
        String guild = RawJson.string(row, "ownerGuildId");
        if (guild != null && !guild.isBlank()) return GUILD;
        return effectiveMint(row) ? STAFF : PRIVATE;
    }

    public static boolean modeRecorded(JsonObject row) {
        return row.has("staffMint") && !row.get("staffMint").isJsonNull();
    }

    public static boolean effectiveMint(JsonObject row) {
        if (modeRecorded(row)) return row.get("staffMint").getAsBoolean();
        if (row.has("autoDealer") && !row.get("autoDealer").isJsonNull()) return false;
        var layout = net.tfminecraft.games.cache.Cache.layoutOf(RawJson.string(row, "gameId"));
        return layout != null && layout.autoDealer();
    }

    public static OwnershipKind of(Table table) {
        if (table.ownerGuildId() != null && !table.ownerGuildId().isBlank()) return GUILD;
        return table.staffMint() ? STAFF : PRIVATE;
    }
}
