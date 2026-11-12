package com.ember.simpleplex

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.PixelCopy
import android.os.Looper
import android.os.Handler
import android.graphics.Bitmap
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.toArgb
import com.embergabor.simplex.core.player.PlayerActivity
import com.embergabor.simplex.core.plex.PlexClient
import com.embergabor.simplex.core.plex.PlexServer
import com.ember.simpleplex.ui.Backdrop
import com.ember.simpleplex.ui.Backdrops
import com.ember.simpleplex.ui.LocalScreenBackdrop
import com.ember.simpleplex.ui.FollowTheme
import com.ember.simpleplex.ui.applyTheme
import com.ember.simpleplex.ui.FolderScreen
import com.ember.simpleplex.ui.GridScreen
import com.ember.simpleplex.ui.HomeScreen
import com.ember.simpleplex.ui.Intro
import com.ember.simpleplex.ui.LocalRefresh
import com.ember.simpleplex.ui.LocalRefreshBump
import com.ember.simpleplex.ui.Nav
import com.ember.simpleplex.ui.PivotScroll
import com.ember.simpleplex.ui.RemoteKeys
import com.ember.simpleplex.ui.Screen
import com.ember.simpleplex.ui.SearchScreen
import com.ember.simpleplex.ui.ShowScreen
import com.ember.simpleplex.ui.SettingsScreen
import com.ember.simpleplex.ui.introReveal
import com.ember.simpleplex.ui.introSkipped
import com.ember.simpleplex.ui.VoiceSearch
import com.ember.simpleplex.ui.SignInScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.embergabor.simplex.core.ui.Palette
import com.embergabor.simplex.core.ui.AppTheme

private val TOP = setOf(Screen.Home)

class MainActivity : ComponentActivity() {

    private val refresh = mutableIntStateOf(0)

    /** The screen as it was, over the next one until that has its content; [coverFade] fades it away. */
    private var cover by mutableStateOf<ImageBitmap?>(null)
    private val coverFade = Animatable(1f)

    /**
     * A screen reader is on. Only then is the screen described to accessibility services: Compose does it
     * whenever any service is on (on the Sony, the AT4K launcher, which only watches which app is open),
     * and it took about a quarter of the main thread's time after each screen change.
     */
    private val screenReader = mutableStateOf(false)
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
                    Box(Modifier.fillMaxSize().then(if (screenReader.value) Modifier else Modifier.clearAndSetSemantics { })) {
                        Backdrop()
                        Box(Modifier.graphicsLayer { alpha = introReveal; compositingStrategy = CompositingStrategy.ModulateAlpha }) { App(plex) }
                        // The last screen, held over the next until that has its content (see App).
                        cover?.let { picture -> Canvas(Modifier.fillMaxSize()) { drawImage(picture, alpha = coverFade.value) } }
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
        // The next screen is still loading under the picture of the last one: keys would act unseen.
        if (cover != null && event.keyCode != KeyEvent.KEYCODE_BACK) return true
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
        screenReader.value = getSystemService(AccessibilityManager::class.java)?.let {
            it.isEnabled && (it.isTouchExplorationEnabled || it.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty())
        } ?: false
        // Coming back from the player: progress and watched marks have changed.
        if (started) refresh.intValue++
        started = true
    }

    @Composable
    private fun App(plex: PlexClient) {
        var signedIn by remember { mutableStateOf(plex.isSignedIn) }
        if (!signedIn) {
            LaunchedEffect(Unit) { introSkipped = true }
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

        // A new screen starts empty while its content loads. So the screen as it was stays up, as a picture,
        // until the new one has its content (it takes focus then; two seconds at most), and then gives way
        // to it with a short crossfade. One full-screen picture while it lasts; the screens aren't faded.
        val target = stack.size - 1 to stack.last()
        var shown by remember { mutableStateOf(target) }
        LaunchedEffect(target) {
            if (target == shown) return@LaunchedEffect
            cover = snapshot()?.asImageBitmap()
            coverFade.snapTo(1f)
            shown = target
        }
        val (depth, s) = shown
        key(stateKey(depth, s)) {
            val backdrop = remember { mutableStateOf<ImageBitmap?>(null) }
            SideEffect { Backdrops.shown = backdrop }
            DisposableEffect(backdrop) { onDispose { if (Backdrops.shown === backdrop) Backdrops.shown = null } }
            val focused = remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withTimeoutOrNull(2000) { snapshotFlow { focused.value }.first { it } }
                // The first screen: the opening's logo gives way to it.
                introSkipped = true
                if (cover == null) return@LaunchedEffect
                withFrameNanos { }
                coverFade.animateTo(0f, tween(150))
                cover = null
            }
            // Keyed by depth, so going back restores the scroll position and focus of the previous screen.
            Box(Modifier.onFocusChanged { focused.value = it.hasFocus }) {
                CompositionLocalProvider(LocalScreenBackdrop provides backdrop) {
                    states.SaveableStateProvider(stateKey(depth, s)) { ScreenContent(s, plex, home, nav) }
                }
            }
        }
    }

    /** The window as it looks now, or null if Android can't copy it. */
    private suspend fun snapshot(): Bitmap? {
        val view = window.decorView
        if (view.width == 0 || view.height == 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { done ->
            PixelCopy.request(window, bitmap, { result -> done.resume(bitmap.takeIf { result == PixelCopy.SUCCESS }) }, Handler(Looper.getMainLooper()))
        }
    }

    @Composable
    private fun ScreenContent(s: Screen, plex: PlexClient, home: PlexServer, nav: Nav) {
        when (s) {
            Screen.Home -> HomeScreen(home, nav)
            Screen.Search -> SearchScreen(home, nav)
            Screen.Settings -> SettingsScreen(plex, nav)
            is Screen.Grid -> GridScreen(home, s.library, nav)
            is Screen.Show -> ShowScreen(home, s.show, nav)
            is Screen.Folder -> FolderScreen(home, s.library, s.key, s.path, nav)
        }
    }

    private fun stateKey(depth: Int, screen: Screen) = "$depth:$screen"
}
