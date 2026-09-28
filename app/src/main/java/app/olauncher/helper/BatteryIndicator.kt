package app.olauncher.helper

import android.os.BatteryManager

/** Android treats full on external power as charging; paused or unplugged is not charging. */
object BatteryIndicator {
    fun isCharging(status: Int, plugged: Int): Boolean = plugged != 0 &&
        (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)

    /**
     * On the charger but held: Samsung's battery protection stops at 85% with status NOT_CHARGING,
     * which otherwise draws exactly like unplugged.
     */
    fun isPaused(status: Int, plugged: Int): Boolean =
        plugged != 0 && status == BatteryManager.BATTERY_STATUS_NOT_CHARGING

    /** Approximate net power entering the battery, never the charger's USB input power. */
    fun batteryWatts(status: Int, plugged: Int, currentMicroamps: Long, voltageMillivolts: Int): Double? {
        if (status != BatteryManager.BATTERY_STATUS_CHARGING || plugged == 0) return null
        // Missing properties and OEM sentinel values must never appear as a plausible wattage.
        if (currentMicroamps <= 0 || currentMicroamps > 20_000_000L) return null
        if (voltageMillivolts !in 2_500..6_000) return null
        return currentMicroamps.toDouble() * voltageMillivolts / 1_000_000_000.0
    }
}
