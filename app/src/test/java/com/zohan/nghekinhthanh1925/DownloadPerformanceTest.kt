package com.zohan.nghekinhthanh1925

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadPerformanceTest {
    private lateinit var context: Context
    private lateinit var store: AudioDownloadStore
    private lateinit var preferences: SharedPreferences

    @Before fun setup() {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "download-performance").apply { deleteRecursively(); mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getExternalFilesDir(type: String?): File = File(root, "external").apply { mkdirs() }
            override fun getFilesDir(): File = File(root, "internal").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("performance_$name", mode)
        }
        preferences = context.getSharedPreferences("audio_downloads_v2", 0)
        preferences.edit().clear().commit()
        store = AudioDownloadStore(context)
    }

    private fun chapter(order: Int, version: String = "VIE1925") = BibleChapter(
        order, "GEN", "Genesis", order, "${version}_$order.mp3", "https://example.com/$order.mp3", version
    )

    @Test fun batchedSummaryPreservesLegacyFallbackAndEditionIsolation() {
        val first = chapter(1)
        val second = chapter(2)
        val legacy = File(context.filesDir, "Bible_Audio").apply { mkdirs() }
        File(legacy, first.filename).writeBytes(ByteArray(10))
        store.targetFile(first).writeBytes(ByteArray(20))
        File(legacy, second.filename).writeBytes(ByteArray(30))
        store.targetFile(second).writeBytes(byteArrayOf())
        store.targetFile(chapter(1, "NIVUK")).writeBytes(ByteArray(40))
        File(store.targetFile(first).parentFile, "partial.mp3.part").writeBytes(ByteArray(50))
        val summary = store.summary(listOf(first, second))
        assertEquals(2, summary.downloaded)
        assertEquals(50L, summary.downloadedBytes)
        assertEquals(40L, store.summary(listOf(chapter(1, "NIVUK"))).downloadedBytes)
        assertEquals(0, store.summary(listOf(chapter(1, "NIV"))).downloaded)
        assertEquals(2, store.delete(listOf(first, second)))
        assertEquals(0, store.summary(listOf(first, second)).downloaded)
        assertEquals(1, store.summary(listOf(chapter(1, "NIVUK"))).downloaded)
    }

    @Test fun summaryPreservesQueuedActiveFailedAndDownloadedPriority() {
        val chapters = (1..4).map { chapter(it) }
        preferences.edit().putStringSet("queued", setOf("1", "2", "3"))
            .putStringSet("active", setOf("2", "3")).putStringSet("failed", setOf("3", "4")).commit()
        store.targetFile(chapters[2]).writeBytes(ByteArray(12))
        assertEquals(DownloadSummary(1, 1, 1, 1, 12L), store.summary(chapters))
        assertTrue(store.isQueued(2))
        assertFalse(store.isQueued(4))
        store.discard(setOf(1, 2, 3))
        assertTrue(store.queuedOrders().isEmpty())
        assertTrue(store.activeOrders().isEmpty())
        assertEquals(setOf(4), store.failedOrders())
    }

    @Test fun summaryHostBenchmarkAgainstPreviousPerChapterScan() {
        val chapters = listOf("VIE1925", "NIV", "NIVUK").flatMap { version -> (1..1189).map { chapter(it, version) } }
        chapters.filter { it.globalOrder <= 20 }.forEach { store.targetFile(it).writeBytes(ByteArray(100)) }
        fun previousScan(): Pair<Int, Long> {
            var count = 0
            var bytes = 0L
            chapters.forEach { chapter ->
                store.availableFile(chapter)?.let { count++; bytes += it.length() }
            }
            return count to bytes
        }
        assertEquals(60 to 6000L, previousScan())
        assertEquals(60, store.summary(chapters).downloaded)
        repeat(3) { previousScan(); store.summary(chapters) }
        fun medianMs(action: () -> Unit): Double = (1..9).map {
            val start = System.nanoTime()
            action()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()[4]
        val previous = medianMs { previousScan() }
        val batched = medianMs { store.summary(chapters) }
        println("SUMMARY_HOST_BENCHMARK chapters=3567 offline=60 previous_ms=$previous batched_ms=$batched")
    }

    @Test fun englishSingleChapterQueriesMatchFullListsForBothRecordings() {
        BibleRepository(RuntimeEnvironment.getApplication()).use { repository ->
            for (version in ENGLISH_AUDIO_VERSIONS) {
                val chapters = repository.chapters(version)
                listOf(chapters.first(), chapters[500], chapters.last()).forEach { chapter ->
                    assertEquals(chapter, repository.chapter(chapter.globalOrder, version))
                    assertEquals(chapter, repository.chapter(chapter.bookCode, chapter.chapter, version))
                }
                assertNull(repository.chapter(0, version))
                assertNull(repository.chapter("UNKNOWN", 1, version))
            }
        }
    }

    @Test fun databaseFingerprintReusedUntilApkIdentityChanges() {
        val base = RuntimeEnvironment.getApplication()
        val databasePreferences = base.getSharedPreferences("bibleDatabase", 0)
        databasePreferences.edit().clear().commit()
        BibleRepository(base).use { assertEquals(3, it.textVersions().size) }
        val hash = databasePreferences.getString("assetHash", null)
        val identity = databasePreferences.getString("assetIdentity", null)
        assertNotNull(hash)
        assertNotNull(identity)
        BibleRepository(base).use { assertEquals(66, it.books().size) }
        assertEquals(hash, databasePreferences.getString("assetHash", null))
        assertEquals(identity, databasePreferences.getString("assetIdentity", null))
        databasePreferences.edit().putString("assetIdentity", "previous-apk").putString("assetHash", "invalid").commit()
        BibleRepository(base).use { assertEquals(66, it.books().size) }
        assertEquals(hash, databasePreferences.getString("assetHash", null))
        assertEquals(identity, databasePreferences.getString("assetIdentity", null))
    }
}
