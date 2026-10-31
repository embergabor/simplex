@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.ember.simpleplex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api

/**
 * Home | PlexVerse | Kids, at the top of each of the three. Kids only once PlexVerse is set up with
 * kids kept separate.
 */
@Composable
fun TopMenu(current: Screen, nav: Nav, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val refresh = LocalRefresh.current
    val setup = remember(refresh) { PlexVersePrefs(context).setup }
    Row(modifier.padding(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Home", { nav.push(Screen.Home) }, selected = current == Screen.Home)
        PillButton("PlexVerse", { nav.push(Screen.PlexVerse) }, selected = current == Screen.PlexVerse)
        if (setup.isSetUp && setup.kidsSeparate) {
            PillButton("Kids", { nav.push(Screen.Kids) }, selected = current == Screen.Kids)
        }
    }
}

/** Every server's libraries as one per category. Shows the setup until there is one. */
@Composable
fun PlexVerseScreen(nav: Nav) {
    val context = LocalContext.current
    val refresh = LocalRefresh.current
    val setup = remember(refresh) { PlexVersePrefs(context).setup }
    if (!setup.isSetUp) return PlexVerseSetupScreen(nav)

    val memory = rememberFocusMemory()
    Column(Modifier.fillMaxSize().padding(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TopMenu(Screen.PlexVerse, nav)
        Row(Modifier.padding(horizontal = 48.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // ponytail: categories open nothing until their grids exist (steps 5 and 7).
            IconTile(Icons.Film, "Movies", {}, Modifier.size(180.dp, 108.dp).remembersFocus(memory, "movies", default = true))
            IconTile(Icons.Tv, "Series", {}, Modifier.size(180.dp, 108.dp).remembersFocus(memory, "series"))
            IconTile(Icons.Folder, "Videos", {}, Modifier.size(180.dp, 108.dp).remembersFocus(memory, "videos"))
            IconTile(Icons.Edit, "Customize", { nav.push(Screen.PlexVerseSetup) }, Modifier.size(108.dp, 108.dp).remembersFocus(memory, "customize"))
        }
    }
}

/** Which server library goes into which category, and the server order. Built in step 4. */
@Composable
fun PlexVerseSetupScreen(nav: Nav) {
    Column(Modifier.fillMaxSize().padding(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TopMenu(Screen.PlexVerse, nav)
        Message("PlexVerse isn't set up yet.")
    }
}

/** Kids' movies and series in one grid. Built in step 8. */
@Composable
fun KidsScreen(nav: Nav) {
    Column(Modifier.fillMaxSize().padding(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TopMenu(Screen.Kids, nav)
        Message("Nothing for kids yet.")
    }
}
