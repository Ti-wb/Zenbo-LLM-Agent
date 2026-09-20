package com.robot.asus.kira;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

import static org.junit.Assert.*;

public class HermesBodyReadTest {
    @Test public void knownLengthReadsTheExactPayloadAtTheAudioLimitAndLeavesClosureToTheCaller() throws Exception {
        byte[] audio = new byte[10 * 1024 * 1024];
        for (int index = 0; index < audio.length; index++) audio[index] = (byte) index;
        TrackedBody body = new TrackedBody(audio.length, new Buffer().write(audio));
        try (ResponseBody ignored = body) {
            assertArrayEquals(audio, HermesClient.readBounded(body, audio.length));
            assertFalse(body.closed.get());
        }
        assertTrue(body.closed.get());
    }

    @Test public void anOversizedDeclaredLengthIsRejectedBeforeReading() throws Exception {
        TrackedBody body = new TrackedBody(1025, new Buffer().write(new byte[1025]));
        try (ResponseBody ignored = body) {
            try { HermesClient.readBounded(body, 1024); fail(); }
            catch (IOException expected) { assertEquals("Response exceeds size limit", expected.getMessage()); }
            assertEquals(0, body.bytesRead);
        }
        assertTrue(body.closed.get());
    }

    @Test public void declaredLengthsCannotHideTruncatedOrExtraBytes() throws Exception {
        for (int actual : new int[]{15, 17}) {
            TrackedBody body = new TrackedBody(16, new Buffer().write(new byte[actual]));
            try (ResponseBody ignored = body) {
                try { HermesClient.readBounded(body, 32); fail("actual=" + actual); }
                catch (IOException expected) { }
            }
            assertTrue(body.closed.get());
        }
    }

    @Test public void zeroLengthIsValidOnlyForAnEmptyBody() throws Exception {
        try (ResponseBody empty = new TrackedBody(0, new Buffer())) {
            assertArrayEquals(new byte[0], HermesClient.readBounded(empty, 16));
        }
        try (ResponseBody nonempty = new TrackedBody(0, new Buffer().writeByte(1))) {
            try { HermesClient.readBounded(nonempty, 16); fail(); }
            catch (IOException expected) { }
        }
    }

    @Test public void unknownLengthsRetainTheSameBoundWithoutRejectingValidChunkedBodies() throws Exception {
        byte[] bytes = new byte[1024];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) index;
        try (ResponseBody body = new TrackedBody(-1, new Buffer().write(bytes))) {
            assertArrayEquals(bytes, HermesClient.readBounded(body, 1024));
        }
        TrackedBody body = new TrackedBody(-1, new Buffer().write(new byte[1025]));
        try (ResponseBody ignored = body) {
            try { HermesClient.readBounded(body, 1024); fail(); }
            catch (IOException expected) { assertEquals("Response exceeds size limit", expected.getMessage()); }
        }
        assertTrue(body.closed.get());
    }

    @Test public void interruptedReadsPropagateAndTheCallerStillClosesBothReadPaths() throws Exception {
        for (long length : new long[]{16, -1}) {
            TrackedBody body = new TrackedBody(length, new Buffer().write(new byte[16]));
            body.interrupted = true;
            try (ResponseBody ignored = body) {
                try { HermesClient.readBounded(body, 32); fail(); }
                catch (IOException expected) { assertEquals("Canceled", expected.getMessage()); }
            }
            assertTrue(body.closed.get());
        }
    }

    private static final class TrackedBody extends ResponseBody {
        final long length;
        final BufferedSource source;
        final AtomicBoolean closed = new AtomicBoolean();
        long bytesRead;
        boolean interrupted;
        TrackedBody(long length, Buffer bytes) {
            this.length = length;
            source = Okio.buffer(new ForwardingSource(bytes) {
                @Override public long read(Buffer sink, long byteCount) throws IOException {
                    if (interrupted) throw new IOException("Canceled");
                    long count = super.read(sink, byteCount);
                    if (count > 0) bytesRead += count;
                    return count;
                }
                @Override public void close() throws IOException { closed.set(true); super.close(); }
            });
        }
        @Override public MediaType contentType() { return MediaType.get("application/octet-stream"); }
        @Override public long contentLength() { return length; }
        @Override public BufferedSource source() { return source; }
    }
}
