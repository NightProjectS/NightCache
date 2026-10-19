package weeno.nightcache.objects;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import weeno.nightcache.interfaces.ObjectPool;

/**
 * Snapshot immutable des statistiques d'un {@link ObjectPool}.
 */
public final class PoolStats {

    private final String name;
    private final int available;
    private final int capacity;
    private final double fillRate;

    public PoolStats(@NotNull String name, int available, int capacity) {
        this.name = name;
        this.available = available;
        this.capacity = capacity;
        this.fillRate = capacity == 0 ? 0.0 : (double) available / capacity;
    }

    /** @return identifiant du pool. */
    @NotNull @Contract(pure = true) public String name() { return name; }

    /** @return nombre d'objets disponibles au moment du snapshot. */
    @Contract(pure = true) public int available() { return available; }

    /** @return capacité maximale configurée. */
    @Contract(pure = true) public int capacity() { return capacity; }

    /** @return taux de remplissage entre {@code 0.0} (vide) et {@code 1.0} (plein). */
    @Contract(pure = true) public double fillRate() { return fillRate; }

    /**
     * Capture un snapshot depuis un pool existant.
     *
     * @param name identifiant à associer au snapshot.
     * @param pool pool à observer.
     */
    @NotNull
    @Contract("_, _ -> new")
    public static PoolStats snapshot(@NotNull String name, @NotNull ObjectPool<?> pool) {
        return new PoolStats(name, pool.available(), pool.capacity());
    }

    @NotNull
    @Override
    public String toString() {
        return "PoolStats{name=" + name
                + ", available=" + available + "/" + capacity
                + ", fill=" + String.format("%.1f%%", fillRate * 100) + "}";
    }
}
