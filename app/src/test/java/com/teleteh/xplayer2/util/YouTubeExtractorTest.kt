package com.teleteh.xplayer2.util

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeExtractorTest {
    private fun response(formats: String, extra: String = "") = JSONObject("""
        {"playabilityStatus":{"status":"OK"},"videoDetails":{"title":"Example title"},
         "streamingData":{"adaptiveFormats":[$formats]$extra}}
    """)

    @Test fun prefersCompatible1080pVideoAndDefaultAacOverHighBitrateDub() {
        val selected = YouTubeExtractor.parseCandidates(response("""
            {"url":"https://cdn.example/4k","mimeType":"video/mp4; codecs=avc1","height":2160,"bitrate":9000},
            {"url":"https://cdn.example/1080","mimeType":"video/mp4; codecs=avc1","height":1080,"bitrate":4000},
            {"url":"https://cdn.example/720","mimeType":"video/mp4; codecs=avc1","height":720,"bitrate":2000},
            {"url":"https://cdn.example/dub","mimeType":"audio/mp4; codecs=mp4a","bitrate":300,"audioTrack":{"audioIsDefault":false,"displayName":"French"}},
            {"url":"https://cdn.example/original","mimeType":"audio/mp4; codecs=mp4a","bitrate":128,"audioTrack":{"audioIsDefault":true,"displayName":"English"}}
        """))!!
        assertEquals("https://cdn.example/1080", selected.videos.first().url)
        assertEquals("https://cdn.example/original", selected.audios.first().url)
        assertEquals("Example title", selected.title)
    }

    @Test fun ignoresCipherOnlyAndNonHttpsFormats() {
        val selected = YouTubeExtractor.parseCandidates(response("""
            {"signatureCipher":"url=secret","mimeType":"video/mp4","height":1080},
            {"url":"http://cdn.example/video","mimeType":"video/mp4","height":1080},
            {"url":"https://user:pass@cdn.example/video","mimeType":"video/mp4","height":1080}
        """))!!
        assertTrue(selected.videos.isEmpty())
    }

    @Test fun keepsHlsFallbackWhenNoDirectFormatsExist() {
        val selected = YouTubeExtractor.parseCandidates(response("", """,
            "serverAbrStreamingUrl":"https://cdn.example/sabr",
            "hlsManifestUrl":"https://cdn.example/master.m3u8"
        """))!!
        assertTrue(selected.videos.isEmpty())
        assertEquals("https://cdn.example/master.m3u8", selected.hls)
    }

    @Test fun loginRequiredDoesNotProduceCandidates() {
        assertNull(YouTubeExtractor.parseCandidates(JSONObject("""
            {"playabilityStatus":{"status":"LOGIN_REQUIRED"},"streamingData":{"hlsManifestUrl":"https://cdn.example/master.m3u8"}}
        """)))
    }

    @Test fun titleSurvivesMuxedFallback() {
        val selected = YouTubeExtractor.parseCandidates(response("", """,
            "formats":[{"url":"https://cdn.example/muxed","mimeType":"video/mp4; codecs=avc1,mp4a","height":720}]
        """))!!
        assertEquals("Example title", selected.title)
        assertEquals("https://cdn.example/muxed", selected.muxed.single().url)
    }

    @Test fun urlValidationRejectsMissingHostAndInvalidUri() {
        assertFalse(YouTubeExtractor.isHttpsUrl("https:///video"))
        assertFalse(YouTubeExtractor.isHttpsUrl("file:///tmp/video"))
        assertFalse(YouTubeExtractor.isHttpsUrl("https://cdn.example/bad space"))
        assertTrue(YouTubeExtractor.isHttpsUrl("https://cdn.example/video?sig=a%2Bb"))
    }
}
