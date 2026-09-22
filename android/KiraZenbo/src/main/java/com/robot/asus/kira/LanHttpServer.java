package com.robot.asus.kira;

import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.http.Headers;
import com.koushikdutta.async.http.server.AsyncHttpRequestBodyProvider;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.HttpServerRequestCallback;

import java.util.List;

/** The LAN surface never uses AndroidAsync's unbounded, content-type-selected parsers. */
final class LanHttpServer extends AsyncHttpServer {
    @Override public void addAction(String method, String path, HttpServerRequestCallback callback,
                                    AsyncHttpRequestBodyProvider ignored) {
        super.addAction(method, path, callback, LanHttpServer::body);
    }

    private static BoundedJsonBody body(Headers headers) {
        // AndroidAsync resumes pending input before onRequest during 100-continue negotiation.
        // LAN browser commands do not use Expect. Reject it and suppress that early dispatch.
        boolean rejected = headers.removeAll("Expect") != null;
        List<String> encodings = headers.getAll("Content-Encoding");
        if (encodings != null) {
            for (String encoding : encodings) {
                if (!"identity".equalsIgnoreCase(encoding.trim())) rejected = true;
            }
        }
        return new BoundedJsonBody(rejected);
    }

    @Override protected boolean onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        BoundedJsonBody body = (BoundedJsonBody) request.getBody();
        if (body.rejectedHeaders) {
            // Decoders are installed, but no body bytes have been dispatched. Detach at the
            // socket: limiting a parser AFTER gzip/deflate would allow unbounded inflation.
            body.cancel();
            request.getSocket().setDataCallback(new DataCallback.NullDataCallback());
            request.getSocket().setEndCallback(error -> { });
            request.getSocket().close();
            return true;
        }
        return false;
    }
}
