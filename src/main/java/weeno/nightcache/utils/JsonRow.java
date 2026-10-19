package weeno.nightcache.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import weeno.nightcache.interfaces.Serializer;

/**
 * Ligne JSON compacte pour les valeurs des {@code SqliteStore} : remplace les colonnes SQL
 * de l'ancien schéma MySQL. Clés courtes (1-3 lettres) pour limiter la taille disque.
 * Les champs {@code null} ne sont pas écrits et se relisent comme {@code null}/0.
 */
public final class JsonRow {

    /** Sérialiseur partagé par tous les stores dont la valeur est une {@link JsonRow}. */
    public static final Serializer<JsonRow> SERIALIZER = new Serializer<JsonRow>() {
        @NotNull
        @Override
        public String serialize(@NotNull JsonRow value) {
            return value.toString();
        }

        @Nullable
        @Override
        public JsonRow deserialize(@NotNull String data) {
            return parse(data);
        }
    };

    private static final Gson GSON = new Gson();

    private final JsonObject o;

    private JsonRow(@NotNull JsonObject o) {
        this.o = o;
    }

    @NotNull
    @Contract(" -> new")
    public static JsonRow create() {
        return new JsonRow(new JsonObject());
    }

    /** @return la ligne parsée, ou {@code null} si le texte n'est pas un objet JSON. */
    @Nullable
    public static JsonRow parse(@NotNull String raw) {
        try {
            // Gson#fromJson(String, Class) existe de gson 2.2.4 (Spigot 1.8) aux versions récentes,
            // contrairement à JsonParser.parseString (2.8.6+).
            JsonElement e = GSON.fromJson(raw, JsonElement.class);
            return e != null && e.isJsonObject() ? new JsonRow(e.getAsJsonObject()) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @NotNull
    public JsonRow put(@NotNull String k, @Nullable String v) {
        if (v != null) o.addProperty(k, v);
        return this;
    }

    @NotNull
    public JsonRow put(@NotNull String k, long v) {
        o.addProperty(k, v);
        return this;
    }

    @NotNull
    public JsonRow put(@NotNull String k, boolean v) {
        o.addProperty(k, v);
        return this;
    }

    @NotNull
    public JsonRow put(@NotNull String k, double v) {
        o.addProperty(k, v);
        return this;
    }

    @NotNull
    public JsonRow put(@NotNull String k, @Nullable java.util.UUID v) {
        if (v != null) o.addProperty(k, v.toString());
        return this;
    }

    public boolean has(@NotNull String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull();
    }

    @Nullable
    public String getString(@NotNull String k) {
        return str(k);
    }

    @Nullable
    public String getString(@NotNull String k, @Nullable String fallback) {
        String v = str(k);
        return v != null ? v : fallback;
    }

    public long getLong(@NotNull String k, long fallback) {
        try {
            return has(k) ? o.get(k).getAsLong() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public double getDouble(@NotNull String k, double fallback) {
        try {
            return has(k) ? o.get(k).getAsDouble() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public boolean getBoolean(@NotNull String k, boolean fallback) {
        try {
            return has(k) ? o.get(k).getAsBoolean() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** @return l'UUID, ou {@code null} si absent ou invalide. */
    @Nullable
    public java.util.UUID getUuid(@NotNull String k) {
        String v = str(k);
        if (v == null) return null;
        try {
            return java.util.UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    public String str(@NotNull String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    public int i(@NotNull String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }

    public long l(@NotNull String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0L : e.getAsLong();
    }

    public boolean b(@NotNull String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    @NotNull
    @Override
    public String toString() {
        return o.toString();
    }
}
