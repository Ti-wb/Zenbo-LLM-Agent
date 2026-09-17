package com.robot.asus.kira;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CertificatePinner;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Confirmed-SPKI support and an unauthenticated certificate fingerprint probe. */
public final class TlsTrust {
    private TlsTrust() { }

    public interface ProbeCallback {
        void onSuccess(JSONObject certificate);
        void onError(String code, String message);
    }

    public interface CapabilityCallback {
        void onSuccess(JSONObject result);
        void onError(String code, String message);
    }

    public static OkHttpClient.Builder pinnedBuilder(String host, String pin) {
        try {
            X509TrustManager trustManager = pinOnlyTrustManager();
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, null);
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                    .certificatePinner(new CertificatePinner.Builder().add(host, pin).build());
        } catch (NoSuchAlgorithmException | KeyManagementException error) {
            throw new IllegalStateException("Pinned TLS is unavailable", error);
        }
    }

    public static void probe(String gatewayUrl, String deviceId, ProbeCallback callback) {
        try {
            HttpUrl baseUrl = new HermesEndpoints(gatewayUrl).base();
            OkHttpClient client = pinnedBuilder(baseUrl.host(), "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .certificatePinner(new CertificatePinner.Builder().build())
                    .followRedirects(false).followSslRedirects(false)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            executeProbe(baseUrl, client, deviceId, callback);
        } catch (Exception error) {
            callback.onError("GATEWAY_TLS", error.getMessage());
        }
    }

    public static void probeSystemTrust(String gatewayUrl, String deviceId, ProbeCallback callback) {
        try {
            HttpUrl baseUrl = new HermesEndpoints(gatewayUrl).base();
            OkHttpClient client = new OkHttpClient.Builder()
                    .followRedirects(false).followSslRedirects(false)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            executeProbe(baseUrl, client, deviceId, callback);
        } catch (Exception error) {
            callback.onError("GATEWAY_TLS", error.getMessage());
        }
    }

    private static void executeProbe(
            HttpUrl baseUrl,
            OkHttpClient client,
            String deviceId,
            ProbeCallback callback
    ) {
        Request request = new Request.Builder()
                .url(baseUrl.newBuilder().addPathSegment("capabilities").build())
                .header("X-Zenbo-Device-Id", deviceId)
                                .get()
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                callback.onError(networkErrorCode(error), error.getMessage());
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    Handshake handshake = closeable.handshake();
                    if (handshake == null || handshake.peerCertificates().isEmpty()
                            || !(handshake.peerCertificates().get(0) instanceof X509Certificate)) {
                        callback.onError("GATEWAY_TLS", "Gateway supplied no X.509 certificate");
                        return;
                    }
                    X509Certificate certificate = (X509Certificate) handshake.peerCertificates().get(0);
                    JSONObject result = new JSONObject();
                    result.put("certificatePin", CertificatePinner.pin(certificate));
                    result.put("subject", certificate.getSubjectX500Principal().getName());
                    result.put("issuer", certificate.getIssuerX500Principal().getName());
                    result.put("expiresAt", certificate.getNotAfter().getTime());
                    callback.onSuccess(result);
                } catch (JSONException error) {
                    callback.onError("GATEWAY_TLS", error.getMessage());
                }
            }
        });
    }

    public static void testCapabilities(
            String gatewayUrl, String trustMode, String confirmedPin, String deviceId,
            String apiKey, CapabilityCallback callback
    ) {
        final long startedAt = System.currentTimeMillis();
        try {
            HermesEndpoints endpoints = new HermesEndpoints(gatewayUrl);
            OkHttpClient.Builder builder = GatewaySettings.CONFIRMED_SPKI_PIN.equals(trustMode)
                    ? pinnedBuilder(endpoints.base().host(), confirmedPin) : new OkHttpClient.Builder();
            OkHttpClient client = builder.followRedirects(false).followSslRedirects(false)
                    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build();
            testCapabilities(client, endpoints, deviceId, apiKey, startedAt, callback);
        } catch (Exception error) {
            callback.onError("GATEWAY_INCOMPATIBLE", "Invalid Hermes endpoint, API key or TLS configuration");
        }
    }

    static void testCapabilities(OkHttpClient client, HermesEndpoints endpoints, String deviceId,
                                 String apiKey, long startedAt, CapabilityCallback callback) {
        Request models = HermesClient.authorizedRequest(endpoints.models(), apiKey, deviceId).get().build();
        HermesClient.executeJson(client, models, new HermesTransport.ResultCallback() {
            @Override public void onSuccess(JSONObject result) {
                org.json.JSONArray entries = result.optJSONArray("data");
                if (entries == null || entries.length() == 0) {
                    callback.onError("GATEWAY_INCOMPATIBLE", "This Hermes profile advertises no configured model");
                    return;
                }
                HermesClient.executeJson(client,
                        HermesClient.authorizedRequest(endpoints.capabilities(), apiKey, deviceId).get().build(),
                        new HermesTransport.ResultCallback() {
                            @Override public void onSuccess(JSONObject capabilities) {
                                JSONObject features = capabilities.optJSONObject("features");
                                if (features == null || !features.optBoolean("run_submission")
                                        || !features.optBoolean("run_status") || !features.optBoolean("run_events_sse")
                                        || !features.optBoolean("run_stop")) {
                                    callback.onError("GATEWAY_INCOMPATIBLE", "Hermes Runs, SSE and stop support are required");
                                    return;
                                }
                                JSONObject idempotency = features.optJSONObject("runs_idempotency");
                                if (idempotency == null || !idempotency.optBoolean("supported")
                                        || !idempotency.optBoolean("durable")
                                        || idempotency.optLong("retention_seconds", 0L) < 86400L) {
                                    callback.onError("GATEWAY_INCOMPATIBLE", "Hermes durable run idempotency with 24-hour retention is required");
                                    return;
                                }
                                HermesClient.executeJson(client,
                                        HermesClient.authorizedRequest(endpoints.pluginCapabilities(), apiKey, deviceId).get().build(),
                                        new HermesTransport.ResultCallback() {
                                            @Override public void onSuccess(JSONObject plugin) {
                                                if (!"1.0".equals(plugin.optString("pluginVersion")) || !hasSixTools(plugin)) {
                                                    callback.onError("GATEWAY_INCOMPATIBLE", "The Zenbo plugin does not provide the six device tools");
                                                    return;
                                                }
                                                try {
                                                    plugin.put("available", true);
                                                    callback.onSuccess(new JSONObject()
                                                            .put("reachable", true).put("tlsTrusted", true)
                                                            .put("authenticated", true).put("capabilitiesReceived", true)
                                                            .put("latencyMs", Math.max(0L, System.currentTimeMillis() - startedAt))
                                                            .put("confirmationRequired", false).put("fingerprint", JSONObject.NULL)
                                                            .put("profile", endpoints.profile())
                                                            .put("capabilities", capabilities).put("plugin", plugin));
                                                } catch (JSONException error) {
                                                    callback.onError("GATEWAY_INCOMPATIBLE", "Invalid Hermes capabilities");
                                                }
                                            }
                                            @Override public void onError(String code, String message) {
                                                callback.onError("HERMES_NOT_FOUND".equals(code) ? "HERMES_PLUGIN_UNAVAILABLE" : code,
                                                        "The Hermes Zenbo plugin is unavailable");
                                            }
                                        });
                            }
                            @Override public void onError(String code, String message) { callback.onError(code, message); }
                        });
            }
            @Override public void onError(String code, String message) { callback.onError(code, message); }
        });
    }

    private static boolean hasSixTools(JSONObject plugin) {
        org.json.JSONArray array = plugin.optJSONArray("tools");
        if (array == null || array.length() != 6) return false;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < array.length(); i++) names.add(array.optString(i));
        return names.containsAll(java.util.Arrays.asList("get_system_status", "start_robot_following",
                "stop_robot_following", "look_at_user", "show_emotion", "go_to_sleep"));
    }

    private static String certificatePin(Handshake handshake) {
        if (handshake == null || handshake.peerCertificates().isEmpty()
                || !(handshake.peerCertificates().get(0) instanceof X509Certificate)) return "";
        return CertificatePinner.pin((X509Certificate) handshake.peerCertificates().get(0));
    }

    private static String networkErrorCode(Throwable error) {
        return error instanceof SSLHandshakeException || error instanceof SSLPeerUnverifiedException
                ? "GATEWAY_TLS"
                : "GATEWAY_OFFLINE";
    }

    private static X509TrustManager pinOnlyTrustManager() {
        return new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates are not accepted");
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Empty server certificate chain");
                for (X509Certificate certificate : chain) certificate.checkValidity();
            }

            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
