package com.teleteh.xplayer2

import android.app.Application
import com.google.android.material.color.DynamicColors

class XPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: on Android 12+ tint every activity with the system accent (the colour the
        // user's wallpaper/theme drives). Below 12 this is a no-op, so the theme's brand purple
        // (derived from the launcher icon) is used as the fallback accent instead.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
