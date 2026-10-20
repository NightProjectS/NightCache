package weeno.nightcache.testsupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Lance un même corps sur N threads au même instant (barrière) et remonte la première erreur. */
public final class Concurrency {

    public interface Body {
        void run(int thread) throws Exception;
    }

    private Concurrency() {}

    public static void run(int threads, Body body) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int t = 0; t < threads; t++) {
                int id = t;
                ex.execute(() -> {
                    try {
                        start.await();
                        body.run(id);
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }
            start.countDown();
            ex.shutdown();
            if (!ex.awaitTermination(120, TimeUnit.SECONDS)) throw new AssertionError("threads bloqués");
        } finally {
            ex.shutdownNow();
        }
        if (!errors.isEmpty()) throw new AssertionError(errors.get(0));
    }
}
