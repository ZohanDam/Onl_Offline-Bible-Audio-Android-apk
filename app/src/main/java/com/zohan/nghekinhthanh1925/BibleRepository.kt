package com.zohan.nghekinhthanh1925

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

class BibleRepository(private val context: Context) : Closeable {
    private val database: SQLiteDatabase by lazy { openDatabase() }

    fun textVersions(): List<BibleTextVersion> {
        return database.rawQuery(
            "SELECT code, title, copyright, source_url FROM text_versions ORDER BY display_order",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(BibleTextVersion(
                        cursor.getString(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3)
                    ))
                }
            }
        }
    }

    fun books(versionCode: String = "VIE1925"): List<BibleBook> {
        return database.rawQuery(
            """
            SELECT b.book_order, b.code, v.name, b.testament, b.chapter_count
            FROM books b
            JOIN version_books v ON v.book_code = b.code
            WHERE v.version_code = ?
            ORDER BY b.book_order
            """.trimIndent(),
            arrayOf(versionCode)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        BibleBook(
                            order = cursor.getInt(0),
                            code = cursor.getString(1),
                            name = cursor.getString(2),
                            testament = cursor.getString(3),
                            chapterCount = cursor.getInt(4)
                        )
                    )
                }
            }
        }
    }

    fun chapters(audioVersion: String = "VIE1925"): List<BibleChapter> {
        if (audioVersion in ENGLISH_AUDIO_VERSIONS) {
            return database.rawQuery(
                """
                SELECT c.global_order, c.book_code, b.name,
                       c.chapter_number, c.audio_filename, c.audio_url
                FROM audio_chapters c
                JOIN version_books b ON b.book_code = c.book_code AND b.version_code = 'NIV'
                WHERE c.version_code = ? ORDER BY c.global_order
                """.trimIndent(), arrayOf(audioVersion)
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.toChapter().copy(audioVersion = audioVersion)) }
            }
        }
        return database.rawQuery(
            """
            SELECT c.global_order, c.book_code, b.name,
                   c.chapter_number, c.audio_filename, c.audio_url
            FROM chapters c
            JOIN books b ON b.code = c.book_code
            ORDER BY c.global_order
            """.trimIndent(),
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toChapter())
                }
            }
        }
    }

    fun allAudioChapters(): List<BibleChapter> = chapters() + ENGLISH_AUDIO_VERSIONS.flatMap { chapters(it) }

    fun audioSizeBytes(audioVersion: String): Long? = database.rawQuery(
        "SELECT value FROM metadata WHERE key = ?", arrayOf("${audioVersion.lowercase(java.util.Locale.ROOT)}_audio_bytes")
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() else null }

    fun chapter(globalOrder: Int, audioVersion: String = "VIE1925"): BibleChapter? {
        if (audioVersion in ENGLISH_AUDIO_VERSIONS) return chapters(audioVersion).firstOrNull { it.globalOrder == globalOrder }
        return database.rawQuery(
            """
            SELECT c.global_order, c.book_code, b.name,
                   c.chapter_number, c.audio_filename, c.audio_url
            FROM chapters c
            JOIN books b ON b.code = c.book_code
            WHERE c.global_order = ?
            """.trimIndent(),
            arrayOf(globalOrder.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toChapter() else null
        }
    }

    fun chapter(bookCode: String, chapterNumber: Int, audioVersion: String = "VIE1925"): BibleChapter? {
        if (audioVersion in ENGLISH_AUDIO_VERSIONS) return chapters(audioVersion).firstOrNull {
            it.bookCode == bookCode && it.chapter == chapterNumber
        }
        return database.rawQuery(
            """
            SELECT c.global_order, c.book_code, b.name,
                   c.chapter_number, c.audio_filename, c.audio_url
            FROM chapters c
            JOIN books b ON b.code = c.book_code
            WHERE c.book_code = ? AND c.chapter_number = ?
            """.trimIndent(),
            arrayOf(bookCode, chapterNumber.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toChapter() else null
        }
    }

    fun verses(
        bookCode: String,
        chapterNumber: Int,
        versionCode: String = "VIE1925"
    ): List<BibleVerse> {
        return database.rawQuery(
            """
            SELECT verse_number, text
            FROM version_verses
            WHERE book_code = ? AND chapter_number = ? AND version_code = ?
            ORDER BY verse_order
            """.trimIndent(),
            arrayOf(bookCode, chapterNumber.toString(), versionCode)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(BibleVerse(cursor.getString(0), cursor.getString(1)))
                }
            }
        }
    }

    override fun close() {
        if (database.isOpen) database.close()
    }

    private fun openDatabase(): SQLiteDatabase {
        val target = context.getDatabasePath(DATABASE_NAME)
        val assetSize = context.assets.open(DATABASE_ASSET).use { it.available().toLong() }
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(DATABASE_ASSET).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val assetHash = digest.digest().joinToString("") { "%02x".format(it) }
        val databasePreferences = context.getSharedPreferences("bibleDatabase", Context.MODE_PRIVATE)
        if (!target.exists() || target.length() != assetSize ||
            databasePreferences.getString("assetHash", null) != assetHash) {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "$DATABASE_NAME.tmp")
            context.assets.open(DATABASE_ASSET).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Cannot replace Bible database")
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            databasePreferences.edit().putString("assetHash", assetHash).apply()
        }
        return SQLiteDatabase.openDatabase(
            target.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    private fun android.database.Cursor.toChapter(): BibleChapter {
        return BibleChapter(
            globalOrder = getInt(0),
            bookCode = getString(1),
            bookName = getString(2),
            chapter = getInt(3),
            filename = getString(4),
            url = getString(5)
        )
    }

    companion object {
        private const val DATABASE_ASSET = "bible.db"
        private const val DATABASE_NAME = "vie1925.db"
    }
}
