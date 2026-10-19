package weeno.nightcache.objects;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

public final class CacheStats {

    private final String name;
    private final long estimatedSize;
    private final long hitCount;
    private final long missCount;
    private final long evictionCount;
    private final double hitRate;

    public CacheStats(@NotNull String name, long estimatedSize, long hitCount, long missCount, long evictionCount) {
        this.name = name;
        this.estimatedSize = estimatedSize;
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.evictionCount = evictionCount;
        long total = hitCount + missCount;
        this.hitRate = total == 0 ? 1.0 : (double) hitCount / total;
    }

    /** @return nom du cache auquel appartient ce snapshot. */
    @NotNull @Contract(pure = true) public String name() { return name; }

    /** @return nombre d'entrées au moment du snapshot. */
    @Contract(pure = true) public long estimatedSize() { return estimatedSize; }

    /** @return nombre total de lectures ayant trouvé une entrée valide. */
    @Contract(pure = true) public long hitCount() { return hitCount; }

    /** @return nombre total de lectures n'ayant pas trouvé d'entrée valide. */
    @Contract(pure = true) public long missCount() { return missCount; }

    /** @return nombre total d'entrées expulsées (TTL ou capacité). */
    @Contract(pure = true) public long evictionCount() { return evictionCount; }

    /** @return taux de succès entre {@code 0.0} et {@code 1.0} ({@code 1.0} si aucune lecture). */
    @Contract(pure = true) public double hitRate() { return hitRate; }

    @NotNull
    @Override
    public String toString() {
        return "CacheStats{name=" + name
                + ", size=" + estimatedSize
                + ", hitRate=" + String.format("%.1f%%", hitRate * 100)
                + ", evictions=" + evictionCount + "}";
    }
}
