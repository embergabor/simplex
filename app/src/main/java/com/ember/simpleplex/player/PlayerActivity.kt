package com.ember.simpleplex.player

import android.content.Context
import android.net.Uri
import android.os.Handler
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.ember.simpleplex.plex.Episodes
import com.ember.simpleplex.plex.Item
import com.ember.simpleplex.plex.Part
import com.ember.simpleplex.plex.PlexClient
import com.ember.simpleplex.plex.Stream
import com.ember.simpleplex.ui.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays a queue of Plex items. Files are played as they are (direct play); the TV's own decoder handles
 * deinterlacing, and audio goes out as a bitstream when the TV/receiver accept it, else it's decoded here
 * (FFmpeg for TrueHD/DTS). Multichannel audio that would only reach the receiver as stereo, without a
 * compatibility track, or audio that can't play at all, is converted by Plex instead, which copies the video
 * when it can. A selected subtitle comes along as its own track (then the stream is Matroska).
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
    /** Whether the queue was widened to the show's whole episode list (ordered play only). */
    private var wholeShow = false
    /** A queue that's never widened, e.g. a show's favourites. */
    private var keepQueue = false
    private val canPrevious = mutableStateOf(false)
    private val canNext = mutableStateOf(false)

    /** Where the current stream starts within the file; non-zero only for Plex's audio-converted stream. */
    private var timeBase = 0L
    private var converted = false
    private var tracksApplied = false
    private var triedConversion = false
    private var loadJob: Job? = null

    /** Plex's converted Matroska stream can't be seeked: a seek starts a new conversion there. */
    private var convertedMkv = false

    private val current = mutableStateOf<Item?>(null)
    private val audio = mutableStateOf<Stream?>(null)
    private val subtitle = mutableStateOf<Stream?>(null)
    private val error = mutableStateOf<String?>(null)
    private val previews = mutableStateOf<PreviewThumbs?>(null)

    /** Compatibility track or Plex converting, while it's being asked. */
    private val question = mutableStateOf<AudioQuestion?>(null)
    private var answer: ((Boolean) -> Unit)? = null
    /** The last answer (true: compatibility track), reused for the next videos in the queue. */
    private var lastAnswer: Boolean? = null
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
        keepQueue = intent.getBooleanExtra(EXTRA_KEEP_QUEUE, false)
        index = intent.getIntExtra(EXTRA_START_AT, 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))

        // Platform renderers first (passthrough or the TV's decoders), FFmpeg after them for audio only.
        val renderers = HardwareVideoRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        player = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setSubtitleParserFactory(SubtitleParsers()))
            .build()
        player.addListener(listener)
        // Debug builds log ExoPlayer's track mapping, renderer support and decoders (logcat tag EventLogger).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) player.addAnalyticsListener(EventLogger())
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
                    seekTo = ::seekTo,
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
                    onPrevious = { skip(-1) }.takeIf { canPrevious.value },
                    onNext = { skip(1) }.takeIf { canNext.value },
                    planAudio = { s -> part?.let { PlexTracks.planAudio(it, s, caps::supportsEncoding) } ?: PlexTracks.AudioPlan(s, PlexTracks.AudioChoice.PLAY) },
                    onExit = ::finish,
                    question = question.value,
                    onAnswer = { useCompat -> answer?.invoke(useCompat) },
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

    // Playback never continues in the background. The player must also be released while its video
    // surface still exists: released after it, this TV's MediaTek decoder hangs for about a minute, keeps
    // playing audio and keeps the hardware decoder busy, so the next video fails to start.

    /** Home button or switching apps. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        releasePlayer()
        finish()
    }

    /** Back, or the end of the queue: runs before the window and its surface are torn down. */
    override fun onPause() {
        super.onPause()
        if (isFinishing) releasePlayer()
    }

    /** Anything else that hides the player. */
    override fun onStop() {
        super.onStop()
        releasePlayer()
        finish()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private var released = false

    private fun releasePlayer() {
        if (released) return
        report("stopped")
        released = true
        lifecycleScope.coroutineContext.cancelChildren()
        player.stop()
        player.release()
    }

    private fun positionMs() = timeBase + player.currentPosition.coerceAtLeast(0)

    private fun report(state: String) {
        if (released) return
        val item = current.value ?: return
        val time = positionMs()
        plex.fire { timeline(item.ratingKey, state, time, item.duration) }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val key = queue.getOrNull(index) ?: return@launch finish()
            error.value = null
            // A question still open was about the previous video.
            question.value = null
            answer = null
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
            updateSkips()
            if (item.isEpisode && !shuffle && !keepQueue && !wholeShow) widenToShow(item)
            previews.value = null
            previewJob?.cancel()
            previewJob = launch { previews.value = PreviewThumbs.load(plex, part, cacheDir) }
            triedConversion = false
            val chosen = PlexTracks.audioStreams(part).let { s -> s.firstOrNull { it.selected } ?: s.firstOrNull() }
            val plan = PlexTracks.planAudio(part, chosen, caps::supportsEncoding)
            subtitle.value = PlexTracks.subtitleStreams(part).firstOrNull { it.selected }

            val start = if (fromStart) 0L else item.viewOffset
            val direct = { s: Stream? -> audio.value = s; playDirect(item, part, start) }
            val convert = { audio.value = plan.stream; playConverted(start) }
            when (plan.choice) {
                PlexTracks.AudioChoice.PLAY -> direct(plan.stream)
                PlexTracks.AudioChoice.CONVERT -> convert()
                PlexTracks.AudioChoice.ASK -> ask(plan, reuseLast = true, onCompat = { direct(plan.compat) }, onConvert = convert)
            }
        }
    }

    private fun playDirect(item: Item, part: Part, startMs: Long) {
        timeBase = 0
        converted = false
        delivery.value = Delivery.DIRECT
        audioInput.value = null
        audioOutput.value = null
        tracksApplied = false
        player.setMediaItem(mediaItem(item, part, plex.fileUrl(part.key), converted = null), startMs)
        player.prepare()
        player.play()
    }

    /** [converted]: the MIME type of Plex's converted stream, or null for the file itself. */
    private fun mediaItem(item: Item, part: Part, url: String, converted: String?): MediaItem {
        // Sidecar subtitle files; a converted stream starts mid-file, so they'd be out of sync there.
        // (Plex's Matroska stream carries the selected one itself.)
        val sidecars = if (converted != null) emptyList() else PlexTracks.subtitleStreams(part).filter { it.isExternal }.mapNotNull { s ->
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
            .apply { if (converted != null) setMimeType(converted) }
            .build()
    }

    private fun chooseAudio(s: Stream) {
        val item = current.value ?: return
        val part = item.part ?: return
        val plan = PlexTracks.planAudio(part, s, caps::supportsEncoding)
        val position = positionMs()
        val direct = { t: Stream? ->
            audio.value = t
            if (converted) playDirect(item, part, position) else PlexTracks.apply(player, part, t, subtitle.value)
        }
        val convert = { audio.value = plan.stream; playConverted(position) }
        lifecycleScope.launch {
            // Remember the viewer's pick on the server; Plex converts whichever track is selected there.
            runCatching { plex.selectStreams(part.id, s.id, null) }
            when (plan.choice) {
                PlexTracks.AudioChoice.PLAY -> direct(plan.stream)
                PlexTracks.AudioChoice.CONVERT -> convert()
                PlexTracks.AudioChoice.ASK -> {
                    // A track picked by hand is always asked about; playback waits for the answer.
                    val wasPlaying = player.isPlaying
                    player.pause()
                    ask(plan, reuseLast = false, onCompat = { direct(plan.compat); if (wasPlaying) player.play() }, onConvert = convert)
                }
            }
        }
    }

    /**
     * Asks whether to play the compatibility track or have Plex convert. [reuseLast]: a video starting
     * in the queue takes the answer given for the previous one instead of asking again.
     */
    private fun ask(plan: PlexTracks.AudioPlan, reuseLast: Boolean, onCompat: () -> Unit, onConvert: () -> Unit) {
        val source = plan.stream ?: return
        val compat = plan.compat ?: return
        lastAnswer?.takeIf { reuseLast }?.let { useCompat ->
            if (useCompat) onCompat() else onConvert()
            return
        }
        question.value = AudioQuestion(source, compat)
        answer = { useCompat ->
            question.value = null
            answer = null
            lastAnswer = useCompat
            if (useCompat) onCompat() else onConvert()
        }
    }

    private fun chooseSubtitle(s: Stream?) {
        val item = current.value ?: return
        val part = item.part ?: return
        subtitle.value = s
        PlexTracks.apply(player, part, audio.value, s)
        lifecycleScope.launch {
            // Plex's conversion carries whichever subtitle is selected on the server, so save the choice first.
            runCatching { plex.selectStreams(part.id, null, s?.id ?: 0) }
            // A converted stream restarts with the new subtitle (Matroska), or without one (HLS).
            if (converted) playConverted(positionMs())
        }
    }


    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val part = current.value?.part ?: return
            if (tracks.isEmpty) return
            if (!tracksApplied) {
                tracksApplied = true
                PlexTracks.apply(player, part, audio.value, subtitle.value, onlyChosen = converted)
            } else if (!triedConversion && !converted && PlexTracks.audioUnplayable(tracks)) {
                playConverted(positionMs())
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (player.playbackState != Player.STATE_ENDED) report(if (isPlaying) "playing" else "paused")
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) next()
        }

        override fun onPlayerError(e: PlaybackException) {
            if (!triedConversion && !converted && isAudioError(e)) playConverted(positionMs())
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
     * Plex converts the audio, starting at [offset]. A selected subtitle comes along as its own track;
     * Plex decides whether the video can be copied.
     */
    private fun playConverted(offset: Long) {
        triedConversion = true
        val item = current.value ?: return
        val part = item.part ?: return
        lifecycleScope.launch {
            player.pause()
            val stream = try {
                plex.convertedStream(item.ratingKey, offset, keepSubtitle = subtitle.value != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                player.stop()
                error.value = "This file's audio can't be played on this TV, and Plex couldn't convert it.\n${e.message}"
                return@launch
            }
            timeBase = offset
            converted = true
            convertedMkv = stream.mkv
            delivery.value = when {
                stream.subtitleBurned -> Delivery.BURNING
                stream.videoCopied -> Delivery.AUDIO_CONVERTED
                else -> Delivery.VIDEO_CONVERTED
            }
            audioInput.value = null
            audioOutput.value = null
            tracksApplied = false
            player.setMediaItem(mediaItem(item, part, stream.url, converted = if (stream.mkv) MimeTypes.VIDEO_MATROSKA else MimeTypes.APPLICATION_M3U8))
            player.prepare()
            player.play()
        }
    }

    /** [positionMs] in the file. A converted stream starts at timeBase; the Matroska one restarts for a seek. */
    private fun seekTo(positionMs: Long) {
        if (converted && convertedMkv) playConverted(positionMs)
        else player.seekTo((positionMs - timeBase).coerceAtLeast(0))
    }

    /** The end of a video: marks it watched and plays the next one, or closes the player. */
    private fun next() {
        val item = current.value ?: return finish()
        plex.fire {
            timeline(item.ratingKey, "stopped", item.duration, item.duration)
            setWatched(item.ratingKey, true)
        }
        index++
        if (index < queue.size) load() else finish()
    }

    /** The previous/next episode buttons. Leaves the current one as it is, without marking it watched. */
    private fun skip(by: Int) {
        val target = index + by
        if (target !in queue.indices) return
        report("stopped")
        index = target
        load()
    }

    /**
     * An episode played in order (Play, an episode card, Continue watching) gets the show's whole
     * episode list as its queue, so previous and next can go anywhere in the show.
     */
    private fun widenToShow(item: Item) {
        val showKey = item.grandparentRatingKey ?: return
        wholeShow = true
        lifecycleScope.launch {
            val episodes = runCatching { Episodes.ordered(plex.episodes(showKey)) }.getOrNull() ?: return@launch
            val at = episodes.indexOfFirst { it.ratingKey == item.ratingKey }
            // Still on the same episode? Then line the queue up around it.
            if (at < 0 || queue.getOrNull(index) != item.ratingKey) return@launch
            queue.clear()
            queue += episodes.map { it.ratingKey }
            index = at
            updateSkips()
        }
    }

    /** Previous/next only for episodes (in order or shuffled); movies and home videos have none. */
    private fun updateSkips() {
        val episode = current.value?.isEpisode == true
        canPrevious.value = episode && index > 0
        canNext.value = episode && index < queue.size - 1
    }

    /**
     * Buffers by time rather than size. Blu-ray remuxes can hold several seconds of 40+ Mbit/s video before
     * the first sample of the chosen audio track (Totoro: 26 MB), more than ExoPlayer's default size limit,
     * so it stopped loading before it could start ("stuck buffering"). Here it keeps loading until every
     * chosen track has 10 s buffered, past the size limit if it must; the app has a large heap for that.
     */
    private fun loadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
        .setTargetBufferBytes(96 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /**
     * Video only ever goes through the TV's hardware decoder: it's what deinterlaces and handles HDR,
     * and a software fallback (FFmpeg or Android's own) can't keep up with 4K anyway.
     */
    private class HardwareVideoRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) = super.buildVideoRenderers(
            context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, false,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
    }

    companion object {
        const val EXTRA_KEYS = "keys"
        const val EXTRA_FROM_START = "fromStart"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_START_AT = "startAt"
        const val EXTRA_KEEP_QUEUE = "keepQueue"
    }
}
