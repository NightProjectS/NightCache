package weeno.nightcache.utils;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Function;

/**
 * Convertit une clé {@code K} vers/depuis sa forme {@code String} pour le stockage SQLite.
 * Presets fournis pour les types de clés Minecraft courants.
 *
 * @param <K> type de la clé.
 */
public final class KeyCodec<K> {

    private final Function<K, String> encode;
    private final Function<String, K> decode;

    private KeyCodec(@NotNull Function<K, String> encode, @NotNull Function<String, K> decode) {
        this.encode = encode;
        this.decode = decode;
    }

    /** @return forme texte de la clé (utilisée comme PRIMARY KEY). */
    @NotNull
    public String encode(@NotNull K key) { return encode.apply(key); }

    /** @return clé reconstruite depuis sa forme texte. */
    @NotNull
    public K decode(@NotNull String raw) { return decode.apply(raw); }

    /** Codec personnalisé. */
    @NotNull
    @Contract("_, _ -> new")
    public static <K> KeyCodec<K> of(@NotNull Function<K, String> encode, @NotNull Function<String, K> decode) {
        return new KeyCodec<>(encode, decode);
    }

    /** Codec pour clés {@link UUID} (playerData, factions par id). */
    @NotNull
    @Contract(" -> new")
    public static KeyCodec<UUID> uuid() {
        return new KeyCodec<>(UUID::toString, UUID::fromString);
    }

    /** Codec pour clés {@link String} (noms de factions, identifiants). */
    @NotNull
    @Contract(" -> new")
    public static KeyCodec<String> string() {
        return new KeyCodec<>(Function.identity(), Function.identity());
    }

    /** Codec pour clés {@link Long} (claims/blocs packés via {@link Keys}). */
    @NotNull
    @Contract(" -> new")
    public static KeyCodec<Long> longKey() {
        return new KeyCodec<>(String::valueOf, Long::parseLong);
    }
}
