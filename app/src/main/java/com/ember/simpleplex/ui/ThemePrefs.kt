package com.ember.simpleplex.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.ember.simpleplex.plex.PlexClient
import kotlinx.coroutines.delay
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Light / Dark / Auto, and where the TV is for Auto. Kept on the TV. */
class ThemePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var mode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.Light
        set(value) { prefs.edit().putString("theme", value.name).apply() }

    /** Latitude and longitude, from the IP address. Cleared each time Auto is picked, so it's looked up again. */
    var place: Pair<Double, Double>?
        get() = if (prefs.contains("lat")) prefs.getFloat("lat", 0f).toDouble() to prefs.getFloat("lng", 0f).toDouble() else null
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("lat").remove("lng")
                else putFloat("lat", value.first.toFloat()).putFloat("lng", value.second.toFloat())
            }.apply()
        }
}

/** Sets [Palette.current] from the saved mode, before anything is drawn. */
fun applyTheme(context: Context) {
    val prefs = ThemePrefs(context)
    Palette.mode = prefs.mode
    Palette.current = if (isDark(prefs.mode, prefs.place, Instant.now(), ZoneId.systemDefault())) Palette.Dark else Palette.Light
}

/** Keeps the palette in step with [Palette.mode]. Auto looks up the place once and follows the sun. */
@Composable
fun FollowTheme(plex: PlexClient) {
    val context = LocalContext.current
    val mode = Palette.mode
    LaunchedEffect(mode) {
        val prefs = ThemePrefs(context)
        prefs.mode = mode
        if (mode == ThemeMode.Auto && prefs.place == null) {
            // City-level is plenty for sunset. Offline: 19:00–07:00 until a later start finds it.
            prefs.place = runCatching {
                val j = JSONObject(plex.call(Request.Builder().url("https://ipapi.co/json/").build()))
                j.getDouble("latitude") to j.getDouble("longitude")
            }.getOrNull()
        }
        while (true) {
            applyTheme(context)
            if (mode != ThemeMode.Auto) break
            // ponytail: checks once a minute rather than sleeping until the next sunrise or sunset;
            // also covers a TV that slept through the switch. Costs a few trig calls.
            delay(60_000)
        }
    }
}

/** Dark before sunrise and after sunset; without a place, from 19:00 to 07:00. */
fun isDark(mode: ThemeMode, place: Pair<Double, Double>?, now: Instant, zone: ZoneId): Boolean = when (mode) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.Auto -> {
        val local = now.atZone(zone)
        if (place == null) local.hour >= 19 || local.hour < 7
        else sunTimes(local.toLocalDate(), place.first, place.second).let { (rise, set) -> now < rise || now >= set }
    }
}

/**
 * Sunrise and sunset on [date] (the sunrise equation, good to a minute or two). Where the sun doesn't
 * rise, both are noon, so it's dark all day; where it doesn't set, they're a day apart.
 */
fun sunTimes(date: LocalDate, lat: Double, lng: Double): Pair<Instant, Instant> {
    fun rad(d: Double) = d * PI / 180
    val j = date.toEpochDay() - 10957 - lng / 360 // days since J2000, at local solar noon
    val m = (357.5291 + 0.98560028 * j) % 360
    val c = 1.9148 * sin(rad(m)) + 0.02 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
    val lambda = (m + c + 180 + 102.9372) % 360
    val transit = 2451545.0 + j + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
    val decl = asin(sin(rad(lambda)) * sin(rad(23.4397)))
    val cosW = (sin(rad(-0.833)) - sin(rad(lat)) * sin(decl)) / (cos(rad(lat)) * cos(decl))
    val halfDay = acos(cosW.coerceIn(-1.0, 1.0)) / (2 * PI) // in days
    fun instant(julian: Double) = Instant.ofEpochMilli(((julian - 2440587.5) * 86_400_000).toLong())
    return instant(transit - halfDay) to instant(transit + halfDay)
}
