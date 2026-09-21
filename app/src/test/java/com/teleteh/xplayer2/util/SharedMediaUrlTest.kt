package com.teleteh.xplayer2.util

import org.junit.Assert.*
import org.junit.Test

class SharedMediaUrlTest {
    private val id = "aqz-KE-bpKQ"

    @Test fun titleAndProseDoNotBecomeThePlaybackUri() {
        assertEquals("https://youtu.be/$id?si=share", SharedMediaUrl.fromText(
            "Big Buck Bunny\nWatch: https://youtu.be/$id?si=share"))
        assertEquals("https://youtube.com/watch?v=$id", SharedMediaUrl.fromText(
            "Смотри (https://youtube.com/watch?v=$id)."))
        assertNull(SharedMediaUrl.fromText("A title with no URL"))
    }

    @Test fun allSingleVideoFormsResolveToTheSameId() {
        for (url in listOf("https://youtu.be/$id?si=a", "https://www.youtube.com/watch?v=$id&list=123",
            "https://m.youtube.com/shorts/$id", "https://youtube.com/live/$id",
            "https://www.youtube-nocookie.com/embed/$id", "https://music.youtube.com/watch?v=$id")) {
            assertEquals(url, id, YouTubeLink.videoId(url))
        }
    }

    @Test fun channelsPlaylistsAndLookalikeHostsAreNotVideos() {
        for (url in listOf("https://youtube.com/@channel", "https://youtube.com/playlist?list=123",
            "https://youtube.com.evil.test/watch?v=$id", "https://evil.test/watch?v=$id",
            "https://youtube.com/watch?v=short", "https://youtu.be/$id/extra")) {
            assertNull(url, YouTubeLink.videoId(url))
        }
    }
}
