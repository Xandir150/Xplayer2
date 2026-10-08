package com.teleteh.xplayer2.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogSafeTest {
    @Test fun dropsQueryAndFragment() {
        assertEquals("https://cdn.example/v/a.mp4?…", redactUrl("https://cdn.example/v/a.mp4?sig=SECRET&x=1"))
        assertEquals("https://cdn.example/v?…", redactUrl("https://cdn.example/v#token=SECRET"))
    }

    @Test fun dropsUserInfo() {
        assertEquals("smb://nas/share/f.mkv", redactUrl("smb://user:pw@nas/share/f.mkv"))
    }

    @Test fun leavesPlainUrlAndHandlesEmpty() {
        assertEquals("https://ok.ru/video/1", redactUrl("https://ok.ru/video/1"))
        assertEquals("", redactUrl(null as String?))
    }
}
