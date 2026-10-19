package weeno.nightcache.caches;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import weeno.nightcache.interfaces.ObjectPool;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Implémentation bornée et lock-free de {@link ObjectPool} ({@link ConcurrentLinkedQueue}).
 * Les objets excédentaires (pool plein) sont abandonnés sans allocation — zéro fuite.
 */
public final class BoundedPool<T> implements ObjectPool<T> {

    private final ConcurrentLinkedQueue<T> pool;
    private final Supplier<T> factory;
    private final Consumer<T> reset;
    private final int maxCapacity;
    private final AtomicInteger pooled = new AtomicInteger(0);

    private BoundedPool(@NotNull Supplier<T> factory, @NotNull Consumer<T> reset, int maxCapacity) {
        this.factory = factory;
        this.reset = reset;
        this.maxCapacity = maxCapacity;
        this.pool = new ConcurrentLinkedQueue<>();
    }

    /** Crée un pool borné avec reset personnalisé. */
    @NotNull
    @Contract("_, _, _ -> new")
    public static <T> BoundedPool<T> of(@NotNull Supplier<T> factory, @NotNull Consumer<T> reset, int maxCapacity) {
        return new BoundedPool<>(factory, reset, maxCapacity);
    }

    /** Crée un pool borné sans reset. */
    @NotNull
    @Contract("_, _ -> new")
    public static <T> BoundedPool<T> of(@NotNull Supplier<T> factory, int maxCapacity) {
        return new BoundedPool<>(factory, t -> {}, maxCapacity);
    }

    @Override @NotNull
    public T borrow() {
        T obj = pool.poll();
        if (obj != null) { pooled.decrementAndGet(); return obj; }
        return factory.get();
    }

    @Override
    public void release(@NotNull T obj) {
        if (pooled.get() >= maxCapacity) return;
        reset.accept(obj);
        pool.offer(obj);
        pooled.incrementAndGet();
    }

    @Override
    public int available() { return pooled.get(); }

    @Override
    public int capacity() { return maxCapacity; }

    @Override
    public void clear() {
        pool.clear();
        pooled.set(0);
    }
}
