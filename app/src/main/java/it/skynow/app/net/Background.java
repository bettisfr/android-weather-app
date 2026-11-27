package it.skynow.app.net;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Threads for blocking network and disk work. Pass {@link #IO} to {@code CompletableFuture.supplyAsync}:
 * without it the work lands on the common fork-join pool, which has only one or two threads on many
 * phones and then runs every download one after another. A task on this pool must never block
 * waiting for another task on it: with a few threads that can stall every download.
 */
public final class Background {
    private static final int THREADS = 4;

    public static final Executor IO = create();

    private Background() {}

    private static Executor create() {
        AtomicInteger count = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), work -> {
                    Thread thread = new Thread(work, "SkyHere-io-" + count.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
