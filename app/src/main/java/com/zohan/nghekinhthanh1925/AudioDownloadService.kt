package com.zohan.nghekinhthanh1925

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AudioDownloadService : Service() {
    private lateinit var repository: BibleRepository
    private lateinit var store: AudioDownloadStore
    private lateinit var chaptersByOrder: Map<Int, BibleChapter>
    private val executor = Executors.newFixedThreadPool(WORKER_COUNT)
    private val workQueue = ConcurrentLinkedQueue<Int>()
    private val scheduled = ConcurrentHashMap.newKeySet<Int>()
    private val cancelled = ConcurrentHashMap.newKeySet<Int>()
    private val connections = ConcurrentHashMap<Int, HttpURLConnection>()
    private val liveWorkers = AtomicInteger(0)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationElapsed: Long? = null
    private val notificationLock = Any()
    private var buildingNotification = false
    private var foregroundStarted = false
    @Volatile private var destroyed = false
    internal var connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }

    override fun onCreate() {
        super.onCreate()
        repository = BibleRepository(applicationContext)
        store = AudioDownloadStore(applicationContext)
        chaptersByOrder = repository.allAudioChapters().associateBy { it.downloadKey }
        store.resetActive()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancel(intent.getIntArrayExtra(EXTRA_ORDERS) ?: intArrayOf())
            else -> startDownloads()
        }
        return START_REDELIVER_INTENT
    }

    @Synchronized
    override fun onDestroy() {
        destroyed = true
        executor.shutdownNow()
        connections.values.forEach { it.disconnect() }
        store.resetActive()
        wakeLock?.takeIf { it.isHeld }?.release()
        repository.close()
        super.onDestroy()
    }

    @Synchronized
    private fun startDownloads() {
        if (destroyed) return
        val orders = store.queuedOrders()
        if (orders.isEmpty()) {
            stopWhenIdle()
            return
        }
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, buildNotification(DownloadSummary(0, orders.size, 0, 0, 0L)))
            foregroundStarted = true
        }
        acquireWakeLock()
        orders.sorted().forEach { order ->
            if (scheduled.add(order)) workQueue.offer(order)
        }
        executor.execute { updateNotification() }
        ensureWorkers()
    }

    @Synchronized
    private fun ensureWorkers() {
        if (executor.isShutdown) return
        val count = minOf(WORKER_COUNT - liveWorkers.get(), workQueue.size)
        liveWorkers.addAndGet(count)
        repeat(count) {
            executor.execute {
                try {
                    workerLoop()
                } finally {
                    liveWorkers.decrementAndGet()
                    if (workQueue.isNotEmpty()) ensureWorkers() else stopWhenIdle()
                }
            }
        }
    }

    private fun workerLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val order = workQueue.poll() ?: return
            if (cancelled.remove(order) || !store.isQueued(order)) {
                scheduled.remove(order)
                continue
            }
            val chapter = chaptersByOrder[order]
            if (chapter == null || store.availableFile(chapter) != null) {
                store.finish(order, success = chapter != null)
                scheduled.remove(order)
                updateNotification()
                continue
            }
            store.setActive(order, true)
            updateNotification()
            val success = download(chapter)
            val wasCancelled = cancelled.remove(order) || !store.isQueued(order)
            if (wasCancelled) {
                store.discard(order)
            } else {
                store.finish(order, success)
            }
            scheduled.remove(order)
            updateNotification()
        }
    }

    private fun download(chapter: BibleChapter): Boolean {
        val target = store.targetFile(chapter)
        val temporary = File(target.parentFile, "${target.name}.part")
        target.parentFile?.mkdirs()
        repeat(3) { attempt ->
            if (chapter.downloadKey in cancelled || !store.isQueued(chapter.downloadKey)) {
                temporary.delete()
                return false
            }
            var connection: HttpURLConnection? = null
            try {
                connection = connectionFactory(URL(chapter.url))
                connections[chapter.downloadKey] = connection
                connection.connectTimeout = 30_000
                connection.readTimeout = 90_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "NgheKinhThanh1925/2.17")
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                if (contentType != null && !contentType.startsWith("audio/") &&
                    contentType != "application/octet-stream") {
                    throw IllegalStateException("Response is not audio")
                }
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            if (
                                chapter.downloadKey in cancelled ||
                                Thread.currentThread().isInterrupted
                            ) {
                                throw DownloadCancelled()
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val expectedLength = connection.contentLengthLong
                if (temporary.length() <= 0L ||
                    (expectedLength > 0L && temporary.length() != expectedLength)) {
                    throw IllegalStateException("Incomplete download")
                }
                if (chapter.downloadKey in cancelled || !store.isQueued(chapter.downloadKey)) {
                    throw DownloadCancelled()
                }
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                return target.exists() && target.length() > 0L
            } catch (_: DownloadCancelled) {
                temporary.delete()
                return false
            } catch (_: Exception) {
                temporary.delete()
                if (destroyed || Thread.currentThread().isInterrupted || chapter.downloadKey in cancelled ||
                    !store.isQueued(chapter.downloadKey)) return false
                if (attempt < 2) Thread.sleep(1_000L * (attempt + 1))
            } finally {
                connection?.let { connections.remove(chapter.downloadKey, it) }
                connection?.disconnect()
            }
        }
        return false
    }

    private fun cancel(orders: IntArray) {
        val active = store.activeOrders()
        val inactive = orders.filterTo(HashSet()) { it !in active }
        cancelled.addAll(orders.filter { it in active })
        orders.forEach { connections[it]?.disconnect() }
        workQueue.removeAll(inactive)
        scheduled.removeAll(inactive)
        store.discard(inactive)
        if (!executor.isShutdown) executor.execute {
            val partials = inactive.mapNotNull { order -> chaptersByOrder[order]?.let { "${it.filename}.part" to order } }.toMap()
            val directory = chaptersByOrder.values.firstOrNull()?.let { store.targetFile(it).parentFile }
            directory?.listFiles()?.forEach { file ->
                val order = partials[file.name]
                if (order != null && !store.isQueued(order)) file.delete()
            }
            updateNotification()
        }
        if (scheduled.isEmpty()) stopWhenIdle()
    }

    @Synchronized
    private fun stopWhenIdle() {
        if (destroyed) return
        if (scheduled.isNotEmpty() || store.queuedOrders().isNotEmpty()) return
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NgheKinhThanh1925:AudioDownload"
        ).apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1_000L)
        }
    }

    private fun updateNotification() {
        synchronized(notificationLock) {
            if (destroyed || scheduled.isEmpty() || buildingNotification) return
            val now = SystemClock.elapsedRealtime()
            if (lastNotificationElapsed?.let { now - it < 1_000L } == true) return
            lastNotificationElapsed = now
            buildingNotification = true
        }
        try {
            val notification = buildNotification()
            synchronized(this) {
                if (!destroyed && foregroundStarted && scheduled.isNotEmpty()) {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
        } catch (_: SecurityException) {
        } finally {
            synchronized(notificationLock) { buildingNotification = false }
        }
    }

    private fun buildNotification(summary: DownloadSummary = store.summary(chaptersByOrder.values)): Notification {
        val pending = summary.running + summary.queued
        val detail = when {
            pending > 0 -> UiLanguage.text(this,
                "${summary.downloaded} chương đã lưu · ${summary.running}/$WORKER_COUNT đang tải · ${summary.queued} chờ",
                "${summary.downloaded} chapters saved · ${summary.running}/$WORKER_COUNT downloading · ${summary.queued} queued")
            summary.failed > 0 -> UiLanguage.text(this, "Hoàn tất với ${summary.failed} lỗi", "Finished with ${summary.failed} errors")
            else -> UiLanguage.text(this, "Đã tải xong ${summary.downloaded} chương", "Downloaded ${summary.downloaded} chapters")
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(UiLanguage.text(this, "Đang tải audio Kinh Thánh", "Downloading Bible audio"))
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(pending > 0)
            .setProgress(summary.downloaded + pending, summary.downloaded, false)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                UiLanguage.text(this, "Tải audio Kinh Thánh", "Bible audio downloads"),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun openAppIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private class DownloadCancelled : RuntimeException()

    companion object {
        const val ACTION_START = "com.zohan.nghekinhthanh1925.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.zohan.nghekinhthanh1925.DOWNLOAD_CANCEL"
        const val EXTRA_ORDERS = "orders"
        const val WORKER_COUNT = 12
        private const val CHANNEL_ID = "bible_downloads"
        private const val NOTIFICATION_ID = 1926
    }
}
