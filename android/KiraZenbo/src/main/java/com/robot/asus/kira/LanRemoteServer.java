package com.robot.asus.kira;

import android.content.Context;
import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Opt-in LAN surface, deliberately separate from renderer/admin/Hermes APIs. */
final class LanRemoteServer {
    static final int PORT = 8788;
    private final Context context;
    private final DeviceHardware hardware;
    private final RemoteSessionCoordinator coordinator;
    private final LanRemoteSecurity security = new LanRemoteSecurity();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final LanHttpServer server = new LanHttpServer();
    private volatile List<String> hosts = Collections.emptyList();
    private boolean listening;

    LanRemoteServer(Context context, DeviceHardware hardware, RemoteSessionCoordinator coordinator) {
        this.context = context.getApplicationContext(); this.hardware = hardware; this.coordinator = coordinator;
        registerRoutes();
        watchdog.scheduleAtFixedRate(() -> {
            if (security.expireLease(now())) hardware.stop();
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
    synchronized void setEnabled(boolean enabled) throws Exception {
        if (!enabled) { disable(); return; }
        disable();
        List<String> current = localHosts();
        if (current.isEmpty()) throw new IllegalStateException("LAN_UNAVAILABLE");
        hosts = current;
        if (server.listen(PORT) == null) { hosts = Collections.emptyList(); throw new IllegalStateException("LAN_UNAVAILABLE"); }
        listening = true;
        security.enable(now());
    }
    synchronized void disable() {
        security.disable(); hardware.stop();
        if (listening) server.stop();
        listening = false; hosts = Collections.emptyList();
    }
    synchronized void close() { disable(); watchdog.shutdownNow(); }
    JSONObject status(boolean includePairing) {
        JSONObject result = json("enabled", security.enabled(), "connected", security.connected(now()));
        if (includePairing) {
            JSONArray urls = new JSONArray(); for (String host : hosts) urls.put("http://" + host + "/");
            put(result, "urls", urls); put(result, "pairingCode", nullable(security.pairingCode(now())));
            put(result, "pairingExpiresAt", security.pairingCode(now()) == null ? JSONObject.NULL : LocalRuntimeServer.isoTime(System.currentTimeMillis() + Math.max(0, security.pairingExpiresAt() - now())));
        }
        return result;
    }
    private JSONObject deviceStatus() {
        JSONObject result = hardware.status();
        put(result, "motionEnabled", coordinator.isMotionEnabled()); put(result, "remote", status(false));
        return result;
    }
    private void registerRoutes() {
        server.get("/$", this::page);
        server.get("/remote-control.html$", this::page);
        server.addAction("POST", "/remote/pair$", (request, response) -> {
            if (!validSurface(request, response)) return;
            JSONObject body = body(request, response, "code"); if (body == null) return;
            if (!(body.opt("code") instanceof String)) { error(response,400,"INVALID_REQUEST"); return; }
            String session = security.pair(body.optString("code"), now());
            if (session == null) { error(response,security.throttled(now()) ? 429 : 401,"PAIRING_REJECTED"); return; }
            response.getHeaders().set("Set-Cookie", LanRemoteSecurity.COOKIE + "=" + session + "; HttpOnly; SameSite=Strict; Path=/remote; Max-Age=600");
            success(response,json("csrfToken",security.csrfToken(),"status",deviceStatus()));
        });
        server.get("/remote/status$", (request,response) -> {
            if (authorized(request,response,false)) success(response,deviceStatus());
        });
        server.get("/remote/frame$", (request,response) -> {
            if (!authorized(request,response,false)) return;
            byte[] frame = hardware.cameraJpeg("");
            if (frame == null) { error(response,409,"CAMERA_UNAVAILABLE"); return; }
            headers(response); response.send("image/jpeg",frame);
        });
        server.addAction("POST", "/remote/heartbeat$", (request,response) -> {
            if (!authorized(request,response,true) || body(request,response) == null) return;
            if (!security.heartbeat(cookie(request), request.getHeaders().get("X-CSRF-Token"), now())) {
                error(response,401,"SESSION_EXPIRED"); return;
            }
            success(response,json("accepted",true));
        });
        server.addAction("POST", "/remote/logout$", (request,response) -> {
            if (!authorized(request,response,true) || body(request,response) == null) return;
            security.logout(cookie(request),now()); hardware.stop();
            response.getHeaders().set("Set-Cookie", LanRemoteSecurity.COOKIE + "=; HttpOnly; SameSite=Strict; Path=/remote; Max-Age=0");
            success(response,json("accepted",true));
        });
        server.addAction("POST", "/remote/action$", (request,response) -> {
            if (!authorized(request,response,true)) return;
            JSONObject body = body(request,response,"action"); if (body == null) return;
            String action = body.optString("action","");
            if (!LocalRuntimeServer.validDeviceAction(action)) { error(response,400,"INVALID_REQUEST"); return; }
            if (!security.leaseAlive(now())) { error(response,409,"CONTROLLER_EXPIRED"); return; }
            if (!"stop".equals(action) && !coordinator.manualDeviceActionAllowed()) { error(response,409,"TURN_BUSY"); return; }
            final String token = cookie(request);
            hardware.action("follow".equals(action) ? "follow_start" : action,
                    operation -> security.authenticated(token,now()) && security.leaseAlive(now())
                            && ("stop".equals(action) || coordinator.manualDeviceActionAllowed())
                            && run(operation), result -> AsyncServer.getDefault().post(() -> {
                        if ("error".equals(result.optString("status"))) {
                            JSONObject detail = result.optJSONObject("error");
                            error(response,409,detail == null ? "DEVICE_UNAVAILABLE" : detail.optString("code","DEVICE_UNAVAILABLE"));
                        } else success(response,json("accepted",true));
                    }));
        });
        // Null method and DOTALL also cover HEAD/OPTIONS and decoded targets containing newlines.
        server.addAction(null, "(?s).*", (request,response) -> error(response,404,"NOT_FOUND"));
    }
    private static boolean run(Runnable operation) { operation.run(); return true; }
    private void page(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        if (!validSurface(request,response)) return;
        try {
            headers(response); response.setContentType("text/html; charset=utf-8");
            AppAssetStream.send(context.getAssets().open("app/remote-control.html"),response);
        } catch (Exception unavailable) { error(response,503,"PAGE_UNAVAILABLE"); }
    }
    private boolean validSurface(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
        String peer = null;
        if (request.getSocket() instanceof AsyncNetworkSocket) {
            InetSocketAddress remote = ((AsyncNetworkSocket) request.getSocket()).getRemoteAddress();
            if (remote != null && remote.getAddress() != null) peer = remote.getAddress().getHostAddress();
        }
        boolean write = !"GET".equals(request.getMethod());
        if (!security.enabled() || !LanRemoteSecurity.validRequest(peer, request.getHeaders().get("Host"),
                request.getHeaders().get("Origin"),write,hosts)) { error(response,403,"FORBIDDEN_ORIGIN"); return false; }
        if (write && !"application/json".equalsIgnoreCase(String.valueOf(request.getHeaders().get("Content-Type")).split(";",2)[0].trim())) {
            error(response,415,"INVALID_REQUEST"); return false;
        }
        return true;
    }
    private boolean authorized(AsyncHttpServerRequest request, AsyncHttpServerResponse response, boolean write) {
        if (!validSurface(request,response)) return false;
        boolean valid = write ? security.canWrite(cookie(request),request.getHeaders().get("X-CSRF-Token"),now())
                : security.authenticated(cookie(request),now());
        if (!valid) error(response,401,"SESSION_EXPIRED");
        return valid;
    }
    private static JSONObject body(AsyncHttpServerRequest request, AsyncHttpServerResponse response, String... allowed) {
        try {
            Object value = request.getBody() == null ? null : request.getBody().get();
            if (!(value instanceof JSONObject)) throw new IllegalArgumentException();
            JSONObject body = (JSONObject)value;
            java.util.Set<String> keys = new java.util.HashSet<>(java.util.Arrays.asList(allowed));
            java.util.Iterator<String> iterator = body.keys();
            while (iterator.hasNext()) if (!keys.contains(iterator.next())) throw new IllegalArgumentException();
            return body;
        } catch (Exception invalid) { error(response,400,"INVALID_REQUEST"); return null; }
    }
    private static String cookie(AsyncHttpServerRequest request) {
        String raw = request.getHeaders().get("Cookie"); if (raw == null || raw.length() > 2048) return null;
        for (String part : raw.split(";")) {
            String[] pair = part.trim().split("=",2);
            if (pair.length == 2 && LanRemoteSecurity.COOKIE.equals(pair[0])) return pair[1];
        }
        return null;
    }
    static List<String> localHosts() throws Exception {
        List<String> values = new ArrayList<>();
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                String ip = address.getHostAddress(); if (LanRemoteSecurity.privateIpv4(ip)) values.add(ip + ":" + PORT);
            }
        }
        return Collections.unmodifiableList(values);
    }
    private static void headers(AsyncHttpServerResponse response) {
        response.getHeaders().set("Cache-Control","no-store");
        response.getHeaders().set("X-Content-Type-Options","nosniff");
        response.getHeaders().set("Referrer-Policy","no-referrer");
        response.getHeaders().set("Cross-Origin-Resource-Policy","same-origin");
        response.getHeaders().set("X-Frame-Options","DENY");
        response.getHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    }
    private static void success(AsyncHttpServerResponse response, JSONObject data) {
        headers(response); response.code(200); response.send("application/json; charset=utf-8",json("ok",true,"requestId",UUID.randomUUID().toString(),"data",data,"error",JSONObject.NULL).toString());
    }
    private static void error(AsyncHttpServerResponse response, int code, String error) {
        headers(response); response.code(code); response.send("application/json; charset=utf-8",json("ok",false,"requestId",UUID.randomUUID().toString(),"data",JSONObject.NULL,"error",json("code",error,"message",error,"retryable",false)).toString());
    }
    private static JSONObject json(Object... values) { JSONObject value = new JSONObject(); for(int i=0;i<values.length;i+=2) put(value,String.valueOf(values[i]),values[i+1]); return value; }
    private static void put(JSONObject value,String key,Object object) { try { value.put(key,object); } catch(Exception invalid) { throw new IllegalStateException(invalid); } }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
    private static long now() { return android.os.SystemClock.elapsedRealtime(); }
}
