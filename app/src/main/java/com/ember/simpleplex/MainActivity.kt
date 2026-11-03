package com.ember.simpleplex

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.ember.simpleplex.player.PlayerActivity
import kotlinx.coroutines.delay
import com.ember.simpleplex.plex.PlexClient
import com.ember.simpleplex.plex.PlexServer
import com.ember.simpleplex.ui.AppTheme
import com.ember.simpleplex.ui.Backdrop
import com.ember.simpleplex.ui.Backdrops
import com.ember.simpleplex.ui.LocalScreenBackdrop
import com.ember.simpleplex.ui.Copy
import com.ember.simpleplex.ui.FollowTheme
import com.ember.simpleplex.ui.applyTheme
import com.ember.simpleplex.ui.FolderScreen
import com.ember.simpleplex.ui.GridScreen
import com.ember.simpleplex.ui.HomeScreen
import com.ember.simpleplex.ui.Intro
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
import com.ember.simpleplex.ui.SettingsScreen
import com.ember.simpleplex.ui.introReveal
import com.ember.simpleplex.ui.introSkipped
import com.ember.simpleplex.ui.VerseGridScreen
import com.ember.simpleplex.ui.VerseVideosScreen
import com.ember.simpleplex.ui.Message
import com.ember.simpleplex.ui.VerseStore
import com.ember.simpleplex.ui.VoiceSearch
import com.ember.simpleplex.ui.SignInScreen

/** How long the leaving screen takes to fade out, in ms. */
private const val LEAVE_MS = 120

private val TOP = setOf(Screen.Home, Screen.PlexVerse, Screen.Kids)

class MainActivity : ComponentActivity() {

    private val refresh = mutableIntStateOf(0)
    private var started = false

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val plex = PlexClient.get(this)
        applyTheme(this)
        // The window shows before Compose draws: same ground, so a dark start doesn't flash grey.
        window.setBackgroundDrawable(ColorDrawable(Palette.Ground.toArgb()))
        setContent {
            FollowTheme(plex)
            AppTheme {
                CompositionLocalProvider(
                    LocalRefresh provides refresh.intValue,
                    LocalRefreshBump provides { refresh.intValue++ },
                    LocalBringIntoViewSpec provides PivotScroll.Default,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Backdrop()
                        Box(Modifier.graphicsLayer { alpha = introReveal; compositingStrategy = CompositingStrategy.ModulateAlpha }) { App(plex) }
                        Intro()
                    }
                    // Compose covers the window from here on: drawing its ground too would be one more full-screen pass a frame.
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        window.setBackgroundDrawable(null)
                    }
                }
            }
        }
    }

    /** The remote's mic: Google's voice input, its words then searched for in Search. */
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !heard.isNullOrBlank()) VoiceSearch.heard = heard
    }

    private fun listen(): Boolean {
        if (!PlexClient.get(this).isSignedIn) return false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search")
        return runCatching { voice.launch(intent) }.isSuccess
    }

    // The mic arrives as the search key on most remotes; Android asks for a search with it.
    override fun onSearchRequested() = listen()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_UP && listen()) return true
            if (event.action == KeyEvent.ACTION_DOWN) return true
        }
        RemoteKeys.repeating = event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0
        if (event.action == KeyEvent.ACTION_DOWN) introSkipped = true
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
        // Words from the mic open Search, which takes them as its query.
        LaunchedEffect(VoiceSearch.heard) {
            if (VoiceSearch.heard != null && stack.last() != Screen.Search) stack.add(Screen.Search)
        }
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
                override fun play(ratingKeys: List<String>, resume: Boolean, shuffle: Boolean, startAt: Int, keepQueue: Boolean) {
                    startActivity(
                        Intent(this@MainActivity, PlayerActivity::class.java)
                            .putStringArrayListExtra(PlayerActivity.EXTRA_KEYS, ArrayList(ratingKeys))
                            .putExtra(PlayerActivity.EXTRA_RESUME, resume)
                            .putExtra(PlayerActivity.EXTRA_SHUFFLE, shuffle)
                            .putExtra(PlayerActivity.EXTRA_START_AT, startAt)
                            .putExtra(PlayerActivity.EXTRA_KEEP_QUEUE, keepQueue)
                    )
                }

                override fun signOut() {
                    stack.clear()
                    stack.add(Screen.Home)
                    signedIn = false
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
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            label = "screens",
        ) { (depth, s) ->
            val backdrop = remember { mutableStateOf<ImageBitmap?>(null) }
            if (transition.targetState == EnterExitState.Visible) SideEffect { Backdrops.shown = backdrop }
            DisposableEffect(backdrop) { onDispose { if (Backdrops.shown === backdrop) Backdrops.shown = null } }
            // Building a screen takes the TV 70-130 ms, a pause that broke up the fades. So the leaving screen
            // fades out first, the new one is built once it has gone, while nothing moves, and fades in once
            // its first frame is drawn. The first screen is built at once.
            var built by remember { mutableStateOf(transition.currentState == EnterExitState.Visible) }
            val enter = remember { Animatable(0f) }
            LaunchedEffect(built) {
                if (!built) {
                    delay(LEAVE_MS.toLong())
                    built = true
                    return@LaunchedEffect
                }
                withFrameNanos { }
                enter.animateTo(1f, tween(180))
            }
            val leave by transition.animateFloat({ tween(LEAVE_MS) }, label = "leave") { if (it == EnterExitState.PostExit) 0f else 1f }
            // ModulateAlpha fades each thing drawn instead of the screen as a picture: the TV's GPU can't
            // afford two full-screen offscreen layers a frame.
            val fadeAndSettle = Modifier.graphicsLayer {
                alpha = enter.value * leave
                val scale = 0.98f + 0.02f * enter.value
                scaleX = scale
                scaleY = scale
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // Keyed by depth, so going back restores the scroll position and focus of the previous screen.
            // And by screen: during the fade, the screen leaving and the one coming can share a depth.
            Box(fadeAndSettle) {
                CompositionLocalProvider(LocalScreenBackdrop provides backdrop) {
                    if (built) states.SaveableStateProvider(stateKey(depth, s)) { ScreenContent(s, plex, home, nav) }
                }
            }
        }
    }

    @Composable
    private fun ScreenContent(s: Screen, plex: PlexClient, home: PlexServer, nav: Nav) {
        when (s) {
            Screen.Home -> HomeScreen(home, nav)
            Screen.Search -> SearchScreen(home, nav)
            Screen.Settings -> SettingsScreen(plex, nav)
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
