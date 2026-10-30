package com.ember.simpleplex.player

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.ember.simpleplex.plex.Episodes
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.Part
import com.ember.simpleplex.plex.PlexClient
import com.ember.simpleplex.plex.Stream
import com.ember.simpleplex.ui.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays a queue of Plex items. Files are played as they are (direct play); the TV's own decoder handles
 * deinterlacing, and audio goes out as a bitstream when the TV/receiver accept it, else it's decoded here
 * (FFmpeg for TrueHD/DTS). Only if neither works does Plex convert the audio, never the video.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var plex: PlexClient
    private lateinit var player: ExoPlayer
    private lateinit var caps: AudioCapabilities

    private val queue = mutableListOf<String>()
    private var index = 0
    private var fromStart = false
    private var shuffle = false

    /** Where the current stream starts within the file; non-zero only for Plex's audio-converted stream. */
    private var timeBase = 0L
    private var converted = false
    private var tracksApplied = false
    private var triedAudioOnly = false
    private var loadJob: Job? = null

    /** Burning subtitles into a converted stream: the viewer's answer for the current item, if asked. */
    private var burnChoice: Boolean? = null
    private var burning = false
    private val burnQuestion = mutableStateOf<CompletableDeferred<Boolean>?>(null)

    private val current = mutableStateOf<Item?>(null)
    private val audio = mutableStateOf<Stream?>(null)
    private val subtitle = mutableStateOf<Stream?>(null)
    private val error = mutableStateOf<String?>(null)
    private val previews = mutableStateOf<PreviewThumbs?>(null)
    private val delivery = mutableStateOf(Delivery.DIRECT)
    private val audioInput = mutableStateOf<AudioInput?>(null)
    private val audioOutput = mutableStateOf<AudioOutput?>(null)
    private var previewJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        plex = PlexClient.get(this)
        // What the TV accepts as a bitstream on its current output (here: eARC to the receiver).
        caps = AudioCapabilities.getCapabilities(this, AudioAttributes.DEFAULT, null)
        queue += intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        fromStart = intent.getBooleanExtra(EXTRA_FROM_START, false)
        shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false)

        // Platform renderers first (passthrough or the TV's decoders), FFmpeg after them for anything left.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setSubtitleParserFactory(SubtitleParsers()))
            .build()
        player.addListener(listener)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                audioInput.value = AudioInput(format.sampleMimeType, format.channelCount)
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
                audioOutput.value = AudioOutput(config.encoding, Describe.channelCount(config.channelConfig))
            }
        })

        setContent {
            AppTheme {
                val part = current.value?.part
                PlayerScreen(
                    player = player,
                    item = current.value,
                    audioOptions = part?.let(PlexTracks::audioStreams).orEmpty(),
                    subtitleOptions = part?.let(PlexTracks::subtitleStreams).orEmpty(),
                    audio = audio.value,
                    subtitle = subtitle.value,
                    error = error.value,
                    position = ::positionMs,
                    // A converted stream starts at timeBase, so it can't go earlier than that.
                    seekTo = { player.seekTo((it - timeBase).coerceAtLeast(0)) },
                    previews = previews.value,
                    onAudio = ::chooseAudio,
                    onSubtitle = ::chooseSubtitle,
                    info = Describe.info(
                        video = part?.streams?.firstOrNull { it.streamType == 1 },
                        // While Plex converts, the source is what's selected on the server, not the fallback.
                        audio = audio.value,
                        subtitle = subtitle.value,
                        delivery = delivery.value,
                        input = audioInput.value,
                        output = audioOutput.value,
                    ),
                    askBurn = burnQuestion.value != null,
                    onBurnAnswer = { burnQuestion.value?.complete(it) },
                )
            }
        }

        lifecycleScope.launch {
            while (true) {
                delay(10_000)
                if (player.isPlaying) report("playing")
            }
        }
        load()
    }

    override fun onStop() {
        super.onStop()
        report("stopped")
        player.pause()
        finish()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private fun positionMs() = timeBase + player.currentPosition.coerceAtLeast(0)

    private fun report(state: String) {
        val item = current.value ?: return
        val time = positionMs()
        plex.fire { timeline(item.ratingKey, state, time, item.duration) }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val key = queue.getOrNull(index) ?: return@launch finish()
            error.value = null
            val item = try {
                plex.metadata(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = "Couldn't load this video.\n${e.message}"
                return@launch
            }
            val part = item.part ?: run {
                error.value = "Plex has no file for “${item.title}”."
                return@launch
            }

            current.value = item
            previews.value = null
            previewJob?.cancel()
            previewJob = launch { previews.value = PreviewThumbs.load(plex, part, cacheDir) }
            triedAudioOnly = false
            burnChoice = null
            val chosen = PlexTracks.audioStreams(part).let { s -> s.firstOrNull { it.selected } ?: s.firstOrNull() }
            val plan = PlexTracks.planAudio(part, chosen, caps)
            audio.value = plan.stream
            subtitle.value = PlexTracks.subtitleStreams(part).firstOrNull { it.selected }

            val start = if (fromStart) 0L else item.viewOffset
            if (plan.convert) playAudioConverted(start) else playDirect(item, part, start)
        }
    }

    private fun playDirect(item: Item, part: Part, startMs: Long) {
        timeBase = 0
        converted = false
        delivery.value = Delivery.DIRECT
        audioInput.value = null
        audioOutput.value = null
        tracksApplied = false
        player.setMediaItem(mediaItem(item, part, plex.fileUrl(part.key), hls = false), startMs)
        player.prepare()
        player.play()
    }

    private fun mediaItem(item: Item, part: Part, url: String, hls: Boolean): MediaItem {
        // Sidecar subtitle files; a converted stream starts mid-file, so they'd be out of sync there.
        val sidecars = if (hls) emptyList() else PlexTracks.subtitleStreams(part).filter { it.isExternal }.mapNotNull { s ->
            val mime = PlexTracks.subtitleMime(s.codec) ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(plex.fileUrl(s.key!!)))
                .setId(PlexTracks.externalId(s.id))
                .setMimeType(mime)
                .setLanguage(s.languageCode)
                .setLabel(s.displayTitle)
                .build()
        }
        return MediaItem.Builder()
            .setUri(url)
            .setMediaId(item.ratingKey)
            .setSubtitleConfigurations(sidecars)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
    }

    private fun chooseAudio(s: Stream) {
        val item = current.value ?: return
        val part = item.part ?: return
        val plan = PlexTracks.planAudio(part, s, caps)
        audio.value = plan.stream
        val position = positionMs()
        lifecycleScope.launch {
            // Remember the viewer's pick on the server; Plex converts whichever track is selected there.
            runCatching { plex.selectStreams(part.id, s.id, null) }
            when {
                plan.convert -> playAudioConverted(position)
                converted -> playDirect(item, part, position)
                else -> PlexTracks.apply(player, part, plan.stream, subtitle.value)
            }
        }
    }

    private fun chooseSubtitle(s: Stream?) {
        val item = current.value ?: return
        val part = item.part ?: return
        subtitle.value = s
        PlexTracks.apply(player, part, audio.value, s)
        lifecycleScope.launch {
            // Plex burns in whichever subtitle is selected on the server, so save the choice first.
            runCatching { plex.selectStreams(part.id, null, s?.id ?: 0) }
            if (converted && (s != null || burning)) {
                burnChoice = null
                playAudioConverted(positionMs())
            }
        }
    }

    /** Asks once per item whether to burn subtitles into a converted stream. Back means no. */
    private suspend fun askBurn(): Boolean {
        burnChoice?.let { return it }
        val answer = CompletableDeferred<Boolean>()
        burnQuestion.value = answer
        return answer.await().also {
            burnChoice = it
            burnQuestion.value = null
        }
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val part = current.value?.part ?: return
            if (tracks.isEmpty) return
            if (!tracksApplied) {
                tracksApplied = true
                PlexTracks.apply(player, part, audio.value, subtitle.value)
            } else if (!triedAudioOnly && !converted && PlexTracks.audioUnplayable(tracks)) {
                playAudioConverted(positionMs())
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (player.playbackState != Player.STATE_ENDED) report(if (isPlaying) "playing" else "paused")
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) next()
        }

        override fun onPlayerError(e: PlaybackException) {
            if (!triedAudioOnly && !converted && isAudioError(e)) playAudioConverted(positionMs())
            else error.value = "Playback failed.\n${e.errorCodeName}: ${e.message}"
        }
    }

    private fun isAudioError(e: PlaybackException): Boolean {
        val ex = e as? ExoPlaybackException ?: return false
        return ex.type == ExoPlaybackException.TYPE_RENDERER &&
            ex.rendererIndex != C.INDEX_UNSET &&
            player.getRendererType(ex.rendererIndex) == C.TRACK_TYPE_AUDIO
    }

    /**
     * Plex copies the video and converts only the audio, starting at [offset]. Subtitles can't come along
     * in that stream, so if one is selected the viewer chooses: none, or burned in (Plex converts the video).
     */
    private fun playAudioConverted(offset: Long) {
        triedAudioOnly = true
        val item = current.value ?: return
        val part = item.part ?: return
        lifecycleScope.launch {
            player.pause()
            val burn = subtitle.value != null && askBurn()
            if (!burn) subtitle.value = null
            val url = runCatching { plex.audioOnlyStream(item.ratingKey, offset, burn) }.getOrNull()
            if (url == null) {
                player.stop()
                error.value = "This file's audio can't be played on this TV, and Plex can't convert just the audio."
                return@launch
            }
            timeBase = offset
            converted = true
            burning = burn
            delivery.value = if (burn) Delivery.BURNING else Delivery.AUDIO_CONVERTED
            audioInput.value = null
            audioOutput.value = null
            tracksApplied = false
            player.setMediaItem(mediaItem(item, part, url, hls = true))
            player.prepare()
            player.play()
        }
    }

    private fun next() {
        val item = current.value ?: return finish()
        plex.fire {
            timeline(item.ratingKey, "stopped", item.duration, item.duration)
            setWatched(item.ratingKey, true)
        }
        index++
        if (index < queue.size) return load()

        // Started from Continue watching on an episode: keep going with the rest of the show.
        val showKey = item.grandparentRatingKey
        if (!item.isEpisode || shuffle || showKey == null) return finish()
        lifecycleScope.launch {
            val episodes = runCatching { Episodes.ordered(plex.episodes(showKey)) }.getOrNull().orEmpty()
            val at = episodes.indexOfFirst { it.ratingKey == item.ratingKey }
            val rest = if (at >= 0) episodes.drop(at + 1) else emptyList()
            if (rest.isEmpty()) return@launch finish()
            fromStart = false
            queue += rest.map { it.ratingKey }
            load()
        }
    }

    companion object {
        const val EXTRA_KEYS = "keys"
        const val EXTRA_FROM_START = "fromStart"
        const val EXTRA_SHUFFLE = "shuffle"
    }
}
