package dev.autobridge

import kotlin.math.roundToInt

/** What the truck shares about itself. null = the truck didn't share it (not supported, or not allowed). */
data class Truck(
    val speedMs: Float? = null,
    val gear: Int? = null,              // android.car.VehicleGear
    val parkingBrake: Boolean? = null,
    val batteryWh: Float? = null,
    val capacityWh: Float? = null,
    val rangeM: Float? = null,
    // For the bar under Android Auto (TruckBar). Charging power is in mW, negative while charging.
    val rateMw: Float? = null,
    val chargeState: Int? = null,        // 1 charging, 2 full, 3 not charging
    val chargeSec: Int? = null,
    val chargeLimit: Float? = null,      // %
    val currentA: Float? = null,
    val portOpen: Boolean? = null,
    val portConnected: Boolean? = null,
    val regen: Int? = null,              // 1 off, 2 partial, 3 full (one-pedal)
    val miles: Boolean? = null,          // the truck's own display units
) {
    /** Charging when the truck says so, or when plugged in with power flowing into the battery. */
    val charging get() = chargeState == 1 || (portConnected == true && (rateMw ?: 0f) < 0f)

    val batteryPercent: Int?
        get() = if (batteryWh != null && capacityWh != null && capacityWh > 0) (batteryWh / capacityWh * 100).roundToInt().coerceIn(0, 100) else null

    /** For Native: [speed mm/s, Android Auto gear, parking brake 0/1, battery %, range m]; UNKNOWN where not shared. */
    fun toArray() = intArrayOf(
        speedMs?.let { (it * 1000).roundToInt() } ?: UNKNOWN,
        gear?.let(::aaGear) ?: UNKNOWN,
        parkingBrake?.let { if (it) 1 else 0 } ?: UNKNOWN,
        batteryPercent ?: UNKNOWN,
        rangeM?.roundToInt() ?: UNKNOWN,
    )

    /** The big bar on the waiting screen, or null when the truck shares nothing. */
    fun line(miles: Boolean): String? {
        val parts = listOfNotNull(
            batteryPercent?.let { "$it%" },
            rangeM?.let { if (miles) "${(it / 1609.344f).roundToInt()} mi" else "${(it / 1000).roundToInt()} km" },
            gear?.let(::gearName),
            parkingBrake?.let { "brake ${if (it) "on" else "off"}" },
            speedMs?.let { if (miles) "${(it * 2.23694f).roundToInt()} mph" else "${(it * 3.6f).roundToInt()} km/h" },
        )
        return if (parts.isEmpty()) null else parts.joinToString("  ·  ")
    }

    companion object {
        const val UNKNOWN = Int.MIN_VALUE

        /** android.car.VehicleGear (bit flags) to Android Auto's Gear numbers. */
        fun aaGear(vehicleGear: Int): Int? = when (vehicleGear) {
            0x1 -> 0      // neutral
            0x2 -> 102    // reverse
            0x4 -> 101    // park
            0x8 -> 100    // drive
            else -> (1..9).firstOrNull { vehicleGear == 0x8 shl it }  // GEAR_1 = 0x10 … GEAR_9 = 0x1000
        }

        fun gearName(vehicleGear: Int): String? = when (aaGear(vehicleGear)) {
            null -> null
            0 -> "N"; 102 -> "R"; 101 -> "P"; 100 -> "D"
            else -> aaGear(vehicleGear).toString()
        }
    }
}
