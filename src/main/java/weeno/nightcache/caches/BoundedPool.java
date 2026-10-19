package weeno.nightcache.caches;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import weeno.nightcache.interfaces.ObjectPool;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link ObjectPool} borné en pile LIFO sur tableau : {@code borrow}/{@code release} n'allouent rien (l'ancienne
 * file chaînée créait un nœud par restitution, ce qui annulait l'intérêt du pool). Le tableau grandit par
 * doublement jusqu'à {@code maxCapacity} seulement si le pool se remplit. Le dernier objet rendu ressort en
 * premier (encore chaud en cache CPU). Les objets excédentaires sont abandonnés : zéro fuite.
 * <p>
 * Thread: thread-safe (verrou interne, sections de quelques instructions ; le reset s'exécute hors verrou).
 */
public final class BoundedPool<T> implements ObjectPool<T> {

    private static final int INITIAL = 16;

    private final Supplier<T> factory;
    private final Consumer<T> reset;
    private final int maxCapacity;
    private final Object lock = new Object();
    private Object[] stack;
    private int size;

    private BoundedPool(@NotNull Supplier<T> factory, @NotNull Consumer<T> reset, int maxCapacity) {
        this.factory = factory;
        this.reset = reset;
        this.maxCapacity = Math.max(0, maxCapacity);
        this.stack = new Object[Math.min(INITIAL, this.maxCapacity)];
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
    @SuppressWarnings("unchecked")
    public T borrow() {
        synchronized (lock) {
            if (size > 0) {
                Object obj = stack[--size];
                stack[size] = null; // la pile ne retient pas l'objet prêté
                return (T) obj;
            }
        }
        return factory.get();
    }

    @Override
    public void release(@NotNull T obj) {
        synchronized (lock) {
            if (size >= maxCapacity) return; // pool plein : abandon, pas de reset inutile
        }
        reset.accept(obj);
        synchronized (lock) {
            if (size >= maxCapacity) return;
            if (size == stack.length) stack = Arrays.copyOf(stack, Math.min(maxCapacity, Math.max(INITIAL, stack.length << 1)));
            stack[size++] = obj;
        }
    }

    @Override
    public int available() {
        synchronized (lock) { return size; }
    }

    @Override
    public int capacity() { return maxCapacity; }

    @Override
    public void clear() {
        synchronized (lock) {
            stack = new Object[Math.min(INITIAL, maxCapacity)];
            size = 0;
        }
    }
}
