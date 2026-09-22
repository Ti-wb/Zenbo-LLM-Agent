package com.koushikdutta.async;

import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.callback.WritableCallback;
import com.koushikdutta.async.future.Cancellable;
import com.koushikdutta.async.future.SimpleCancellable;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Synthetic socket/timer only: all HTTP routing, framing and body parsing remain AndroidAsync. */
public final class ControlledSocket extends AsyncNetworkSocket {
    public static final class Clock extends AsyncServer {
        private static final class Task extends SimpleCancellable {
            final long at;
            final Runnable callback;
            Task(long at, Runnable callback) { this.at = at; this.callback = callback; }
        }
        private final List<Task> tasks = new ArrayList<>();
        private long now;
        public Clock() { mInstance = this; }
        @Override public Cancellable postDelayed(Runnable callback, long delay) {
            Task task = new Task(now + delay, callback); tasks.add(task); return task;
        }
        @Override public Cancellable post(Runnable callback) { return postDelayed(callback, 0); }
        @Override public Thread getAffinity() { return Thread.currentThread(); }
        @Override public boolean isAffinityThread() { return true; }
        public void advance(long milliseconds) { now += milliseconds; drain(); }
        public void drain() {
            for (int count = 0; count < 1000; count++) {
                Task due = null;
                for (Task task : tasks) {
                    if (!task.isCancelled() && !task.isDone() && task.at <= now) { due = task; break; }
                }
                if (due == null) return;
                due.setComplete(); due.callback.run();
            }
            throw new AssertionError("Unbounded event queue");
        }
        public int pending() {
            int count = 0;
            for (Task task : tasks) if (!task.isCancelled() && !task.isDone()) count++;
            return count;
        }
    }

    public final Clock clock;
    public final ByteArrayOutputStream output = new ByteArrayOutputStream();
    public int decoderDispatches;
    private final ByteBufferList pending = new ByteBufferList();
    private DataCallback data;
    private CompletedCallback end, closed;
    private WritableCallback writable;
    private boolean open = true, paused;

    public ControlledSocket(Clock clock) { this.clock = clock; }
    public void feed(String value) { feed(value.getBytes(StandardCharsets.UTF_8)); }
    public void feed(byte[] bytes) {
        pending.add(new ByteBufferList(bytes));
        Util.emitAllData(this, pending);
        clock.drain();
    }
    public String response() { return new String(output.toByteArray(), StandardCharsets.UTF_8); }
    @Override public void write(ByteBufferList bytes) {
        byte[] part = bytes.getAllByteArray(); output.write(part, 0, part.length);
    }
    @Override public void setDataCallback(DataCallback callback) {
        if (callback == null) { data = null; return; }
        boolean decoder = callback.getClass().getName().startsWith("com.koushikdutta.async.http.filter.");
        data = (source, bytes) -> {
            if (decoder) decoderDispatches++;
            callback.onDataAvailable(source, bytes);
        };
    }
    @Override public DataCallback getDataCallback() { return data; }
    @Override public void setEndCallback(CompletedCallback callback) { end = callback; }
    @Override public CompletedCallback getEndCallback() { return end; }
    @Override public void setClosedCallback(CompletedCallback callback) { closed = callback; }
    @Override public CompletedCallback getClosedCallback() { return closed; }
    @Override public void setWriteableCallback(WritableCallback callback) { writable = callback; }
    @Override public WritableCallback getWriteableCallback() { return writable; }
    @Override public void close() {
        if (!open) return;
        open = false;
        if (end != null) end.onCompleted(null);
        if (closed != null) closed.onCompleted(null);
    }
    @Override public void end() { close(); }
    @Override public boolean isOpen() { return open; }
    @Override public boolean isPaused() { return paused; }
    @Override public boolean isChunked() { return false; }
    @Override public void pause() { paused = true; }
    @Override public void resume() {
        if (!paused) return;
        paused = false;
        // AndroidAsync's real socket resumes its current pending buffer synchronously.
        // In particular, Expect: 100-continue can reenter body decoding here.
        Util.emitAllData(this, pending);
    }
    @Override public AsyncServer getServer() { return clock; }
    @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("192.168.10.3", 40000); }
    @Override public String charset() { return "UTF-8"; }
}
