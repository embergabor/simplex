package com.ember.simpleplex.ui

import android.content.Context

/** Which libraries are hidden from the home screen, by Plex library key. Kept on the TV. */
class LibraryPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var hidden: Set<String>
        get() = prefs.getStringSet("hiddenLibraries", emptySet()).orEmpty().toSet()
        set(value) { prefs.edit().putStringSet("hiddenLibraries", value).apply() }
}
