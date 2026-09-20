package com.robot.asus.kira;

import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.WritableCallback;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Resource-ownership tests. The injected pump does not emulate Android sockets. */
public class AppAssetStreamTest {
    @Test public void disconnectWhileHeadersAreBackpressuredClosesBeforeThePumpCanStart() {
        FakeHttp http = new FakeHttp();
        AssetInput input = new AssetInput(12 * 1024 * 1024);
        AtomicInteger originalCloseCalls = new AtomicInteger();
        http.socketClosed = error -> originalCloseCalls.incrementAndGet();
        AppAssetStream.send(input, http.response, (stream, callback) -> fail("Headers have not drained"));
        assertTrue(http.headersStarted);
        assertFalse(http.sinkReady);
        assertEquals(0, input.closes);
        http.disconnect();
        assertEquals(1, input.closes);
        assertEquals(1, originalCloseCalls.get());
        assertEquals(0, input.position);
        assertEquals(0, http.ends);
    }

    @Test public void emptyAssetWaitsForTheBodySinkAndPreservesItsMimeType() {
        FakeHttp http = new FakeHttp();
        AssetInput input = new AssetInput(0);
        http.response.setContentType("application/javascript; charset=utf-8");
        AppAssetStream.send(input, http.response, (stream, callback) -> callback.onCompleted(null));
        assertEquals(0, http.ends);
        assertEquals(0, input.closes);
        http.flushHeaders();
        assertEquals(1, http.ends);
        assertEquals(1, input.closes);
        assertEquals("application/javascript; charset=utf-8", http.mimeType);
    }

    @Test public void disconnectAfterThePumpTakesOverAndLateCompletionCannotFinishTwice() {
        FakeHttp http = new FakeHttp();
        AssetInput input = new AssetInput(12 * 1024 * 1024);
        AtomicInteger originalCloseCalls = new AtomicInteger();
        AtomicReference<CompletedCallback> pumpFinished = new AtomicReference<>();
        http.socketClosed = error -> originalCloseCalls.incrementAndGet();
        AppAssetStream.send(input, http.response, (stream, callback) -> {
            pumpFinished.set(callback);
            // Like Util.pump, take over the response's close callback after headers.
            http.response.setClosedCallback(callback);
        });
        http.flushHeaders();
        http.disconnect();
        pumpFinished.get().onCompleted(null);
        assertEquals(1, input.closes);
        assertEquals(1, originalCloseCalls.get());
        assertEquals(0, http.ends);
        assertNull(http.writable);
    }

    @Test public void failureAfterHeadersBeginAbortsTheSocketAndClosesTheInput() {
        FakeHttp http = new FakeHttp();
        AssetInput input = new AssetInput(1);
        AppAssetStream.send(input, http.response, (stream, callback) -> {
            throw new IllegalStateException("Pump unavailable");
        });
        http.flushHeaders();
        assertFalse(http.open);
        assertEquals(1, input.closes);
        assertEquals(0, http.ends);
    }

    @Test public void transfersWithoutReadingAheadAndKeepsTheInputOpenUntilCompletion() throws Exception {
        AssetInput input = new AssetInput(12 * 1024 * 1024 + 17);
        AtomicReference<InputStream> owned = new AtomicReference<>();
        AtomicReference<CompletedCallback> finish = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AppAssetStream.transfer(input, (stream, callback) -> {
            owned.set(stream);
            finish.set(callback);
        }, failure::set);
        assertEquals(0, input.position);
        assertEquals(0, input.closes);
        assertEquals(0, input.available()); // A generic stream may report zero before EOF.

        byte[] chunk = new byte[64 * 1024];
        long received = 0;
        while (true) {
            int count = owned.get().read(chunk);
            if (count < 0) break;
            for (int index = 0; index < count; index++) assertEquals((byte) (received + index), chunk[index]);
            received += count;
        }
        assertEquals(input.length, received);
        assertEquals(0, input.closes);
        // AndroidAsync closes on EOF, then notifies its owner; only one native close is needed.
        owned.get().close();
        finish.get().onCompleted(null);
        assertEquals(1, input.closes);
        assertNull(failure.get());
    }

