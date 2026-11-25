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
        assertEquals("—", b.mid); assertEquals("Speed", b.midLabel)  // speed stays in the middle while charging
        assertEquals("15 min", b.timeLeft); assertEquals("to 80%", b.timeLabel)  // the time left sits next to the kW
        assertEquals("8", b.power); assertEquals("Limit 80%", b.meterRight); assertEquals("80 A max", b.caption)
        assertEquals(0.8f, b.meterMark, 1e-6f)
        assertEquals(BarText.Port.PLUGGED, b.port)
        assertEquals("79", b.percent); assertEquals("Charging", b.batteryLabel); assertEquals(false, b.brakeOn)
    }

    @Test fun timeLeftCountsDown() {
        assertEquals("1 h 45 min", BarText.duration(6300)); assertEquals("1 h", BarText.duration(3600))
        assertEquals("15 min", BarText.duration(900)); assertEquals("1 min", BarText.duration(1)); assertEquals("2 h 1 min", BarText.duration(7201))
        assertEquals("", BarText.of(charging.copy(chargeState = 3, portConnected = false), true).timeLeft)  // only while charging
    }

    @Test fun drivingShowsSpeedAndASignedMeter() {
        val t = charging.copy(gear = 0x8, chargeState = 3, portConnected = false, portOpen = false, rateMw = -24_000_000f, speedMs = 20.1f)
        val b = BarText.of(t, localeMiles = true)
        assertEquals("D", b.gear); assertEquals("Drive", b.gearWord); assertEquals(true, b.gearActive)
        assertEquals("45", b.mid); assertEquals("mph", b.midUnit); assertEquals("", b.timeLeft)
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

class BatteryInfoTest {
    private val charging = Truck(gear = 0x4, batteryWh = 164_290f, capacityWh = 206_670f, rangeM = 613_984f,
        rateMw = -8_000_000f, chargeState = 1, chargeSec = 900, chargeLimit = 80f, currentA = 48f, portOpen = true, portConnected = true, miles = true)

    @Test fun chargingDetails() {
        val i = BatteryInfo.of(charging, true, java.time.LocalTime.of(22, 50))
        assertEquals(BatteryInfo.State.CHARGING, i.state); assertEquals(79, i.percent); assertEquals(0.8f, i.limit!!, 1e-6f)
        assertEquals("164 of 207 kWh", i.energy); assertEquals("382", i.range)
        assertEquals("8 kW", i.power); assertEquals("TIME TO 80%", i.timeLabel); assertEquals("15 min", i.timeLeft)
        assertEquals("11:05 PM", i.readyBy)
        assertEquals("+19 mi/h", i.adding)  // 8 kW × (613,984 m / 164,290 Wh) = 29.9 km per hour
        assertEquals("TO 80%", i.toAddLabel); assertEquals("1 kWh", i.toAdd)  // 165.3 kWh wanted, 164.3 there
        assertEquals("48 A", i.current)
    }

    @Test fun notChargingShowsDashes() {
        val i = BatteryInfo.of(charging.copy(chargeState = 3, portConnected = false, portOpen = false, rateMw = 20_000_000f, batteryWh = 100_000f), true, java.time.LocalTime.NOON)
        assertEquals(BatteryInfo.State.ON_BATTERY, i.state)
        assertEquals("—", i.power); assertEquals("—", i.timeLeft); assertEquals("—", i.readyBy); assertEquals("—", i.adding)
        assertEquals("65 kWh", i.toAdd)  // 165.3 − 100 kWh to reach the 80% limit
        assertEquals(BatteryInfo.State.PLUGGED, BatteryInfo.of(charging.copy(chargeState = 3, rateMw = 0f), true, java.time.LocalTime.NOON).state)
    }
}
