package com.robot.asus.kira;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collection;

/** In-memory, single-controller pairing and lease. No PIN, credentials, or tokens are persisted. */
final class LanRemoteSecurity {
    static final long PAIR_TTL_MS = 120_000;
    static final long HEARTBEAT_TIMEOUT_MS = 1_750;
    static final long SESSION_TTL_MS = 10 * 60_000;
    static final String COOKIE = "zenbo_remote_session";
    private final SecureRandom random = new SecureRandom();
    private boolean enabled;
    private String pairingCode, sessionToken, csrfToken;
    private long pairingExpiresAt, sessionExpiresAt, lastHeartbeat, retryAt;
    private int failedAttempts;

    synchronized void enable(long now) {
        enabled = true;
        clearSession();
        pairingCode = String.format(java.util.Locale.US, "%08d", random.nextInt(100_000_000));
        pairingExpiresAt = now + PAIR_TTL_MS;
        failedAttempts = 0;
        retryAt = 0;
    }
    synchronized void disable() {
        enabled = false;
        pairingCode = null;
        pairingExpiresAt = 0;
        clearSession();
    }
    synchronized String pair(String code, long now) {
        if (!enabled || now < retryAt || sessionToken != null) return null;
        if (pairingCode == null || now >= pairingExpiresAt || !equal(pairingCode, code)) {
            failedAttempts++;
            retryAt = now + Math.min(30_000L, 1000L << Math.min(5, failedAttempts - 1));
            return null;
        }
        sessionToken = token();
        csrfToken = token();
        sessionExpiresAt = now + SESSION_TTL_MS;
        lastHeartbeat = now;
        pairingCode = null;
        pairingExpiresAt = 0;
        return sessionToken;
    }
    synchronized boolean authenticated(String token, long now) {
        return enabled && sessionToken != null && now < sessionExpiresAt && equal(sessionToken, token);
    }
    synchronized boolean canWrite(String token, String csrf, long now) {
        return authenticated(token, now) && equal(csrfToken, csrf);
    }
    synchronized boolean heartbeat(String token, String csrf, long now) {
        if (!canWrite(token, csrf, now) || !leaseAlive(now)) return false;
        lastHeartbeat = now;
        return true;
    }
    synchronized boolean leaseAlive(long now) {
        return enabled && sessionToken != null && now < sessionExpiresAt
                && now - lastHeartbeat < HEARTBEAT_TIMEOUT_MS;
    }
    synchronized boolean expireLease(long now) {
        if (sessionToken == null || leaseAlive(now)) return false;
        clearSession();
        return true;
    }
    synchronized boolean logout(String token, long now) {
        if (!authenticated(token, now)) return false;
        clearSession();
        return true;
    }
    synchronized boolean enabled() { return enabled; }
    synchronized boolean connected(long now) { return authenticated(sessionToken, now) && leaseAlive(now); }
    synchronized String pairingCode(long now) { return now < pairingExpiresAt ? pairingCode : null; }
    synchronized long pairingExpiresAt() { return pairingExpiresAt; }
    synchronized String csrfToken() { return csrfToken; }
    synchronized boolean throttled(long now) { return now < retryAt; }
    private void clearSession() { sessionToken = null; csrfToken = null; sessionExpiresAt = 0; lastHeartbeat = 0; }
    private String token() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte b : bytes) result.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return result.toString();
    }
    static boolean equal(String expected, String candidate) {
        return expected != null && candidate != null && candidate.length() <= 128
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }
    static boolean privateIpv4(String address) {
        if (address == null || !address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = address.split("\\."); int[] n = new int[4];
        for (int i = 0; i < 4; i++) { n[i] = Integer.parseInt(parts[i]); if (n[i] > 255 || !parts[i].equals(String.valueOf(n[i]))) return false; }
        return n[0] == 10 || (n[0] == 172 && n[1] >= 16 && n[1] <= 31) || (n[0] == 192 && n[1] == 168);
    }
    static boolean validRequest(String peer, String host, String origin, boolean write, Collection<String> hosts) {
        if (!privateIpv4(peer) || host == null || !hosts.contains(host)) return false;
        if (origin == null) return !write;
        return origin.equals("http://" + host);
    }
}
