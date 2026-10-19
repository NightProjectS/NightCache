package weeno.nightcache.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import weeno.nightcache.interfaces.Serializer;

import java.lang.reflect.Type;

/**
 * Sérialiseur JSON basé sur Gson (version moderne shadée avec le plugin —
 * celle du serveur 1.8 ne supporte pas les records sur JVM récente).
 * Produit du JSON compact (pas de pretty-print) pour minimiser l'espace disque.
 *
 * @param <V> type de l'objet sérialisé.
 */
public final class GsonSerializer<V> implements Serializer<V> {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Type type;

    private GsonSerializer(@NotNull Type type) {
        this.type = type;
    }

    /**
     * Crée un sérialiseur pour une classe simple (sans génériques).
     *
     * @param type classe de la valeur.
     * @param <V>  type sérialisé.
     */
    @NotNull
    @Contract("_ -> new")
    public static <V> GsonSerializer<V> of(@NotNull Class<V> type) {
        return new GsonSerializer<>(type);
    }

    /**
     * Crée un sérialiseur pour un type générique (ex. {@code new TypeToken<List<X>>(){}.getType()}).
     *
     * @param type type complet (avec génériques).
     * @param <V>  type sérialisé.
     */
    @NotNull
    @Contract("_ -> new")
    public static <V> GsonSerializer<V> of(@NotNull Type type) {
        return new GsonSerializer<>(type);
    }

    @Override
    @NotNull
    public String serialize(@NotNull V value) {
        return GSON.toJson(value, type);
    }

    @Override
    @Nullable
    public V deserialize(@NotNull String data) {
        try {
            return GSON.fromJson(data, type);
        } catch (RuntimeException e) {
            return null; // ligne illisible : ignorée par l'appelant
        }
    }
}
