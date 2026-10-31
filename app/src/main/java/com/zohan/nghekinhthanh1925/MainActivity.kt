package com.zohan.nghekinhthanh1925

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    private lateinit var repository: BibleRepository
    private lateinit var downloads: AudioDownloadStore
    private val loops by lazy { PlaybackLoopStore(applicationContext) }
    private lateinit var appUpdates: AppUpdateController
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var books: List<BibleBook>
    private lateinit var chapters: List<BibleChapter>
    private lateinit var currentChapter: BibleChapter
    private lateinit var textVersions: List<BibleTextVersion>
    private var selectedTextVersion = "VIE1925"

    private var fontSize = 20f
    private var playerBookView: TextView? = null
    private var playerChapterView: TextView? = null
    private var playerOfflineView: TextView? = null
    private var playerPositionView: TextView? = null
    private var playerDurationView: TextView? = null
    private var playerSpeedView: TextView? = null
    private var playerTimerView: TextView? = null
    private var playerTimerButton: View? = null
    private var timerDialogRefresh: Runnable? = null
    private var timerDialogDeadline = 0L
    private var playerPlayButton: ImageButton? = null
    private var playerProgressView: SeekBar? = null
    private var playerBarView: View? = null
    private var compactNavigationView: View? = null
    private var compactPreviousView: ImageButton? = null
    private var compactNextView: ImageButton? = null
    private var playerPanelToggleView: View? = null
    private var playerPanelVisible = true
    private var isSeeking = false
    private var darkMode = false
    private var readerScrollView: ScrollView? = null
    private var settingsPopup: PopupWindow? = null
    private var settingsPopupContent: LinearLayout? = null

    private val colorBackground get() = if (darkMode) Color.rgb(18, 18, 18) else Color.rgb(250, 250, 250)
    private val colorSurface get() = if (darkMode) Color.rgb(30, 30, 30) else Color.WHITE
    private val colorText get() = if (darkMode) Color.rgb(238, 238, 238) else Color.rgb(31, 31, 31)
    private val colorMuted get() = if (darkMode) Color.rgb(183, 183, 183) else Color.rgb(103, 103, 103)
    private val colorBorder get() = if (darkMode) Color.rgb(66, 66, 66) else Color.rgb(226, 226, 226)
    private val colorSelection get() = if (darkMode) Color.rgb(65, 65, 65) else Color.rgb(230, 230, 230)
    private val colorAccent = Color.rgb(190, 40, 45)
    private val colorEmphasis get() = if (darkMode) Color.rgb(255, 156, 159) else colorAccent

    override fun onCreate(savedInstanceState: Bundle?) {
        preferences = getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        applyReaderTheme()
        super.onCreate(savedInstanceState)
        appUpdates = AppUpdateController(this)
        repository = BibleRepository(applicationContext)
        downloads = AudioDownloadStore(applicationContext)
        textVersions = repository.textVersions()
        selectedTextVersion = preferences.getString(KEY_TEXT_VERSION, "VIE1925")
            ?.takeIf { code -> textVersions.any { it.code == code } } ?: "VIE1925"
        if ((PlaybackStateStore.isPlaying || PlaybackStateStore.playWhenReady) &&
            !audioMatchesText(selectedTextVersion, PlaybackStateStore.audioVersion)) {
            selectedTextVersion = textVersionForAudio(PlaybackStateStore.audioVersion)
        }
        books = repository.books(selectedTextVersion)
        if (!preferences.contains("uiLanguage")) UiLanguage.set(this, selectedTextVersion == "NIV")
        if (!PlaybackStateStore.isPlaying && !PlaybackStateStore.playWhenReady) SleepTimer.restore(this)
        val initialAudio = if ((PlaybackStateStore.isPlaying || PlaybackStateStore.playWhenReady) &&
            audioMatchesText(selectedTextVersion, PlaybackStateStore.audioVersion)) PlaybackStateStore.audioVersion
            else preferredAudioVersion()
        chapters = repository.chapters(initialAudio)
        fontSize = preferences.getFloat(KEY_FONT_SIZE, 20f)
        playerPanelVisible = preferences.getBoolean(KEY_PLAYER_PANEL_VISIBLE, true)
        val initialOrder = PlaybackStateStore.currentOrder.takeIf { it > 0 } ?: preferences.getInt(
            KEY_LAST_ORDER,
            preferences.getInt(KEY_READER_ORDER, 1)
        )
        currentChapter = chapters.firstOrNull { it.globalOrder == initialOrder } ?: chapters.first()
        PlaybackStateStore.currentOrder = currentChapter.globalOrder
        PlaybackStateStore.audioVersion = currentChapter.audioVersion
        preferences.edit().putString(KEY_AUDIO_VERSION, currentChapter.audioVersion).apply()
        PlaybackStateStore.speed = preferences.getFloat(KEY_SPEED, 1.0f)
        requestNotificationPermission()
        downloads.resumeIfNeeded()
        renderReader()
    }

    override fun onResume() {
        super.onResume()
        appUpdates.onResume()
        if (PlaybackStateStore.sleepDeadlineElapsed > 0 && SleepTimer.remainingMs() == 0L) {
            sendPlayerAction(AudioPlayerService.ACTION_CHECK_SLEEP)
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        timerDialogRefresh?.let {
            handler.removeCallbacks(it)
            handler.post(it)
        }
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        timerDialogRefresh?.let { handler.removeCallbacks(it) }
        super.onPause()
    }

    override fun onDestroy() {
        settingsPopup?.dismiss()
        if (::appUpdates.isInitialized) appUpdates.close()
        handler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdownNow()
        repository.close()
        super.onDestroy()
    }

    private fun renderReader() {
        preferences.edit().putInt(KEY_READER_ORDER, currentChapter.globalOrder).apply()
        val root = vertical().apply {
            setBackgroundColor(colorBackground)
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset: Int
                val bottomInset: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val systemInsets = insets.getInsets(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                    )
                    topInset = systemInsets.top
                    bottomInset = systemInsets.bottom
                } else {
                    @Suppress("DEPRECATION")
                    topInset = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottomInset = insets.systemWindowInsetBottom
                }
                view.setPadding(0, topInset, 0, bottomInset)
                insets
            }
        }
        val toolbar = readerToolbar()
        root.addView(toolbar, matchWrap())
        root.addView(readerContent(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        playerBarView = playerBar().apply {
            visibility = if (playerPanelVisible) View.VISIBLE else View.GONE
        }
        root.addView(playerBarView, matchWrap())
        compactNavigationView = compactChapterNavigation().apply {
            visibility = if (playerPanelVisible) View.GONE else View.VISIBLE
        }
        root.addView(compactNavigationView, matchWrap())
        setContentView(root)
        // The system-bar controller requires the decor view created by setContentView.
        updateSystemBars()
        root.requestApplyInsets()
        updatePlayerBar()
    }

    private fun readerToolbar(): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(4), dp(6), dp(4))
            setBackgroundColor(colorSurface)
            elevation = dp(2).toFloat()
            addView(selectorChip("Sách") { showBookPicker() }.also {
                it.contentDescription = uiText("Chọn sách", "Choose book")
                playerBookView = it
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
            addView(selectorChip("1") {
                books.firstOrNull { it.code == currentChapter.bookCode }?.let(::showChapterPicker)
            }.also {
                it.contentDescription = uiText("Chọn chương", "Choose chapter")
                playerChapterView = it
            }, LinearLayout.LayoutParams(dp(50), dp(40)).apply { marginEnd = dp(2) })
            addView(speakerStatusButton(), LinearLayout.LayoutParams(dp(44), dp(42)).apply {
                marginEnd = dp(2)
            })
            addView(downloadStatusButton(), LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(2)
            })
            addView(plainIconButton(R.drawable.ic_settings, uiText("Cài đặt", "Settings")) {
                showToolbarMenu(it)
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
        }
    }

    private fun readerContent(): View {
        val content = vertical().apply {
            setPadding(dp(22), dp(24), dp(22), dp(32))
        }
        content.addView(TextView(this).apply {
            text = readerBookName(currentChapter.bookCode)
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = getString(
                if (selectedTextVersion == "NIV" || UiLanguage.isEnglish(this@MainActivity)) R.string.chapter_heading_en else R.string.chapter_heading_vi,
                currentChapter.chapter
            )
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorMuted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(horizontal().apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
            addView(TextView(this@MainActivity).apply {
                text = "$selectedTextVersion  ▾"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorMuted)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                contentDescription = uiText("Chọn bản dịch", "Bible version")
                setOnClickListener { showTextVersionDialog() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        })
        val verses = repository.verses(
            currentChapter.bookCode, currentChapter.chapter, selectedTextVersion
        )
        content.addView(TextView(this).apply {
            text = buildVerseText(verses)
            textSize = fontSize
            setTextColor(colorText)
            setLineSpacing(dp(6).toFloat(), 1.12f)
            setTextIsSelectable(true)
        })
        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            readerScrollView = this
        }
    }

    private fun buildVerseText(verses: List<BibleVerse>): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        verses.forEachIndexed { index, verse ->
            val start = builder.length
            builder.append(verse.number)
            val end = builder.length
            builder.setSpan(
                RelativeSizeSpan(0.7f),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                ForegroundColorSpan(colorEmphasis),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.append("  ")
            builder.append(verse.text)
            if (index != verses.lastIndex) builder.append("\n\n")
        }
        return builder
    }

    private fun playerBar(): View {
        val container = vertical().apply {
            setBackgroundColor(colorSurface)
            elevation = dp(10).toFloat()
        }
        if (selectedTextVersion == "VIE2010") {
            container.addView(TextView(this).apply {
                text = "Audio: VIE1925"
                textSize = 11f
                setTextColor(colorMuted)
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            }, matchWrap())
        } else if (selectedTextVersion == "NIV") {
            container.addView(audioRecordingButton(), matchWrap())
        }
        val timeline = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), 0)
        }
        playerPositionView = timelineLabel(Gravity.START)
        playerDurationView = timelineLabel(Gravity.END)
        timeline.addView(playerPositionView, LinearLayout.LayoutParams(dp(46), dp(34)))
        playerProgressView = SeekBar(this).apply {
            max = max(PlaybackStateStore.durationMs, 1)
            progress = PlaybackStateStore.displayPositionMs().coerceIn(0, max)
            progressTintList = android.content.res.ColorStateList.valueOf(colorAccent)
            thumbTintList = android.content.res.ColorStateList.valueOf(colorAccent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(colorBorder)
            setPadding(dp(14), 0, dp(14), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) = Unit

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val position = seekBar?.progress ?: 0
                    sendPlayerAction(AudioPlayerService.ACTION_SEEK) {
                        putExtra(AudioPlayerService.EXTRA_POSITION, position)
                    }
                    isSeeking = false
                }
            })
        }
        timeline.addView(playerProgressView, LinearLayout.LayoutParams(0, dp(34), 1f))
        timeline.addView(playerDurationView, LinearLayout.LayoutParams(dp(46), dp(34)))
        timeline.addView(plainIconButton(R.drawable.ic_collapse, uiText(
            "Thu gọn bảng điều khiển", "Collapse audio controls"
        )) { togglePlayerPanel() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        container.addView(timeline)

        val transport = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        playerSpeedView = transportOptionButton(formatSpeed(PlaybackStateStore.speed), uiText("Tốc độ", "Playback speed")) {
            showSpeedDialog()
        }
        transport.addView(playerSpeedView, LinearLayout.LayoutParams(dp(58), dp(54)))
        transport.addView(chapterButton(previous = true) {
            navigateChapter(-1)
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        playerPlayButton = mediaButton(
            if (PlaybackStateStore.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            },
            uiText("Phát hoặc tạm dừng", "Play or pause"),
            primary = true,
            large = true
        ) { togglePlayback() }
        transport.addView(playerPlayButton)
        transport.addView(chapterButton(previous = false) {
            navigateChapter(1)
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        playerTimerButton = vertical().apply {
            gravity = Gravity.CENTER
            background = rounded(colorBackground, colorBorder, 16)
            setOnClickListener { showSleepTimerDialog() }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_timer)
                imageTintList = android.content.res.ColorStateList.valueOf(colorText)
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            playerTimerView = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(colorText)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            addView(playerTimerView, matchWrap())
        }
        transport.addView(playerTimerButton, LinearLayout.LayoutParams(dp(72), dp(54)))
        container.addView(transport)
        return container
    }

    private fun compactChapterNavigation(): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        setBackgroundColor(colorSurface)
        compactPreviousView = chapterButton(previous = true) { navigateChapter(-1) }
        compactNextView = chapterButton(previous = false) { navigateChapter(1) }
        addView(compactPreviousView, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
        addView(plainIconButton(R.drawable.ic_expand, uiText(
            "Mở rộng bảng điều khiển", "Expand audio controls"
        )) { togglePlayerPanel() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
        addView(compactNextView, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun togglePlayback() {
        if (PlaybackStateStore.playWhenReady || PlaybackStateStore.isPlaying) {
            PlaybackStateStore.playWhenReady = false
            PlaybackStateStore.isPlaying = false
            sendPlayerAction(AudioPlayerService.ACTION_PAUSE)
        } else {
            PlaybackStateStore.playWhenReady = true
            if (PlaybackStateStore.currentOrder == currentChapter.globalOrder &&
                PlaybackStateStore.audioVersion == currentChapter.audioVersion &&
                PlaybackStateStore.durationMs > 0) {
                sendPlayerAction(AudioPlayerService.ACTION_RESUME)
            } else {
                playChapter(currentChapter)
            }
        }
        handler.postDelayed({ updatePlayerBar() }, 250)
    }

    private fun playChapter(chapter: BibleChapter) {
        sendPlayerAction(AudioPlayerService.ACTION_PLAY_ORDER) {
            putExtra(AudioPlayerService.EXTRA_ORDER, chapter.globalOrder)
            putExtra(AudioPlayerService.EXTRA_AUDIO_VERSION, chapter.audioVersion)
            putExtra(AudioPlayerService.EXTRA_AUTO_PLAY, true)
            putExtra(AudioPlayerService.EXTRA_START_FROM_BEGINNING, true)
        }
        handler.postDelayed({ updatePlayerBar() }, 250)
    }

    private fun navigateChapter(offset: Int) {
        val loopOrder = loops.load(currentChapter.audioVersion).relativeOrder(currentChapter.globalOrder, offset)
        val order = loopOrder ?: currentChapter.globalOrder + offset
        val chapter = chapters.getOrNull(order - 1) ?: return
        selectChapter(chapter, restart = loopOrder == currentChapter.globalOrder)
    }

    private fun selectChapter(chapter: BibleChapter, restart: Boolean = false) {
        loops.onManualSelection(currentChapter.audioVersion, chapter.audioVersion, chapter.globalOrder)
        if (!restart && chapter.globalOrder == currentChapter.globalOrder && chapter.audioVersion == currentChapter.audioVersion) return
        val continuePlaying = PlaybackStateStore.playWhenReady || PlaybackStateStore.isPlaying
        currentChapter = chapter
        PlaybackStateStore.audioVersion = chapter.audioVersion
        PlaybackStateStore.playWhenReady = continuePlaying
        PlaybackStateStore.currentOrder = chapter.globalOrder
        PlaybackStateStore.positionMs = 0
        PlaybackStateStore.positionUpdatedElapsed = 0
        PlaybackStateStore.durationMs = 0
        PlaybackStateStore.isPlaying = false
        preferences.edit().putInt(KEY_LAST_ORDER, chapter.globalOrder)
            .putString(KEY_AUDIO_VERSION, chapter.audioVersion).apply()
        renderReader()
        sendPlayerAction(if (continuePlaying) AudioPlayerService.ACTION_PLAY_ORDER else AudioPlayerService.ACTION_SELECT_ORDER) {
            putExtra(AudioPlayerService.EXTRA_ORDER, chapter.globalOrder)
            putExtra(AudioPlayerService.EXTRA_AUDIO_VERSION, chapter.audioVersion)
            putExtra(AudioPlayerService.EXTRA_AUTO_PLAY, continuePlaying)
            putExtra(AudioPlayerService.EXTRA_START_FROM_BEGINNING, true)
        }
    }

    private fun showBookPicker() {
        var dialog: AlertDialog? = null
        val bookList = vertical().apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun addSection(title: String, sectionBooks: List<BibleBook>) {
            bookList.addView(TextView(this).apply {
                text = title
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = rounded(colorAccent, null, 8)
            }, matchWrap().apply {
                setMargins(dp(4), dp(10), dp(4), dp(5))
            })

            sectionBooks.forEach { book ->
                val active = book.code == currentChapter.bookCode
                bookList.addView(pickerChoice(book.name, active) {
                    val chapter = chapters.firstOrNull { it.bookCode == book.code && it.chapter == 1 }
                        ?: return@pickerChoice
                    dialog?.dismiss()
                    selectChapter(chapter)
                }, matchWrap())
                bookList.addView(View(this).apply {
                    setBackgroundColor(colorBorder)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1)
                ))
            }
        }

        addSection(uiText("Cựu Ước", "Old Testament"), books.filter { it.testament == "old" })
        addSection(uiText("Tân Ước", "New Testament"), books.filter { it.testament != "old" })

        dialog = AlertDialog.Builder(this)
            .setCustomTitle(TextView(this).apply {
                text = uiText("Chọn sách", "Choose book")
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorText)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(20), dp(20), dp(12))
            })
            .setView(ScrollView(this).apply { addView(bookList) })
            .setNegativeButton(uiText("Đóng", "Close"), null)
            .create()
        dialog.show()
    }

    private fun showChapterPicker(book: BibleBook) {
        var dialog: AlertDialog? = null
        val grid = GridLayout(this).apply {
            columnCount = 5
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        repeat(book.chapterCount) { index ->
            val chapterNumber = index + 1
            val active = book.code == currentChapter.bookCode &&
                chapterNumber == currentChapter.chapter
            val button = Button(this).apply {
                text = chapterNumber.toString()
                textSize = 14f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (active) Color.WHITE else colorText)
                background = if (active) {
                    rounded(colorAccent, null, 7)
                } else {
                    rounded(colorBackground, colorBorder, 7)
                }
                setOnClickListener {
                    val chapter = chapters.firstOrNull { it.bookCode == book.code && it.chapter == chapterNumber }
                        ?: return@setOnClickListener
                    dialog?.dismiss()
                    selectChapter(chapter)
                }
            }
            grid.addView(button, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }

        val content = vertical().apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(grid, matchWrap())
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(book.name)
            .setView(ScrollView(this).apply { addView(content) })
            .setNeutralButton(uiText("Tải sách", "Download book")) { _, _ ->
                enqueueDownloads(
                    chapters.filter { it.bookCode == book.code },
                    book.name
                )
            }
            .setNegativeButton(uiText("Đóng", "Close"), null)
            .show()
    }

    private fun showDownloadsDialog() {
        val content = vertical()
        fun refreshContent() {
            content.removeAllViews()
            content.addView(downloadsContent { refreshContent() }, matchWrap())
        }
        refreshContent()
        AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton(uiText("Đóng", "Close"), null)
            .show()
    }

    private fun downloadsContent(recordingChanged: () -> Unit): View {
        val chapters = this.chapters
        val currentChapter = this.currentChapter
        val summary = downloads.summary(chapters)
        val currentBookChapters = chapters.filter { it.bookCode == currentChapter.bookCode }
        val currentBookSummary = downloads.summary(currentBookChapters)
        val content = vertical().apply {
            setPadding(dp(20), dp(10), dp(20), dp(8))
        }
        content.addView(dialogTitle(uiText("Tải audio ${currentChapter.audioVersion}", "Audio downloads: ${currentChapter.audioVersion}")))
        if (selectedTextVersion == "NIV") {
            ENGLISH_AUDIO_VERSIONS.forEach { version ->
                content.addView(pickerChoice(audioRecordingName(version), version == currentChapter.audioVersion) {
                    selectAudioRecording(version)
                    recordingChanged()
                }.apply { tag = "downloadAudio:$version" }, matchWrap())
            }
        }
        content.addView(statusCard(
            uiText("${summary.downloaded} / ${chapters.size} chương", "${summary.downloaded} / ${chapters.size} chapters"),
            uiText("${formatBytes(summary.downloadedBytes)} đã lưu", "${formatBytes(summary.downloadedBytes)} saved") +
                if (summary.running + summary.queued > 0) {
                    uiText("  •  ${summary.running + summary.queued} đang chờ/tải", "  •  ${summary.running + summary.queued} queued/downloading")
                } else {
                    ""
                }
        ))
        content.addView(primaryButton(
            if (downloads.availableFile(currentChapter) != null) {
                uiText("Chương này đã tải", "Chapter downloaded")
            } else {
                uiText("Tải ${currentChapter.bookName} ${currentChapter.chapter}", "Download ${currentChapter.bookName} ${currentChapter.chapter}")
            }
        ) {
            enqueueDownloads(listOf(currentChapter), "${currentChapter.bookName} ${currentChapter.chapter}")
        })
        content.addView(outlinedButton(
            uiText("Tải sách ${currentChapter.bookName} ", "Download ${currentChapter.bookName} ") +
                "(${currentBookSummary.downloaded}/${currentBookChapters.size})"
        ) {
            enqueueDownloads(currentBookChapters, currentChapter.bookName)
        })
        content.addView(primaryButton(
            uiText("Tải toàn bộ Kinh Thánh (${summary.downloaded}/${chapters.size})", "Download entire Bible (${summary.downloaded}/${chapters.size})")
        ) {
            confirmFullDownload(chapters)
        })
        if (summary.running + summary.queued > 0) {
            content.addView(outlinedButton(uiText("Dừng các mục đang tải", "Cancel pending downloads")) {
                runBackgroundAction(
                    action = { downloads.cancelPending(chapters) },
                    message = { uiText("Đã dừng $it mục tải.", "Cancelled $it downloads.") }
                )
            })
        }
        content.addView(sectionLabel(uiText("Quản lý bộ nhớ", "Storage")))
        content.addView(textButton(uiText("Xóa audio của sách hiện tại", "Delete this book's audio")) {
            confirmDelete(currentBookChapters, currentChapter.bookName)
        })
        content.addView(textButton(uiText("Xóa toàn bộ audio ${currentChapter.audioVersion} đã tải", "Delete all downloaded ${currentChapter.audioVersion} audio")) {
            confirmDelete(chapters, uiText("toàn bộ ${currentChapter.audioVersion}", "all ${currentChapter.audioVersion} audio"))
        })
        return ScrollView(this).apply { addView(content) }
    }

    private fun confirmFullDownload(chapters: List<BibleChapter>) {
        val audioVersion = chapters.first().audioVersion
        val missing = chapters.count { downloads.availableFile(it) == null }
        if (missing == 0) {
            toast(uiText("Toàn bộ audio đã được tải.", "All audio is already downloaded."))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(uiText("Tải toàn bộ $audioVersion?", "Download all $audioVersion?"))
            .setMessage(
                uiText("Còn $missing chương. ", "$missing chapters remaining. ") + audioStorageNote(audioVersion) + " " +
                    uiText("Quá trình tải tiếp tục khi đóng ứng dụng.", "Downloads continue when the app is closed.")
            )
            .setPositiveButton(uiText("Bắt đầu tải", "Download")) { _, _ ->
                enqueueDownloads(chapters, uiText("toàn bộ Kinh Thánh", "the entire Bible"))
            }
            .setNegativeButton(uiText("Hủy", "Cancel"), null)
            .show()
    }

    private fun confirmDelete(items: List<BibleChapter>, label: String) {
        AlertDialog.Builder(this)
            .setTitle(uiText("Xóa audio?", "Delete audio?"))
            .setMessage(uiText("Các tệp của $label sẽ bị xóa khỏi thiết bị.", "Files for $label will be deleted from this device."))
            .setPositiveButton(uiText("Xóa", "Delete")) { _, _ ->
                runBackgroundAction(
                    action = { downloads.delete(items) },
                    message = { uiText("Đã xóa $it chương.", "Deleted $it chapters.") }
                )
            }
            .setNegativeButton(uiText("Hủy", "Cancel"), null)
            .show()
    }

    private fun enqueueDownloads(items: List<BibleChapter>, label: String) {
        runBackgroundAction(
            action = { downloads.enqueue(items) },
            message = {
                if (it == 0) uiText("$label đã có sẵn hoặc đang tải.", "$label is already available or downloading.")
                else uiText("Đã thêm $it chương của $label vào hàng đợi.", "Queued $it chapters from $label.")
            }
        )
    }

    private fun runBackgroundAction(action: () -> Int, message: (Int) -> String) {
        toast(uiText("Đang xử lý...", "Working..."))
        backgroundExecutor.execute {
            val result = action()
            handler.post {
                toast(message(result))
            }
        }
    }

    private fun showSpeedDialog() {
        val labels = speeds.map(::formatSpeed).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(uiText("Tốc độ phát", "Playback speed"))
            .setSingleChoiceItems(labels, speeds.indexOf(PlaybackStateStore.speed)) { dialog, index ->
                val speed = speeds[index]
                PlaybackStateStore.speed = speed
                preferences.edit().putFloat(KEY_SPEED, speed).apply()
                sendPlayerAction(AudioPlayerService.ACTION_SPEED) {
                    putExtra(AudioPlayerService.EXTRA_SPEED, speed)
                }
                dialog.dismiss()
                updatePlayerBar()
            }
            .show()
    }

    private fun showSleepTimerDialog() {
        val values = listOf(15, 30, 60, 90, 120, 240, 480)
        val content = vertical().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(colorMuted)
        }
        val countdown = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            tag = "sleepCountdown"
            setPadding(0, dp(6), 0, dp(16))
        }
        content.addView(status, matchWrap())
        content.addView(countdown, matchWrap())
        val grid = GridLayout(this).apply { columnCount = 3 }
        val buttons = values.associateWith { minutes ->
            Button(this).apply {
                text = when {
                    minutes < 120 -> uiText("$minutes phút", "$minutes min")
                    else -> uiText("${minutes / 60} giờ", "${minutes / 60} h")
                }
                isAllCaps = false
                textSize = 14f
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    sendPlayerAction(AudioPlayerService.ACTION_SLEEP) {
                        putExtra(AudioPlayerService.EXTRA_MINUTES, minutes)
                    }
                }
                grid.addView(this, GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(52)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
        }
        content.addView(grid, matchWrap())
        val dialog = AlertDialog.Builder(this)
            .setTitle(uiText("Hẹn giờ tắt", "Sleep timer"))
            .setView(content)
            .setNegativeButton(uiText("Tắt hẹn giờ", "Turn off")) { _, _ ->
                sendPlayerAction(AudioPlayerService.ACTION_SLEEP) { putExtra(AudioPlayerService.EXTRA_MINUTES, 0) }
            }
            .setPositiveButton(uiText("Đóng", "Close"), null)
            .create()
        val refresh = object : Runnable {
            override fun run() {
                timerDialogDeadline = PlaybackStateStore.sleepDeadlineElapsed
                val active = SleepTimer.remainingMs() > 0
                status.text = if (active) uiText("Còn lại", "Time remaining") else uiText("Chọn thời gian", "Choose a duration")
                countdown.text = if (active) SleepTimer.countdown(this@MainActivity) else uiText("Hẹn giờ đã tắt", "Timer off")
                countdown.textSize = if (active) 28f else 20f
                buttons.forEach { (minutes, button) ->
                    val selected = active && PlaybackStateStore.sleepMinutes == minutes
                    button.isSelected = selected
                    button.setTextColor(colorText)
                    button.background = rounded(if (selected) colorSelection else colorBackground, colorBorder)
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = active
                updatePlayerBar()
                if (active) handler.postDelayed(this, SleepTimer.nextDisplayDelayMs())
            }
        }
        timerDialogRefresh?.let { handler.removeCallbacks(it) }
        timerDialogRefresh = refresh
        dialog.setOnDismissListener {
            handler.removeCallbacks(refresh)
            if (timerDialogRefresh === refresh) timerDialogRefresh = null
        }
        dialog.show()
        refresh.run()
    }

    private fun showFontSizeDialog() {
        val sizes = floatArrayOf(16f, 18f, 20f, 22f, 24f, 27f)
        val labels = sizes.map { "${it.toInt()} pt" }.toTypedArray()
        val selected = sizes.indexOfFirst { it == fontSize }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(uiText("Cỡ chữ", "Font"))
            .setSingleChoiceItems(labels, selected) { _, index ->
                fontSize = sizes[index]
                preferences.edit().putFloat(KEY_FONT_SIZE, fontSize).apply()
                renderReader()
            }
            .setPositiveButton(uiText("Đóng", "Close"), null)
            .show()
    }

    private fun readerBookName(bookCode: String): String {
        return books.firstOrNull { it.code == bookCode }?.name ?: bookCode
    }

    private fun uiText(vietnamese: String, english: String): String =
        UiLanguage.text(this, vietnamese, english)

    private fun showTextVersionDialog() {
        var dialog: AlertDialog? = null
        val choices = vertical().apply {
            setPadding(dp(16), dp(8), dp(16), dp(4))
            textVersions.forEach { version ->
                addView(pickerChoice(version.code, version.code == selectedTextVersion) {
                    dialog?.dismiss()
                    selectTextVersion(version.code)
                }, matchWrap().apply { bottomMargin = dp(4) })
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(uiText("Chọn bản dịch", "Bible version"))
            .setView(choices)
            .setNegativeButton(uiText("Đóng", "Close"), null)
            .create()
        dialog.show()
    }

    private fun selectTextVersion(code: String) {
        if (code == selectedTextVersion) return
        selectedTextVersion = code
        books = repository.books(code)
        preferences.edit().putString(KEY_TEXT_VERSION, code).apply()
        val audioVersion = preferredAudioVersion()
        chapters = repository.chapters(audioVersion)
        if (audioVersion != currentChapter.audioVersion) {
            selectChapter(chapters.first { it.globalOrder == currentChapter.globalOrder })
        } else {
            renderReader()
        }
    }

    private fun preferredAudioVersion(): String = audioVersionForText(selectedTextVersion,
        preferences.getString(KEY_ENGLISH_AUDIO, "NIVUK") ?: "NIVUK")

    private fun selectAudioRecording(version: String) {
        if (version !in ENGLISH_AUDIO_VERSIONS || selectedTextVersion != "NIV") return
        preferences.edit().putString(KEY_ENGLISH_AUDIO, version).apply()
        if (currentChapter.audioVersion == version) return
        chapters = repository.chapters(version)
        selectChapter(chapters.first { it.globalOrder == currentChapter.globalOrder })
        refreshSettingsMenu()
    }

    private fun audioRecordingButton(): View = TextView(this).apply {
        text = getString(R.string.audio_recording_choice, audioRecordingName(currentChapter.audioVersion))
        textSize = 12f
        setTextColor(colorMuted)
        gravity = Gravity.CENTER
        minimumHeight = dp(40)
        contentDescription = uiText("Chọn bản thu audio", "Choose audio recording")
        setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(uiText("Bản thu audio", "Audio recording"))
                .setSingleChoiceItems(ENGLISH_AUDIO_VERSIONS.map(::audioRecordingName).toTypedArray(),
                    ENGLISH_AUDIO_VERSIONS.indexOf(currentChapter.audioVersion)) { dialog, index ->
                    selectAudioRecording(ENGLISH_AUDIO_VERSIONS[index])
                    dialog.dismiss()
                }
                .setNegativeButton(uiText("Đóng", "Close"), null)
                .show()
        }
    }

    private fun audioStorageNote(audioVersion: String = currentChapter.audioVersion): String = if (audioVersion == "VIE1925") {
        uiText("Tổng bộ audio cần khoảng 1,16 GB.", "Full audio download: about 1.16 GB.")
    } else {
        repository.audioSizeBytes(audioVersion)?.let {
            uiText("Tổng bộ audio cần khoảng ", "Full audio download: about ") + String.format(Locale.getDefault(), "%.2f GB.", it / 1_000_000_000.0)
        } ?: uiText("Dung lượng đã tải được hiển thị trong mục tải audio.", "Downloaded size is shown in audio downloads.")
    }

    private fun showAboutDialog() {
        val summary = downloads.summary(chapters)
        val textVersion = textVersions.first { it.code == selectedTextVersion }
        val content = vertical().apply {
            setPadding(dp(22), dp(4), dp(22), dp(16))
        }
        fun section(title: String, body: String, small: Boolean = false) {
            content.addView(sectionLabel(title).apply {
                textSize = 15f
                setTextColor(colorText)
            }, matchWrap())
            content.addView(TextView(this).apply {
                text = body
                textSize = if (small) 12f else 14f
                setTextColor(if (small) colorMuted else colorText)
                setLineSpacing(dp(3).toFloat(), 1.1f)
                setTextIsSelectable(true)
            }, matchWrap())
        }
        section(uiText("Ứng dụng", "The app"), uiText(
            "Ứng dụng bởi Zohan, với mong muốn nghe Kinh Thánh offline.",
            "By Zohan, for listening to the Bible offline."
        ))
        section(uiText("Nội dung", "Bible content"), uiText(
            "66 sách • 1.189 chương\nBản văn: ${textVersion.code}\nAudio: ${currentChapter.audioVersion}",
            "66 books • 1,189 chapters\nText: ${textVersion.code}\nAudio: ${currentChapter.audioVersion}"
        ))
        val storage = audioStorageNote()
        section(uiText("Audio offline", "Offline audio"), uiText(
            "${summary.downloaded}/${chapters.size} chương đã tải.\n$storage\n" +
                "Audio có thể nghe được online hoặc tải về máy để nghe offline.",
            "${summary.downloaded}/${chapters.size} chapters downloaded.\n$storage\n" +
                "Stream audio online or download it in the app to listen offline."
        ))
        val sources = buildString {
            append("${textVersion.title}\n${textVersion.copyrightNotice}\n${textVersion.sourceUrl}")
            if (currentChapter.audioVersion == "NIV") {
                append("\n\nThe Listener’s Bible®: NIV Edition. Audio © 2011 Max McLean. All rights reserved worldwide.")
                append("\nhttps://www.bible.com/audio-bible/111/GEN.1.NIV")
            } else if (currentChapter.audioVersion == "NIVUK") {
                append("\n\nNIVUK (Anglicised), read by David Suchet. Audio © 2013 Biblica, Inc. All rights reserved worldwide.")
                append("\nhttps://www.biblegateway.com/audio/suchet/nivuk/Gen.1")
                append("\nAudio: NIVUK; displayed text: NIV (2011), not the Anglicised text edition.")
            }
        }
        section(uiText("Nguồn và bản quyền", "Sources and copyright"), sources, small = true)
        AlertDialog.Builder(this)
            .setTitle(uiText("Giới thiệu ứng dụng", "About this app"))
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(uiText("Đóng", "Close"), null)
            .show()
    }

    private fun updatePlayerBar() {
        val displayedChapter = currentChapter
        val looping = loops.load(displayedChapter.audioVersion).enabled
        playerBookView?.text = "${readerBookName(displayedChapter.bookCode)}  ▾"
        playerChapterView?.text = "${displayedChapter.chapter}"
        compactPreviousView?.apply {
            isEnabled = looping || displayedChapter.globalOrder > 1
            alpha = if (isEnabled) 1f else 0.3f
        }
        compactNextView?.apply {
            isEnabled = looping || displayedChapter.globalOrder < chapters.size
            alpha = if (isEnabled) 1f else 0.3f
        }
        val offline = downloads.availableFile(displayedChapter) != null
        playerOfflineView?.text = if (offline) "Offline" else "Online"
        val position = PlaybackStateStore.displayPositionMs()
        playerPositionView?.text = formatTime(position)
        playerDurationView?.text = formatTime(PlaybackStateStore.durationMs)
        playerSpeedView?.text = formatSpeed(PlaybackStateStore.speed)
        val timerText = if (SleepTimer.remainingMs() > 0) SleepTimer.countdown(this) else uiText("Hẹn giờ", "Timer")
        if (playerTimerView?.text?.toString() != timerText) playerTimerView?.text = timerText
        playerTimerButton?.contentDescription = uiText("Hẹn giờ tắt", "Sleep timer")
        playerPlayButton?.setImageResource(
            if (PlaybackStateStore.isPlaying || PlaybackStateStore.playWhenReady) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        )
        playerProgressView?.takeUnless { isSeeking }?.let {
            it.max = max(PlaybackStateStore.durationMs, 1)
            it.progress = position.coerceIn(0, it.max)
        }
    }

    private fun togglePlayerPanel() {
        playerPanelVisible = !playerPanelVisible
        playerBarView?.visibility = if (playerPanelVisible) View.VISIBLE else View.GONE
        compactNavigationView?.visibility = if (playerPanelVisible) View.GONE else View.VISIBLE
        playerPanelToggleView?.contentDescription =
            if (playerPanelVisible) uiText("Ẩn bảng điều khiển", "Hide audio controls") else uiText("Hiện bảng điều khiển", "Show audio controls")
        preferences.edit()
            .putBoolean(KEY_PLAYER_PANEL_VISIBLE, playerPanelVisible)
            .apply()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val playingOrder = PlaybackStateStore.currentOrder
            val audioVersion = PlaybackStateStore.audioVersion
            if (playingOrder > 0 && (playingOrder != currentChapter.globalOrder || audioVersion != currentChapter.audioVersion)) {
                if (!audioMatchesText(selectedTextVersion, audioVersion)) {
                    selectedTextVersion = textVersionForAudio(audioVersion)
                    books = repository.books(selectedTextVersion)
                    preferences.edit().putString(KEY_TEXT_VERSION, selectedTextVersion).apply()
                }
                if (chapters.firstOrNull()?.audioVersion != audioVersion) chapters = repository.chapters(audioVersion)
                chapters.firstOrNull { it.globalOrder == playingOrder }?.let {
                    currentChapter = it
                    renderReader()
                }
            }
            updatePlayerBar()
            if (timerDialogDeadline != PlaybackStateStore.sleepDeadlineElapsed) {
                timerDialogRefresh?.let {
                    timerDialogDeadline = PlaybackStateStore.sleepDeadlineElapsed
                    handler.removeCallbacks(it)
                    handler.post(it)
                }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private fun sendPlayerAction(action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(this, AudioPlayerService::class.java)
            .setAction(action)
            .apply(configure)
        val needsForeground = action == AudioPlayerService.ACTION_PLAY_ORDER ||
            action == AudioPlayerService.ACTION_TOGGLE || action == AudioPlayerService.ACTION_RESUME
        if (needsForeground) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1925)
        }
    }

    private fun showToolbarMenu(anchor: View) {
        if (settingsPopup?.isShowing == true) return
        val content = vertical().apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
        settingsPopup = popup
        settingsPopupContent = content
        popup.setOnDismissListener {
            if (settingsPopup === popup) {
                settingsPopup = null
                settingsPopupContent = null
            }
        }
        refreshSettingsMenu()
        // The content container survives setContentView; the gear itself does not.
        val stableAnchor = findViewById<View>(android.R.id.content)
        val gearPosition = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val containerPosition = IntArray(2).also { stableAnchor.getLocationOnScreen(it) }
        val offset = gearPosition[1] + anchor.height - containerPosition[1] - stableAnchor.height - dp(4)
        popup.showAsDropDown(stableAnchor, -dp(6), offset, Gravity.END)
    }

    private fun refreshSettingsMenu() {
        val content = settingsPopupContent ?: return
        content.removeAllViews()
        content.apply {
            background = rounded(colorSurface, colorBorder, 8)
            addView(settingsMenuItem("Aa", uiText("Cỡ chữ", "Font")) { showFontSizeDialog() })
            addView(readerSettingsChoices {
                refreshReaderOptions()
                refreshSettingsMenu()
            })
            addView(settingsMenuItem("", uiText("Phát lặp", "Loop playback"), R.drawable.ic_repeat) {
                showLoopPlaybackDialog()
            }.apply {
                isSelected = loops.load(currentChapter.audioVersion).enabled
                if (isSelected) (this as LinearLayout).addView(TextView(this@MainActivity).apply {
                    text = uiText("Bật", "On")
                    textSize = 12f
                    setTextColor(colorMuted)
                    setPadding(dp(12), 0, 0, 0)
                })
            })
            addView(settingsMenuItem("", uiText("Kiểm tra cập nhật", "Check for updates"),
                R.drawable.ic_system_update) {
                appUpdates.check()
            })
            addView(settingsMenuItem("ⓘ", uiText("Giới thiệu", "About")) {
                showAboutDialog()
            })
        }
        settingsPopup?.takeIf { it.isShowing }?.update(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showLoopPlaybackDialog() {
        val version = currentChapter.audioVersion
        val loopChapters = chapters
        PlaybackLoopPresetsDialog(this, books, loopChapters, loops,
            canActivate = { version == currentChapter.audioVersion && version == PlaybackStateStore.audioVersion },
            onPlaybackChanged = onChanged@ { original, selection ->
                if (version != currentChapter.audioVersion || version != PlaybackStateStore.audioVersion) return@onChanged
                val playingOrder = PlaybackStateStore.currentOrder.takeIf { it > 0 } ?: currentChapter.globalOrder
                if (selection.enabled && selection != original && playingOrder != selection.orders.first()) {
                    loopChapters.firstOrNull { it.globalOrder == selection.orders.first() }?.let {
                        selectChapter(it, restart = it.globalOrder == currentChapter.globalOrder)
                    }
                } else updatePlayerBar()
                refreshSettingsMenu()
            }).show()
    }

    private fun applyReaderTheme() {
        darkMode = preferences.getString(KEY_THEME_MODE, "light") == "dark"
        setTheme(if (darkMode) R.style.AppTheme_Dark else R.style.AppTheme)
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars() {
        window.statusBarColor = colorSurface
        window.navigationBarColor = colorSurface
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (darkMode) 0 else mask, mask)
        } else {
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility =
                (window.decorView.systemUiVisibility and mask.inv()) or
                    (if (darkMode) 0 else mask)
        }
    }

    private fun refreshReaderOptions() {
        val scrollPosition = readerScrollView?.scrollY ?: 0
        applyReaderTheme()
        renderReader()
        readerScrollView?.post { readerScrollView?.scrollTo(0, scrollPosition) }
    }

    private fun readerSettingsChoices(changed: () -> Unit): View {
        return horizontal().apply {
            contentDescription = uiText("Ngôn ngữ và giao diện", "Language and appearance")
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            listOf("VN", "EN").forEachIndexed { index, label ->
                addView(TextView(this@MainActivity).apply {
                    text = label
                    contentDescription = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(colorText)
                    setTypeface(typeface, Typeface.BOLD)
                    isSelected = UiLanguage.isEnglish(this@MainActivity) == (index == 1)
                    background = rounded(if (isSelected) colorSelection else Color.TRANSPARENT, Color.TRANSPARENT, 6)
                    setOnClickListener {
                        UiLanguage.set(this@MainActivity, index == 1)
                        changed()
                    }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
            }
            addView(View(this@MainActivity).apply { setBackgroundColor(colorBorder) },
                LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                    leftMargin = dp(8)
                    rightMargin = dp(8)
                })
            listOf(R.drawable.ic_sun to uiText("Ngày", "Day"), R.drawable.ic_moon to uiText("Đêm", "Night"))
                .forEachIndexed { index, (icon, description) ->
                    addView(ImageButton(this@MainActivity).apply {
                        setImageResource(icon)
                        imageTintList = android.content.res.ColorStateList.valueOf(colorText)
                        scaleType = ImageView.ScaleType.CENTER
                        contentDescription = description
                        isSelected = darkMode == (index == 1)
                        background = rounded(if (isSelected) colorSelection else Color.TRANSPARENT, Color.TRANSPARENT, 6)
                        setOnClickListener {
                            preferences.edit().putString(KEY_THEME_MODE, if (index == 1) "dark" else "light").apply()
                            changed()
                        }
                    }, LinearLayout.LayoutParams(dp(44), dp(44)))
                }
            layoutParams = matchWrap()
        }
    }

    private fun speakerStatusButton(): View {
        return vertical().apply {
            gravity = Gravity.CENTER
            contentDescription = if (playerPanelVisible) uiText("Ẩn bảng điều khiển", "Hide audio controls") else uiText("Hiện bảng điều khiển", "Show audio controls")
            setOnClickListener { togglePlayerPanel() }
            playerPanelToggleView = this
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_speaker_simple)
                imageTintList = android.content.res.ColorStateList.valueOf(colorText)
                scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(25)
            ))
            addView(TextView(this@MainActivity).apply {
                textSize = 10f
                setTextColor(colorMuted)
                maxLines = 1
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                playerOfflineView = this
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(13)
            ))
        }
    }

    private fun downloadStatusButton(): View {
        return ImageView(this).apply {
            setImageResource(R.drawable.ic_download_bold)
            imageTintList = android.content.res.ColorStateList.valueOf(colorText)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = uiText("Tải xuống", "Downloads")
            setOnClickListener { showDownloadsDialog() }
        }
    }

    private fun settingsMenuItem(
        symbol: String,
        label: String,
        icon: Int? = null,
        action: () -> Unit
    ): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            contentDescription = label
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { action() }
            val symbolView = if (icon != null) ImageView(this@MainActivity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(colorText)
                scaleType = ImageView.ScaleType.CENTER
            } else TextView(this@MainActivity).apply {
                text = symbol
                textSize = if (symbol == "Aa") 17f else 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorText)
                gravity = Gravity.CENTER
            }
            symbolView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(symbolView, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                setTextColor(colorText)
                maxWidth = dp(200)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = matchWrap()
        }
    }

    private fun plainIconButton(icon: Int, description: String, action: (View) -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(colorText)
            scaleType = ImageView.ScaleType.CENTER
            background = null
            setPadding(0, 0, 0, 0)
            contentDescription = description
            setOnClickListener { action(it) }
        }
    }

    private fun selectorChip(text: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(colorBackground, colorBorder, 8)
            setOnClickListener { action() }
        }
    }

    private fun pickerChoice(label: String, selected: Boolean, action: () -> Unit): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isSelected = selected
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = if (selected) uiText("$label, đang chọn", "$label, selected") else label
            if (selected) background = rounded(colorSelection)
            setOnClickListener { action() }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = if (selected) 18f else 16f
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(colorText)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = if (selected) "✓" else ""
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorText)
                gravity = Gravity.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun mediaButton(
        icon: Int,
        description: String,
        primary: Boolean = false,
        large: Boolean = false,
        action: () -> Unit
    ): ImageButton {
        val size = if (large) 62 else 48
        return ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(if (primary) Color.WHITE else colorText)
            background = if (primary) {
                rounded(colorAccent, null, 100)
            } else {
                rounded(Color.TRANSPARENT, null, 100)
            }
            contentDescription = description
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
    }

    private fun primaryButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(colorAccent, null, 8)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply { setMargins(0, dp(5), 0, dp(5)) }
        }
    }

    private fun outlinedButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(colorText)
            gravity = Gravity.CENTER
            background = rounded(colorSurface, colorBorder, 8)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { setMargins(dp(3), dp(5), dp(3), dp(5)) }
        }
    }

    private fun textButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(colorEmphasis)
            background = rounded(Color.TRANSPARENT)
            setOnClickListener { action() }
        }
    }

    private fun timelineLabel(alignment: Int): TextView {
        return TextView(this).apply {
            textSize = 11f
            setTextColor(colorMuted)
            gravity = alignment or Gravity.CENTER_VERTICAL
        }
    }

    private fun chapterButton(
        previous: Boolean,
        action: () -> Unit
    ): ImageButton = plainIconButton(
        if (previous) R.drawable.ic_chapter_previous else R.drawable.ic_chapter_next,
        if (previous) uiText("Chương trước", "Previous chapter") else uiText("Chương sau", "Next chapter")
    ) { action() }

    private fun transportOptionButton(
        text: String,
        description: String,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            gravity = Gravity.CENTER
            background = rounded(colorBackground, colorBorder, 100)
            contentDescription = description
            setOnClickListener { action() }
        }
    }

    private fun dialogTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorMuted)
            setPadding(0, dp(18), 0, dp(5))
        }
    }

    private fun statusCard(title: String, detail: String): View {
        return vertical().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(colorBackground, colorBorder, 8)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorText)
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 13f
                setTextColor(colorMuted)
            })
        }
    }

    private fun rounded(
        fill: Int,
        stroke: Int? = null,
        radius: Int = 8
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun vertical() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = (milliseconds / 1_000).coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val megabytes = bytes / 1024.0 / 1024.0
        return if (megabytes >= 1024.0) {
            String.format(Locale.getDefault(), "%.2f GB", megabytes / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.0f MB", megabytes)
        }
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}x"
        } else {
            "${speed}x"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val STATE_PREFERENCES = "state"
        private const val KEY_LAST_ORDER = "lastOrder"
        private const val KEY_READER_ORDER = "readerOrder"
        private const val KEY_FONT_SIZE = "fontSize"
        private const val KEY_SPEED = "speed"
        private const val KEY_PLAYER_PANEL_VISIBLE = "playerPanelVisible"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_TEXT_VERSION = "textVersion"
        private const val KEY_AUDIO_VERSION = "audioVersion"
        private const val KEY_ENGLISH_AUDIO = "englishAudioVersion"
    }
}