    @Test public void aDisconnectedOrFailedTransferClosesTheInputWithoutReadingTheRemainder() {
        for (Exception error : new Exception[]{null, new IOException("Disconnected")}) {
            AssetInput input = new AssetInput(12 * 1024 * 1024);
            AtomicReference<CompletedCallback> finish = new AtomicReference<>();
            AtomicReference<Exception> received = new AtomicReference<>();
            AppAssetStream.transfer(input, (stream, callback) -> finish.set(callback), received::set);
            finish.get().onCompleted(error);
            assertEquals(0, input.position);
            assertEquals(1, input.closes);
            assertSame(error, received.get());
        }
    }

    @Test public void readFailuresCloseAndPropagateWithoutReportingSuccessfulCompletion() {
        IOException expected = new IOException("APK read failed");
        AssetInput input = new AssetInput(1) {
            @Override public int read(byte[] bytes, int offset, int count) throws IOException { throw expected; }
        };
        AtomicReference<Exception> received = new AtomicReference<>();
        AppAssetStream.transfer(input, (stream, callback) -> {
            try { stream.read(new byte[16]); fail(); }
            catch (IOException error) { callback.onCompleted(error); }
        }, received::set);
        assertSame(expected, received.get());
        assertEquals(1, input.closes);
    }

    @Test public void pumpSetupFailureStillClosesTheInput() {
        AssetInput input = new AssetInput(1);
        RuntimeException expected = new IllegalStateException("Sink unavailable");
        try {
            AppAssetStream.transfer(input, (stream, callback) -> { throw expected; }, error -> fail());
            fail();
        } catch (RuntimeException actual) { assertSame(expected, actual); }
        assertEquals(1, input.closes);
    }

    @Test public void emptyAssetsCompleteAndClose() {
        AssetInput input = new AssetInput(0);
        AtomicReference<Exception> received = new AtomicReference<>();
        AppAssetStream.transfer(input, (stream, callback) -> {
            try { assertEquals(-1, stream.read()); callback.onCompleted(null); }
            catch (IOException error) { throw new AssertionError(error); }
        }, received::set);
        assertEquals(1, input.closes);
        assertNull(received.get());
    }

    private static class AssetInput extends InputStream {
        final int length;
        int position;
        int closes;
        AssetInput(int length) { this.length = length; }
        @Override public int read() { return position == length ? -1 : position++ & 255; }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (position == length) return -1;
            int received = Math.min(count, length - position);
            for (int index = 0; index < received; index++) bytes[offset + index] = (byte) position++;
            return received;
        }
        @Override public void close() { closes++; }
    }

    /** Models AndroidAsync's header-pending window without Android's Looper stubs. */
    private static final class FakeHttp {
        boolean open = true;
        boolean headersStarted;
        boolean sinkReady;
        int ends;
        String mimeType;
        CompletedCallback socketClosed;
        CompletedCallback responseClosed;
        WritableCallback writable;
        final AsyncSocket socket = (AsyncSocket) Proxy.newProxyInstance(AsyncSocket.class.getClassLoader(),
                new Class<?>[]{AsyncSocket.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isOpen": return open;
                        case "getClosedCallback": return socketClosed;
                        case "setClosedCallback": socketClosed = (CompletedCallback) args[0]; return null;
                        case "close": disconnect(); return null;
                        default: throw new AssertionError("Unexpected socket method: " + method.getName());
                    }
                });
        final AsyncHttpServerResponse response = (AsyncHttpServerResponse) Proxy.newProxyInstance(
                AsyncHttpServerResponse.class.getClassLoader(), new Class<?>[]{AsyncHttpServerResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSocket": return socket;
                        case "isOpen": return open;
                        case "setClosedCallback":
                            responseClosed = (CompletedCallback) args[0];
                            if (sinkReady) socketClosed = responseClosed;
                            return null;
                        case "setWriteableCallback": writable = (WritableCallback) args[0]; return null;
                        case "setContentType": mimeType = (String) args[0]; return null;
                        case "writeHead": headersStarted = true; return null;
                        case "end":
                            assertTrue("Must wait for the body sink before end()", sinkReady);
                            ends++;
                            return null;
                        default: throw new AssertionError("Unexpected response method: " + method.getName());
                    }
                });
        void flushHeaders() {
            assertTrue(headersStarted);
            sinkReady = true;
            socketClosed = responseClosed;
            if (writable != null) writable.onWriteable();
        }
        void disconnect() {
            if (!open) return;
            open = false;
            if (socketClosed != null) socketClosed.onCompleted(null);
        }
    }
}
