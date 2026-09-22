package com.robot.asus.kira;

import org.json.JSONObject;

/** Only the documented ASUS DS payload can establish a measured sound direction. */
final class SpeakerDirection {
    static final long MAX_AGE_MS = 3_000L;
    private double degrees = Double.NaN;
    private long measuredAt;

    synchronized boolean observe(JSONObject event, long now) {
        JSONObject query = event == null ? null : event.optJSONObject("event_slu_query");
        Object value = query == null ? null : query.opt("doa");
        if (!(value instanceof Number)) return false;
        double candidate = ((Number) value).doubleValue();
        if (Double.isNaN(candidate) || Double.isInfinite(candidate) || candidate < -180 || candidate > 180) return false;
        degrees = candidate;
        measuredAt = now;
        return true;
    }

    synchronized double fresh(long now) {
        return now >= measuredAt && now - measuredAt <= MAX_AGE_MS ? degrees : Double.NaN;
    }

    synchronized void clear() { degrees = Double.NaN; measuredAt = 0; }
}
