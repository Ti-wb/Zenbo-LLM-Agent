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
            HttpUrl baseUrl = AgentGatewayClient.apiBaseUrl(gatewayUrl);
            OkHttpClient client = pinnedBuilder(baseUrl.host(), "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .certificatePinner(new CertificatePinner.Builder().build())
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
            HttpUrl baseUrl = AgentGatewayClient.apiBaseUrl(gatewayUrl);
            OkHttpClient client = new OkHttpClient.Builder()
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
                .header("X-Zenbo-Protocol", "1.0")
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
            String gatewayUrl,
            String trustMode,
            String confirmedPin,
            String deviceId,
            String transientToken,
            String agentProfile,
            CapabilityCallback callback
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            if (transientToken == null || transientToken.length() < 16 || transientToken.length() > 4096) {
                throw new IllegalArgumentException("A device token containing 16 to 4096 characters is required");
            }
            HttpUrl baseUrl = AgentGatewayClient.apiBaseUrl(gatewayUrl);
            OkHttpClient.Builder builder = GatewaySettings.CONFIRMED_SPKI_PIN.equals(trustMode)
                    ? pinnedBuilder(baseUrl.host(), confirmedPin)
                    : new OkHttpClient.Builder();
            OkHttpClient client = builder
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder()
                    .url(baseUrl.newBuilder().addPathSegment("capabilities").build())
                    .header("Authorization", "Bearer " + transientToken)
                    .header("X-Zenbo-Device-Id", deviceId)
                    .header("X-Zenbo-Protocol", "1.0")
                    .get()
                    .build();
            client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                    callback.onError(networkErrorCode(error), error.getMessage());
                }

                @Override public void onResponse(Call call, Response response) {
                    try (Response closeable = response) {
                        if (closeable.code() == 401 || closeable.code() == 403) {
                            callback.onError("GATEWAY_AUTH", "Gateway rejected the device credential");
                            return;
                        }
                        if (closeable.code() == 426) {
                            callback.onError("GATEWAY_INCOMPATIBLE", "Gateway protocol 1.0 is not supported");
                            return;
                        }
                        ResponseBody body = closeable.body();
                        if (!closeable.isSuccessful() || body == null) {
                            callback.onError("GATEWAY_OFFLINE", "Gateway returned HTTP " + closeable.code());
                            return;
                        }
                        JSONObject capabilities = new JSONObject(body.string());
                        String protocolVersion = capabilities.optString("protocolVersion", "");
                        if (!"1.0".equals(protocolVersion)) {
                            callback.onError("GATEWAY_INCOMPATIBLE", "Gateway protocol 1.0 is required");
                            return;
                        }
                        boolean profileFound = false;
                        org.json.JSONArray profiles = capabilities.optJSONArray("agentProfiles");
                        for (int index = 0; profiles != null && index < profiles.length(); index++) {
                            JSONObject profile = profiles.optJSONObject(index);
                            if (profile != null && agentProfile.equals(profile.optString("id", ""))) profileFound = true;
                        }
                        if (!profileFound) {
                            callback.onError("GATEWAY_INCOMPATIBLE", "Configured agent profile is unavailable");
                            return;
                        }
                        String fingerprint = certificatePin(closeable.handshake());
                        callback.onSuccess(new JSONObject()
                                .put("reachable", true)
                                .put("tlsTrusted", true)
                                .put("latencyMs", Math.max(0L, System.currentTimeMillis() - startedAt))
                                .put("protocolVersion", protocolVersion)
                                .put("confirmationRequired", false)
                                .put("fingerprint", fingerprint.isEmpty() ? JSONObject.NULL : fingerprint)
                                .put("authenticated", true)
                                .put("capabilitiesReceived", true));
                    } catch (Exception error) {
                        callback.onError("GATEWAY_INCOMPATIBLE", error.getMessage());
                    }
                }
            });
        } catch (Exception error) {
            callback.onError("GATEWAY_TLS", error.getMessage());
        }
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
