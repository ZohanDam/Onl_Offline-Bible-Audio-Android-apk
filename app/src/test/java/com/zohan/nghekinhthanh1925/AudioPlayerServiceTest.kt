package com.zohan.nghekinhthanh1925

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.AudioManager
import android.app.Notification
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioPlayerServiceTest {
    private lateinit var controller: ServiceController<AudioPlayerService>
    private lateinit var service: AudioPlayerService
    private lateinit var repository: BibleRepository

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("audio_downloads_v2", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PlaybackLoopStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        PlaybackStateStore.currentOrder = 0
        PlaybackStateStore.isPlaying = false
        PlaybackStateStore.playWhenReady = false
        PlaybackStateStore.audioVersion = "VIE1925"
        PlaybackStateStore.positionMs = 0
        PlaybackStateStore.positionUpdatedElapsed = 0
        PlaybackStateStore.durationMs = 0
        PlaybackStateStore.speed = 1f
        PlaybackStateStore.sleepDeadlineElapsed = 0
        PlaybackStateStore.sleepMinutes = 0
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(60_000, 1000) }
        controller = Robolectric.buildService(AudioPlayerService::class.java).create()
        service = controller.get()
        repository = BibleRepository(context)
    }

    @After fun cleanup() {
        controller.destroy()
        repository.close()
    }

    private fun action(action: String) {
        service.onStartCommand(Intent(service, AudioPlayerService::class.java).setAction(action), 0, 1)
    }

    private fun select(order: Int, version: String, play: Boolean) {
        service.onStartCommand(Intent(service, AudioPlayerService::class.java)
            .setAction(if (play) AudioPlayerService.ACTION_PLAY_ORDER else AudioPlayerService.ACTION_SELECT_ORDER)
            .putExtra(AudioPlayerService.EXTRA_ORDER, order)
            .putExtra(AudioPlayerService.EXTRA_AUDIO_VERSION, version)
            .putExtra(AudioPlayerService.EXTRA_AUTO_PLAY, play)
            .putExtra(AudioPlayerService.EXTRA_START_FROM_BEGINNING, true), 0, 1)
    }

    private fun player(): MediaPlayer? {
        return AudioPlayerService::class.java.getDeclaredField("mediaPlayer").apply {
            isAccessible = true
        }.get(service) as MediaPlayer?
    }

    private fun prepare() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    @Test fun bothEditionsHaveCompleteSeparateDownloadIdentities() {
        val vietnamese = repository.chapters()
        val english = repository.chapters("NIV")
        assertEquals(1189, vietnamese.size)
        assertEquals(1189, english.size)
        assertEquals(2378, (vietnamese + english).map { it.downloadKey }.toSet().size)
        assertEquals(2378, (vietnamese + english).map { it.filename }.toSet().size)
        assertEquals(1, vietnamese.first().downloadKey)
        assertEquals("position_1", vietnamese.first().positionKey)
        assertNotEquals(vietnamese.first().positionKey, english.first().positionKey)
        assertEquals("VIE1925", audioVersionForText("VIE2010"))
        assertEquals("NIVUK", audioVersionForText("NIV"))
        assertEquals("NIV", audioVersionForText("NIV", "NIV"))
    }

    @Test fun selectingAndSkippingWhilePausedNeverCreatesPlayer() {
        select(1, "NIV", false)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertEquals("NIV", PlaybackStateStore.audioVersion)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertFalse(PlaybackStateStore.isPlaying)
        assertNull(player())
        action(AudioPlayerService.ACTION_PREVIOUS)
        assertEquals(1, PlaybackStateStore.currentOrder)
        assertNull(player())
        select(1, "VIE1925", false)
        assertEquals("VIE1925", PlaybackStateStore.audioVersion)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertNull(player())
    }

    @Test fun pauseDuringPreparationPreventsDelayedAutoplay() {
        select(1, "NIV", true)
        action(AudioPlayerService.ACTION_PAUSE)
        prepare()
        assertFalse(player()!!.isPlaying)
        assertFalse(PlaybackStateStore.playWhenReady)
        service.onStartCommand(Intent(service, AudioPlayerService::class.java)
            .setAction(AudioPlayerService.ACTION_SPEED)
            .putExtra(AudioPlayerService.EXTRA_SPEED, 1.5f), 0, 1)
        assertFalse(player()!!.isPlaying)
        assertEquals(1.5f, PlaybackStateStore.speed)
    }

    @Test fun playingNextContinuesInNivFromZero() {
        select(1, "NIV", true)
        prepare()
        assertTrue(player()!!.isPlaying)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertTrue(PlaybackStateStore.playWhenReady)
        assertEquals(0, PlaybackStateStore.positionMs)
        prepare()
        assertTrue(player()!!.isPlaying)
        assertEquals("NIV", PlaybackStateStore.audioVersion)
    }

    @Test fun pausedNextDoesNotResumeOldOrNewRecording() {
        select(1, "NIV", true)
        prepare()
        action(AudioPlayerService.ACTION_PAUSE)
        action(AudioPlayerService.ACTION_NEXT)
        prepare()
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertFalse(PlaybackStateStore.isPlaying)
        assertNull(player())
    }

    @Test fun stalePreparedCallbackCannotStartPreviousChapter() {
        select(1, "NIV", true)
        val oldPlayer = player()!!
        val callback = shadowOf(oldPlayer).onPreparedListener
        select(2, "NIV", false)
        callback.onPrepared(oldPlayer)
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertNull(player())
    }

    @Test fun downloadedNivChapterUsesLocalFile() {
        val chapter = repository.chapters("NIV").first()
        val file = AudioDownloadStore(service).targetFile(chapter)
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(73, 68, 51))
        try {
            select(1, "NIV", true)
            assertEquals(DataSource.toDataSource(file.absolutePath), shadowOf(player()!!).dataSource)
            prepare()
            assertTrue(player()!!.isPlaying)
        } finally {
            file.delete()
        }
    }

    @Test fun switchingEditionWhilePlayingKeepsChapterAndStartsAtZero() {
        select(101, "VIE1925", true)
        prepare()
        assertTrue(player()!!.isPlaying)
        select(101, "NIV", true)
        assertEquals(101, PlaybackStateStore.currentOrder)
        assertEquals(0, PlaybackStateStore.positionMs)
        prepare()
        assertEquals("NIV", PlaybackStateStore.audioVersion)
        assertTrue(player()!!.isPlaying)
    }

    @Test fun nivQueueAndDeleteLeaveVietnameseDownloadsAlone() {
        val store = AudioDownloadStore(service)
        val vietnamese = repository.chapters().first()
        val english = repository.chapters("NIV").first()
        store.enqueue(listOf(vietnamese, english))
        assertEquals(setOf(vietnamese.downloadKey, english.downloadKey), store.queuedOrders())
        store.cancelPending(listOf(english))
        assertEquals(setOf(vietnamese.downloadKey), store.queuedOrders())
        val oldFile = store.targetFile(vietnamese)
        val newFile = store.targetFile(english)
        oldFile.writeBytes(byteArrayOf(73, 68, 51))
        newFile.writeBytes(byteArrayOf(73, 68, 51))
        try {
            assertEquals(1, store.delete(listOf(english)))
            assertTrue(oldFile.exists())
            assertFalse(newFile.exists())
            assertNotNull(store.availableFile(vietnamese))
            assertNull(store.availableFile(english))
        } finally {
            oldFile.delete()
            newFile.delete()
        }
    }

    private fun seek(position: Int) {
        service.onStartCommand(Intent(service, AudioPlayerService::class.java)
            .setAction(AudioPlayerService.ACTION_SEEK).putExtra(AudioPlayerService.EXTRA_POSITION, position), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun timer(minutes: Int) {
        service.onStartCommand(Intent(service, AudioPlayerService::class.java)
            .setAction(AudioPlayerService.ACTION_SLEEP).putExtra(AudioPlayerService.EXTRA_MINUTES, minutes), 0, 1)
    }

    @Test fun rewindMovesFifteenSecondsWithoutChangingChapterOrPauseState() {
        select(2, "NIV", true)
        prepare()
        action(AudioPlayerService.ACTION_PAUSE)
        seek(25_000)
        action(AudioPlayerService.ACTION_REWIND_15)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(10_000, player()!!.currentPosition)
        assertEquals(10_000, PlaybackStateStore.positionMs)
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertFalse(player()!!.isPlaying)
        action(AudioPlayerService.ACTION_REWIND_15)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, player()!!.currentPosition)
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    @Test fun rewindWhilePlayingKeepsPlaying() {
        select(1, "VIE1925", true)
        prepare()
        seek(25_000)
        action(AudioPlayerService.ACTION_REWIND_15)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(10_000, player()!!.currentPosition)
        assertTrue(player()!!.isPlaying)
    }

    @Test fun timerExpiryPausesAndClearsTheCountdown() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(600_000, 1000) }
        select(1, "VIE1925", true)
        prepare()
        timer(1)
        assertEquals("1 ph", SleepTimer.countdown(service))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertFalse(PlaybackStateStore.isPlaying)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertEquals(0L, PlaybackStateStore.sleepDeadlineElapsed)
        assertFalse(service.getSharedPreferences("state", Context.MODE_PRIVATE).getBoolean("wasPlaying", true))
    }

    @Test fun timerCanBeReplacedAndCancelledWithoutPausing() {
        select(1, "NIV", true)
        prepare()
        timer(15)
        timer(480)
        assertEquals("480 ph", SleepTimer.countdown(service))
        assertEquals(480, PlaybackStateStore.sleepMinutes)
        timer(0)
        assertEquals(0L, PlaybackStateStore.sleepDeadlineElapsed)
        assertTrue(player()!!.isPlaying)
    }

    @Test fun expiredSavedTimerCannotRestartAudioAfterServiceRecreation() {
        timer(1)
        val state = service.getSharedPreferences("state", Context.MODE_PRIVATE)
        state.edit().putLong("sleepDeadlineElapsed", SystemClock.elapsedRealtime() - 1)
            .putBoolean("wasPlaying", true).commit()
        controller.destroy()
        controller = Robolectric.buildService(AudioPlayerService::class.java).create()
        service = controller.get()
        service.onStartCommand(null, 0, 1)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertNull(player())
        assertEquals(0L, PlaybackStateStore.sleepDeadlineElapsed)
    }

    @Test fun savedTimerDoesNotSurviveADeviceReboot() {
        timer(15)
        Settings.Global.putInt(service.contentResolver, Settings.Global.BOOT_COUNT, 123)
        SleepTimer.restore(service)
        assertEquals(0L, PlaybackStateStore.sleepDeadlineElapsed)
    }

    @Test fun playbackCheckpointsAreThrottledButPauseSavesImmediately() {
        select(1, "VIE1925", true)
        prepare()
        val state = service.getSharedPreferences("state", Context.MODE_PRIVATE)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(0L, state.getLong("position_1", -1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        val checkpoint = state.getLong("position_1", -1)
        assertTrue(checkpoint >= 10_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        action(AudioPlayerService.ACTION_PAUSE)
        assertTrue(state.getLong("position_1", -1) > checkpoint)
        assertEquals(player()!!.currentPosition.toLong(), state.getLong("position_1", -1))
    }

    @Test fun sparseProgressSamplesStillProduceSmoothPositionAndStopWhenPaused() {
        select(1, "VIE1925", true)
        prepare()
        val sample = PlaybackStateStore.positionMs
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(sample, PlaybackStateStore.positionMs)
        assertTrue(PlaybackStateStore.displayPositionMs() >= sample + 2_000)
        action(AudioPlayerService.ACTION_PAUSE)
        val paused = PlaybackStateStore.displayPositionMs()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(paused, PlaybackStateStore.displayPositionMs())
    }

    @Test fun minuteDisplayRoundsUpAndSchedulesOnlyTheNextMinuteBoundary() {
        timer(480)
        assertEquals(480L, SleepTimer.remainingMinutes())
        assertEquals(60_000L, SleepTimer.nextDisplayDelayMs())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(480L, SleepTimer.remainingMinutes())
        assertEquals(40_000L, SleepTimer.nextDisplayDelayMs())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40))
        assertEquals(479L, SleepTimer.remainingMinutes())
        UiLanguage.set(service, true)
        assertEquals("479 min", SleepTimer.countdown(service))
    }

    private fun sessionState(): PlaybackState =
        AudioPlayerService::class.java.getDeclaredMethod("buildMediaPlaybackState").apply {
            isAccessible = true
        }.invoke(service) as PlaybackState

    @Test fun progressEstimateRespectsSpeedAndChapterDuration() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        PlaybackStateStore.positionMs = 1_000
        PlaybackStateStore.positionUpdatedElapsed = SystemClock.elapsedRealtime()
        PlaybackStateStore.durationMs = 60_000
        PlaybackStateStore.speed = 1.5f
        PlaybackStateStore.isPlaying = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(4_000, PlaybackStateStore.displayPositionMs())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(60_000, PlaybackStateStore.displayPositionMs())
    }

    @Test fun streamingBufferEventsSuspendAndRestoreProgressEstimation() {
        select(1, "NIV", true)
        prepare()
        shadowOf(player()!!).invokeInfoListener(MediaPlayer.MEDIA_INFO_BUFFERING_START, 0)
        assertEquals(PlaybackState.STATE_BUFFERING, sessionState().state)
        assertEquals(0f, sessionState().playbackSpeed)
        assertEquals(0L, PlaybackStateStore.positionUpdatedElapsed)
        assertEquals(PlaybackStateStore.positionMs, PlaybackStateStore.displayPositionMs())
        shadowOf(player()!!).invokeInfoListener(MediaPlayer.MEDIA_INFO_BUFFERING_END, 0)
        assertEquals(PlaybackState.STATE_PLAYING, sessionState().state)
        assertTrue(PlaybackStateStore.positionUpdatedElapsed > 0)
        action(AudioPlayerService.ACTION_PAUSE)
        assertEquals(0f, sessionState().playbackSpeed)
    }

    private fun focus(change: Int) {
        val listener = AudioPlayerService::class.java.getDeclaredField("audioFocusListener").apply {
            isAccessible = true
        }.get(service) as AudioManager.OnAudioFocusChangeListener
        listener.onAudioFocusChange(change)
    }

    @Test fun loopCompletionFollowsChosenOrderAndWrapsFromLastSelection() {
        select(3, "VIE1925", false)
        PlaybackLoopStore(service).save("VIE1925", PlaybackLoopSelection(true, listOf(3, 1, 2)))
        select(3, "VIE1925", true)
        prepare()
        for (expected in listOf(1, 2, 3)) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            assertEquals(expected, PlaybackStateStore.currentOrder)
            assertEquals(0, PlaybackStateStore.positionMs)
            assertTrue(PlaybackStateStore.playWhenReady)
            prepare()
            assertTrue(player()!!.isPlaying)
        }
        assertEquals(0L, service.getSharedPreferences("state", Context.MODE_PRIVATE).getLong("position_2", -1))
    }

    @Test fun lastBibleChapterCanLoopByItself() {
        select(1189, "VIE1925", false)
        PlaybackLoopStore(service).save("VIE1925", PlaybackLoopSelection(true, listOf(1189)))
        select(1189, "VIE1925", true)
        prepare()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(1189, PlaybackStateStore.currentOrder)
        assertEquals(0, PlaybackStateStore.positionMs)
        assertTrue(PlaybackStateStore.playWhenReady)
        prepare()
        assertTrue(player()!!.isPlaying)
    }

    @Test fun pausedLoopNavigationWrapsWithoutCreatingPlayer() {
        select(3, "VIE1925", false)
        PlaybackLoopStore(service).save("VIE1925", PlaybackLoopSelection(true, listOf(3, 1)))
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(1, PlaybackStateStore.currentOrder)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(3, PlaybackStateStore.currentOrder)
        action(AudioPlayerService.ACTION_PREVIOUS)
        assertEquals(1, PlaybackStateStore.currentOrder)
        assertNull(player())
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    @Test fun manualChapterOutsideLoopDisablesItAndReturnsToNormalSequence() {
        select(3, "VIE1925", false)
        val loops = PlaybackLoopStore(service)
        loops.save("VIE1925", PlaybackLoopSelection(true, listOf(3, 1)))
        select(10, "VIE1925", false)
        assertFalse(loops.load("VIE1925").enabled)
        assertEquals(listOf(3, 1), loops.load("VIE1925").orders)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(11, PlaybackStateStore.currentOrder)
        assertNull(player())
    }

    @Test fun manualChapterInsideLoopKeepsSelectedSequence() {
        select(3, "VIE1925", false)
        val loops = PlaybackLoopStore(service)
        loops.save("VIE1925", PlaybackLoopSelection(true, listOf(3, 1, 10)))
        select(1, "VIE1925", false)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(10, PlaybackStateStore.currentOrder)
        assertTrue(loops.load("VIE1925").enabled)
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    @Test fun nivLoopUsesOnlyNivAudioAndEditionChangeDisablesIt() {
        select(10, "NIV", false)
        val loops = PlaybackLoopStore(service)
        loops.save("NIV", PlaybackLoopSelection(true, listOf(10, 2)))
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals("NIV", PlaybackStateStore.audioVersion)
        assertEquals(2, PlaybackStateStore.currentOrder)
        select(2, "VIE1925", false)
        assertFalse(loops.load("NIV").enabled)
        assertEquals(listOf(10, 2), loops.load("NIV").orders)
    }

    @Test fun sleepTimerStopsLoopAndDoesNotRestartAfterFocusGain() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(10_000, 1000) }
        select(1, "VIE1925", false)
        val loops = PlaybackLoopStore(service)
        loops.save("VIE1925", PlaybackLoopSelection(true, listOf(1, 2)))
        select(1, "VIE1925", true)
        prepare()
        timer(1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        focus(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(PlaybackStateStore.playWhenReady)
        assertFalse(PlaybackStateStore.isPlaying)
        assertEquals(0L, PlaybackStateStore.sleepDeadlineElapsed)
        assertTrue(loops.load("VIE1925").enabled)
    }

    @Test fun loopSurvivesServiceRecreationAndResumesSavedSequence() {
        select(3, "VIE1925", false)
        PlaybackLoopStore(service).save("VIE1925", PlaybackLoopSelection(true, listOf(3, 2)))
        select(3, "VIE1925", true)
        prepare()
        controller.destroy()
        controller = Robolectric.buildService(AudioPlayerService::class.java).create()
        service = controller.get()
        service.onStartCommand(null, 0, 1)
        prepare()
        assertTrue(player()!!.isPlaying)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertTrue(PlaybackStateStore.playWhenReady)
    }

    @Test fun changingNamedPresetIsVisibleToServiceAndDeletedPresetReturnsToNormalOrder() {
        select(3, "VIE1925", false)
        val store = PlaybackLoopStore(service)
        val first = store.savePreset("VIE1925", "Night", listOf(3, 1))
        val second = store.savePreset("VIE1925", "Morning", listOf(3, 20))
        store.activatePreset("VIE1925", first.id)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(1, PlaybackStateStore.currentOrder)
        store.activatePreset("VIE1925", second.id)
        select(3, "VIE1925", false)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(20, PlaybackStateStore.currentOrder)
        store.deletePreset("VIE1925", second.id)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(21, PlaybackStateStore.currentOrder)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertNull(player())
    }

    private fun startBetweenCheckpoints() {
        select(1, "VIE1925", true)
        prepare()
        seek(25_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(player()!!.currentPosition > PlaybackStateStore.positionMs)
    }

    @Test fun allThreeRecordingsHaveIndependentFilesQueuesAndPositions() {
        val all = repository.allAudioChapters()
        assertEquals(3567, all.size)
        assertEquals(3567, all.map { it.downloadKey }.toSet().size)
        assertEquals(3567, all.map { it.filename }.toSet().size)
        assertEquals(3567, all.map { it.positionKey }.toSet().size)
        val uk = repository.chapters("NIVUK")
        assertEquals(1189, uk.size)
        assertEquals(66, uk.map { it.bookCode }.toSet().size)
        assertEquals(2_000_001, uk.first().downloadKey)
        assertTrue(uk.all { it.url.startsWith("https://stream.biblegateway.com/bibles/32/nivuk-suchet/") })
        assertTrue(uk.all { it.filename.startsWith("NIVUK_") && it.audioVersion == "NIVUK" })
        assertEquals("NIVUK", repository.chapter("MAT", 1, "NIVUK")!!.audioVersion)
        assertTrue(repository.audioSizeBytes("NIVUK")!! > 100_000_000L)
    }

    @Test fun switchingBetweenEnglishRecordingsKeepsPausedAndUsesIndependentPositions() {
        select(1, "NIV", true)
        prepare()
        seek(25_000)
        action(AudioPlayerService.ACTION_PAUSE)
        select(1, "NIVUK", false)
        assertEquals("NIVUK", PlaybackStateStore.audioVersion)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertNull(player())
        val prefs = service.getSharedPreferences("state", Context.MODE_PRIVATE)
        assertEquals(25_000L, prefs.getLong("position_NIV_1", -1))
        assertEquals(0L, prefs.getLong("position_NIVUK_1", -1))
        action(AudioPlayerService.ACTION_RESUME)
        prepare()
        assertTrue(player()!!.isPlaying)
        seek(10_000)
        action(AudioPlayerService.ACTION_PAUSE)
        assertEquals(10_000L, prefs.getLong("position_NIVUK_1", -1))
        assertEquals(25_000L, prefs.getLong("position_NIV_1", -1))
    }

    @Test fun nivukLoopAndServiceRestartStayOnTheSuchetRecording() {
        select(3, "NIVUK", false)
        val loops = PlaybackLoopStore(service)
        loops.save("NIVUK", PlaybackLoopSelection(true, listOf(3, 1)))
        select(3, "NIVUK", true)
        prepare()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(1, PlaybackStateStore.currentOrder)
        assertEquals("NIVUK", PlaybackStateStore.audioVersion)
        prepare()
        controller.destroy()
        controller = Robolectric.buildService(AudioPlayerService::class.java).create()
        service = controller.get()
        service.onStartCommand(null, 0, 1)
        prepare()
        assertEquals("NIVUK", PlaybackStateStore.audioVersion)
        assertTrue(player()!!.isPlaying)
        assertTrue(PlaybackLoopStore(service).load("NIVUK").enabled)
        select(1, "NIV", false)
        assertFalse(loops.load("NIVUK").enabled)
        assertEquals(listOf(3, 1), loops.load("NIVUK").orders)
    }

    @Test fun existingNivFileDoesNotMasqueradeAsNivukAndDeletionIsIsolated() {
        val store = AudioDownloadStore(service)
        val niv = repository.chapter(1, "NIV")!!
        val uk = repository.chapter(1, "NIVUK")!!
        val vi = repository.chapter(1)!!
        val files = listOf(niv, uk, vi).map { store.targetFile(it) }
        try {
            store.targetFile(niv).writeText("original NIV audio")
            store.targetFile(vi).writeText("original VIE audio")
            assertNull(store.availableFile(uk))
            store.enqueue(listOf(uk))
            assertTrue(uk.downloadKey in store.queuedOrders())
            assertFalse(niv.downloadKey in store.queuedOrders())
            store.targetFile(uk).writeText("new Suchet audio")
            assertEquals(1, store.summary(listOf(uk)).downloaded)
            assertEquals(1, store.delete(listOf(uk)))
            assertEquals("original NIV audio", store.availableFile(niv)!!.readText())
            assertEquals("original VIE audio", store.availableFile(vi)!!.readText())
            assertNull(store.availableFile(uk))
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test fun downloadedNivukPlaysItsOwnLocalFileRatherThanEitherOtherRecording() {
        val chapter = repository.chapter(1, "NIVUK")!!
        val file = AudioDownloadStore(service).targetFile(chapter)
        try {
            file.writeText("test Suchet recording")
            select(1, "NIVUK", true)
            prepare()
            assertEquals(DataSource.toDataSource(file.absolutePath), shadowOf(player()!!).dataSource)
            assertTrue(player()!!.isPlaying)
            assertEquals("NIVUK", PlaybackStateStore.audioVersion)
        } finally {
            file.delete()
        }
    }

    @Test @Config(sdk = [34]) fun interruptionPauseActionSavesImmediatelyWithoutWaitingForPoll() {
        startBetweenCheckpoints()
        val exactPosition = player()!!.currentPosition
        action(AudioPlayerService.ACTION_PAUSE)
        assertFalse(player()!!.isPlaying)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertEquals(exactPosition, PlaybackStateStore.positionMs)
        val state = service.getSharedPreferences("state", Context.MODE_PRIVATE)
        assertEquals(exactPosition.toLong(), state.getLong("position_1", -1))
        assertFalse(state.getBoolean("wasPlaying", true))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(exactPosition, player()!!.currentPosition)
        assertFalse(PlaybackStateStore.isPlaying)
    }

    @Test @Config(sdk = [34]) fun interruptionTransientFocusLossSavesAndWaitsForFocusGain() {
        startBetweenCheckpoints()
        val exactPosition = player()!!.currentPosition
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(player()!!.isPlaying)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertEquals(exactPosition.toLong(), service.getSharedPreferences("state", Context.MODE_PRIVATE)
            .getLong("position_1", -1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertFalse(player()!!.isPlaying)
        assertEquals(exactPosition, player()!!.currentPosition)
        focus(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(player()!!.isPlaying)
        assertTrue(PlaybackStateStore.playWhenReady)
    }

    @Test @Config(sdk = [34]) fun interruptionManualPauseDuringFocusLossPreventsAutomaticResume() {
        startBetweenCheckpoints()
        focus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        action(AudioPlayerService.ACTION_PAUSE)
        focus(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(player()!!.isPlaying)
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    @Test @Config(sdk = [34]) fun interruptionPermanentFocusLossDoesNotAutomaticallyResume() {
        startBetweenCheckpoints()
        focus(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(player()!!.isPlaying)
        assertFalse(PlaybackStateStore.playWhenReady)
        focus(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(player()!!.isPlaying)
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    @Test @Config(sdk = [34]) fun android14SessionAndNotificationExposeRewindPlayNext() {
        select(1, "VIE1925", false)
        val state = AudioPlayerService::class.java.getDeclaredMethod("buildMediaPlaybackState").apply {
            isAccessible = true
        }.invoke(service) as PlaybackState
        assertEquals(0L, state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        assertNotEquals(0L, state.actions and PlaybackState.ACTION_SKIP_TO_NEXT)
        assertEquals(AudioPlayerService.ACTION_REWIND_15, state.customActions.single().action)
        val notification = AudioPlayerService::class.java.getDeclaredMethod("buildNotification").apply {
            isAccessible = true
        }.invoke(service) as Notification
        assertEquals(listOf(AudioPlayerService.ACTION_REWIND_15, AudioPlayerService.ACTION_TOGGLE, AudioPlayerService.ACTION_NEXT),
            notification.actions.map { shadowOf(it.actionIntent).savedIntent.action })
        action(AudioPlayerService.ACTION_REWIND_15)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertNull(player())
    }
}
