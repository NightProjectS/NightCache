package weeno.nightcache.testsupport;

import java.lang.management.ManagementFactory;

/**
 * Mesure exacte des octets alloués par le thread courant ({@code com.sun.management.ThreadMXBean}). Les résultats
 * sont publiés dans {@link #sink} pour que l'analyse d'échappement ne supprime pas une allocation à mesurer.
 */
public final class Alloc {

    public static volatile Object sink;
    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private Alloc() {}

    /** Octets alloués par {@code calls} appels, après {@code warmup} appels d'échauffement (JIT). */
    public static long bytes(int warmup, int calls, Runnable op) {
        for (int i = 0; i < warmup; i++) op.run();
        long id = Thread.currentThread().getId();
        long overhead = measure(id, () -> {});
        long before = MX.getThreadAllocatedBytes(id);
        for (int i = 0; i < calls; i++) op.run();
        long after = MX.getThreadAllocatedBytes(id);
        return Math.max(0L, after - before - overhead);
    }

    private static long measure(long id, Runnable r) {
        long before = MX.getThreadAllocatedBytes(id);
        r.run();
        return MX.getThreadAllocatedBytes(id) - before;
    }
}
