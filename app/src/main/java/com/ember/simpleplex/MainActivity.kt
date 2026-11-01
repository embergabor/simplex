package com.ember.simpleplex

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import com.ember.simpleplex.plex.PlexServer
import com.ember.simpleplex.ui.AppTheme
import com.ember.simpleplex.ui.Copy
import com.ember.simpleplex.ui.FolderScreen
import com.ember.simpleplex.ui.GridScreen
import com.ember.simpleplex.ui.HomeScreen
import com.ember.simpleplex.ui.KidsScreen
import com.ember.simpleplex.ui.LocalRefresh
import com.ember.simpleplex.ui.LocalRefreshBump
import com.ember.simpleplex.ui.Nav
import com.ember.simpleplex.ui.Palette
import com.ember.simpleplex.ui.PivotScroll
import com.ember.simpleplex.ui.RemoteKeys
import com.ember.simpleplex.ui.PlexVerseScreen
import com.ember.simpleplex.ui.PlexVerseSetupScreen
import com.ember.simpleplex.ui.Screen
import com.ember.simpleplex.ui.SearchScreen
import com.ember.simpleplex.ui.ShowScreen
import com.ember.simpleplex.ui.VerseGridScreen
import com.ember.simpleplex.ui.VerseVideosScreen
import com.ember.simpleplex.ui.Message
import com.ember.simpleplex.ui.VerseStore
import com.ember.simpleplex.ui.SignInScreen

private val TOP = setOf(Screen.Home, Screen.PlexVerse, Screen.Kids)

class MainActivity : ComponentActivity() {

    private val refresh = mutableIntStateOf(0)
    private var started = false

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val plex = PlexClient.get(this)
        setContent {
            AppTheme {
                CompositionLocalProvider(
                    LocalRefresh provides refresh.intValue,
                    LocalRefreshBump provides { refresh.intValue++ },
                    LocalBringIntoViewSpec provides PivotScroll.Default,
                ) {
                    Box(Modifier.fillMaxSize().background(Palette.Ground)) { App(plex) }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        RemoteKeys.repeating = event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { RemoteKeys.dx = -1; RemoteKeys.dy = 0 }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { RemoteKeys.dx = 1; RemoteKeys.dy = 0 }
            KeyEvent.KEYCODE_DPAD_UP -> { RemoteKeys.dx = 0; RemoteKeys.dy = -1 }
            KeyEvent.KEYCODE_DPAD_DOWN -> { RemoteKeys.dx = 0; RemoteKeys.dy = 1 }
            else -> { RemoteKeys.dx = 0; RemoteKeys.dy = 0 } // focus after OK or Back: no lean
        }
        return super.dispatchKeyEvent(event)
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

        val home = requireNotNull(plex.home)
        val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
        val states = rememberSaveableStateHolder()
        val nav = remember {
            object : Nav {
                override fun push(screen: Screen) {
                    // The top menu's screens are visited once: going to one already open goes back to it.
                    val open = if (screen in TOP) stack.indexOf(screen) else -1
                    if (open < 0) { stack.add(screen); return }
                    while (stack.size > open + 1) {
                        states.removeState(stateKey(stack.size - 1, stack.last()))
                        stack.removeAt(stack.lastIndex)
                    }
                }
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
            states.removeState(stateKey(stack.size - 1, stack.last()))
            stack.removeAt(stack.lastIndex)
        }

        // A quick fade and settle between screens; kept short so moving around stays fast.
        AnimatedContent(
            targetState = stack.size - 1 to stack.last(),
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.98f)) togetherWith fadeOut(tween(120))
            },
            label = "screens",
        ) { (depth, s) ->
            // Keyed by depth, so going back restores the scroll position and focus of the previous screen.
            // And by screen: during the fade, the screen leaving and the one coming can share a depth.
            states.SaveableStateProvider(stateKey(depth, s)) {
                when (s) {
                    Screen.Home -> HomeScreen(home, nav)
                    Screen.Search -> SearchScreen(home, nav)
                    is Screen.Grid -> GridScreen(home, s.library, nav)
                    is Screen.Show -> ShowScreen(copiesOf(plex, s), nav)
                    is Screen.Folder -> serverOf(plex, s.machineId)?.let { FolderScreen(it, s.library, s.key, s.path, nav) }
                        ?: Message("This library's server can't be reached right now.")
                    Screen.PlexVerse -> PlexVerseScreen(plex, nav)
                    Screen.Kids -> KidsScreen(plex, nav)
                    Screen.PlexVerseSetup -> PlexVerseSetupScreen(plex, nav)
                    is Screen.VerseGrid -> VerseGridScreen(plex, s.category, nav)
                    Screen.VerseVideos -> VerseVideosScreen(plex, nav)
                }
            }
        }
    }

    private fun stateKey(depth: Int, screen: Screen) = "$depth:$screen"

    /** The server with [machineId] if PlexVerse has reached it, or the home server for null. */
    private fun serverOf(plex: PlexClient, machineId: String?): PlexServer? {
        val home = requireNotNull(plex.home)
        if (machineId == null || machineId == home.machineId) return home
        return VerseStore.get(this, plex).connected.value[machineId]
    }

    /** A show's copies on the servers PlexVerse has reached, or the home server's show. */
    private fun copiesOf(plex: PlexClient, s: Screen.Show): List<Copy> {
        val sources = s.sources ?: return listOf(Copy(requireNotNull(plex.home), s.show))
        val connected = VerseStore.get(this, plex).connected.value
        return sources.mapNotNull { src -> connected[src.machineId]?.let { Copy(it, src.item) } }
    }
}
