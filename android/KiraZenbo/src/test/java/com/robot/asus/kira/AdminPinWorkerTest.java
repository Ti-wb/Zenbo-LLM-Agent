package com.robot.asus.kira;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AdminPinWorkerTest {
    private final AdminPinWorker worker = new AdminPinWorker();
    private final BlockingQueue<Runnable> replies = new LinkedBlockingQueue<>();

    @After public void close() { worker.stop(); }

    @Test public void hashingDoesNotBlockCallerAndConcurrentAttemptsHaveNoQueue() throws Exception {
        worker.start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Thread eventLoop = Thread.currentThread();
        AtomicReference<Thread> hashThread = new AtomicReference<>();
        AtomicReference<Thread> responseThread = new AtomicReference<>();
        try {
            submitEventually(worker, () -> {
                hashThread.set(Thread.currentThread());
                calls.incrementAndGet();
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return true;
            }, result -> {
                assertEquals(Boolean.TRUE, result.value);
                assertNull(result.error);
                responseThread.set(Thread.currentThread());
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) {
                assertFalse(worker.submit(() -> { calls.incrementAndGet(); return false; },
                        replies::add, result -> fail("Busy attempts must not run")));
            }
            assertNotSame(eventLoop, hashThread.get());
            assertNull(responseThread.get());
            release.countDown();
            Runnable response = nextReply();
            assertFalse(worker.submit(() -> true, replies::add, result -> fail("Premature admission")));
            response.run();
            assertSame(eventLoop, responseThread.get());
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test public void stopRevokesAlreadyPostedResultsAcrossRestart() throws Exception {
        worker.start();
        AtomicInteger delivered = new AtomicInteger();
        submitEventually(worker, () -> true, result -> delivered.incrementAndGet());
        Runnable oldReply = nextReply();
        worker.stop();
        worker.start();
        oldReply.run();
        assertEquals(0, delivered.get());
        submitEventually(worker, () -> true, result -> delivered.incrementAndGet());
        nextReply().run();
        assertEquals(1, delivered.get());
    }

    @Test public void recreatedServerCannotOverlapDerivationThatIgnoresInterrupt() throws Exception {
        worker.start();
        AdminPinWorker replacement = new AdminPinWorker();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger delivered = new AtomicInteger();
        try {
            submitEventually(worker, () -> {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { done = release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException expected) { interrupted.set(true); }
                }
                return true;
            }, result -> delivered.incrementAndGet());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            worker.stop();
            replacement.start();
            assertFalse(replacement.submit(() -> true, replies::add, result -> fail("Second hashing worker")));
            release.countDown();
            nextReply().run();
            assertTrue(interrupted.get());
            assertEquals(0, delivered.get());
            submitEventually(replacement, () -> true, result -> delivered.incrementAndGet());
            nextReply().run();
            assertEquals(1, delivered.get());
        } finally { release.countDown(); replacement.stop(); }
    }

    @Test public void operationFailureIsDeliveredOnReplyExecutorAndReleasesAdmission() throws Exception {
        worker.start();
        AtomicInteger delivered = new AtomicInteger();
        submitEventually(worker, () -> { throw new IllegalStateException("synthetic failure"); }, result -> {
            assertTrue(result.error instanceof IllegalStateException);
            assertNull(result.value);
            delivered.incrementAndGet();
        });
        assertEquals(0, delivered.get());
        nextReply().run();
        assertEquals(1, delivered.get());
        submitEventually(worker, () -> true, result -> delivered.incrementAndGet());
        nextReply().run();
        assertEquals(2, delivered.get());
    }

    @Test public void unopenedOrStoppedWorkerRejectsWork() {
        assertFalse(worker.submit(() -> true, replies::add, result -> fail("Not started")));
        worker.start();
        worker.stop();
        assertFalse(worker.submit(() -> true, replies::add, result -> fail("Stopped")));
    }

    private Runnable nextReply() throws Exception {
        Runnable reply = replies.poll(5, TimeUnit.SECONDS);
        assertNotNull("Timed out waiting for PIN result", reply);
        return reply;
    }

    private void submitEventually(AdminPinWorker target, java.util.concurrent.Callable<Boolean> operation,
                                  AdminPinWorker.Completion<Boolean> completed) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!target.submit(operation, replies::add, completed)) {
            assertTrue("Worker did not become available", System.nanoTime() < deadline);
            Thread.sleep(1L);
        }
    }
}
