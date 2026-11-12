package com.ember.simpleplex.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.embergabor.simplex.core.plex.PlexClient
import com.embergabor.simplex.core.plex.PlexStatus
import com.embergabor.simplex.core.plex.WakeOnLan
import com.embergabor.simplex.core.ui.Palette
import com.embergabor.simplex.core.ui.PillButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** Why a load failed, as far as it can be told: each has its own way out. */
enum class Trouble {
    /** The TV has no network at all. */
    OFFLINE,

    /** Nothing answered at the server's address: asleep, off, or moved. */
    UNREACHABLE,

    /** Plex answered that it no longer accepts this TV's sign-in. */
    SIGN_IN,

    /** Plex answered, but not with what was asked for (still starting, something deleted). */
    SERVER;

    companion object {
        fun of(error: Throwable, online: Boolean): Trouble = when {
            error is PlexStatus && error.code == 401 -> SIGN_IN
            error is PlexStatus || error !is IOException -> SERVER
            !online -> OFFLINE
            else -> UNREACHABLE
        }
    }
}

private fun online(context: Context) =
    context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null

/**
 * A load that failed ([what]: "Couldn't load Movies."). It says what's wrong as far as it can tell and
 * offers the way out: waking a server that doesn't answer (with its address saved in Settings), the
 * network settings for a TV without network, signing in again after a refused sign-in. The server is
 * only ever woken by the button: it may be off on purpose. Settings stays in reach, since this takes
 * Home's place. Tries again by itself every few
 * seconds, or right away with the button; a retry reloads every screen's data (the same refresh as
 * coming back from the player), and this stays until something loads.
 */
@Composable
fun ErrorMessage(what: String, error: Throwable, nav: Nav?, modifier: Modifier = Modifier.fillMaxSize()) {
    val retry = LocalRefreshBump.current
    val first = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trouble = Trouble.of(error, online(context))
    val mac = remember { WakeOnLan.Prefs(context).mac }
    var wake by remember { mutableStateOf<String?>(null) }
    suspend fun wakeNow(mac: ByteArray) {
        wake = runCatching { WakeOnLan.send(mac, PlexClient.get(context).home?.uri) }
            .fold({ "Wake signal sent. It can take a minute to start." }, { "Couldn't send the wake signal: ${it.message}" })
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
        while (true) {
            delay(5000)
            retry()
        }
    }

    val settings = nav?.let { { it.push(Screen.Settings) } }
    val again = "Trying again every few seconds."
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        val (title, detail) = when (trouble) {
            Trouble.OFFLINE -> "This TV isn't connected to the network." to "Check its cable or Wi-Fi. $again"
            Trouble.UNREACHABLE -> "Your Plex server isn't answering." to when {
                mac != null -> "${wake ?: "It may be asleep or switched off."} $again"
                else -> "It may be asleep or switched off. To wake it from here, add its network address in Settings, under Server. $again"
            }
            Trouble.SIGN_IN -> "Plex no longer accepts this TV's sign-in." to "Sign in again to go on."
            Trouble.SERVER -> what to "${error.message ?: "Plex gave no reason."} $again"
        }
        Text(title, fontSize = 15.sp, color = Palette.Secondary, textAlign = TextAlign.Center)
        Text(detail, fontSize = 12.sp, color = Palette.Secondary, textAlign = TextAlign.Center)
        // What the request itself said, for telling a wrong address from a sleeping server.
        if (trouble == Trouble.UNREACHABLE) error.message?.let {
            Text(it, fontSize = 10.sp, color = Palette.Secondary.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val focused = Modifier.focusRequester(first)
            when (trouble) {
                Trouble.OFFLINE -> {
                    PillButton("Network settings", { openNetworkSettings(context) }, focused)
                    PillButton("Try again now", retry)
                    settings?.let { PillButton("Settings", it) }
                }
                Trouble.UNREACHABLE -> if (mac != null) {
                    PillButton("Wake server", { scope.launch { wakeNow(mac) } }, focused)
                    PillButton("Try again now", retry)
                    settings?.let { PillButton("Settings", it) }
                } else {
                    // Without the address nothing here can wake it: Settings is the way on.
                    settings?.let { PillButton("Set up waking", it, focused) }
                    PillButton("Try again now", retry, if (settings == null) focused else Modifier)
                }
                Trouble.SIGN_IN -> {
                    if (nav != null) PillButton("Sign in again", { PlexClient.get(context).signOut(); nav.signOut() }, focused)
                    PillButton("Try again now", retry, if (nav == null) focused else Modifier)
                }
                Trouble.SERVER -> {
                    PillButton("Try again now", retry, focused)
                    settings?.let { PillButton("Settings", it) }
                }
            }
        }
    }
}

/** The TV's own network settings; its main settings where it has no such screen. */
private fun openNetworkSettings(context: Context) {
    for (action in listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS)) {
        val opened = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (opened) return
    }
}

/** While the first answer from the server is awaited: said only after a moment, since it's usually quick. */
@Composable
fun Connecting() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1500)
        shown = true
    }
    if (shown) Message("Connecting to your Plex server…")
}
