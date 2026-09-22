package com.robot.asus.kira;

import android.os.BatteryManager;

import org.json.JSONObject;

/** Nullable battery readings: unavailable firmware data must not look like an empty battery. */
final class BatteryState {
    static final BatteryState UNKNOWN = new BatteryState(null, null, null);
    final Integer percentage;
    final Boolean charging;
    final Boolean powerConnected;

    private BatteryState(Integer percentage, Boolean charging, Boolean powerConnected) {
        this.percentage = percentage;
        this.charging = charging;
        this.powerConnected = powerConnected;
    }

    static BatteryState fromReading(int level, int scale, int status, boolean present) {
        return fromReading(level, scale, status, present, -1);
    }

    static BatteryState fromReading(int level, int scale, int status, boolean present, int plugged) {
        if (!present && plugged < 0) return UNKNOWN;
        Integer percentage = present && level >= 0 && scale > 0 && level <= scale
                ? (int) Math.round(level * 100.0 / scale) : null;
        Boolean charging = null;
        if (present && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)) {
            charging = true;
        } else if (present && (status == BatteryManager.BATTERY_STATUS_DISCHARGING
                || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING)) {
            charging = false;
        }
        // A full or paused battery can still be tethered. Prefer the actual cable reading.
        Boolean connected = null;
        if (plugged >= 0) connected = plugged != 0;
        else if (Boolean.TRUE.equals(charging)) connected = true;
        return new BatteryState(percentage, charging, connected);
    }

    JSONObject toJson() { return HermesClient.json("percentage", percentage, "charging", charging); }
}
