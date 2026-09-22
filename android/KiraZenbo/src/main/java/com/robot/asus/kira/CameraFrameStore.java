package com.robot.asus.kira;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Bounded memory-only image storage. Turning the camera off revokes all retained frames. */
final class CameraFrameStore {
    static final int MAX_BYTES = 512 * 1024;
    static final long TTL_MS = 60_000;
    static final long FRESH_MS = 1_500;
    static final class Frame {
        final String id = UUID.randomUUID().toString();
        final byte[] jpeg;
        final int width, height;
        final long time;
        final String sha256;
        Frame(byte[] jpeg, int width, int height, long time) throws Exception {
            this.jpeg = jpeg.clone(); this.width = width; this.height = height; this.time = time;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(jpeg);
            StringBuilder hash = new StringBuilder();
            for (byte value : digest) hash.append(String.format(Locale.US, "%02x", value & 255));
            sha256 = hash.toString();
        }
        String capturedAt() {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new Date(time));
        }
    }
    private Frame latest;
    private final LinkedHashMap<String, Frame> snapshots = new LinkedHashMap<>();
    synchronized void update(byte[] jpeg, int width, int height, long now) throws Exception {
        if (jpeg == null || jpeg.length < 4 || jpeg.length > MAX_BYTES || width < 1 || height < 1
                || width > 1280 || height > 1280 || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216
                || (jpeg[jpeg.length - 2] & 255) != 255 || (jpeg[jpeg.length - 1] & 255) != 217)
            throw new IllegalArgumentException("Invalid camera frame");
        latest = new Frame(jpeg, width, height, now);
        prune(now);
    }
    synchronized Frame capture(long now) {
        if (latest == null || now < latest.time || now - latest.time > FRESH_MS) return null;
        snapshots.put(latest.id, latest);
        prune(now);
        while (snapshots.size() > 4) snapshots.remove(snapshots.keySet().iterator().next());
        return latest;
    }
    synchronized byte[] read(String id, long now) {
        prune(now);
        Frame frame = id == null || id.isEmpty() ? latest : snapshots.get(id);
        long limit = id == null || id.isEmpty() ? FRESH_MS : TTL_MS;
        return frame != null && now >= frame.time && now - frame.time <= limit ? frame.jpeg.clone() : null;
    }
    synchronized void clear() { latest = null; snapshots.clear(); }
    private void prune(long now) {
        Iterator<Frame> frames = snapshots.values().iterator();
        while (frames.hasNext()) { Frame frame = frames.next(); if (now < frame.time || now - frame.time > TTL_MS) frames.remove(); }
    }
}
