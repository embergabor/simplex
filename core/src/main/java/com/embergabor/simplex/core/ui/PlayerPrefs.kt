package com.embergabor.simplex.core.ui

import android.content.Context

/**
 * The player's options, kept on the TV. The app's Settings change them; the player reads them. In the
 * "ui" preferences, where Simplex kept them before the player became its own library.
 */
class PlayerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** The format readout in the player's corner, with the controls. The remote's Info button shows it either way. */
    var playbackDetails: Boolean
        get() = prefs.getBoolean("playbackDetails", false)
        set(value) { prefs.edit().putBoolean("playbackDetails", value).apply() }

    /** Video and sound kept in step by the TV's own hardware (tunneled playback), from the next video on. */
    var tunneled: Boolean
        get() = prefs.getBoolean("tunneled", false)
        set(value) { prefs.edit().putBoolean("tunneled", value).apply() }

    /** How far subtitles sit above where the video puts them, as a fraction of the screen's height. */
    var subtitleLift: Float
        get() = prefs.getFloat("subtitleLift", 0f)
        set(value) { prefs.edit().putFloat("subtitleLift", value).apply() }
}
