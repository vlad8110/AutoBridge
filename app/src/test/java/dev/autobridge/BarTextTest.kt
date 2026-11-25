package dev.autobridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BarTextTest {
    // The values in the 23:37 log: charging at home, 8 kW, 15 min to an 80% limit.
    private val charging = Truck(gear = 0x4, parkingBrake = false, batteryWh = 164_290f, capacityWh = 206_670f, rangeM = 613_984f,
        rateMw = -8_000_000f, chargeState = 1, chargeSec = 900, chargeLimit = 80f, currentA = 80f, portOpen = true,
        portConnected = true, regen = 2, outsideC = 22.5f, miles = true, fahrenheit = true)

    @Test fun chargingMatchesTheTruck() {
        val b = BarText.of(charging, localeMiles = true)
        assertEquals("79%", b.percent)
        assertEquals(true, b.charging)
        assertEquals(listOf("382 mi", "164", "8 kW", "15 min", "Plugged", "73°"), b.cols.map { it.value })
        assertEquals(listOf("Range", "Energy", "80 A max", "to 80%", "Port open", "Outside"), b.cols.map { it.label })
        assertEquals("P", b.gear); assertEquals(false, b.gearActive); assertEquals("Off", b.brake)
    }

    @Test fun drivingShowsSpeedPowerAndRegen() {
        val t = charging.copy(gear = 0x8, chargeState = 3, portConnected = false, portOpen = false, rateMw = 62_400_000f, speedMs = 20.1f)
        val b = BarText.of(t, localeMiles = true)
        assertEquals(listOf("45 mph", "62 kW", "Partial"), b.cols.subList(2, 5).map { it.value })
        assertEquals(BarText.Tone.BAD, b.cols[3].tone)
        assertEquals("D", b.gear); assertEquals(true, b.gearActive)
        assertEquals(BarText.Tone.GOOD, BarText.of(t.copy(rateMw = -15_000_000f), true).cols[3].tone)  // regen while driving
    }

    @Test fun missingValuesShowADash() {
        val b = BarText.of(Truck(), localeMiles = false)
        assertEquals("—", b.percent); assertEquals("—", b.gear)
        assertEquals("—", b.cols[0].value)
    }

    @Test fun formats() {
        assertEquals("7.4", BarText.kw(7_360_000f)); assertEquals("8", BarText.kw(8_000_000f)); assertEquals("62", BarText.kw(62_400_000f))
        assertEquals("15 min", BarText.duration(900)); assertEquals("1 h 20 min", BarText.duration(4800))
    }
}
