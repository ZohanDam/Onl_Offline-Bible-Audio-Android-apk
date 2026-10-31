package com.zohan.nghekinhthanh1925

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioDownloadServiceTest {
    private lateinit var controller: ServiceController<AudioDownloadService>
    private lateinit var service: AudioDownloadService
    private lateinit var store: AudioDownloadStore
    private lateinit var chapters: List<BibleChapter>
    private val gates = mutableListOf<CountDownLatch>()

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("audio_downloads_v2", 0).edit().clear().commit()
        store = AudioDownloadStore(context)
        BibleRepository(context).use { chapters = it.chapters("NIVUK").take(30) }
        chapters.forEach { store.targetFile(it).delete() }
        controller = Robolectric.buildService(AudioDownloadService::class.java).create()
        service = controller.get()
    }

    @After fun cleanup() {
        gates.forEach { it.countDown() }
        controller.destroy()
        val executor = field("executor") as java.util.concurrent.ExecutorService
        assertTrue("Workers must terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
        chapters.forEach { store.targetFile(it).delete() }
    }

    private fun field(name: String): Any = AudioDownloadService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)!!

    private fun start(items: List<BibleChapter>) {
        store.enqueue(items)
        service.onStartCommand(Intent(service, AudioDownloadService::class.java).setAction(AudioDownloadService.ACTION_START), 0, 1)
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("Download worker condition timed out", condition())
    }

    private open class Response(url: URL, private val body: ByteArray = ByteArray(128),
                                private val type: String = "audio/mpeg", private val length: Long = body.size.toLong()) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = 200
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentType(): String = type
        override fun getContentLengthLong(): Long = length
    }

    @Test fun downloadsDrainWithTwelveWorkersAndReleaseWakeLock() {
        val started = CountDownLatch(12)
        val release = CountDownLatch(1).also { gates.add(it) }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        service.connectionFactory = { url ->
            object : Response(url) {
                override fun getInputStream(): ByteArrayInputStream {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    started.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    active.decrementAndGet()
                    return super.getInputStream()
                }
            }
        }
        start(chapters.take(24))
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertEquals(12, peak.get())
        release.countDown()
        await { store.queuedOrders().isEmpty() && (field("liveWorkers") as AtomicInteger).get() == 0 && shadowOf(service).isStoppedBySelf }
        assertEquals(24, store.summary(chapters).downloaded)
        assertTrue(store.activeOrders().isEmpty())
        assertTrue(store.failedOrders().isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse((AudioDownloadService::class.java.getDeclaredField("wakeLock").apply { isAccessible = true }
            .get(service) as? android.os.PowerManager.WakeLock)?.isHeld == true)
    }

    @Test fun idleWorkersExitAndNewWorkRestoresParallelism() {
        val tailStarted = CountDownLatch(1)
        val release = CountDownLatch(1).also { gates.add(it) }
        val newWorkers = CountDownLatch(10)
        service.connectionFactory = { url ->
            object : Response(url) {
                override fun getInputStream(): ByteArrayInputStream {
                    if (url.toString() == chapters[1].url) {
                        tailStarted.countDown()
                        assertTrue(release.await(10, TimeUnit.SECONDS))
                    } else if (url.toString() != chapters[0].url) {
                        newWorkers.countDown()
                        assertTrue(release.await(10, TimeUnit.SECONDS))
                    }
                    return super.getInputStream()
                }
            }
        }
        start(chapters.take(2))
        assertTrue(tailStarted.await(10, TimeUnit.SECONDS))
        await { (field("liveWorkers") as AtomicInteger).get() == 1 }
        start(chapters.drop(2).take(10))
        assertTrue("New work must not wait for the remaining old download", newWorkers.await(10, TimeUnit.SECONDS))
        assertTrue((field("liveWorkers") as AtomicInteger).get() <= 12)
        release.countDown()
        await { store.queuedOrders().isEmpty() && (field("liveWorkers") as AtomicInteger).get() == 0 }
        assertEquals(12, store.summary(chapters).downloaded)
    }

    @Test fun notificationBurstsAreCoalesced() {
        AudioDownloadService::class.java.getDeclaredField("foregroundStarted").apply { isAccessible = true }.setBoolean(service, true)
        @Suppress("UNCHECKED_CAST")
        (field("scheduled") as MutableSet<Int>).add(chapters[0].downloadKey)
        val update = AudioDownloadService::class.java.getDeclaredMethod("updateNotification").apply { isAccessible = true }
        val manager = shadowOf(service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        update.invoke(service)
        val first = manager.getNotification(1926)
        assertNotNull(first)
        repeat(24) { update.invoke(service) }
        assertSame(first, manager.getNotification(1926))
        SystemClock.sleep(1_000)
        update.invoke(service)
        assertNotSame(first, manager.getNotification(1926))
    }

    @Test fun notificationScanDoesNotHoldMainThreadServiceControlLock() {
        @Suppress("UNCHECKED_CAST")
        (field("scheduled") as MutableSet<Int>).add(chapters[0].downloadKey)
        val update = AudioDownloadService::class.java.getDeclaredMethod("updateNotification").apply { isAccessible = true }
        val storeLock = AudioDownloadStore::class.java.getDeclaredField("LOCK").apply { isAccessible = true }.get(null)!!
        val controlReady = CountDownLatch(1)
        val scanning = Thread { update.invoke(service) }
        val control = Thread { synchronized(service) { controlReady.countDown() } }
        try {
            synchronized(storeLock) {
                scanning.start()
                await { scanning.state == Thread.State.BLOCKED }
                control.start()
                assertTrue("A storage scan must not block service controls", controlReady.await(2, TimeUnit.SECONDS))
            }
        } finally {
            scanning.join(5_000)
            control.join(5_000)
        }
        assertFalse(scanning.isAlive)
        assertFalse(control.isAlive)
    }

    @Test fun cancelledPendingChaptersCanBeEnqueuedAgain() {
        store.enqueue(chapters)
        service.onStartCommand(Intent(service, AudioDownloadService::class.java).setAction(AudioDownloadService.ACTION_CANCEL)
            .putExtra(AudioDownloadService.EXTRA_ORDERS, chapters.map { it.downloadKey }.toIntArray()), 0, 1)
        assertTrue(store.queuedOrders().isEmpty())
        assertTrue((field("cancelled") as Set<*>).isEmpty())
        service.connectionFactory = { Response(it) }
        start(chapters.take(1))
        await { store.queuedOrders().isEmpty() && (field("liveWorkers") as AtomicInteger).get() == 0 }
        assertNotNull(store.availableFile(chapters[0]))
    }

    @Test fun activeCancellationClosesConnectionWithoutRetrying() {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1).also { gates.add(it) }
        val requests = AtomicInteger()
        service.connectionFactory = { url ->
            requests.incrementAndGet()
            object : Response(url) {
                override fun getInputStream(): ByteArrayInputStream {
                    opened.countDown()
                    assertTrue(closed.await(10, TimeUnit.SECONDS))
                    throw IOException("Connection closed")
                }
                override fun disconnect() { closed.countDown() }
            }
        }
        start(chapters.take(1))
        assertTrue(opened.await(10, TimeUnit.SECONDS))
        store.cancelPending(chapters.take(1))
        service.onStartCommand(Intent(service, AudioDownloadService::class.java).setAction(AudioDownloadService.ACTION_CANCEL)
            .putExtra(AudioDownloadService.EXTRA_ORDERS, intArrayOf(chapters[0].downloadKey)), 0, 2)
        await { (field("liveWorkers") as AtomicInteger).get() == 0 }
        assertEquals(1, requests.get())
        assertTrue(store.queuedOrders().isEmpty())
        assertTrue(store.failedOrders().isEmpty())
        assertNull(store.availableFile(chapters[0]))
    }

    @Test fun incompleteAudioIsNeverPublishedAsOffline() {
        service.connectionFactory = { Response(it, length = 256) }
        start(chapters.take(1))
        await { store.queuedOrders().isEmpty() && (field("liveWorkers") as AtomicInteger).get() == 0 }
        assertNull(store.availableFile(chapters[0]))
        assertEquals(setOf(chapters[0].downloadKey), store.failedOrders())
    }
}
