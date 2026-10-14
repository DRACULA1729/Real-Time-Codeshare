package com.collabcode.realtime_codeshare.ot;

import com.collabcode.realtime_codeshare.ot.OTService.DocumentStore;
import com.collabcode.realtime_codeshare.ot.OTService.InsertOp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Many threads hammering commit() must not throw or lose updates. */
class OTConcurrencyStressTest {

    @Test
    void concurrentCommits_noExceptionsNoLostUpdates() throws Exception {
        final int threads = 8;
        final int opsPerThread = 250;
        final DocumentStore store = new DocumentStore();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threads; t++) {
            final String clientId = "client-" + t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        // base v0 forces every op to transform against full history -> max contention.
                        store.commit(new InsertOp(clientId, 0, 0, "x"));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "stress run timed out");

        int total = threads * opsPerThread;
        assertTrue(errors.isEmpty(), () -> "commit threw under contention: " + errors);
        // Every single-char insert must survive: version and doc length both equal the op count.
        assertEquals(total, store.getVersion());
        assertEquals(total, store.getDocument().length());
    }
}
