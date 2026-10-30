package com.ember.simpleplex.player

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the TV to a refresh rate that matches the video (23.976 → 23.976 Hz, 25p → 50 Hz, 1080i50 → 50 Hz, ...).
 * Uses the window's preferred display mode, which works from Android 6 up, including Sony's Android 10 TVs.
 */
class DisplayModeSwitcher(private val activity: Activity) {

    @Suppress("DEPRECATION")
    private val display: Display?
        get() = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay

    /** Returns after the TV has finished switching, so playback doesn't start into a blank HDMI resync. */
    suspend fun match(fps: Float?, interlaced: Boolean) {
        val d = display ?: return
        if (fps == null || fps <= 0f) return
        // Interlaced video is reported per frame (25, 29.97); its fields need a 50 / 59.94 Hz display.
        val target = if (interlaced && fps < 31f) fps * 2 else fps
        val current = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .mapNotNull { mode ->
                val ratio = mode.refreshRate / target
                val multiple = ratio.roundToInt()
                val error = abs(ratio - multiple) / multiple.coerceAtLeast(1)
                if (multiple >= 1 && error < 0.0015f) Triple(mode, multiple, error) else null
            }
            .minWithOrNull(compareBy({ it.second }, { it.third }))
            ?.first ?: return
        if (best.modeId == current.modeId) return

        val window = activity.window
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        withTimeoutOrNull(5000) {
            while (display?.mode?.modeId != best.modeId) delay(100)
        }
        // The TV and the receiver need a moment to resync HDMI/eARC after the switch.
        delay(800)
    }

    companion object {
        /** Plex's coarse media-level label, used when the stream has no exact frame rate. */
        fun fpsFromLabel(label: String?): Float? = when (label?.lowercase()) {
            "24p" -> 23.976f
            "pal" -> 25f
            "ntsc" -> 29.97f
            "50p" -> 50f
            "60p" -> 59.94f
            else -> null
        }
    }
}
