package com.zohan.nghekinhthanh1925

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityStartupTest {
    @Before fun reset() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("audio_downloads_v2", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PlaybackLoopStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        PlaybackStateStore.currentOrder = 0
        PlaybackStateStore.audioVersion = "VIE1925"
        PlaybackStateStore.playWhenReady = false
        PlaybackStateStore.isPlaying = false
        PlaybackStateStore.durationMs = 0
        PlaybackStateStore.positionMs = 0
        PlaybackStateStore.positionUpdatedElapsed = 0
        PlaybackStateStore.sleepDeadlineElapsed = 0
        PlaybackStateStore.sleepMinutes = 0
    }

    private fun launch(expectedVersion: String) {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            val activity = controller.setup().get()
            val texts = textViews(activity.findViewById(android.R.id.content))
            assertTrue("Edition selector must be visible", texts.any { it.contains(expectedVersion) })
            assertTrue("Chapter text must be visible", texts.any { it.length > 200 })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun textViews(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(textViews(view.getChildAt(index)))
    }

    @Test fun freshInstallOpensReader() = launch("VIE1925")

    @Test fun savedNivSelectionOpensReader() {
        RuntimeEnvironment.getApplication().getSharedPreferences("state", Context.MODE_PRIVATE)
            .edit().putString("textVersion", "NIV").putInt("lastOrder", 929).commit()
        launch("NIV")
        assertEquals("NIVUK", PlaybackStateStore.audioVersion)
    }

    @Test fun savedMaxMcLeanChoiceStillOpensReader() {
        RuntimeEnvironment.getApplication().getSharedPreferences("state", Context.MODE_PRIVATE)
            .edit().putString("textVersion", "NIV").putString("englishAudioVersion", "NIV").commit()
        launch("NIV")
        assertEquals("NIV", PlaybackStateStore.audioVersion)
    }

    @Test fun activeNivukAudioOpensWithNivTextRatherThanANonexistentNivukTextVersion() {
        PlaybackStateStore.audioVersion = "NIVUK"
        PlaybackStateStore.currentOrder = 929
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        launch("NIV")
        assertEquals("NIVUK", PlaybackStateStore.audioVersion)
        assertTrue(PlaybackStateStore.playWhenReady)
    }

    @Test fun savedDarkModeOpensReader() {
        RuntimeEnvironment.getApplication().getSharedPreferences("state", Context.MODE_PRIVATE)
            .edit().putString("themeMode", "dark").putString("textVersion", "VIE2010").commit()
        launch("VIE2010")
    }
}
