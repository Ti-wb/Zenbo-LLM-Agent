package com.robot.asus.kira;

import android.os.BatteryManager;

import org.json.JSONObject;

/** Nullable battery readings: unavailable firmware data must not look like an empty battery. */
final class BatteryState {
    static final BatteryState UNKNOWN = new BatteryState(null, null);
    final Integer percentage;
    final Boolean charging;

    private BatteryState(Integer percentage, Boolean charging) {
        this.percentage = percentage;
        this.charging = charging;
    }

    static BatteryState fromReading(int level, int scale, int status, boolean present) {
        if (!present) return UNKNOWN;
        Integer percentage = level >= 0 && scale > 0 && level <= scale
                ? (int) Math.round(level * 100.0 / scale) : null;
        Boolean charging = null;
        if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
            charging = true;
        } else if (status == BatteryManager.BATTERY_STATUS_DISCHARGING
                || status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
            charging = false;
        }
        return new BatteryState(percentage, charging);
    }

    JSONObject toJson() { return HermesClient.json("percentage", percentage, "charging", charging); }
}
