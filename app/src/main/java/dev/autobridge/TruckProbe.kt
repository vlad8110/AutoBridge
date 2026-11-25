package dev.autobridge

import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager

/**
 * Every vehicle property the truck lets AutoBridge read, with its current value, for the log.
 * The truck decides what's on this list (GM hides some, Android keeps others for system apps).
 */
object TruckProbe {
    fun dump(props: CarPropertyManager): List<String> = runCatching {
        props.propertyList.sortedBy { VehiclePropertyIds.toString(it.propertyId) }.flatMap { cfg ->
            val name = VehiclePropertyIds.toString(cfg.propertyId)
            val areas = cfg.areaIds.takeIf { it.isNotEmpty() } ?: intArrayOf(0)
            areas.map { area ->
                val value = runCatching {
                    val v = props.getProperty<Any>(cfg.propertyId, area)
                    if (v.status != 0) "unavailable (status ${v.status})" else show(v.value)
                }.getOrElse { "can't read: ${it.javaClass.simpleName}" }
                "$name${if (areas.size > 1) " [area $area]" else ""} = $value"
            }
        }
    }.getOrElse { listOf("property list unavailable: $it") }

    private fun show(v: Any?): String = when (v) {
        is IntArray -> v.contentToString()
        is LongArray -> v.contentToString()
        is FloatArray -> v.contentToString()
        is ByteArray -> "${v.size} bytes"
        is Array<*> -> v.contentToString()
        else -> v.toString()
    }
}
