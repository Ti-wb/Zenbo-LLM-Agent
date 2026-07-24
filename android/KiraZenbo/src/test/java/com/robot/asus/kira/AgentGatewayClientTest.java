package com.robot.asus.kira;

import org.json.JSONObject;
import org.junit.Test;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

public class AgentGatewayClientTest {
    @Test
    public void gatewayRootGetsProtocolBasePath() {
        HttpUrl url = AgentGatewayClient.apiBaseUrl("https://gateway.lan");
        assertEquals("https", url.scheme());
        assertEquals("/agent/v1", url.encodedPath());
    }

    @Test
    public void wssConfigurationUsesHttpsForOkHttpHandshake() {
        HttpUrl url = AgentGatewayClient.apiBaseUrl("wss://gateway.lan/agent/v1");
        assertEquals("https", url.scheme());
        assertEquals("/agent/v1", url.encodedPath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void cleartextGatewayIsRejected() {
        AgentGatewayClient.apiBaseUrl("http://gateway.lan/agent/v1");
    }

    @Test
    public void nativeAuthorizationHeadersNeverPutTokenInUrl() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            HttpUrl url = server.url("/agent/v1/capabilities");
            Request request = AgentGatewayClient.authorizedRequest(url, "device-secret-token", "zenbo-device").get().build();
            try (Response ignored = new OkHttpClient().newCall(request).execute()) {
                RecordedRequest recorded = server.takeRequest();
                assertEquals("Bearer device-secret-token", recorded.getHeader("Authorization"));
                assertEquals("zenbo-device", recorded.getHeader("X-Zenbo-Device-Id"));
                assertEquals("1.0", recorded.getHeader("X-Zenbo-Protocol"));
                assertFalse(recorded.getRequestUrl().toString().contains("device-secret-token"));
            }
        }
    }

    @Test
    public void unauthorizedAndUpgradeRequiredMapToCanonicalGatewayErrors() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setResponseCode(426));
            OkHttpClient client = new OkHttpClient();
            try (Response unauthorized = client.newCall(new Request.Builder().url(server.url("/first")).build()).execute();
                 Response incompatible = client.newCall(new Request.Builder().url(server.url("/second")).build()).execute()) {
                assertEquals("GATEWAY_AUTH", AgentGatewayClient.gatewayErrorForHttpStatus(unauthorized.code()));
                assertEquals("GATEWAY_INCOMPATIBLE", AgentGatewayClient.gatewayErrorForHttpStatus(incompatible.code()));
            }
        }
    }

    @Test
    public void remoteErrorBodyCannotCrossTheNativeSecretBoundary() {
        String secretBody = "proxy reflected Authorization: Bearer super-secret-device-token";
        String detail = AgentGatewayClient.safeGatewayFailureDetail(502, secretBody);
        assertEquals("Gateway request failed with HTTP 502", detail);
        assertFalse(detail.contains("Bearer"));
        assertFalse(detail.contains("super-secret-device-token"));
        assertFalse(detail.contains(secretBody));
    }

    @Test
    public void sessionCreateFingerprintIsCanonicalAndPayloadSensitive() throws Exception {
        JSONObject first = new JSONObject()
                .put("agentProfile", "default")
                .put("context", new JSONObject()
                        .put("robotName", "Zenbo K")
                        .put("language", "zh-TW"));
        JSONObject sameDifferentOrder = new JSONObject()
                .put("context", new JSONObject()
                        .put("language", "zh-TW")
                        .put("robotName", "Zenbo K"))
                .put("agentProfile", "default");
        JSONObject changed = new JSONObject(first.toString())
                .put("agentProfile", "office");

        assertEquals(
                AgentGatewayClient.sessionCreateFingerprint(first),
                AgentGatewayClient.sessionCreateFingerprint(sameDifferentOrder)
        );
        assertNotEquals(
                AgentGatewayClient.sessionCreateFingerprint(first),
                AgentGatewayClient.sessionCreateFingerprint(changed)
        );
    }

    @Test
    public void staleHttpRequestCannotMutateReplacementSessionState() {
        assertFalse(AgentGatewayClient.requestContextIsCurrent(
                7,
                8,
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222"
        ));
        assertFalse(AgentGatewayClient.requestContextIsCurrent(
                8,
                8,
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222"
        ));
        assertEquals(true, AgentGatewayClient.requestContextIsCurrent(
                8,
                8,
                "22222222-2222-4222-8222-222222222222",
                "22222222-2222-4222-8222-222222222222"
        ));
    }
}
