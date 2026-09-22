package com.robot.asus.kira;

import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.DataSink;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.future.Cancellable;
import com.koushikdutta.async.http.AsyncHttpRequest;
import com.koushikdutta.async.http.body.AsyncHttpRequestBody;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Small LAN command bodies are bounded while streaming, including chunked requests. */
final class BoundedJsonBody implements AsyncHttpRequestBody<JSONObject> {
    static final int MAX_BYTES = 2048;
    static final long TIMEOUT_MS = 5_000;
    final boolean rejectedHeaders;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private JSONObject value;
    private Cancellable timeout;
    private boolean finished;
    BoundedJsonBody() { this(false); }
    BoundedJsonBody(boolean rejectedHeaders) { this.rejectedHeaders = rejectedHeaders; }
    @Override public void parse(DataEmitter emitter, CompletedCallback complete) {
        timeout = emitter.getServer().postDelayed(() -> {
            cancel();
            emitter.close();
        }, TIMEOUT_MS);
        emitter.setDataCallback((source, data) -> {
            if (finished) { data.recycle(); return; }
            if (data.remaining() > MAX_BYTES - bytes.size()) {
                cancel(); data.recycle(); source.close(); return;
            }
            byte[] chunk = data.getAllByteArray(); bytes.write(chunk, 0, chunk.length);
        });
        emitter.setEndCallback(error -> {
            if (finished) return;
            finished = true;
            timeout.cancel();
            if (error == null) {
                try { value = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
                catch (Exception invalid) { value = null; }
            }
            complete.onCompleted(error);
        });
    }
    /** Rejection must never become successful completion via a socket-close callback. */
    void cancel() {
        finished = true;
        if (timeout != null) timeout.cancel();
        bytes.reset();
        value = null;
    }
    @Override public JSONObject get() { return value; }
    @Override public String getContentType() { return "application/json"; }
    @Override public boolean readFullyOnRequest() { return true; }
    @Override public int length() { return bytes.size(); }
    @Override public void write(AsyncHttpRequest request, DataSink sink, CompletedCallback callback) {
        callback.onCompleted(new UnsupportedOperationException("Request parser only"));
    }
}
