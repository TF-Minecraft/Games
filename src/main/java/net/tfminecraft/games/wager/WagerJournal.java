package net.tfminecraft.games.wager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.persistence.DurableFiles;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;

/** A durable boundary between ledger postings and item/table side effects. */
public final class WagerJournal {
    record Movement(String from, String to, int value, List<String> items, CitizenTax.Levy tax) {}
    record Plan(String reason, List<Leg> legs, List<Movement> movements, int floatBefore, int floatAfter, int profit,
            Map<UUID, net.tfminecraft.tlibs.character.CharacterRef> characters, CitizenTax.Levy taxQuote) {}
    private record Intent(String operationId, Plan plan, String state) {}
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Map<Path, Intent> ACTIVE = new HashMap<>();
    private WagerJournal() {}

    /** Previous-process intents always require review, including plugin re-enables. */
    public static void initialize() { ACTIVE.clear(); }

    private static Path file(Table table) {
        return Games.plugin.getDataFolder().toPath().resolve("Data/wager-intents").resolve(table.getId() + ".json");
    }

    public static boolean blocked(Table table) {
        return Games.plugin != null && Games.plugin.getDataFolder() != null && Files.exists(file(table));
    }
    public static boolean active(Table table) {
        Intent intent=ACTIVE.get(file(table));return intent!=null && ("prepared".equals(intent.state()) || "applying".equals(intent.state()));
    }

    public static void quarantine(Table table, String reason) {
        table.setMoneyBlocked(true);
        try {
            Path file = file(table);
            com.google.gson.JsonObject review = Files.exists(file)
                    ? com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                    : new com.google.gson.JsonObject();
            if (!review.has("operationId")) review.addProperty("operationId", "games:" + table.getId() + ":" + UUID.randomUUID());
            if (!review.has("state")) review.addProperty("state", "review");
            var observations = review.has("unpaidOutcomes") ? review.getAsJsonArray("unpaidOutcomes") : new com.google.gson.JsonArray();
            observations.add(JSON.toJsonTree(Map.of("reason", reason, "characters", table.wagerCharacters())));
            review.add("unpaidOutcomes", observations);
            DurableFiles.write(file, JSON.toJson(review));
            TableManager.get().saveWager(table);
        } catch (IOException | RuntimeException ex) {
            table.setMoneyBlocked(true);
            warn(table, ex);
        }
    }

    static TxResult apply(Table table, Plan plan, java.util.function.Consumer<String> operation, Supplier<TxResult> mutation) {
        Path file = file(table);
        Intent intent = ACTIVE.get(file);
        if (intent == null && Files.exists(file)) return refused();
        if (intent != null && (!"prepared".equals(intent.state()) || !intent.plan().equals(plan))) return refused();
        try {
            if (intent == null) {
                intent = new Intent("games:" + table.getId() + ":" + UUID.randomUUID(), plan, "prepared");
                DurableFiles.write(file, JSON.toJson(intent));
                ACTIVE.put(file, intent);
            }
            operation.accept(intent.operationId());
            Result result = Denars.status(intent.operationId()).orElse(null);
            if (result == null) result = Denars.post(intent.operationId(), "games:" + plan.reason(), plan.legs());
            if (result == Result.REFUSED) {
                DurableFiles.delete(file);
                ACTIVE.remove(file);
                return refused();
            }
            if (result == Result.UNAVAILABLE) return refused();
            if (result != Result.APPLIED) {
                Intent conflict = new Intent(intent.operationId(), plan, "conflict");
                ACTIVE.put(file, conflict);
                DurableFiles.write(file, JSON.toJson(conflict));
                return refused();
            }
            Intent applying = new Intent(intent.operationId(), plan, "applying");
            ACTIVE.put(file, applying);
            DurableFiles.write(file, JSON.toJson(applying));
            TxResult moved = mutation.get();
            if (!moved.ok() || table.houseFloat() != plan.floatAfter()) throw new IllegalStateException("Item/float application differed from intent");
            TableManager.get().saveWager(table);
            if (!GuildBank.declareProfit(table, intent.operationId(), plan.profit()))
                throw new IllegalStateException("Casino profit bookkeeping is pending");
            DurableFiles.delete(file);
            ACTIVE.remove(file);
            return moved;
        } catch (IOException | RuntimeException ex) {
            table.setMoneyBlocked(true);
            warn(table, ex);
            return refused();
        }
    }

    static boolean retryable(Table table) {
        if (Games.plugin == null || Games.plugin.getDataFolder() == null) return false;
        Intent intent = ACTIVE.get(file(table));
        return intent != null && "prepared".equals(intent.state());
    }

    private static TxResult refused() { return TxResult.failed(TxResult.Reason.BANK_SHORT, 0, 0); }
    private static void warn(Table table, Exception ex) {
        if (Games.plugin != null) Games.plugin.getLogger().severe("[Games] Table " + table.getId() + " money requires review: " + ex);
    }
}
