package com.robot.asus.kira;

import android.content.Context;
import com.koushikdutta.async.ControlledSocket;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Deterministic regression through the installed library's complete request/response lifecycle. */
public final class LanRequestsRegression {
    private static final String HOST = "192.168.10.2:8788";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        originalDefaultParserAcceptsOversizeBody();
        expectCanDispatchBeforeTheEarlyRequestHook();
        everyRouteAndContentTypeIsBounded();
        chunkedBodiesAreBounded();
        compressionIsRejectedBeforeDecoderDispatch();
        slowAndIncompleteRequestsExpire();
        ordinaryControlsAndAuthorizationRemainIntact();
        System.out.println("LAN network regression passed: " + assertions + " assertions; installed AndroidAsync parser/router and production LAN handlers.");
    }

    private static void expectCanDispatchBeforeTheEarlyRequestHook() throws Exception {
        ControlledSocket.Clock clock = new ControlledSocket.Clock();
        ControlledSocket socket = new ControlledSocket(clock);
        int[] decodersBeforeHook = {-1};
        AsyncHttpServer server = new AsyncHttpServer() {
            @Override protected boolean onRequest(com.koushikdutta.async.http.server.AsyncHttpServerRequest request,
                                                  com.koushikdutta.async.http.server.AsyncHttpServerResponse response) {
                decodersBeforeHook[0] = socket.decoderDispatches;
                return true;
            }
        };
        server.addAction("POST", "/remote/pair$", (request, response) -> {
            throw new AssertionError("Positive control should close on expanded overflow");
        }, headers -> new BoundedJsonBody(false));
        server.getListenCallback().onAccepted(socket);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(compressed)) { output.write(new byte[128 * 1024]); }
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(headers("POST", "/remote/pair", "application/json", compressed.size(),
                "Expect: 100-continue\r\nContent-Encoding: gzip\r\n").getBytes(StandardCharsets.UTF_8));
        wire.write(compressed.toByteArray());
        socket.feed(wire.toByteArray());
        check(decodersBeforeHook[0] > 0, "Positive control: 100-continue decodes pending compressed bytes before onRequest");
    }

    private static void originalDefaultParserAcceptsOversizeBody() {
        ControlledSocket.Clock clock = new ControlledSocket.Clock();
        AsyncHttpServer server = new AsyncHttpServer();
        int[] received = {0};
        server.get("/$", (request, response) -> {
            received[0] = ((JSONObject) request.getBody().get()).getString("probe").length();
            response.send("application/json", "{}");
        });
        ControlledSocket socket = new ControlledSocket(clock);
        server.getListenCallback().onAccepted(socket);
        String payload = new JSONObject().put("probe", "x".repeat(4096)).toString();
        socket.feed(headers("GET", "/", "application/json", payload.length(), "") + payload);
        check(received[0] == 4096, "Positive control must reproduce library default accumulation before handler");
        code(socket, 200);
    }

    private static void everyRouteAndContentTypeIsBounded() throws Exception {
        String[][] routes = {
            {"GET", "/"}, {"GET", "/remote-control.html"}, {"GET", "/remote/status"}, {"GET", "/remote/frame"},
            {"POST", "/remote/pair"}, {"POST", "/remote/heartbeat"}, {"POST", "/remote/logout"}, {"POST", "/remote/action"},
            {"GET", "/missing"}, {"POST", "/missing"}, {"HEAD", "/"}, {"OPTIONS", "/remote/action"},
            {"PUT", "/missing"}, {"TRACE", "*"}, {"GET", "/%0a"}, {"CUSTOM", "http://example.invalid/target"},
        };
        String[] types = {"application/json", "text/plain", "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=synthetic", "application/octet-stream", ""};
        try (Fixture fixture = new Fixture()) {
            for (String[] route : routes) {
                for (String type : types) {
                    ControlledSocket socket = fixture.socket();
                    socket.feed(headers(route[0], route[1], type, 4096, ""));
                    socket.feed("x".repeat(2048));
                    check(socket.isOpen(), "At cap, incomplete body should wait");
                    check(socket.output.size() == 0, "Route/auth must wait for full bounded body");
                    socket.feed("x");
                    check(!socket.isOpen(), "Oversized body must close: " + route[0] + " " + route[1] + " " + type);
                    check(socket.output.size() == 0, "Rejection cannot invoke handler through successful EOF");
                    check(socket.clock.pending() == 0, "Overflow cancels its timer");
                }
            }
            check(fixture.hardware.statusCalls == 0 && fixture.hardware.frames == 0 && fixture.hardware.actions == 0,
                    "No oversized request can reach hardware handlers");
        }
    }

    private static void chunkedBodiesAreBounded() throws Exception {
        for (String[] route : new String[][]{{"GET", "/"}, {"POST", "/remote/pair"}, {"OPTIONS", "/missing"}}) {
            try (Fixture fixture = new Fixture()) {
                ControlledSocket socket = fixture.socket();
                socket.feed(headers(route[0], route[1], "application/json", -1, "Transfer-Encoding: chunked\r\n"));
                socket.feed("800\r\n" + "x".repeat(2048) + "\r\n");
                check(socket.isOpen() && socket.output.size() == 0, "Chunked body waits at the cap");
                socket.feed("1\r\nx\r\n0\r\n\r\n");
                check(!socket.isOpen(), "Chunked body crossing cap closes");
                check(socket.output.size() == 0 && socket.clock.pending() == 0, "Chunked rejection is terminal");
            }
        }
    }

    private static void compressionIsRejectedBeforeDecoderDispatch() throws Exception {
        byte[] expansion = new JSONObject().put("probe", "x".repeat(128 * 1024)).toString().getBytes(StandardCharsets.UTF_8);
        for (String encoding : new String[]{"gzip", "deflate", "GZip", "br", "identity, gzip", ""}) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            if (encoding.equals("deflate")) {
                try (DeflaterOutputStream output = new DeflaterOutputStream(compressed)) { output.write(expansion); }
            } else {
                try (GZIPOutputStream output = new GZIPOutputStream(compressed)) { output.write(expansion); }
            }
            check(compressed.size() < 2048, "Compressed test expands beyond cap while encoded bytes fit");
            for (String[] route : new String[][]{{"GET", "/"}, {"POST", "/remote/pair"}, {"OPTIONS", "/%0a"}}) {
              for (String expect : new String[]{"", "Expect: 100-continue\r\n"}) {
                try (Fixture fixture = new Fixture()) {
                    ControlledSocket socket = fixture.socket();
                    ByteArrayOutputStream wire = new ByteArrayOutputStream();
                    wire.write(headers(route[0], route[1], "application/json", compressed.size(),
                            "Content-Encoding: " + encoding + "\r\n" + expect).getBytes(StandardCharsets.UTF_8));
                    wire.write(compressed.toByteArray());
                    socket.feed(wire.toByteArray()); // Headers and bomb arrive in the same network buffer.
                    check(!socket.isOpen(), "Unsupported content encoding closes at headers");
                    check(socket.decoderDispatches == 0, "No body bytes reach framing/inflation after header rejection");
                    check(socket.output.size() == 0 && socket.clock.pending() == 0, "Encoding rejection cannot reach routes or leave timers");
                }
              }
            }
        }
        try (Fixture fixture = new Fixture()) {
            ControlledSocket expected = fixture.socket();
            expected.feed(headers("POST", "/remote/pair", "application/json", 2,
                    "Expect: 100-continue\r\n") + "{}");
            check(!expected.isOpen() && expected.decoderDispatches == 0 && expected.output.size() == 0,
                    "Unsupported Expect is rejected before negotiation even without compression");
            for (String duplicate : new String[]{"Content-Encoding: identity\r\nContent-Encoding: gzip\r\n",
                    "Content-Encoding: gzip\r\nContent-Encoding: identity\r\n"}) {
                ControlledSocket socket = fixture.socket();
                socket.feed(headers("POST", "/remote/pair", "application/json", 2, duplicate) + "{}");
                check(!socket.isOpen() && socket.decoderDispatches == 0, "Every repeated encoding field must be checked");
            }
        }
    }

    private static void slowAndIncompleteRequestsExpire() throws Exception {
        for (String[] route : new String[][]{{"GET", "/remote/status"}, {"POST", "/remote/pair"}, {"HEAD", "/missing"}}) {
            try (Fixture fixture = new Fixture()) {
                ControlledSocket socket = fixture.socket();
                socket.feed(headers(route[0], route[1], "application/json", 100, "") + "{");
                socket.clock.advance(4999);
                check(socket.isOpen(), "Body deadline is five seconds");
                socket.feed(" "); // A slow trickle does not renew the deadline.
                socket.clock.advance(1);
                check(!socket.isOpen() && socket.output.size() == 0, "Timeout closes without running handler");
                check(socket.clock.pending() == 0, "Timeout has no outstanding timers");
            }
        }
    }

    private static void ordinaryControlsAndAuthorizationRemainIntact() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String path : new String[]{"/", "/remote-control.html"}) {
                ControlledSocket page = fixture.request("GET", path, "", "", "");
                code(page, 200);
                check(page.response().contains("synthetic remote page"), "GET page streams asset contents");
                check(page.clock.pending() == 0, "No-body GET cancels body timer");
            }
            code(fixture.request("GET", "/remote/status", "", "", ""), 401);
            code(fixture.request("GET", "/remote/frame", "", "", ""), 401);
            String code = fixture.security.pairingCode(100);
            ControlledSocket paired = fixture.request("POST", "/remote/pair", "application/json",
                    new JSONObject().put("code", code).toString(), "");
            code(paired, 200);
            String cookie = header(paired, "Set-Cookie").split(";", 2)[0];
            String csrf = json(paired).getJSONObject("data").getString("csrfToken");
            String credentials = "Cookie: " + cookie + "\r\nX-CSRF-Token: " + csrf + "\r\n";
            code(fixture.request("POST", "/remote/pair", "application/json", new JSONObject().put("code", code).toString(), ""), 401);
            code(fixture.request("GET", "/remote/status", "", "", credentials), 200);
            ControlledSocket frame = fixture.request("GET", "/remote/frame", "", "", credentials);
            code(frame, 200);
            check(header(frame, "Content-Type").equals("image/jpeg") && fixture.hardware.frames == 1, "Authenticated frame stays available");
            code(fixture.request("POST", "/remote/heartbeat", "application/json", "{}", credentials), 200);
            code(fixture.request("POST", "/remote/heartbeat", "application/json", "{}" + " ".repeat(2046), credentials), 200);
            code(fixture.request("POST", "/remote/heartbeat", "application/json", "{}", credentials + "Content-Encoding: identity\r\n"), 200);
            ControlledSocket chunked = fixture.socket();
            chunked.feed(headers("POST", "/remote/heartbeat", "application/json", -1,
                    credentials + "Transfer-Encoding: chunked\r\n") + "1\r\n{\r\n1\r\n}\r\n0\r\n\r\n");
            code(chunked, 200);
            code(fixture.request("POST", "/remote/action", "application/json", "{\"action\":\"forward\"}", credentials), 200);
            check(fixture.hardware.actions == 1, "Authenticated action executed exactly once");
            code(fixture.request("POST", "/remote/action", "application/json", "{\"action\":\"forward\"}", "Cookie: " + cookie + "\r\nX-CSRF-Token: wrong\r\n"), 401);
            code(fixture.request("POST", "/remote/heartbeat", "text/plain", "{}", credentials), 415);
            code(fixture.request("POST", "/remote/heartbeat", "application/json", "{", credentials), 400);
            ControlledSocket foreign = fixture.socket();
            foreign.feed(headers("POST", "/remote/action", "application/json", 2, credentials)
                    .replace("Origin: http://" + HOST, "Origin: https://untrusted.invalid") + "{}");
            code(foreign, 403);
            check(fixture.hardware.actions == 1, "Failed authorization never performs another action");
            for (String[] route : new String[][]{{"GET", "/missing"}, {"POST", "/missing"}, {"HEAD", "/"}, {"OPTIONS", "/%0a"}, {"TRACE", "*"}}) {
                code(fixture.request(route[0], route[1], "application/json", "{}", ""), 404);
            }
            code(fixture.request("POST", "/remote/logout", "application/json", "{}", credentials), 200);
            code(fixture.request("GET", "/remote/status", "", "", credentials), 401);
        }
    }

    private static String headers(String method, String path, String type, int length, String additional) {
        return method + " " + path + " HTTP/1.1\r\nHost: " + HOST + "\r\nOrigin: http://" + HOST + "\r\n"
                + (type.isEmpty() ? "" : "Content-Type: " + type + "\r\n")
                + (length < 0 ? "" : "Content-Length: " + length + "\r\n")
                + additional + "\r\n";
    }
    private static void code(ControlledSocket socket, int expected) {
        check(socket.response().startsWith("HTTP/1.1 " + expected + " "),
                "Expected HTTP " + expected + ", got " + socket.response().split("\r\n", 2)[0]);
    }
    private static String header(ControlledSocket socket, String name) {
        for (String line : socket.response().split("\r\n\r\n", 2)[0].split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.US).startsWith(name.toLowerCase(java.util.Locale.US) + ":")) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        throw new AssertionError("Missing response header: " + name);
    }
    private static JSONObject json(ControlledSocket socket) { return new JSONObject(socket.response().split("\r\n\r\n", 2)[1]); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value);
    }
    private static final class Fixture implements AutoCloseable {
        final DeviceHardware hardware = new DeviceHardware();
        final LanRemoteServer remote = new LanRemoteServer(new Context(), hardware, new RemoteSessionCoordinator());
        final AsyncHttpServer server;
        final LanRemoteSecurity security;
        Fixture() throws Exception {
            server = (AsyncHttpServer) field(remote, "server");
            security = (LanRemoteSecurity) field(remote, "security");
            security.enable(100);
            Field hosts = LanRemoteServer.class.getDeclaredField("hosts"); hosts.setAccessible(true);
            hosts.set(remote, Collections.singletonList(HOST));
        }
        ControlledSocket socket() {
            ControlledSocket socket = new ControlledSocket(new ControlledSocket.Clock());
            server.getListenCallback().onAccepted(socket); return socket;
        }
        ControlledSocket request(String method, String path, String type, String body, String additional) {
            ControlledSocket socket = socket();
            // Omit Content-Length entirely for normal browser GETs.
            int length = method.equals("GET") && body.isEmpty() ? -1 : body.getBytes(StandardCharsets.UTF_8).length;
            socket.feed(headers(method, path, type, length, additional) + body); return socket;
        }
        @Override public void close() { remote.close(); }
    }
}
