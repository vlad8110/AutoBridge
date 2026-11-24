package dev.autobridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TruckTest {
    @Test fun nothingSharedMeansNothingShownOrSent() {
        assertNull(Truck().line(miles = true))
        assertArrayEquals(IntArray(5) { Truck.UNKNOWN }, Truck().toArray())
    }

    @Test fun valuesForAndroidAutoAndTheScreen() {
        val t = Truck(speedMs = 10f, gear = 0x4, parkingBrake = true, batteryWh = 100_000f, capacityWh = 200_000f, rangeM = 321_869f)
        assertArrayEquals(intArrayOf(10_000, 101, 1, 50, 321_869), t.toArray())
        assertEquals("Truck: battery 50% · range 200 mi · gear P · parking brake on · 22 mph", t.line(miles = true))
        assertEquals("Truck: battery 50% · range 322 km · gear P · parking brake on · 36 km/h", t.line(miles = false))
        assertEquals(Console.Kind.INFO, Console.kind(t.line(true)!!))
    }

    @Test fun gears() {
        assertEquals(listOf(0, 102, 101, 100, 1, 9), listOf(0x1, 0x2, 0x4, 0x8, 0x10, 0x1000).map(Truck::aaGear))
        assertNull(Truck.aaGear(0))
        assertNull(Truck(batteryWh = 5f).batteryPercent)  // no capacity, no percentage
    }
}
