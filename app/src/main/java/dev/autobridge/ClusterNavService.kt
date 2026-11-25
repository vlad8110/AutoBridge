package dev.autobridge

import android.content.Intent
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.time.ZonedDateTime

/** Bound by Google's Templates Host; hands Android Auto's turns to GM's cluster (see [ClusterNav]). */
class ClusterNavService : CarAppService() {
    // Only the real Templates Host / Android Auto may bind: the session hands out the driver's route.
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = ClusterSession()
}

private class ClusterSession : Session() {
    private var nav: NavigationManager? = null
    private var navigating = false
    private var stoppedByHost = false  // another navigation app took over; stay out until this route ends

    override fun onCreateScreen(intent: Intent): Screen {
        if (ClusterNav.sink != null) return Info(carContext)  // a second session (another display) stays passive
        val nm = runCatching { carContext.getCarService(NavigationManager::class.java) }
            .onFailure { Log.e(TAG, "cluster: no NavigationManager: $it") }.getOrNull() ?: return Info(carContext)
        nav = nm
        nm.setNavigationManagerCallback(object : NavigationManagerCallback {  // must be set before navigationStarted
            override fun onStopNavigation() { navigating = false; stoppedByHost = true; Log.w(TAG, "cluster: the car stopped our navigation") }
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (navigating) runCatching { nm.navigationEnded() }
                if (ClusterNav.sink === render) ClusterNav.sink = null
            }
        })
        ClusterNav.sink = render
        return Info(carContext)
    }

    private val render: (ClusterNav.Route) -> Unit = render@{ r ->
        val nm = nav ?: return@render
        if (!r.active) {
            stoppedByHost = false
            if (navigating) { runCatching { nm.navigationEnded() }; navigating = false; Log.i(TAG, "cluster: route ended") }
            return@render
        }
        if (stoppedByHost || !r.showable) return@render
        try {
            if (!navigating) { nm.navigationStarted(); navigating = true; Log.i(TAG, "cluster: route started") }
            nm.updateTrip(trip(r))
        } catch (t: Throwable) {
            Log.e(TAG, "cluster: trip update failed: $t")
        }
    }

    private fun trip(r: ClusterNav.Route): Trip {
        val now = ZonedDateTime.now()
        val arrival = now.plusSeconds(r.destSec.coerceAtLeast(0).toLong())
        val type = ClusterNav.maneuver(r.type, r.exit)
        val maneuver = Maneuver.Builder(type).apply {
            if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW || type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) setRoundaboutExitNumber(r.exit)
            // GM's cluster draws its own arrow from the type; an icon is carried because every proven GM path had one.
            setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_bridge)).build())
        }.build()
        val step = Step.Builder().setManeuver(maneuver).apply {
            (r.cue ?: r.road)?.let { setCue(it) }
            r.road?.let { setRoad(it) }
        }.build()
        val stepEstimate = TravelEstimate.Builder(ClusterNav.distanceOf(r.stepMeters, r.stepE3, r.stepUnit) ?: ZERO, now.plusSeconds(r.stepSec.coerceAtLeast(0).toLong()))
            .apply { if (r.stepSec >= 0) setRemainingTimeSeconds(r.stepSec.toLong()) }.build()
        val destEstimate = TravelEstimate.Builder(ClusterNav.distanceOf(r.destMeters, r.destE3, r.destUnit) ?: ZERO, arrival)
            .apply { if (r.destSec >= 0) setRemainingTimeSeconds(r.destSec.toLong()) }.build()
        return Trip.Builder()
            .addStep(step, stepEstimate)
            .addDestination(Destination.Builder().setName(r.destination ?: "Destination").build(), destEstimate)
            .apply { r.currentRoad?.let { setCurrentRoad(it) } }
            .setLoading(false)  // GM shows a spinner instead of the turn while a trip is loading (carlink)
            .build()
    }

    /** Only on screen for the ~1 s bootstrap flash. */
    private class Info(ctx: CarContext) : Screen(ctx) {
        override fun onGetTemplate(): Template = NavigationTemplate.Builder()
            .setNavigationInfo(MessageInfo.Builder("AutoBridge").setText("Connecting directions to the cluster…").build())
            .setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build())
            .build()
    }

    private companion object {
        const val TAG = "AutoBridge"
        val ZERO: Distance = Distance.create(0.0, Distance.UNIT_METERS)
    }
}
