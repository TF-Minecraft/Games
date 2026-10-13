package net.tfminecraft.games.ownership;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Reject duplicate keys before a parser or typed model can discard them. */
public final class RawJson {
    public static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private RawJson() {}

    public static JsonObject read(Path path) throws IOException {
        return parse(Files.readString(path));
    }

    public static JsonObject parse(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = value(reader);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("Expected one JSON object");
            return value.getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IOException("Invalid JSON", ex);
        }
    }

    private static JsonElement value(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new IOException("Duplicate JSON key: " + name);
                    object.add(name, value(reader));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(value(reader));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Unexpected JSON value");
        };
    }

    public static UUID uuid(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            return null;
        try {
            UUID id = UUID.fromString(value.getAsString());
            return id.toString().equalsIgnoreCase(value.getAsString()) ? id : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull()
                ? null
                : value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                        ? value.getAsString()
                        : null;
    }

    public static long integer(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IOException("Expected numeric integer");
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException ex) {
            throw new IOException("Expected supported integer", ex);
        }
    }

    public static JsonElement overlay(JsonElement before, JsonElement known) {
        if (before != null && before.isJsonObject() && known.isJsonObject()) {
            JsonObject next = before.getAsJsonObject().deepCopy();
            known.getAsJsonObject()
                    .entrySet()
                    .forEach(
                            entry ->
                                    next.add(
                                            entry.getKey(),
                                            overlay(next.get(entry.getKey()), entry.getValue())));
            return next;
        }
        return known.deepCopy();
    }
}
