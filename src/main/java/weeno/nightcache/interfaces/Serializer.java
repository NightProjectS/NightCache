package weeno.nightcache.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Convertit une valeur {@code V} vers/depuis une représentation texte (JSON) persistable.
 *
 * @param <V> type de l'objet sérialisé.
 */
public interface Serializer<V> {

    /**
     * @param value objet à sérialiser.
     * @return représentation texte compacte (JSON).
     */
    @NotNull
    String serialize(@NotNull V value);

    /**
     * @param data texte produit par {@link #serialize(Object)}.
     * @return objet reconstruit, ou {@code null} si {@code data} est illisible.
     */
    @Nullable
    V deserialize(@NotNull String data);
}
