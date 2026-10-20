package weeno.nightcache.testsupport;

import java.util.ArrayList;
import java.util.List;

/** Threads de la lib encore vivants (tous nommés {@code NightCache-…}). */
public final class Threads {

    private Threads() {}

    public static List<String> nightCache() {
        List<String> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("NightCache-")) out.add(t.getName() + " " + t.getState());
        }
        return out;
    }

    /** Attend (borné) la fin des threads de la lib qui terminent leur dernière tâche. */
    public static List<String> awaitNone(long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        List<String> alive = nightCache();
        while (!alive.isEmpty() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
            alive = nightCache();
        }
        return alive;
    }
}
