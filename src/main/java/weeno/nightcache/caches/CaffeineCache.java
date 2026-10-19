package weeno.nightcache.caches;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import weeno.nightcache.interfaces.ManagedCache;
import weeno.nightcache.objects.CacheSpec;
import weeno.nightcache.objects.CacheStats;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class CaffeineCache<K, V> implements ManagedCache<K, V> {

    private final String name;
    private final Cache<K, V> cache;
    private final boolean statsEnabled;

    public CaffeineCache(@NotNull CacheSpec spec) {
        this.name = spec.name();
        this.statsEnabled = spec.stats();

        Caffeine<Object, Object> b = Caffeine.newBuilder();
        if (spec.maxSize() > 0) {
            b.maximumSize(spec.maxSize());
            b.initialCapacity((int) Math.min(spec.maxSize(), 256));
        }
        if (spec.ttlWriteMs() > 0) b.expireAfterWrite(spec.ttlWriteMs(), TimeUnit.MILLISECONDS);
        if (spec.ttlAccessMs() > 0) b.expireAfterAccess(spec.ttlAccessMs(), TimeUnit.MILLISECONDS);
        if (spec.weakKeys()) b.weakKeys();
        if (spec.weakValues()) b.weakValues();
        if (spec.softValues()) b.softValues();
        if (spec.stats()) b.recordStats();

        this.cache = b.build();
    }

    @Override @NotNull @Contract(pure = true)
    public String name() { return name; }

    @Override @Nullable
    public V getIfPresent(@NotNull K key) { return cache.getIfPresent(key); }

    @Override @NotNull
    public V get(@NotNull K key, @NotNull Function<K, V> loader) { return cache.get(key, loader); }

    @Override
    public void put(@NotNull K key, @NotNull V value) { cache.put(key, value); }

    @Override
    public void invalidate(@NotNull K key) { cache.invalidate(key); }

    @Override
    public void invalidateAll() { cache.invalidateAll(); }

    @Override @Contract(pure = true)
    public long estimatedSize() { return cache.estimatedSize(); }

    @Override @NotNull
    public ConcurrentMap<K, V> asMap() { return cache.asMap(); }

    @Override @NotNull
    public CacheStats stats() {
        if (!statsEnabled) return new CacheStats(name, cache.estimatedSize(), 0, 0, 0);
        com.github.benmanes.caffeine.cache.stats.CacheStats s = cache.stats();
        return new CacheStats(name, cache.estimatedSize(), s.hitCount(), s.missCount(), s.evictionCount());
    }

    @Override
    public void cleanUp() { cache.cleanUp(); }
}
