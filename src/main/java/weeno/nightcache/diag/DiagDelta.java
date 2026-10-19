package weeno.nightcache.diag;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/** Écart d'un compteur entre deux {@link DiagSnapshot}. */
public final class DiagDelta {

    private final String key;
    private final long before;
    private final long after;

    public DiagDelta(@NotNull String key, long before, long after) {
        this.key = key;
        this.before = before;
        this.after = after;
    }

    @NotNull @Contract(pure = true) public String key() { return key; }
    @Contract(pure = true) public long before() { return before; }
    @Contract(pure = true) public long after() { return after; }

    @Contract(pure = true)
    public long delta() {
        return after - before;
    }

    @Contract(pure = true)
    public boolean isGrowth() {
        return after > before;
    }
}
