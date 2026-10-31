package com.zohan.nghekinhthanh1925

import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.os.Environment
import java.io.File

class AudioDownloadStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val audioDirectory = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
        AUDIO_DIRECTORY
    ).also { it.mkdirs() }
    private val legacyDirectory = File(context.filesDir, AUDIO_DIRECTORY)

    init {
        migrateLegacyDownloads()
    }

    fun availableFile(chapter: BibleChapter): File? {
        val downloaded = fileFor(chapter)
        if (downloaded.exists() && downloaded.length() > 0L) return downloaded
        val legacy = File(legacyDirectory, chapter.filename)
        return legacy.takeIf { it.exists() && it.length() > 0L }
    }

    fun enqueue(chapters: Collection<BibleChapter>): Int {
        val missing = chapters.filter { availableFile(it) == null }
        val added = synchronized(LOCK) {
            val queued = readOrders(KEY_QUEUED)
            val failed = readOrders(KEY_FAILED)
            var count = 0
            missing.forEach { chapter ->
                if (queued.add(chapter.downloadKey)) {
                    failed.remove(chapter.downloadKey)
                    count += 1
                }
            }
            writeOrders(KEY_QUEUED, queued)
            writeOrders(KEY_FAILED, failed)
            count
        }
        if (added > 0) {
            startService(AudioDownloadService.ACTION_START)
        }
        return added
    }

    fun resumeIfNeeded() {
        if (queuedOrders().isNotEmpty()) {
            startService(AudioDownloadService.ACTION_START)
        }
    }

    fun summary(chapters: Collection<BibleChapter>): DownloadSummary {
        val (queued, active, failed) = synchronized(LOCK) {
            Triple(readOrders(KEY_QUEUED), readOrders(KEY_ACTIVE), readOrders(KEY_FAILED))
        }
        val wanted = chapters.mapTo(HashSet()) { it.filename }
        val sizes = HashMap<String, Long>()
        // Enumerate existing files instead of probing every missing chapter twice.
        listOf(legacyDirectory, audioDirectory).distinct().forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (file.name in wanted) {
                    val size = file.length()
                    if (size > 0L) sizes[file.name] = size
                }
            }
        }
        var downloaded = 0
        var downloadedBytes = 0L
        var queuedCount = 0
        var runningCount = 0
        var failedCount = 0

        chapters.forEach { chapter ->
            val size = sizes[chapter.filename]
            when {
                size != null -> {
                    downloaded += 1
                    downloadedBytes += size
                }
                chapter.downloadKey in active -> runningCount += 1
                chapter.downloadKey in queued -> queuedCount += 1
                chapter.downloadKey in failed -> failedCount += 1
            }
        }
        return DownloadSummary(
            downloaded = downloaded,
            queued = queuedCount,
            running = runningCount,
            failed = failedCount,
            downloadedBytes = downloadedBytes
        )
    }

    fun cancelPending(chapters: Collection<BibleChapter>): Int {
        val orders = chapters.map { it.downloadKey }.toSet()
        val affected = synchronized(LOCK) {
            val queued = readOrders(KEY_QUEUED)
            val active = readOrders(KEY_ACTIVE)
            val count = (queued + active).count { it in orders }
            queued.removeAll(orders)
            writeOrders(KEY_QUEUED, queued)
            count
        }
        if (affected == 0) return 0
        startService(
            AudioDownloadService.ACTION_CANCEL,
            orders.toIntArray()
        )
        return affected
    }

    fun delete(chapters: Collection<BibleChapter>): Int {
        cancelPending(chapters)
        val failed = failedOrders()
        var deleted = 0
        chapters.forEach { chapter ->
            val files = listOf(fileFor(chapter), File(legacyDirectory, chapter.filename))
            var removed = false
            files.distinct().forEach { if (it.exists() && it.delete()) removed = true }
            if (removed) deleted += 1
            failed.remove(chapter.downloadKey)
        }
        writeOrders(KEY_FAILED, failed)
        return deleted
    }

    fun targetFile(chapter: BibleChapter): File = fileFor(chapter)

    fun queuedOrders(): MutableSet<Int> = synchronized(LOCK) {
        readOrders(KEY_QUEUED)
    }

    fun isQueued(order: Int): Boolean = synchronized(LOCK) {
        preferences.getStringSet(KEY_QUEUED, emptySet()).orEmpty().contains(order.toString())
    }

    fun activeOrders(): MutableSet<Int> = synchronized(LOCK) {
        readOrders(KEY_ACTIVE)
    }

    fun failedOrders(): MutableSet<Int> = synchronized(LOCK) {
        readOrders(KEY_FAILED)
    }

    fun setActive(order: Int, active: Boolean) {
        synchronized(LOCK) {
            val orders = readOrders(KEY_ACTIVE)
            if (active) orders.add(order) else orders.remove(order)
            writeOrders(KEY_ACTIVE, orders)
        }
    }

    fun finish(order: Int, success: Boolean) {
        synchronized(LOCK) {
            val queued = readOrders(KEY_QUEUED).apply { remove(order) }
            val active = readOrders(KEY_ACTIVE).apply { remove(order) }
            val failed = readOrders(KEY_FAILED).apply {
                if (success) remove(order) else add(order)
            }
            writeOrders(KEY_QUEUED, queued)
            writeOrders(KEY_ACTIVE, active)
            writeOrders(KEY_FAILED, failed)
        }
    }

    fun discard(order: Int) {
        discard(setOf(order))
    }

    fun discard(orders: Set<Int>) {
        synchronized(LOCK) {
            writeOrders(KEY_QUEUED, readOrders(KEY_QUEUED).apply { removeAll(orders) })
            writeOrders(KEY_ACTIVE, readOrders(KEY_ACTIVE).apply { removeAll(orders) })
            writeOrders(KEY_FAILED, readOrders(KEY_FAILED).apply { removeAll(orders) })
        }
    }

    fun resetActive() {
        synchronized(LOCK) {
            writeOrders(KEY_ACTIVE, emptySet())
        }
    }

    private fun fileFor(chapter: BibleChapter): File {
        return File(audioDirectory, chapter.filename)
    }

    private fun startService(action: String, orders: IntArray? = null) {
        val intent = Intent(context, AudioDownloadService::class.java)
            .setAction(action)
        if (orders != null) intent.putExtra(AudioDownloadService.EXTRA_ORDERS, orders)
        if (action == AudioDownloadService.ACTION_START) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun migrateLegacyDownloads() {
        val migrationPreferences = context.getSharedPreferences(
            LEGACY_PREFERENCES,
            Context.MODE_PRIVATE
        )
        if (migrationPreferences.all.isEmpty()) return
        val ids = migrationPreferences.all.values.mapNotNull { value ->
            (value as? Long)?.takeIf { it >= 0L }
        }
        if (ids.isNotEmpty()) {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val activeIds = mutableListOf<Long>()
            manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                while (cursor.moveToNext()) {
                    if (
                        cursor.getInt(statusIndex) == DownloadManager.STATUS_PENDING ||
                        cursor.getInt(statusIndex) == DownloadManager.STATUS_RUNNING ||
                        cursor.getInt(statusIndex) == DownloadManager.STATUS_PAUSED
                    ) {
                        activeIds.add(cursor.getLong(idIndex))
                    }
                }
            }
            if (activeIds.isNotEmpty()) manager.remove(*activeIds.toLongArray())
        }
        migrationPreferences.edit().clear().apply()
    }

    private fun readOrders(key: String): MutableSet<Int> {
        return preferences.getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNullTo(mutableSetOf()) { it.toIntOrNull() }
    }

    private fun writeOrders(key: String, orders: Collection<Int>) {
        preferences.edit()
            .putStringSet(key, orders.mapTo(mutableSetOf()) { it.toString() })
            .apply()
    }

    companion object {
        private const val PREFERENCES = "audio_downloads_v2"
        private const val LEGACY_PREFERENCES = "audio_downloads"
        private const val AUDIO_DIRECTORY = "Bible_Audio"
        private const val KEY_QUEUED = "queued"
        private const val KEY_ACTIVE = "active"
        private const val KEY_FAILED = "failed"
        private val LOCK = Any()
    }
}
