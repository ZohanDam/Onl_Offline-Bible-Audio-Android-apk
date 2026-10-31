package com.zohan.nghekinhthanh1925

data class BibleBook(
    val order: Int,
    val code: String,
    val name: String,
    val testament: String,
    val chapterCount: Int
)

data class BibleChapter(
    val globalOrder: Int,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val filename: String,
    val url: String,
    val audioVersion: String = "VIE1925"
) {
    val downloadKey: Int get() = when (audioVersion) {
        "NIV" -> 1_000_000 + globalOrder
        "NIVUK" -> 2_000_000 + globalOrder
        else -> globalOrder
    }
    val positionKey: String get() = if (audioVersion == "VIE1925") "position_$globalOrder"
        else "position_${audioVersion}_$globalOrder"
}

val ENGLISH_AUDIO_VERSIONS = listOf("NIVUK", "NIV")

fun audioVersionForText(textVersion: String, englishAudioVersion: String = "NIVUK"): String =
    if (textVersion == "NIV") englishAudioVersion.takeIf { it in ENGLISH_AUDIO_VERSIONS } ?: "NIVUK" else "VIE1925"

fun audioMatchesText(textVersion: String, audioVersion: String): Boolean =
    if (textVersion == "NIV") audioVersion in ENGLISH_AUDIO_VERSIONS else audioVersion == "VIE1925"

fun textVersionForAudio(audioVersion: String): String = if (audioVersion in ENGLISH_AUDIO_VERSIONS) "NIV" else "VIE1925"

fun audioRecordingName(audioVersion: String): String = when (audioVersion) {
    "NIVUK" -> "NIVUK · David Suchet"
    "NIV" -> "NIV · Max McLean"
    else -> "VIE1925"
}

data class BibleVerse(
    val number: String,
    val text: String
)

data class BibleTextVersion(
    val code: String,
    val title: String,
    val copyrightNotice: String,
    val sourceUrl: String
)

data class DownloadSummary(
    val downloaded: Int,
    val queued: Int,
    val running: Int,
    val failed: Int,
    val downloadedBytes: Long
)

object PlaybackStateStore {
    @Volatile var audioVersion: String = "VIE1925"
    @Volatile var playWhenReady: Boolean = false
    @Volatile var currentOrder: Int = 0
    @Volatile var isPlaying: Boolean = false
    @Volatile var positionMs: Int = 0
    @Volatile var positionUpdatedElapsed: Long = 0
    @Volatile var durationMs: Int = 0
    @Volatile var speed: Float = 1.0f
    @Volatile var sleepDeadlineElapsed: Long = 0
    @Volatile var sleepMinutes: Int = 0

    fun displayPositionMs(): Int {
        if (!isPlaying || positionUpdatedElapsed == 0L) return positionMs
        // Keep the visible seek bar smooth between native-player samples.
        val elapsed = (android.os.SystemClock.elapsedRealtime() - positionUpdatedElapsed).coerceAtLeast(0)
        return (positionMs.toLong() + (elapsed * speed).toLong())
            .coerceIn(0, durationMs.coerceAtLeast(0).toLong()).toInt()
    }
}
