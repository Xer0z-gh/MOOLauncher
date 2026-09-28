package app.olauncher.data

import app.olauncher.helper.BatteryIndicator
import android.os.BatteryManager
import org.junit.Assert.*
import org.junit.Test

class BatteryIndicatorTest {
    @Test fun chargingAndFullNeedExternalPower() {
        assertTrue(BatteryIndicator.isCharging(BatteryManager.BATTERY_STATUS_CHARGING, 2))
        assertTrue(BatteryIndicator.isCharging(BatteryManager.BATTERY_STATUS_FULL, 1))
        assertFalse(BatteryIndicator.isCharging(BatteryManager.BATTERY_STATUS_CHARGING, 0))
        assertFalse(BatteryIndicator.isCharging(BatteryManager.BATTERY_STATUS_FULL, 0))
    }
    @Test fun netBatteryPowerUsesMicroampsAndMillivolts() {
        assertEquals(6.3, BatteryIndicator.batteryWatts(BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_PLUGGED_USB, 1_500_000, 4_200)!!, 0.0001)
    }
    @Test fun powerIsHiddenWhenNotChargingOrTelemetryIsInvalid() {
        val charging = BatteryManager.BATTERY_STATUS_CHARGING
        val usb = BatteryManager.BATTERY_PLUGGED_USB
        assertNull(BatteryIndicator.batteryWatts(BatteryManager.BATTERY_STATUS_NOT_CHARGING, usb, 1_000_000, 4_200))
        assertNull(BatteryIndicator.batteryWatts(charging, 0, 1_000_000, 4_200))
        assertNull(BatteryIndicator.batteryWatts(charging, usb, -1_000_000, 4_200))
        assertNull(BatteryIndicator.batteryWatts(charging, usb, Long.MIN_VALUE, 4_200))
        assertNull(BatteryIndicator.batteryWatts(charging, usb, 1_000_000, 0))
    }
    @Test fun pausedDischargingAndUnknownNeverShowTheBolt() {
        assertFalse(BatteryIndicator.isCharging(BatteryManager.BATTERY_STATUS_NOT_CHARGING, 2))
        assertFalse(BatteryIndicator.isCharging(BatteryManager.BATTERY_STATUS_DISCHARGING, 0))
        assertFalse(BatteryIndicator.isCharging(BatteryManager.BATTERY_STATUS_UNKNOWN, 2))
    }
}
