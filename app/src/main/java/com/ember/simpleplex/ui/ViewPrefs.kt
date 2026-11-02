package com.ember.simpleplex.ui

import android.content.Context

/** Viewing options from Settings, kept on the TV. */
class ViewPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** The format readout in the player's corner, with the controls. The remote's Info button shows it either way. */
    var playbackDetails: Boolean
        get() = prefs.getBoolean("playbackDetails", false)
        set(value) { prefs.edit().putBoolean("playbackDetails", value).apply() }

    /** Unwatched episodes' pictures blurred on the show page and in search, so they don't give anything away. */
    var blurUnwatched: Boolean
        get() = prefs.getBoolean("blurUnwatched", false)
        set(value) { prefs.edit().putBoolean("blurUnwatched", value).apply() }
}
