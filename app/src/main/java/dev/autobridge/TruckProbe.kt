package dev.autobridge

import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Modifier

/**
 * Every vehicle property the truck lets AutoBridge read, with its current value, for the log.
 * The truck decides what's on this list (GM hides some, Android keeps others for system apps).
 */
object TruckProbe {
    private const val TAG = "AutoBridge.Truck"
    private const val EVERY_MS = 10_000L  // one line per property at most every 10 s, so a long drive fits the saved log

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

    /** Each car permission AutoBridge asks for, and whether the truck granted it. */
    fun permissions(c: Context): String = runCatching {
        c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            .filter { it.startsWith("android.car.permission.") }
            .joinToString(", ") { "${it.removePrefix("android.car.permission.")} ${if (c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) "yes" else "NO"}" }
    }.getOrElse { "unknown: $it" }

    /**
     * Every property Android knows that isn't on the truck's list, asked for anyway: a permission
     * error means the truck has it but won't share it; anything else means this truck doesn't have it.
     */
    fun hidden(props: CarPropertyManager): List<String> = runCatching {
        val listed = props.propertyList.map { it.propertyId }.toSet()
        val known = VehiclePropertyIds::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .map { it.getInt(null) }.filter { it !in listed && it != 0 }.distinct()
        val (denied, absent) = known.map { id ->
            id to runCatching { props.getProperty<Any>(id, 0) }.exceptionOrNull()
        }.partition { it.second is SecurityException }
        listOf(
            "truck has these but won't share them with AutoBridge: " + denied.joinToString { VehiclePropertyIds.toString(it.first) }.ifEmpty { "none" },
            "not on this truck (or hidden entirely): ${absent.size} — " + absent.joinToString { VehiclePropertyIds.toString(it.first) },
        )
    }.getOrElse { listOf("hidden-property check failed: $it") }

    /** Logs every readable property as it changes during a drive (throttled), into the saved log. */
    fun record(props: CarPropertyManager) {
        val last = HashMap<Long, Pair<Long, String>>()
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) {
                if (v.status != CarPropertyValue.STATUS_AVAILABLE) return
                val key = (v.propertyId.toLong() shl 32) or (v.areaId.toLong() and 0xffffffffL)
                val text = show(v.value); val now = SystemClock.elapsedRealtime()
                synchronized(last) {
                    val prev = last[key]
                    if (prev != null && (prev.second == text || now - prev.first < EVERY_MS)) return
                    last[key] = now to text
                }
                Log.i(TAG, "drive: ${VehiclePropertyIds.toString(v.propertyId)}${if (v.areaId != 0) " [area ${v.areaId}]" else ""} = $text")
            }
            override fun onErrorEvent(propertyId: Int, zone: Int) {}
        }
        runCatching { props.propertyList }.getOrDefault(emptyList()).forEach { cfg ->
            val rate = if (cfg.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) 1f else CarPropertyManager.SENSOR_RATE_ONCHANGE
            if (cfg.changeMode != CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_STATIC)
                runCatching { props.registerCallback(cb, cfg.propertyId, rate) }.onFailure { Log.w(TAG, "can't follow ${VehiclePropertyIds.toString(cfg.propertyId)}: $it") }
        }
    }

    private fun show(v: Any?): String = when (v) {
        is IntArray -> v.contentToString()
        is LongArray -> v.contentToString()
        is FloatArray -> v.contentToString()
        is ByteArray -> "${v.size} bytes"
        is Array<*> -> v.contentToString()
        else -> v.toString()
    }
}
