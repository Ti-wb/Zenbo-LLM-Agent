package com.robot.asus.kira;

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
}
