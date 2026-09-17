package com.robot.asus.kira;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import static org.junit.Assert.*;

public class TlsTrustTest {
    @Test public void bundledRootMatchesOfficialCertificate() throws Exception {
        X509Certificate root = TlsTrust.loadIsrgRoot();
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(root.getEncoded())) {
            fingerprint.append(String.format("%02X", value & 0xff));
        }
        assertEquals("96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6", fingerprint.toString());
        assertTrue(root.getBasicConstraints() >= 0);
        root.verify(root.getPublicKey());
    }

    @Test public void supplementalTrustPreservesPlatformRootsAndRejectsUnknownIssuer() throws Exception {
        X509Certificate x1 = TlsTrust.loadIsrgRoot();
        List<X509Certificate> unrelated = new ArrayList<>();
        for (X509Certificate root : manager(null).getAcceptedIssuers()) {
            if (root.getSubjectX500Principal().equals(root.getIssuerX500Principal())
                    && !root.getPublicKey().equals(x1.getPublicKey())
                    && (unrelated.isEmpty() || !root.getPublicKey().equals(unrelated.get(0).getPublicKey()))) {
                unrelated.add(root);
                if (unrelated.size() == 2) break;
            }
        }
        assertEquals("JDK test trust store must have two unrelated roots", 2, unrelated.size());
        KeyStore platformRoots = KeyStore.getInstance(KeyStore.getDefaultType());
        platformRoots.load(null, null);
        platformRoots.setCertificateEntry("existing-platform-root", unrelated.get(0));
        X509TrustManager combined = TlsTrust.withIsrgRoot(manager(platformRoots));
        combined.checkServerTrusted(new X509Certificate[]{unrelated.get(0)}, authType(unrelated.get(0)));
        combined.checkServerTrusted(new X509Certificate[]{x1}, "RSA");
        try {
            combined.checkServerTrusted(new X509Certificate[]{unrelated.get(1)}, authType(unrelated.get(1)));
            fail("A root outside both trust stores must be rejected");
        } catch (CertificateException expected) { }
    }

    @Test public void hermesClientKeepsHostnameChecksAndRejectsOtherOriginsBeforeNetwork() throws Exception {
        OkHttpClient client = TlsTrust.systemTrustBuilder(HttpUrl.get("https://hermes.example/p/robot/v1")).build();
        assertSame(new OkHttpClient().hostnameVerifier(), client.hostnameVerifier());
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
        X509Certificate root = TlsTrust.loadIsrgRoot();
        SSLSession session = (SSLSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SSLSession.class}, (proxy, method, args) -> {
                    if ("getPeerCertificates".equals(method.getName())) return new X509Certificate[]{root};
                    return null;
                });
        assertFalse(client.hostnameVerifier().verify("hermes.example", session));
        for (String url : new String[]{"https://other.example/", "http://hermes.example/", "https://hermes.example:444/"}) {
            try {
                client.newCall(new Request.Builder().url(url).build()).execute().close();
                fail("A Hermes TLS client must stay on its configured HTTPS origin");
            } catch (SSLPeerUnverifiedException expected) {
                assertTrue(expected.getMessage().contains("configured HTTPS origin"));
            }
        }
    }

    private static X509TrustManager manager(KeyStore roots) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(roots);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new AssertionError("Missing JVM trust manager");
    }

    private static String authType(X509Certificate certificate) {
        return "RSA".equals(certificate.getPublicKey().getAlgorithm()) ? "RSA" : "ECDHE_ECDSA";
    }
}
