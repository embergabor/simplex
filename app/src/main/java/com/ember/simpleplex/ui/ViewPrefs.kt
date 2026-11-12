package com.ember.simpleplex.ui

import android.content.Context
import com.embergabor.simplex.core.ui.PlayerPrefs

/** Viewing options from Settings, kept on the TV. The player's own are in [com.embergabor.simplex.core.ui.PlayerPrefs]. */
class ViewPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Unwatched episodes' pictures blurred on the show page and in search, so they don't give anything away. */
    var blurUnwatched: Boolean
        get() = prefs.getBoolean("blurUnwatched", false)
        set(value) { prefs.edit().putBoolean("blurUnwatched", value).apply() }
}
