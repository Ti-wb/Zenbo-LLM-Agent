package com.robot.asus.kira;

import android.os.BatteryManager;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BatteryStateTest {
    @Test public void normalizesFirmwareScaleAndChargingState() throws Exception {
        JSONObject reading = BatteryState.fromReading(165, 200, BatteryManager.BATTERY_STATUS_CHARGING, true).toJson();
        assertEquals(83, reading.getInt("percentage"));
        assertTrue(reading.getBoolean("charging"));
        assertEquals(Integer.valueOf(0), BatteryState.fromReading(0, 100,
                BatteryManager.BATTERY_STATUS_DISCHARGING, true).percentage);
        assertEquals(Boolean.FALSE, BatteryState.fromReading(20, 100,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING, true).charging);
        assertEquals(Boolean.TRUE, BatteryState.fromReading(100, 100,
                BatteryManager.BATTERY_STATUS_FULL, true).charging);
    }

    @Test public void tetherStateUsesCableReadingsEvenWhenBatteryIsFullOrNotCharging() {
        assertEquals(Boolean.TRUE, BatteryState.fromReading(100, 100,
                BatteryManager.BATTERY_STATUS_FULL, true, BatteryManager.BATTERY_PLUGGED_AC).powerConnected);
        assertEquals(Boolean.TRUE, BatteryState.fromReading(100, 100,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING, true, BatteryManager.BATTERY_PLUGGED_USB).powerConnected);
        assertEquals(Boolean.FALSE, BatteryState.fromReading(100, 100,
                BatteryManager.BATTERY_STATUS_FULL, true, 0).powerConnected);
        assertEquals(Boolean.TRUE, BatteryState.fromReading(-1, -1,
                BatteryManager.BATTERY_STATUS_UNKNOWN, false, BatteryManager.BATTERY_PLUGGED_AC).powerConnected);
        assertNull(BatteryState.UNKNOWN.powerConnected);
        assertEquals("POWER_CONNECTED", RobotGateway.motionBlockedReason(true, true));
        assertEquals("USB_CONNECTED", RobotGateway.motionBlockedReason(false, true));
        assertEquals("", RobotGateway.motionBlockedReason(false, false));
        assertEquals("", RobotGateway.motionBlockedReason(null, null));
    }

    @Test public void unavailableOrInvalidReadingsStayUnknown() {
        assertTrue(BatteryState.UNKNOWN.toJson().isNull("percentage"));
        assertTrue(BatteryState.UNKNOWN.toJson().isNull("charging"));
        assertNull(BatteryState.fromReading(-1, 100, BatteryManager.BATTERY_STATUS_UNKNOWN, true).percentage);
        assertNull(BatteryState.fromReading(1, 0, BatteryManager.BATTERY_STATUS_UNKNOWN, true).percentage);
        assertNull(BatteryState.fromReading(101, 100, BatteryManager.BATTERY_STATUS_UNKNOWN, true).percentage);
        assertNull(BatteryState.fromReading(50, 100, BatteryManager.BATTERY_STATUS_UNKNOWN, true).charging);
        assertSame(BatteryState.UNKNOWN, BatteryState.fromReading(50, 100,
                BatteryManager.BATTERY_STATUS_CHARGING, false));
    }
}
