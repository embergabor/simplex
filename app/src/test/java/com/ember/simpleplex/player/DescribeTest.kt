package com.ember.simpleplex.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import com.ember.simpleplex.plex.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class DescribeTest {

    private fun stream(
        type: Int, codec: String, profile: String? = null, title: String = "English",
        width: Int? = null, height: Int? = null, fps: Float? = null, scan: String? = null, trc: String? = null, dovi: Int? = null, channels: Int? = null,
    ) = Stream(1, type, codec, profile, 0, "eng", title, true, false, null, null, width, height, fps, scan, trc, dovi, channels)

    @Test fun video() {
        assertEquals("HEVC · 3840×2160 · 23.976 fps · Dolby Vision P8", Describe.video(stream(1, "hevc", width = 3840, height = 2160, fps = 23.976f, trc = "smpte2084", dovi = 8)))
        assertEquals("HEVC · 3840×2160 · 24 fps · HDR10", Describe.video(stream(1, "hevc", width = 3840, height = 2160, fps = 24f, trc = "smpte2084")))
        assertEquals("H.264 · 1280×720p · 25 fps · SDR", Describe.video(stream(1, "h264", width = 1280, height = 720, fps = 25f, scan = "progressive")))
        assertEquals("H.264 · 1920×1080i · 25 fps · SDR", Describe.video(stream(1, "h264", width = 1920, height = 1080, fps = 25f, scan = "interlaced")))
        assertEquals("VC-1 · 1920×1080p · 23.976 fps · SDR", Describe.video(stream(1, "vc1", width = 1920, height = 1080, fps = 23.976f, scan = "progressive")))
        assertEquals("MPEG-2 · 720×576 · 25 fps · SDR", Describe.video(stream(1, "mpeg2video", width = 720, height = 576, fps = 25f)))
    }

    @Test fun audioPassthroughAndDecoded() {
        val dts = stream(2, "dca", profile = "ma", channels = 8)
        assertEquals("DTS-HD MA 7.1 → passthrough", Describe.audio(dts, Delivery.DIRECT, null, AudioOutput(C.ENCODING_DTS_HD, 8)))
        val truehd = stream(2, "truehd", title = "English (TrueHD 7.1 Atmos)", channels = 8)
        assertEquals("TrueHD 7.1 Atmos → decoded, PCM Stereo", Describe.audio(truehd, Delivery.DIRECT, null, AudioOutput(C.ENCODING_PCM_16BIT, 2)))
    }

    @Test fun audioConvertedByPlex() {
        val truehd = stream(2, "truehd", channels = 8)
        assertEquals(
            "TrueHD 7.1 → Plex E-AC3 5.1 → passthrough",
            Describe.audio(truehd, Delivery.AUDIO_CONVERTED, AudioInput(MimeTypes.AUDIO_E_AC3, 6), AudioOutput(C.ENCODING_E_AC3, 6)),
        )
    }

    @Test fun subtitlesAndMarkers() {
        val info = Describe.info(null, null, stream(3, "hdmv_pgs_subtitle"), Delivery.BURNING, null, null)
        assertEquals("PGS", info.subtitle)
        assertEquals(listOf("Audio transcode", "Video transcode", "Subtitles burned in"), info.markers)
        assertEquals(listOf("Audio transcode", "Video transcode"), Describe.info(null, null, null, Delivery.VIDEO_CONVERTED, null, null).markers)
        assertEquals("Off", Describe.info(null, null, null, Delivery.DIRECT, null, null).subtitle)
        assertEquals(listOf("Direct play"), Describe.info(null, null, null, Delivery.DIRECT, null, null).markers)
    }
}
