package dev.autobridge

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterNavTest {
    @Test fun androidAutoTurnsBecomeClusterTurns() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, ClusterNav.maneuver(7, 0))
        assertEquals(Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT, ClusterNav.maneuver(24, 0))
        assertEquals(Maneuver.TYPE_DESTINATION, ClusterNav.maneuver(39, 0))
        assertEquals(Maneuver.TYPE_UNKNOWN, ClusterNav.maneuver(0, 0))
        // Enter-and-exit needs an exit number, or the library throws.
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, ClusterNav.maneuver(34, 2))
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, ClusterNav.maneuver(34, 0))
    }

    @Test fun olderTurnEvents() {
        assertEquals(7, ClusterNav.fromLegacy(4, 1))   // turn, left
        assertEquals(8, ClusterNav.fromLegacy(4, 2))   // turn, right
        assertEquals(24, ClusterNav.fromLegacy(8, 3))  // off ramp, side unknown -> right
        assertEquals(39, ClusterNav.fromLegacy(19, 3)) // destination
        assertEquals(0, ClusterNav.fromLegacy(99, 1))
    }

    @Test fun distances() {
        val d = ClusterNav.distanceOf(480, 300, Distance.UNIT_MILES_P1)!!
        assertEquals(0.3, d.displayDistance, 1e-9)
        assertEquals(Distance.UNIT_MILES_P1, d.displayUnit)
        assertEquals(Distance.UNIT_METERS, ClusterNav.distanceOf(480, -1, 0)!!.displayUnit)
        assertNull(ClusterNav.distanceOf(-1, -1, 0))
    }

    @Test fun logNames() {
        assertEquals("autobridge-1970-01-01_00-00-00-auto.txt",
            Logs.name("Auto!", java.util.Date(0 - java.util.TimeZone.getDefault().getOffset(0L).toLong())))
    }
}
