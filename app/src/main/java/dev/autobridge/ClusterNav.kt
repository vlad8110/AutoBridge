package dev.autobridge

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import java.lang.ref.WeakReference

/**
 * Android Auto's turn-by-turn, kept here for GM's cluster. The chain (from OCBM / carlink):
 * our CarAppService's NavigationManager -> Google's Templates Host -> the car -> GM's cluster, which
 * draws its own arrow for the maneuver type. The Templates Host only binds our service after our
 * CarAppActivity has been opened once, so [bootstrap] opens it for a second (a short flash).
 */
object ClusterNav {
    private const val TAG = "AutoBridge"
    private const val CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity"
    private const val TEMPLATES_HOST = "com.google.android.apps.automotive.templates.host"

    /** Everything known about the route. Types are Android Auto's NavigationManeuver numbers; -1 = unknown. */
    data class Route(
        val active: Boolean = false,
        val type: Int = 0, val exit: Int = 0, val road: String? = null, val cue: String? = null, val destination: String? = null,
        val stepMeters: Int = -1, val stepE3: Int = -1, val stepUnit: Int = 0, val stepSec: Int = -1,
        val destMeters: Int = -1, val destE3: Int = -1, val destUnit: Int = 0, val destSec: Int = -1,
        val currentRoad: String? = null,
    ) {
        /** Worth showing: a route with a turn in it. */
        val showable get() = active && type != 0
    }

    @Volatile var route = Route(); private set
    /** The bound cluster session; null until the Templates Host binds us. Main thread. */
    var sink: ((Route) -> Unit)? = null
        set(v) {
            field = v; Log.i(TAG, "cluster: session ${if (v == null) "unbound" else "bound"}"); v?.invoke(route)
            // Dropped during a route (seen at a Bluetooth reconnect): no new route start would rebind it, so do it now.
            if (v == null && route.showable) main.postDelayed({ if (sink == null) front?.get()?.let { bootstrap(it, "lost mid-route") } }, 2000)
        }
    private val main by lazy { Handler(Looper.getMainLooper()) }  // lazy: keeps the mapping functions unit-testable
    private val deliver = Runnable { sink?.invoke(route) }
    private var front: WeakReference<Activity>? = null
    private var triedAt = 0L

    // ---- from the phone (native session thread) ----

    /** Android Auto's NavigationStatus: 0 unavailable, 1 active, 2 inactive, 3 rerouting. */
    fun status(s: Int) {
        Log.i(TAG, "cluster: navigation status $s")
        update(if (s == 1 || s == 3) route.copy(active = true) else Route())
    }
    fun step(type: Int, exit: Int, road: String?, cue: String?, destination: String?) =
        update(route.copy(active = true, type = type, exit = exit, road = road, cue = cue, destination = destination ?: route.destination))
    fun legacyTurn(event: Int, side: Int, exit: Int, road: String?) = step(fromLegacy(event, side), exit, road, null, null)
    fun distance(m: Int, e3: Int, unit: Int, sec: Int, destM: Int, destE3: Int, destUnit: Int, destSec: Int, currentRoad: String?) {
        var r = route.copy(stepMeters = m, stepE3 = e3, stepUnit = unit, stepSec = sec, currentRoad = currentRoad ?: route.currentRoad)
        if (destM >= 0 || destSec >= 0) r = r.copy(destMeters = destM, destE3 = destE3, destUnit = destUnit, destSec = destSec)
        update(r)
    }
    fun phoneGone() = update(Route())

    private fun update(r: Route) {
        val started = r.showable && !route.showable
        route = r
        main.removeCallbacks(deliver)
        main.postDelayed(deliver, 250)  // coalesce bursts; the last state always lands
        if (started) main.post { if (sink == null) front?.get()?.let { bootstrap(it, "route started") } }
    }

    // ---- binding the Templates Host ----

    fun resumed(a: Activity) {
        front = WeakReference(a)
        if (sink == null && triedAt == 0L) main.postDelayed({ front?.get()?.let { bootstrap(it, "app opened") } }, 1500)
    }
    fun paused(a: Activity) { if (front?.get() === a) front = null }

