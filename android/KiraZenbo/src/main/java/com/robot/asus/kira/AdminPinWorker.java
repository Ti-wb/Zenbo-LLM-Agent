package com.robot.asus.kira;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One expensive PIN operation at a time, with no waiting work or stale callbacks. */
final class AdminPinWorker {
    private static final Object EXECUTOR_LOCK = new Object();
    private static ThreadPoolExecutor executor;
    private static AdminPinWorker runningOwner;
    private Object pending;
    private long generation;
    private boolean open;

    synchronized void start() {
        if (open) return;
        open = true;
        generation++;
    }

    synchronized void stop() {
        open = false;
        generation++;
        pending = null;
        // PBKDF may ignore interruption. A service restart creates a new worker
        // instance, so the process-wide executor is retained until it terminates.
        synchronized (EXECUTOR_LOCK) {
            if (executor != null && (runningOwner == this || runningOwner == null)) executor.shutdownNow();
        }
    }

    synchronized <T> boolean submit(Callable<T> work, Executor replies, Completion<T> completed) {
        if (!open || pending != null) return false;
        synchronized (EXECUTOR_LOCK) {
            if (executor == null || executor.isShutdown()) {
                if (executor != null && !executor.isTerminated()) return false;
                executor = new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
                        new SynchronousQueue<>(), runnable -> {
                            Thread thread = new Thread(runnable, "zenbo-admin-pin");
                            thread.setDaemon(true);
                            return thread;
                        });
            }
            Object ticket = new Object();
            long acceptedGeneration = generation;
            pending = ticket;
            try {
                executor.execute(() -> {
                    try {
                        T value = null;
                        Exception error = null;
                        try { value = work.call(); }
                        catch (Exception failure) { error = failure; }
                        Result<T> result = new Result<>(value, error);
                        try {
                            replies.execute(() -> {
                                try {
                                    synchronized (AdminPinWorker.this) {
                                        if (!open || acceptedGeneration != generation || pending != ticket) return;
                                    }
                                    completed.onComplete(result);
                                } finally {
                                    clearPending(ticket);
                                }
                            });
                        } catch (RuntimeException deliveryFailure) {
                            clearPending(ticket);
                        }
                    } finally {
                        synchronized (EXECUTOR_LOCK) {
                            if (runningOwner == AdminPinWorker.this) runningOwner = null;
                        }
                    }
                });
                runningOwner = this;
                return true;
            } catch (RejectedExecutionException busy) {
                clearPending(ticket);
                return false;
            }
        }
    }

    private synchronized void clearPending(Object ticket) {
        if (pending == ticket) pending = null;
    }

    static final class Result<T> {
        final T value;
        final Exception error;

        Result(T value, Exception error) {
            this.value = value;
            this.error = error;
        }
    }

    interface Completion<T> { void onComplete(Result<T> result); }
}
