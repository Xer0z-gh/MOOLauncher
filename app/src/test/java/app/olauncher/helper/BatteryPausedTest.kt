package app.olauncher.helper

import android.os.BatteryManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPausedTest {
    @Test fun heldOnTheChargerIsPaused() {
        // Samsung battery protection at 85%: plugged, status NOT_CHARGING.
        assertTrue(BatteryIndicator.isPaused(BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_PLUGGED_AC))
    }

    @Test fun unpluggedChargingAndFullAreNotPaused() {
        assertFalse(BatteryIndicator.isPaused(BatteryManager.BATTERY_STATUS_NOT_CHARGING, 0))
        assertFalse(BatteryIndicator.isPaused(BatteryManager.BATTERY_STATUS_DISCHARGING, 0))
        assertFalse(BatteryIndicator.isPaused(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_USB))
        assertFalse(BatteryIndicator.isPaused(BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_USB))
    }
}
