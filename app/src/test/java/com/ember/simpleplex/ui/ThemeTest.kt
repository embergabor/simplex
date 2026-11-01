package com.ember.simpleplex.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ThemeTest {

    private val budapest = 47.5 to 19.04
    private val zone = ZoneId.of("Europe/Budapest")
    private fun at(s: String) = Instant.parse(s)
    private fun dark(now: String, place: Pair<Double, Double>? = budapest) = isDark(ThemeMode.Auto, place, at(now), zone)

    @Test fun sunTimesMatchBudapest() {
        // Solar noon about 11:28 CET, day about 10 h 4 min: sunrise near 06:26, sunset near 16:30 CET on 31 October 2026.
        val (rise, set) = sunTimes(LocalDate.of(2026, 10, 31), budapest.first, budapest.second)
        assertTrue(Duration.between(at("2026-10-31T05:26:00Z"), rise).abs().toMinutes() <= 3)
        assertTrue(Duration.between(at("2026-10-31T15:30:00Z"), set).abs().toMinutes() <= 3)
    }

    @Test fun autoFollowsTheSun() {
        assertTrue(dark("2026-10-31T04:00:00Z"))  // before sunrise
        assertFalse(dark("2026-10-31T11:00:00Z")) // daytime
        assertTrue(dark("2026-10-31T17:00:00Z"))  // after sunset
        assertTrue(dark("2026-10-31T22:59:00Z"))  // just before local midnight
    }

    @Test fun withoutPlaceDarkFromSevenToSeven() {
        assertTrue(dark("2026-10-31T18:30:00Z", place = null))  // 19:30 local
        assertFalse(dark("2026-10-31T06:30:00Z", place = null)) // 07:30 local
    }

    @Test fun polarNightIsDarkAndMidnightSunIsLight() {
        val tromso = 69.65 to 18.96
        assertTrue(isDark(ThemeMode.Auto, tromso, at("2026-12-21T11:00:00Z"), zone))
        assertFalse(isDark(ThemeMode.Auto, tromso, at("2026-06-21T23:00:00Z"), zone))
    }

    @Test fun fixedModesIgnoreTheSun() {
        assertEquals(false, isDark(ThemeMode.Light, budapest, at("2026-10-31T22:00:00Z"), zone))
        assertEquals(true, isDark(ThemeMode.Dark, budapest, at("2026-10-31T11:00:00Z"), zone))
    }
}