    private fun bootstrap(a: Activity, why: String) {
        val now = System.currentTimeMillis()
        if (sink != null || a.isFinishing || now - triedAt < 60_000) return
        triedAt = now
        if (runCatching { a.packageManager.getPackageInfo(TEMPLATES_HOST, 0) }.isFailure) {
            Log.w(TAG, "cluster: no Templates Host on this car, so no turn-by-turn in the cluster"); return
        }
        val task = a.taskId
        try {
            a.startActivity(Intent().setClassName(a, CAR_APP_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            Log.i(TAG, "cluster: opened CarAppActivity to bind the Templates Host ($why)")
        } catch (t: Throwable) {
            Log.e(TAG, "cluster: could not open CarAppActivity: $t"); return
        }
        main.postDelayed({
            runCatching { a.getSystemService(ActivityManager::class.java).moveTaskToFront(task, 0) }
                .onFailure { Log.w(TAG, "cluster: could not bring AutoBridge back: $it") }
        }, 1000)
        main.postDelayed({ if (sink == null) Log.e(TAG, "cluster: Templates Host did not bind within 10 s") }, 10_000)
    }

    // ---- maneuvers ----

    /** Android Auto's NavigationManeuver type -> Car App Library's Maneuver type (right-hand traffic). */
    fun maneuver(aa: Int, exit: Int): Int = when (aa) {
        1 -> Maneuver.TYPE_DEPART
        2 -> Maneuver.TYPE_NAME_CHANGE
        3 -> Maneuver.TYPE_KEEP_LEFT
        4 -> Maneuver.TYPE_KEEP_RIGHT
        5 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        6 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        7 -> Maneuver.TYPE_TURN_NORMAL_LEFT
        8 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        9 -> Maneuver.TYPE_TURN_SHARP_LEFT
        10 -> Maneuver.TYPE_TURN_SHARP_RIGHT
        11 -> Maneuver.TYPE_U_TURN_LEFT
        12 -> Maneuver.TYPE_U_TURN_RIGHT
        13 -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
        14 -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
        15 -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
        16 -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
        17 -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
        18 -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
        19 -> Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
        20 -> Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT
        21 -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
        22 -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
        23 -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
        24 -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        25 -> Maneuver.TYPE_FORK_LEFT
        26 -> Maneuver.TYPE_FORK_RIGHT
        27 -> Maneuver.TYPE_MERGE_LEFT
        28 -> Maneuver.TYPE_MERGE_RIGHT
        29 -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
        30 -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        31 -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
        // The library refuses enter-and-exit without an exit number; the angle variants also need an angle.
        32, 33 -> if (exit > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CW
        34, 35 -> if (exit > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
        36 -> Maneuver.TYPE_STRAIGHT
        37 -> Maneuver.TYPE_FERRY_BOAT
        38 -> Maneuver.TYPE_FERRY_TRAIN
        39 -> Maneuver.TYPE_DESTINATION
        40 -> Maneuver.TYPE_DESTINATION_STRAIGHT
        41 -> Maneuver.TYPE_DESTINATION_LEFT
        42 -> Maneuver.TYPE_DESTINATION_RIGHT
        else -> Maneuver.TYPE_UNKNOWN
    }

    /** Older Android Auto's turn event (NextTurnEnum + side 1 left / 2 right) -> NavigationManeuver type. */
    fun fromLegacy(event: Int, side: Int): Int {
        val left = side == 1
        return when (event) {
            1 -> 1; 2 -> 2
            3 -> if (left) 5 else 6
            4 -> if (left) 7 else 8
            5 -> if (left) 9 else 10
            6 -> if (left) 11 else 12
            7 -> if (left) 15 else 16
            8 -> if (left) 23 else 24
            9 -> if (left) 25 else 26
            10 -> if (side == 1) 27 else if (side == 2) 28 else 29
            11 -> 30; 12 -> 31
            13 -> if (left) 32 else 34
            14 -> 36; 16 -> 37; 17 -> 38; 19 -> 39
            else -> 0
        }
    }

    /** Android Auto's display distance (value x1000 + unit) when given, else metres. Units match the library's. */
    fun distanceOf(meters: Int, e3: Int, unit: Int): Distance? = when {
        e3 >= 0 && unit in 1..7 -> Distance.create(e3 / 1000.0, unit)
        meters >= 0 -> Distance.create(meters.toDouble(), Distance.UNIT_METERS)
        else -> null
    }
}
