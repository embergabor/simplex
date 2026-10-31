package com.ember.simpleplex

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ember.simpleplex.player.PlayerActivity
import com.ember.simpleplex.plex.PlexClient
import com.ember.simpleplex.ui.AppTheme
import com.ember.simpleplex.ui.FolderScreen
import com.ember.simpleplex.ui.GridScreen
import com.ember.simpleplex.ui.HomeScreen
import com.ember.simpleplex.ui.LocalRefresh
import com.ember.simpleplex.ui.LocalRefreshBump
import com.ember.simpleplex.ui.Nav
import com.ember.simpleplex.ui.Palette
import com.ember.simpleplex.ui.Screen
import com.ember.simpleplex.ui.SearchScreen
import com.ember.simpleplex.ui.ShowScreen
import com.ember.simpleplex.ui.SignInScreen

class MainActivity : ComponentActivity() {

    private val refresh = mutableIntStateOf(0)
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val plex = PlexClient.get(this)
        setContent {
            AppTheme {
                CompositionLocalProvider(
                    LocalRefresh provides refresh.intValue,
                    LocalRefreshBump provides { refresh.intValue++ },
                ) {
                    Box(Modifier.fillMaxSize().background(Palette.Ground)) { App(plex) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the player: progress and watched marks have changed.
        if (started) refresh.intValue++
        started = true
    }

    @Composable
    private fun App(plex: PlexClient) {
        var signedIn by remember { mutableStateOf(plex.isSignedIn) }
        if (!signedIn) {
            SignInScreen(plex) { signedIn = true }
            return
        }

        val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
        val states = rememberSaveableStateHolder()
        val nav = remember {
            object : Nav {
                override fun push(screen: Screen) { stack.add(screen) }
                override fun play(ratingKeys: List<String>, fromStart: Boolean, shuffle: Boolean, startAt: Int, keepQueue: Boolean) {
                    startActivity(
                        Intent(this@MainActivity, PlayerActivity::class.java)
                            .putStringArrayListExtra(PlayerActivity.EXTRA_KEYS, ArrayList(ratingKeys))
                            .putExtra(PlayerActivity.EXTRA_FROM_START, fromStart)
                            .putExtra(PlayerActivity.EXTRA_SHUFFLE, shuffle)
                            .putExtra(PlayerActivity.EXTRA_START_AT, startAt)
                            .putExtra(PlayerActivity.EXTRA_KEEP_QUEUE, keepQueue)
                    )
                }
            }
        }

        BackHandler(enabled = stack.size > 1) {
            states.removeState(stack.size - 1)
            stack.removeAt(stack.lastIndex)
        }

        // Keyed by depth, so going back restores the scroll position and focus of the previous screen.
        states.SaveableStateProvider(stack.size - 1) {
            when (val s = stack.last()) {
                Screen.Home -> HomeScreen(plex, nav)
                Screen.Search -> SearchScreen(plex, nav)
                is Screen.Grid -> GridScreen(plex, s.library, nav)
                is Screen.Show -> ShowScreen(plex, s.show, nav)
                is Screen.Folder -> FolderScreen(plex, s.library, s.key, s.path, nav)
            }
        }
    }
}
