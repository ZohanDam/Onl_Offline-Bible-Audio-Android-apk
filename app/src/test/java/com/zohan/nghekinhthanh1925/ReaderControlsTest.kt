package com.zohan.nghekinhthanh1925

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.Switch
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderControlsTest {
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("state", Context.MODE_PRIVATE)

    @Before fun reset() {
        preferences.edit().clear().commit()
        RuntimeEnvironment.getApplication().getSharedPreferences(PlaybackLoopStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("audio_downloads_v2", Context.MODE_PRIVATE).edit().clear().commit()
        PlaybackStateStore.currentOrder = 0
        PlaybackStateStore.audioVersion = "VIE1925"
        PlaybackStateStore.playWhenReady = false
        PlaybackStateStore.isPlaying = false
        PlaybackStateStore.positionMs = 0
        PlaybackStateStore.positionUpdatedElapsed = 0
        PlaybackStateStore.durationMs = 0
        PlaybackStateStore.speed = 1f
        PlaybackStateStore.sleepDeadlineElapsed = 0
        PlaybackStateStore.sleepMinutes = 0
    }

    private fun withReader(test: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            test(controller.setup().get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun views(root: View): List<View> = buildList {
        if (root.visibility != View.VISIBLE) return@buildList
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
        }
    }

    private fun root(activity: MainActivity): View = activity.findViewById(android.R.id.content)

    private fun control(activity: MainActivity, description: String): View =
        views(root(activity)).single { it.contentDescription == description }

    private fun selectedBook(theme: String) {
        preferences.edit().putString("themeMode", theme).commit()
        withReader { activity ->
            control(activity, "Chọn sách").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val rows = views(dialog.window!!.decorView)
            val selected = rows.single { it.isSelected }
            val labels = views(selected).filterIsInstance<TextView>()
            assertNotNull("Selected book must have a background highlight", selected.background)
            assertEquals("✓", labels.last().text.toString())
            val label = labels.first()
            assertEquals(Typeface.BOLD, label.typeface.style and Typeface.BOLD)
            val otherBook = rows.filterIsInstance<LinearLayout>()
                .first { it.hasOnClickListeners() && !it.isSelected }
            assertTrue(labels.first().textSize > (otherBook.getChildAt(0) as TextView).textSize)
            assertNotEquals(Color.rgb(190, 40, 45), labels.first().currentTextColor)
            assertTrue(selected.contentDescription.toString().endsWith("đang chọn"))
            dialog.dismiss()
        }
    }

    @Test fun selectedBookHasNeutralHighlightBoldTextAndCheckmark() = selectedBook("light")

    @Test fun selectedBookIsDistinctInDarkMode() = selectedBook("dark")

    @Test fun hiddenPlayerKeepsSmallArrowsAndPausedNavigation() = withReader { activity ->
        control(activity, "Ẩn bảng điều khiển").performClick()
        assertFalse(views(root(activity)).any { it.contentDescription == "Phát hoặc tạm dừng" })
        assertChapterIcons(activity)
        assertFalse(control(activity, "Chương trước").isEnabled)
        control(activity, "Chương sau").performClick()
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertEquals(0, PlaybackStateStore.positionMs)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertFalse(PlaybackStateStore.isPlaying)
        assertEquals("2", (control(activity, "Chọn chương") as TextView).text.toString())
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AudioPlayerService.ACTION_SELECT_ORDER, intent.action)
        assertFalse(intent.getBooleanExtra(AudioPlayerService.EXTRA_AUTO_PLAY, true))
        control(activity, "Chương trước").performClick()
        assertEquals(1, PlaybackStateStore.currentOrder)
        assertFalse(PlaybackStateStore.playWhenReady)
        control(activity, "Hiện bảng điều khiển").performClick()
        assertEquals(1, views(root(activity)).count { it.contentDescription == "Chương sau" })
        assertNotNull(control(activity, "Phát hoặc tạm dừng"))
    }

    @Test fun hiddenPlayerNavigationContinuesOnlyExistingPlayback() {
        PlaybackStateStore.currentOrder = 2
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        PlaybackStateStore.positionMs = 25_000
        withReader { activity ->
            control(activity, "Ẩn bảng điều khiển").performClick()
            control(activity, "Chương sau").performClick()
            assertEquals(3, PlaybackStateStore.currentOrder)
            assertEquals(0, PlaybackStateStore.positionMs)
            assertTrue(PlaybackStateStore.playWhenReady)
            val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
            assertEquals(AudioPlayerService.ACTION_PLAY_ORDER, intent.action)
            assertTrue(intent.getBooleanExtra(AudioPlayerService.EXTRA_START_FROM_BEGINNING, false))
            assertNotNull(control(activity, "Hiện bảng điều khiển"))
        }
    }

    @Test fun savedHiddenPlayerRestoresNavigationAndRespectsLastChapter() {
        preferences.edit().putBoolean("playerPanelVisible", false).putInt("lastOrder", 1189).commit()
        withReader { activity ->
            assertNotNull(control(activity, "Hiện bảng điều khiển"))
            assertFalse(control(activity, "Chương sau").isEnabled)
            assertTrue(control(activity, "Chương trước").isEnabled)
            control(activity, "Chương sau").performClick()
            assertEquals(1189, PlaybackStateStore.currentOrder)
            control(activity, "Chương trước").performClick()
            assertEquals(1188, PlaybackStateStore.currentOrder)
            assertFalse(preferences.getBoolean("playerPanelVisible", true))
        }
    }

    @Test fun compactVersionPickerSelectsNivWithoutStartingPlayback() {
        preferences.edit().putInt("lastOrder", 929).putBoolean("playerPanelVisible", false).commit()
        withReader { activity ->
            assertNull(control(activity, "Chọn bản dịch").background)
            control(activity, "Chọn bản dịch").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val choices = views(dialog.window!!.decorView)
                .filter { it.contentDescription?.toString()?.substringBefore(',') in
                    listOf("VIE1925", "VIE2010", "NIV") }
            assertEquals(3, choices.size)
            assertEquals("VIE1925, đang chọn", choices.single { it.isSelected }.contentDescription)
            choices.single { it.contentDescription == "NIV" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertEquals("NIV", preferences.getString("textVersion", null))
            assertEquals("NIVUK", PlaybackStateStore.audioVersion)
            assertEquals(929, PlaybackStateStore.currentOrder)
            assertFalse(PlaybackStateStore.playWhenReady)
            val chapter = (control(activity, "Chọn chương") as TextView).text
            assertTrue(views(root(activity)).filterIsInstance<TextView>().any { it.text.toString() == "Chapter $chapter" })
            assertNotNull(control(activity, "Hiện bảng điều khiển"))
            control(activity, "Chọn bản dịch").performClick()
            val reopened = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("NIV, đang chọn", views(reopened.window!!.decorView)
                .single { it.isSelected }.contentDescription)
            reopened.dismiss()
        }
    }

    @Test fun vie2010SelectionKeepsChapterAndExistingVietnamesePlayback() {
        PlaybackStateStore.currentOrder = 20
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        PlaybackStateStore.positionMs = 5_000
        withReader { activity ->
            control(activity, "Chọn bản dịch").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            views(dialog.window!!.decorView).single { it.contentDescription == "VIE2010" }.performClick()
            assertEquals("VIE2010", preferences.getString("textVersion", null))
            assertEquals("VIE1925", PlaybackStateStore.audioVersion)
            assertEquals(20, PlaybackStateStore.currentOrder)
            assertEquals(5_000, PlaybackStateStore.positionMs)
            assertTrue(PlaybackStateStore.playWhenReady)
            assertTrue(PlaybackStateStore.isPlaying)
        }
    }

    private fun assertChapterIcons(activity: MainActivity) {
        for (description in listOf("Chương trước", "Chương sau")) {
            val button = control(activity, description) as ImageButton
            assertNull("Chapter arrows must remain borderless", button.background)
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f,
                activity.resources.displayMetrics).toInt(), button.drawable.intrinsicWidth)
        }
    }

    @Test fun bothPlayerStatesHaveLargerChapterIcons() = withReader { activity ->
        assertChapterIcons(activity)
        control(activity, "Ẩn bảng điều khiển").performClick()
        assertChapterIcons(activity)
    }

    @Test fun collapseButtonHidesPlayerWithoutStoppingAudio() {
        PlaybackStateStore.currentOrder = 2
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        PlaybackStateStore.positionMs = 5_000
        withReader { activity ->
            control(activity, "Thu gọn bảng điều khiển").performClick()
            assertFalse(views(root(activity)).any { it.contentDescription == "Phát hoặc tạm dừng" })
            assertNotNull(control(activity, "Hiện bảng điều khiển"))
            assertChapterIcons(activity)
            assertTrue(PlaybackStateStore.isPlaying)
            assertTrue(PlaybackStateStore.playWhenReady)
            assertEquals(5_000, PlaybackStateStore.positionMs)
            assertFalse(preferences.getBoolean("playerPanelVisible", true))
            control(activity, "Hiện bảng điều khiển").performClick()
            assertNotNull(control(activity, "Thu gọn bảng điều khiển"))
        }
    }

    private fun menuRows(activity: MainActivity, description: String): Pair<android.widget.PopupWindow, List<LinearLayout>> {
        assertTrue(control(activity, description) is ImageButton)
        control(activity, description).performClick()
        val popup = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        val container = popup.contentView as LinearLayout
        val rows = (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<LinearLayout>()
        return popup to rows
    }

    @Test fun gearMenuHasLabeledOptionsAndStructuredAboutLast() = withReader { activity ->
        val (popup, rows) = menuRows(activity, "Cài đặt")
        val labels = listOf("Cỡ chữ", "Ngôn ngữ và giao diện", "Phát lặp", "Kiểm tra cập nhật", "Giới thiệu")
        assertEquals(labels, rows.map { it.contentDescription.toString() })
        listOf(0, 2, 3, 4).forEach { index ->
            val row = rows[index]
            val label = labels[index]
            assertTrue(views(row).filterIsInstance<TextView>().any { it.text.toString() == label })
        }
        assertEquals(listOf("VN", "EN"), views(rows[1]).filterIsInstance<TextView>().map { it.text.toString() })
        assertEquals(listOf("Ngày", "Đêm"), views(rows[1]).filterIsInstance<ImageButton>().map { it.contentDescription.toString() })
        assertTrue(views(rows[1]).single { it.contentDescription == "VN" }.isSelected)
        assertTrue("Update must use a distinct vector icon", rows[3].getChildAt(0) is ImageView)
        rows.last().performClick()
        assertTrue(popup.isShowing)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val texts = views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("Giới thiệu ứng dụng"))
        val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
        assertTrue(texts.any { it.contains("Phiên bản ${installed.versionName} (${AppUpdateClient.versionCode(installed)})") })
        val sections = listOf("Ứng dụng", "Nội dung", "Audio offline", "Nguồn và bản quyền")
        assertTrue(sections.all { texts.contains(it) })
        assertEquals(sections.map { texts.indexOf(it) }.sorted(), sections.map { texts.indexOf(it) })
        assertTrue(texts.any { it.contains("bible.com") })
        dialog.dismiss()
        assertTrue(popup.isShowing)
    }

    @Test fun nivUsesEnglishChapterHeadingAndSettingsLabels() {
        preferences.edit().putString("textVersion", "NIV").putString("englishAudioVersion", "NIV").putInt("lastOrder", 2).commit()
        withReader { activity ->
            val texts = views(root(activity)).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.contains("Chapter 2"))
            assertFalse(texts.contains("Chương 2"))
            val (_, rows) = menuRows(activity, "Settings")
            assertEquals(listOf("Font", "Language and appearance", "Loop playback", "Check for updates", "About"),
                rows.map { it.contentDescription.toString() })
            rows.last().performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val about = views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(about.contains("About this app"))
            val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
            assertTrue(about.any { it.contains("Version ${installed.versionName} (${AppUpdateClient.versionCode(installed)})") })
            assertTrue(about.contains("Sources and copyright"))
            assertTrue(about.any { it.contains("Max McLean") })
            assertTrue(about.any { it.contains("Biblica") })
            dialog.dismiss()
        }
    }

    @Test fun vietnameseEditionKeepsVietnameseChapterHeading() {
        preferences.edit().putString("textVersion", "VIE2010").putInt("lastOrder", 2).commit()
        withReader { activity ->
            val texts = views(root(activity)).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.contains("Chương 2"))
            assertFalse(texts.contains("Chapter 2"))
        }
    }

    @Test fun nivukIsFirstDefaultAudioWhileTextStaysNivAndChooserRemembersNiv() {
        preferences.edit().putString("textVersion", "NIV").putInt("lastOrder", 929).commit()
        withReader { activity ->
            assertEquals("NIVUK", PlaybackStateStore.audioVersion)
            assertEquals("NIV", preferences.getString("textVersion", null))
            assertTrue(views(root(activity)).filterIsInstance<TextView>().any { it.text.toString().contains("David Suchet") })
            control(activity, "Choose audio recording").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("NIVUK · David Suchet", dialog.listView.adapter.getItem(0))
            assertEquals("NIV · Max McLean", dialog.listView.adapter.getItem(1))
            assertEquals(0, dialog.listView.checkedItemPosition)
            shadowOf(dialog).clickOnItem(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("NIV", PlaybackStateStore.audioVersion)
            assertEquals("NIV", preferences.getString("englishAudioVersion", null))
            assertEquals(929, PlaybackStateStore.currentOrder)
            assertFalse(PlaybackStateStore.playWhenReady)
            control(activity, "Next chapter").performClick()
            assertEquals("NIV", PlaybackStateStore.audioVersion)
        }
        withReader { activity ->
            assertEquals("NIV", PlaybackStateStore.audioVersion)
            control(activity, "Choose audio recording").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(1, dialog.listView.checkedItemPosition)
            dialog.dismiss()
        }
    }

    @Test fun downloadRecordingOptionsStayOrderedAndVisibleWhileSwitching() {
        preferences.edit().putString("textVersion", "NIV").putInt("lastOrder", 3).commit()
        withReader { activity ->
            control(activity, "Downloads").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            fun choices() = views(dialog.window!!.decorView).filter { it.tag?.toString()?.startsWith("downloadAudio:") == true }
            assertEquals(listOf("downloadAudio:NIVUK", "downloadAudio:NIV"), choices().map { it.tag })
            assertTrue(choices().first().isSelected)
            choices()[1].performClick()
            assertTrue(dialog.isShowing)
            assertTrue(choices()[1].isSelected)
            assertEquals("NIV", PlaybackStateStore.audioVersion)
            PlaybackStateStore.positionMs = 25_000
            choices()[0].performClick()
            assertTrue(dialog.isShowing)
            assertTrue(choices()[0].isSelected)
            assertEquals("NIVUK", PlaybackStateStore.audioVersion)
            assertEquals(0, PlaybackStateStore.positionMs)
            assertEquals(3, PlaybackStateStore.currentOrder)
            assertFalse(PlaybackStateStore.playWhenReady)
            assertTrue(views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("NIVUK") })
            dialog.dismiss()
        }
    }

    @Test fun activeNivIsNotInterruptedOnOpeningReaderButSwitchingToNivukKeepsPlaying() {
        preferences.edit().putString("textVersion", "NIV").commit()
        PlaybackStateStore.audioVersion = "NIV"
        PlaybackStateStore.currentOrder = 3
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        PlaybackStateStore.positionMs = 20_000
        withReader { activity ->
            assertEquals("NIV", PlaybackStateStore.audioVersion)
            assertEquals(20_000, PlaybackStateStore.positionMs)
            control(activity, "Choose audio recording").performClick()
            shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("NIVUK", PlaybackStateStore.audioVersion)
            assertTrue(PlaybackStateStore.playWhenReady)
            assertEquals(0, PlaybackStateStore.positionMs)
            assertEquals(3, PlaybackStateStore.currentOrder)
            val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
            assertEquals(AudioPlayerService.ACTION_PLAY_ORDER, intent.action)
        }
    }

    @Test fun nivukPlaybackTickerNeverSelectsANonexistentTextEdition() {
        preferences.edit().putString("textVersion", "NIV").commit()
        withReader { activity ->
            PlaybackStateStore.audioVersion = "NIVUK"
            PlaybackStateStore.currentOrder = 4
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals("NIV", preferences.getString("textVersion", null))
            assertTrue(views(root(activity)).filterIsInstance<TextView>().any { it.text.toString() == "Chapter 4" })
            val (_, rows) = menuRows(activity, "Settings")
            rows.last().performClick()
            val about = ShadowAlertDialog.getLatestAlertDialog()
            val labels = views(about.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(labels.any { it.contains("David Suchet") && it.contains("2013") })
            about.dismiss()
        }
    }

    @Test fun languageButtonsChangeOnlyInterfaceAndRememberTheChoice() = withReader { activity ->
        val (popup, rows) = menuRows(activity, "Cài đặt")
        views(rows[1]).single { it.contentDescription == "EN" }.performClick()
        assertTrue(popup.isShowing)
        assertTrue(UiLanguage.isEnglish(activity))
        assertEquals("en", preferences.getString("uiLanguage", null))
        assertNotNull(control(activity, "Choose book"))
        assertEquals("VIE1925", preferences.getString("textVersion", "VIE1925"))
        assertFalse(PlaybackStateStore.playWhenReady)
        val (englishPopup, englishRows) = menuRows(activity, "Settings")
        assertSame(popup, englishPopup)
        assertEquals("Font", englishRows.first().contentDescription)
        assertTrue(views(englishRows[1]).single { it.contentDescription == "EN" }.isSelected)
        views(englishRows[1]).single { it.contentDescription == "VN" }.performClick()
        assertTrue(popup.isShowing)
        assertNotNull(control(activity, "Chọn sách"))
        assertEquals("vi", preferences.getString("uiLanguage", null))
    }

    @Test fun dayNightButtonsApplyImmediatelyWithoutAnotherDialogOrAudioRestart() {
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        withReader { activity ->
            val (popup, rows) = menuRows(activity, "Cài đặt")
            views(rows[1]).single { it.contentDescription == "Đêm" }.performClick()
            assertTrue(popup.isShowing)
            assertEquals("dark", preferences.getString("themeMode", null))
            assertTrue(PlaybackStateStore.isPlaying)
            val (darkPopup, darkRows) = menuRows(activity, "Cài đặt")
            assertSame(popup, darkPopup)
            assertTrue(views(darkRows[1]).single { it.contentDescription == "Đêm" }.isSelected)
            assertFalse(views(darkRows[1]).filterIsInstance<TextView>().any { it.text.toString().contains("hệ thống") })
            views(darkRows[1]).single { it.contentDescription == "Ngày" }.performClick()
            assertTrue(popup.isShowing)
            assertEquals("light", preferences.getString("themeMode", null))
            assertTrue(PlaybackStateStore.playWhenReady)
        }
    }

    @Test fun compactPlayerHasAnExpandButton() = withReader { activity ->
        control(activity, "Thu gọn bảng điều khiển").performClick()
        control(activity, "Mở rộng bảng điều khiển").performClick()
        assertNotNull(control(activity, "Thu gọn bảng điều khiển"))
        assertNotNull(control(activity, "Phát hoặc tạm dừng"))
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    @Test fun fontChooserPreviewsMultipleSizesWithoutClosingItOrTheSettingsMenu() = withReader { activity ->
        val (popup, rows) = menuRows(activity, "Cài đặt")
        rows.first().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(popup.isShowing)
        for ((index, size) in listOf(0 to 16f, 1 to 18f, 3 to 22f)) {
            shadowOf(dialog).clickOnItem(index)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dialog.isShowing)
            assertTrue(popup.isShowing)
            assertEquals(size, preferences.getFloat("fontSize", 0f))
            assertFalse(PlaybackStateStore.playWhenReady)
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertTrue(popup.isShowing)
        (popup.contentView as LinearLayout).getChildAt(0).performClick()
        val reopened = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(3, reopened.listView.checkedItemPosition)
        reopened.dismiss()
    }

    @Test fun settingsCanStillBeDismissedOutsideAndOpenedAgain() = withReader { activity ->
        val (popup, _) = menuRows(activity, "Cài đặt")
        assertTrue(popup.isOutsideTouchable)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
        try { popup.contentView.rootView.dispatchTouchEvent(event) } finally { event.recycle() }
        assertFalse(popup.isShowing)
        val (reopened, _) = menuRows(activity, "Cài đặt")
        assertTrue(reopened.isShowing)
        assertNotSame(popup, reopened)
    }

    @Test fun destroyingReaderClosesTheSettingsWindow() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val (popup, _) = menuRows(controller.get(), "Cài đặt")
        controller.pause().stop().destroy()
        assertFalse(popup.isShowing)
    }

    @Test fun applyingLoopKeepsSettingsOpenAndRefreshesItsStatus() = withReader { activity ->
        val (popup, rows) = menuRows(activity, "Cài đặt")
        rows[2].performClick()
        val manager = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(popup.isShowing)
        manager.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        val editor = ShadowAlertDialog.getLatestAlertDialog()
        (tagged(editor, "loopName") as android.widget.EditText).setText("Evening")
        tagged(editor, "loopChapters:GEN").performClick()
        val chapters = ShadowAlertDialog.getLatestAlertDialog()
        tagged(chapters, "loopChapter:3").performClick()
        chapters.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(3, PlaybackStateStore.currentOrder)
        assertTrue(popup.isShowing)
        assertTrue((popup.contentView as LinearLayout).getChildAt(2).isSelected)
        manager.dismiss()
        assertTrue(popup.isShowing)
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    private fun loopDialog(activity: MainActivity): android.app.AlertDialog {
        val (_, rows) = menuRows(activity, "Cài đặt")
        rows.single { it.contentDescription == "Phát lặp" }.performClick()
        val manager = ShadowAlertDialog.getLatestAlertDialog()
        manager.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        return ShadowAlertDialog.getLatestAlertDialog().also {
            (tagged(it, "loopName") as android.widget.EditText).setText("Evening")
        }
    }

    private fun tagged(dialog: android.app.AlertDialog, tag: String): View =
        views(dialog.window!!.decorView).single { it.tag == tag }

    private fun loopManager(activity: MainActivity): android.app.AlertDialog {
        val (_, rows) = menuRows(activity, "Cài đặt")
        rows.single { it.contentDescription == "Phát lặp" }.performClick()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    @Test fun savedLoopListSwitchesBetweenNamedPresetsWithoutAutoplayAndOffRetainsThem() = withReader { activity ->
        val store = PlaybackLoopStore(activity)
        val first = store.savePreset("VIE1925", "Evening", listOf(3, 1))
        val second = store.savePreset("VIE1925", "Morning", listOf(51, 2))
        val manager = loopManager(activity)
        tagged(manager, "loopPreset:${first.id}").performClick()
        assertEquals(3, PlaybackStateStore.currentOrder)
        tagged(manager, "loopPreset:${second.id}").performClick()
        assertEquals(51, PlaybackStateStore.currentOrder)
        assertEquals(second.id, store.selectedPresetId("VIE1925"))
        assertFalse(PlaybackStateStore.playWhenReady)
        tagged(manager, "loopOff").performClick()
        assertFalse(store.load("VIE1925").enabled)
        assertEquals(2, store.presets("VIE1925").size)
        assertEquals(51, PlaybackStateStore.currentOrder)
        manager.dismiss()
    }

    @Test fun loopEditorRejectsBlankOrDuplicateNameAndKeepsDraftUntilSaved() = withReader { activity ->
        val store = PlaybackLoopStore(activity)
        store.savePreset("VIE1925", "Evening", listOf(1))
        val editor = loopDialog(activity)
        tagged(editor, "loopBook:EXO").performClick()
        val name = tagged(editor, "loopName") as android.widget.EditText
        name.setText(" ")
        editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(editor.isShowing)
        assertNotNull(name.error)
        name.setText(" evening ")
        editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(editor.isShowing)
        assertEquals(1, store.presets("VIE1925").size)
        name.setText("Morning")
        editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(editor.isShowing)
        assertEquals(listOf("Evening", "Morning"), store.presets("VIE1925").map { it.name })
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    @Test fun renamingAndDeletingActiveLoopPreserveAudioStateAndOtherPresets() = withReader { activity ->
        val store = PlaybackLoopStore(activity)
        val active = store.savePreset("VIE1925", "Evening", listOf(1, 3))
        val other = store.savePreset("VIE1925", "Morning", listOf(2))
        store.activatePreset("VIE1925", active.id)
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        PlaybackStateStore.positionMs = 17_000
        val manager = loopManager(activity)
        tagged(manager, "loopOptions:${active.id}").performClick()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(1)
        val rename = ShadowAlertDialog.getLatestAlertDialog()
        (tagged(rename, "loopRename") as android.widget.EditText).setText("Night reading")
        rename.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Night reading", store.presets("VIE1925").first().name)
        assertEquals(active.id, store.selectedPresetId("VIE1925"))
        assertEquals(17_000, PlaybackStateStore.positionMs)
        assertTrue(PlaybackStateStore.playWhenReady)
        tagged(manager, "loopOptions:${active.id}").performClick()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(2)
        val confirm = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(store.load("VIE1925").enabled)
        confirm.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(store.load("VIE1925").enabled)
        assertEquals(listOf(other), store.presets("VIE1925"))
        assertTrue(PlaybackStateStore.playWhenReady)
        assertEquals(17_000, PlaybackStateStore.positionMs)
        manager.dismiss()
    }

    @Test fun editingInactivePresetDoesNotActivateItOrInterruptCurrentLoop() = withReader { activity ->
        val store = PlaybackLoopStore(activity)
        val active = store.savePreset("VIE1925", "Evening", listOf(1, 3))
        val other = store.savePreset("VIE1925", "Morning", listOf(2))
        store.activatePreset("VIE1925", active.id)
        PlaybackStateStore.positionMs = 17_000
        val manager = loopManager(activity)
        tagged(manager, "loopOptions:${other.id}").performClick()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0)
        val editor = ShadowAlertDialog.getLatestAlertDialog()
        assertFalse(views(editor.window!!.decorView).filterIsInstance<Switch>().single().isChecked)
        tagged(editor, "loopBook:EXO").performClick()
        editor.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(active.id, store.selectedPresetId("VIE1925"))
        assertEquals(PlaybackLoopSelection(true, listOf(1, 3)), store.load("VIE1925"))
        assertEquals(listOf(2) + (51..90).toList(), store.presets("VIE1925").last().orders)
        assertEquals(17_000, PlaybackStateStore.positionMs)
        assertFalse(PlaybackStateStore.playWhenReady)
        manager.dismiss()
    }

    @Test fun savingUnchangedActivePresetDoesNotRestartCurrentChapter() = withReader { activity ->
        val store = PlaybackLoopStore(activity)
        val preset = store.savePreset("VIE1925", "Evening", listOf(3, 1))
        store.activatePreset("VIE1925", preset.id)
        PlaybackStateStore.positionMs = 17_000
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        val manager = loopManager(activity)
        tagged(manager, "loopOptions:${preset.id}").performClick()
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0)
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, PlaybackStateStore.currentOrder)
        assertEquals(17_000, PlaybackStateStore.positionMs)
        assertTrue(PlaybackStateStore.playWhenReady)
        manager.dismiss()
    }

    @Test fun loopChaptersKeepTapOrderAndApplyWithoutStartingPausedAudio() = withReader { activity ->
        val dialog = loopDialog(activity)
        tagged(dialog, "loopChapters:GEN").performClick()
        val chapters = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(5, views(chapters.window!!.decorView).filterIsInstance<GridLayout>().single().columnCount)
        val buttons = views(chapters.window!!.decorView).filterIsInstance<Button>()
            .filter { it.tag?.toString()?.startsWith("loopChapter:") == true }
        assertEquals((1..50).map { it.toString() }, buttons.map { it.text.toString() })
        tagged(chapters, "loopChapter:3").performClick()
        tagged(chapters, "loopChapter:1").performClick()
        assertTrue(tagged(chapters, "loopChapter:3").isSelected)
        assertFalse(PlaybackLoopStore(activity).load("VIE1925").enabled)
        chapters.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(PlaybackLoopSelection(true, listOf(3, 1)), PlaybackLoopStore(activity).load("VIE1925"))
        assertEquals(3, PlaybackStateStore.currentOrder)
        assertFalse(PlaybackStateStore.playWhenReady)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AudioPlayerService.ACTION_SELECT_ORDER, intent.action)
        assertFalse(intent.getBooleanExtra(AudioPlayerService.EXTRA_AUTO_PLAY, true))
    }

    @Test fun loopWholeBooksAppendInChosenOrderAndCancelDoesNotSave() = withReader { activity ->
        val dialog = loopDialog(activity)
        (tagged(dialog, "loopBook:EXO") as CheckBox).performClick()
        (tagged(dialog, "loopBook:GEN") as CheckBox).performClick()
        assertEquals("50/50 ▾", (tagged(dialog, "loopChapters:GEN") as Button).text.toString())
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(PlaybackLoopStore(activity).load("VIE1925").orders.isEmpty())
        val reopened = loopDialog(activity)
        tagged(reopened, "loopBook:EXO").performClick()
        tagged(reopened, "loopBook:GEN").performClick()
        reopened.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals((51..90).toList() + (1..50).toList(), PlaybackLoopStore(activity).load("VIE1925").orders)
        assertEquals("Evening", PlaybackLoopStore(activity).presets("VIE1925").single().name)
        assertEquals(51, PlaybackStateStore.currentOrder)
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    @Test fun emptyLoopCannotBeAppliedAsEnabled() = withReader { activity ->
        val dialog = loopDialog(activity)
        val toggle = views(dialog.window!!.decorView).filterIsInstance<Switch>().single()
        assertTrue(toggle.isChecked)
        assertFalse(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled)
        toggle.performClick()
        assertFalse(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.dismiss()
    }

    @Test fun applyingLoopUsesActualPlayerOrderWhenDisplayedChapterHasNotCaughtUp() = withReader { activity ->
        val dialog = loopDialog(activity)
        tagged(dialog, "loopChapters:GEN").performClick()
        val chapters = ShadowAlertDialog.getLatestAlertDialog()
        tagged(chapters, "loopChapter:1").performClick()
        tagged(chapters, "loopChapter:3").performClick()
        chapters.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        PlaybackStateStore.currentOrder = 2
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, PlaybackStateStore.currentOrder)
        assertTrue(PlaybackStateStore.playWhenReady)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AudioPlayerService.ACTION_PLAY_ORDER, intent.action)
        assertEquals(1, intent.getIntExtra(AudioPlayerService.EXTRA_ORDER, -1))
    }

    @Test fun collapsedLoopArrowsWrapAtBibleBoundariesWithoutAutoplay() {
        preferences.edit().putInt("lastOrder", 1189).putBoolean("playerPanelVisible", false).commit()
        PlaybackLoopStore(RuntimeEnvironment.getApplication())
            .save("VIE1925", PlaybackLoopSelection(true, listOf(1189, 1)))
        withReader { activity ->
            assertTrue(control(activity, "Chương sau").isEnabled)
            control(activity, "Chương sau").performClick()
            assertEquals(1, PlaybackStateStore.currentOrder)
            assertTrue(control(activity, "Chương trước").isEnabled)
            control(activity, "Chương trước").performClick()
            assertEquals(1189, PlaybackStateStore.currentOrder)
            assertTrue(PlaybackLoopStore(activity).load("VIE1925").enabled)
            assertFalse(PlaybackStateStore.playWhenReady)
        }
    }

    @Test fun singleChapterLoopArrowRestartsPositionWithoutAutoplay() {
        PlaybackLoopStore(RuntimeEnvironment.getApplication()).save("VIE1925", PlaybackLoopSelection(true, listOf(1)))
        withReader { activity ->
            PlaybackStateStore.positionMs = 20_000
            control(activity, "Chương sau").performClick()
            assertEquals(1, PlaybackStateStore.currentOrder)
            assertEquals(0, PlaybackStateStore.positionMs)
            assertFalse(PlaybackStateStore.playWhenReady)
            assertTrue(PlaybackLoopStore(activity).load("VIE1925").enabled)
        }
    }

    @Test fun manualChapterPickerOutsideLoopTurnsLoopOffAndRetainsChoices() {
        PlaybackLoopStore(RuntimeEnvironment.getApplication()).save("VIE1925", PlaybackLoopSelection(true, listOf(1, 2)))
        withReader { activity ->
            control(activity, "Chọn chương").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            views(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.toString() == "3" }.performClick()
            assertEquals(PlaybackLoopSelection(false, listOf(1, 2)), PlaybackLoopStore(activity).load("VIE1925"))
            assertEquals(3, PlaybackStateStore.currentOrder)
            assertFalse(PlaybackStateStore.playWhenReady)
        }
    }

    @Test fun timerCountdownUpdatesInWholeMinutesInPlayerAndDialog() {
        SleepTimer.start(RuntimeEnvironment.getApplication(), 15)
        withReader { activity ->
            assertTrue(views(root(activity)).filterIsInstance<TextView>().any { it.text.toString() == "15 ph" })
            control(activity, "Hẹn giờ tắt").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val countdown = views(dialog.window!!.decorView).single { it.tag == "sleepCountdown" } as TextView
            assertEquals("15 ph", countdown.text.toString())
            val choices = views(dialog.window!!.decorView).filterIsInstance<Button>()
            assertTrue(choices.any { it.text.toString() == "4 giờ" })
            assertTrue(choices.any { it.text.toString() == "8 giờ" })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals("15 ph", countdown.text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            shadowOf(Looper.getMainLooper()).idle()
            val remaining = SleepTimer.countdown(activity)
            assertEquals("14 ph", remaining)
            assertEquals(remaining, countdown.text.toString())
            assertTrue(views(root(activity)).filterIsInstance<TextView>().any { it.text.toString() == remaining })
            SleepTimer.start(activity, 30)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals("30 ph", countdown.text.toString())
            assertTrue(choices.single { it.text.toString() == "30 phút" }.isSelected)
            dialog.dismiss()
            val stoppedText = countdown.text.toString()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            assertEquals("Dismissed dialog must stop ticking", stoppedText, countdown.text.toString())
        }
    }

    @Test fun timerDialogStopsRefreshingOffScreenAndCatchesUpOnResume() {
        SleepTimer.start(RuntimeEnvironment.getApplication(), 15)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            control(activity, "Hẹn giờ tắt").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val countdown = views(dialog.window!!.decorView).single { it.tag == "sleepCountdown" } as TextView
            controller.pause()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
            assertEquals("15 ph", countdown.text.toString())
            controller.resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SleepTimer.countdown(activity), countdown.text.toString())
            assertTrue(SleepTimer.remainingMs() < 900_000)
            dialog.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
