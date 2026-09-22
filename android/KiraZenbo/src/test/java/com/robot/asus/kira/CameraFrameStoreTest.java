package com.robot.asus.kira;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraFrameStoreTest {
    private static byte[] syntheticJpegEnvelope(int marker) {
        return new byte[]{(byte)255, (byte)216, (byte)marker, (byte)255, (byte)217};
    }
    @Test public void previewExpiresBeforeRetainedSnapshotAndDisableRevokesBoth() throws Exception {
        CameraFrameStore store = new CameraFrameStore();
        store.update(syntheticJpegEnvelope(1), 640, 480, 10_000);
        CameraFrameStore.Frame frame = store.capture(10_000);
        assertNotNull(frame);
        assertEquals(64, frame.sha256.length());
        assertNull(store.read("", 11_501));
        assertNotNull(store.read(frame.id, 11_501));
        assertNull(store.capture(11_501));
        assertNull(store.read(frame.id, 70_001));
        store.update(syntheticJpegEnvelope(2), 640, 480, 80_000);
        String next = store.capture(80_000).id;
        store.clear(); assertNull(store.read("", 80_001)); assertNull(store.read(next, 80_001));
    }
    @Test public void snapshotCountIsBoundedAndReadersCannotModifyStoredBytes() throws Exception {
        CameraFrameStore store = new CameraFrameStore();
        store.update(syntheticJpegEnvelope(0), 640, 480, 1000);
        String oldest = store.capture(1000).id;
        for (int i = 1; i < 5; i++) { store.update(syntheticJpegEnvelope(i), 640, 480, 1000 + i); store.capture(1000 + i); }
        assertNull(store.read(oldest, 1005));
        byte[] bytes = store.read("", 1005); bytes[2] = 99;
        assertEquals(4, store.read("", 1005)[2]);
    }
    @Test public void oversizedAndUnboundedFramesAreRejected() throws Exception {
        CameraFrameStore store = new CameraFrameStore();
        try { store.update(new byte[CameraFrameStore.MAX_BYTES + 1], 640, 480, 1); fail(); } catch (IllegalArgumentException expected) { }
        try { store.update(syntheticJpegEnvelope(1), 1281, 480, 1); fail(); } catch (IllegalArgumentException expected) { }
        try { store.update(new byte[]{1, 2, 3, 4}, 640, 480, 1); fail(); } catch (IllegalArgumentException expected) { }
    }
}
