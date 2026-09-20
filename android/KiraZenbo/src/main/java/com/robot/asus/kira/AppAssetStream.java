package com.robot.asus.kira;

import com.koushikdutta.async.Util;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transfers APK assets with AndroidAsync's bounded, backpressure-aware stream pump. */
final class AppAssetStream {
    interface Pump {
        void start(InputStream input, CompletedCallback completed);
    }

    static void send(InputStream input, AsyncHttpServerResponse response) {
        send(input, response, (owned, completed) -> Util.pump(owned, response, completed));
    }

    static void send(InputStream input, AsyncHttpServerResponse response, Pump pump) {
        AsyncSocket socket = response.getSocket();
        transfer(input, (owned, completed) -> {
            CompletedCallback previous = socket.getClosedCallback();
            AtomicBoolean previousNotified = new AtomicBoolean();
            // Before headers drain, response.setClosedCallback only stores a callback:
            // no response sink exists yet. Guard that window on the raw socket too.
            class CloseGuard implements CompletedCallback {
                void finish(Exception error) {
                    boolean disconnected = !socket.isOpen();
                    if (socket.getClosedCallback() == this) socket.setClosedCallback(previous);
                    try { completed.onCompleted(error); }
                    finally {
                        if (disconnected && previous != null && previousNotified.compareAndSet(false, true)) {
                            previous.onCompleted(error);
                        }
                    }
                }
                @Override public void onCompleted(Exception error) { finish(error); }
            }
            CloseGuard guard = new CloseGuard();
            socket.setClosedCallback(guard);
            if (!socket.isOpen()) {
                guard.onCompleted(null);
                return;
            }
            try {
                AtomicBoolean bodyStarted = new AtomicBoolean();
                response.setClosedCallback(guard);
                response.setWriteableCallback(() -> {
                    if (!socket.isOpen()) { guard.onCompleted(null); return; }
                    if (!bodyStarted.compareAndSet(false, true)) return;
                    try { pump.start(owned, guard::finish); }
                    catch (RuntimeException error) { guard.finish(error); }
                });
                // Also preserves the declared MIME type for an empty asset: end()
                // before writeHead() otherwise uses AndroidAsync's text/html fallback.
                // Wait for its writable callback before pumping, so even EOF cannot
                // call end() while AndroidAsync is still constructing the body sink.
                response.writeHead();
            } catch (RuntimeException error) {
                // Headers may already be partially sent. Abort instead of adding JSON.
                guard.finish(error);
            }
        }, error -> {
            response.setWriteableCallback(null);
            response.setClosedCallback(null);
            if (error != null) socket.close();
            else if (response.isOpen()) response.end();
        });
    }

    static void transfer(InputStream input, Pump pump, CompletedCallback completed) {
        // AndroidAsync closes on EOF/read failure, but its sink-close callback does
        // not close the input. One owner covers all paths, including disconnect.
        InputStream owned = new FilterInputStream(input) {
            private boolean closed;

            @Override public synchronized void close() throws IOException {
                if (closed) return;
                closed = true;
                super.close();
            }
        };
        AtomicBoolean finished = new AtomicBoolean();
        try {
            pump.start(owned, error -> {
                if (!finished.compareAndSet(false, true)) return;
                try { owned.close(); }
                catch (IOException closeError) { if (error == null) error = closeError; }
                completed.onCompleted(error);
            });
        } catch (RuntimeException error) {
            try { owned.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    private AppAssetStream() { }
}
