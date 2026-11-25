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
        assertEquals("P", b.gear); assertEquals("Park", b.gearWord); assertEquals(false, b.gearActive)
        assertEquals("382", b.range); assertEquals("mi", b.rangeUnit); assertEquals("164 / 207 kWh", b.energy)
        assertEquals("15", b.mid); assertEquals("min", b.midUnit); assertEquals("to 80%", b.midLabel)
        assertEquals("8", b.power); assertEquals("80 A max", b.meterRight); assertEquals("Limit 80%", b.caption)
        assertEquals(0.8f, b.meterMark, 1e-6f)
        assertEquals(BarText.Port.PLUGGED, b.port)
        assertEquals("79", b.percent); assertEquals("Charging", b.batteryLabel)
    }

    @Test fun drivingShowsSpeedAndASignedMeter() {
        val t = charging.copy(gear = 0x8, chargeState = 3, portConnected = false, portOpen = false, rateMw = -24_000_000f, speedMs = 20.1f)
        val b = BarText.of(t, localeMiles = true)
        assertEquals("D", b.gear); assertEquals("Drive", b.gearWord); assertEquals(true, b.gearActive)
        assertEquals("45", b.mid); assertEquals("mph", b.midUnit)
        assertEquals("−24", b.power); assertEquals(BarText.Tone.GOOD, b.powerTone)  // regen
        assertEquals(0.5f - kotlin.math.sqrt(24f / 200f) / 2, b.meterFrom, 1e-5f); assertEquals(0.5f, b.meterTo, 1e-6f)  // square-root scale, left of centre
        assertEquals("Partial regen", b.caption)
        assertEquals(BarText.Port.CLOSED, b.port)  // shown as Closed, not hidden
        val drawing = BarText.of(t.copy(rateMw = 62_400_000f), true)
        assertEquals("62", drawing.power); assertEquals(BarText.Tone.BAD, drawing.powerTone); assertEquals(0.5f, drawing.meterFrom, 1e-6f)
    }

    @Test fun portLeftOpenWhileDrivingIsFlagged() {
        val b = BarText.of(charging.copy(chargeState = 3, portConnected = false, portOpen = true, rateMw = 0f), true)
        assertEquals(BarText.Port.OPEN, b.port)
    }

    @Test fun missingValuesShowADash() {
        val b = BarText.of(Truck(), localeMiles = false)
        assertEquals("—", b.percent); assertEquals("—", b.gear); assertEquals("—", b.range); assertEquals("km", b.rangeUnit)
        assertEquals(BarText.Port.HIDDEN, b.port)  // the truck shares nothing about the port
    }

    @Test fun numbers() {
        assertEquals("7.4", BarText.number(7.36f)); assertEquals("8", BarText.number(8f)); assertEquals("62", BarText.number(62.4f))
    }
}
