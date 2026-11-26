package dev.autobridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TruckTest {
    @Test fun nothingSharedMeansNothingShownOrSent() {
        assertArrayEquals(IntArray(5) { Truck.UNKNOWN }, Truck().toArray())
    }

    @Test fun valuesForAndroidAutoAndTheScreen() {
        val t = Truck(speedMs = 10f, gear = 0x4, parkingBrake = true, batteryWh = 100_000f, capacityWh = 200_000f, rangeM = 321_869f)
        assertArrayEquals(intArrayOf(10_000, 101, 1, 50, 321_869), t.toArray())
    }

    @Test fun gears() {
        assertEquals(listOf(0, 102, 101, 100, 1, 9), listOf(0x1, 0x2, 0x4, 0x8, 0x10, 0x1000).map(Truck::aaGear))
        assertNull(Truck.aaGear(0))
        assertNull(Truck(batteryWh = 5f).batteryPercent)  // no capacity, no percentage
    }
}
