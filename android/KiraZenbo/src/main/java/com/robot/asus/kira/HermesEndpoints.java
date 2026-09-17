package com.robot.asus.kira;

import java.util.List;

import okhttp3.HttpUrl;

/** Derives all Hermes routes without ever dropping the explicitly selected profile. */
public final class HermesEndpoints {
    public static final String DEFAULT_BASE_URL =
            "https://hermes.internal.c3land.org/hermes-api/p/grok/v1";

    private final HttpUrl base;
    private final HttpUrl profileRoot;
    private final HttpUrl pluginRoot;
    private final String profile;

    public HermesEndpoints(String baseUrl) {
        this(HttpUrl.get(baseUrl), false);
    }

    // Package-private HTTP support is exclusively for in-process MockWebServer tests.
    HermesEndpoints(HttpUrl url, boolean allowTestHttp) {
        if (!("https".equals(url.scheme()) || (allowTestHttp && "http".equals(url.scheme())))) {
            throw new IllegalArgumentException("Hermes endpoint must use HTTPS");
        }
        if (!url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("Hermes endpoint must not contain credentials, query or fragment");
        }
        String path = url.encodedPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        base = url.newBuilder().encodedPath(path).build();
        List<String> segments = base.pathSegments();
        int count = segments.size();
        if (count < 3 || !"v1".equals(segments.get(count - 1))
                || !"p".equals(segments.get(count - 3))
                || !segments.get(count - 2).matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Hermes endpoint must end in /p/{profile}/v1");
        }
        profile = segments.get(count - 2);
        profileRoot = base.newBuilder().removePathSegment(count - 1).build();
        HttpUrl.Builder ingress = base.newBuilder();
        for (int index = count - 1; index >= count - 3; index--) ingress.removePathSegment(index);
        pluginRoot = ingress.addPathSegment("zenbo").addPathSegment(profile).addPathSegment("v1").build();
    }

    public String profile() { return profile; }
    public HttpUrl base() { return base; }
    public HttpUrl models() { return append(base, "models"); }
    public HttpUrl capabilities() { return append(base, "capabilities"); }
    public HttpUrl sessions() { return append(profileRoot, "api", "sessions"); }
    public HttpUrl runs() { return append(base, "runs"); }
    public HttpUrl run(String id) { return append(runs(), requireId(id)); }
    public HttpUrl runEvents(String id) { return append(run(id), "events"); }
    public HttpUrl runStop(String id) { return append(run(id), "stop"); }
    public HttpUrl pluginCapabilities() { return append(pluginRoot, "capabilities"); }
    public HttpUrl deviceChannel() { return append(pluginRoot, "device-channel"); }
    public HttpUrl transcription() { return append(pluginRoot, "audio", "transcriptions"); }
    public HttpUrl speech() { return append(pluginRoot, "audio", "speech"); }
    public HttpUrl artifact(String id) { return append(pluginRoot, "audio", requireId(id)); }

    static String requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,160}")) {
            throw new IllegalArgumentException("Invalid Hermes identifier");
        }
        return value;
    }

    private static HttpUrl append(HttpUrl root, String... segments) {
        HttpUrl.Builder builder = root.newBuilder();
        for (String segment : segments) builder.addPathSegment(segment);
        return builder.build();
    }
}
