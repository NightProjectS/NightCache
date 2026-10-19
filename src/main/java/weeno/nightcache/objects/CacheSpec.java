package weeno.nightcache.objects;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

public final class CacheSpec {

    private final String name;
    private final long maxSize;
    private final long ttlWriteMs;
    private final long ttlAccessMs;
    private final boolean weakKeys;
    private final boolean weakValues;
    private final boolean softValues;
    private final boolean stats;

    private CacheSpec(Builder b) {
        this.name = b.name;
        this.maxSize = b.maxSize;
        this.ttlWriteMs = b.ttlWriteMs;
        this.ttlAccessMs = b.ttlAccessMs;
        this.weakKeys = b.weakKeys;
        this.weakValues = b.weakValues;
        this.softValues = b.softValues;
        this.stats = b.stats;
    }

    /** @return nom unique du cache (clé dans le registre central). */
    @NotNull @Contract(pure = true) public String name() { return name; }

    /** @return taille maximale du cache ({@code -1} = illimitée). */
    @Contract(pure = true) public long maxSize() { return maxSize; }

    /** @return TTL en ms après écriture ({@code -1} = pas d'expiration). */
    @Contract(pure = true) public long ttlWriteMs() { return ttlWriteMs; }

    /** @return TTL en ms après dernier accès ({@code -1} = pas d'expiration). */
    @Contract(pure = true) public long ttlAccessMs() { return ttlAccessMs; }

    /** @return {@code true} si les clés sont des weak refs. */
    @Contract(pure = true) public boolean weakKeys() { return weakKeys; }

    /** @return {@code true} si les valeurs sont des weak refs. */
    @Contract(pure = true) public boolean weakValues() { return weakValues; }

    /** @return {@code true} si les valeurs sont des soft refs (éviction sous pression mémoire). */
    @Contract(pure = true) public boolean softValues() { return softValues; }

    /** @return {@code true} si l'enregistrement des statistiques Caffeine est activé. */
    @Contract(pure = true) public boolean stats() { return stats; }

    /**
     * Point d'entrée du builder fluide.
     *
     * @param name nom unique du cache.
     */
    @NotNull
    @Contract("_ -> new")
    public static Builder named(@NotNull String name) {
        return new Builder(name);
    }

    /**
     * Raccourci : cache objet→objet borné à {@code maxSize} entrées, sans TTL.
     *
     * @param name    identifiant du cache.
     * @param maxSize nombre max d'entrées avant éviction.
     */
    @NotNull
    @Contract(value = "_, _ -> new", pure = true)
    public static CacheSpec bounded(@NotNull String name, long maxSize) {
        return named(name).maxSize(maxSize).build();
    }

    /**
     * Raccourci : cache objet→objet à expiration TTL après écriture, sans borne de taille.
     *
     * @param name identifiant du cache.
     * @param ttl  durée de vie après écriture.
     * @param unit unité de {@code ttl}.
     */
    @NotNull
    @Contract("_, _, _ -> new")
    public static CacheSpec expiring(@NotNull String name, long ttl, @NotNull TimeUnit unit) {
        return named(name).ttlWrite(ttl, unit).build();
    }

    /**
     * Raccourci : store <b>autoritaire</b> qui n'évince <b>jamais</b> automatiquement
     * (ni TTL, ni borne). Pour les données qui ne doivent jamais disparaître :
     * factions, claims, playerData en ligne.
     * <p>
     * L'appelant doit retirer les entrées explicitement (au quit / dissolution),
     * sinon la map grossit indéfiniment. Tout est vidé au {@code onDisable}.
     *
     * @param name identifiant unique du store.
     */
    @NotNull
    @Contract("_ -> new")
    public static CacheSpec persistent(@NotNull String name) {
        return named(name).build();
    }

    public static final class Builder {
        private final String name;
        private long maxSize = -1;
        private long ttlWriteMs = -1;
        private long ttlAccessMs = -1;
        private boolean weakKeys = false;
        private boolean weakValues = false;
        private boolean softValues = false;
        private boolean stats = false;

        private Builder(@NotNull String name) { this.name = name; }

        /** Borne la taille maximale du cache (éviction W-TinyLFU). */
        @NotNull public Builder maxSize(long v) { maxSize = v; return this; }

        /** Expire les entrées après leur dernière <em>écriture</em>. */
        @NotNull public Builder ttlWrite(long v, @NotNull TimeUnit u) { ttlWriteMs = u.toMillis(v); return this; }

        /** Expire les entrées après leur dernier <em>accès</em>. */
        @NotNull public Builder ttlAccess(long v, @NotNull TimeUnit u) { ttlAccessMs = u.toMillis(v); return this; }

        /** Les clés sont des weak refs. */
        @NotNull public Builder weakKeys() { weakKeys = true; return this; }

        /** Les valeurs sont des weak refs. */
        @NotNull public Builder weakValues() { weakValues = true; return this; }

        /** Les valeurs sont des soft refs (libérées sous pression mémoire). */
        @NotNull public Builder softValues() { softValues = true; return this; }

        /** Active les statistiques Caffeine (~2 % de surcharge). */
        @NotNull public Builder stats() { stats = true; return this; }

        /** @return une instance {@link CacheSpec} immuable. */
        @NotNull
        @Contract(" -> new")
        public CacheSpec build() { return new CacheSpec(this); }
    }
}
