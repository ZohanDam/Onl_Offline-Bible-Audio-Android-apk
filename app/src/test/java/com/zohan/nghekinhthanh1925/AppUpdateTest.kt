package com.zohan.nghekinhthanh1925

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
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
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AppUpdateTest {
    private lateinit var context: Context
    private val bytes = "test APK payload".toByteArray()
    private val assetUrl = "https://github.com/${AppUpdateClient.REPOSITORY}/releases/download/v2.19/app.apk"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, "app-updates").deleteRecursively()
        shadowOf(context.packageManager).installPackage(info(20))
    }

    private fun info(version: Int, signature: String = "01020304"): PackageInfo = PackageInfo().apply {
        packageName = context.packageName
        @Suppress("DEPRECATION")
        versionCode = version
        versionName = if (version == 20) "2.18" else "2.19"
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 26; packageName = context.packageName }
        signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(signature))) }
    }

    private fun manifest(): JSONObject = JSONObject()
        .put("packageName", context.packageName).put("versionCode", 21).put("versionName", "2.19")
        .put("minimumSdk", 26).put("apkUrl", assetUrl).put("sizeBytes", bytes.size)
        .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        .put("notes", "Update test")

    private fun release(): AppRelease = AppUpdateClient.parseManifest(manifest(), context.packageName)

    private fun repository(name: String = AppUpdateClient.REPOSITORY): JSONObject = JSONObject()
        .put("id", AppUpdateClient.REPOSITORY_ID).put("full_name", name)

    private class Response(url: URL, private val body: ByteArray, private val status: Int = 200,
                           private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getContentLengthLong(): Long = body.size.toLong()
    }

    @Test fun manifestRejectsWrongPackageAndUntrustedHosts() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateClient.parseManifest(manifest().put("packageName", "other.app"), context.packageName)
        }
        for (url in listOf("http://github.com/file.apk", "https://example.com/file.apk",
            "https://github.com/other/repository/releases/download/v2/app.apk")) {
            assertThrows(UpdateSourceException::class.java) {
                AppUpdateClient.parseManifest(manifest().put("apkUrl", url), context.packageName)
            }
        }
    }

    @Test fun updaterUsesRenamedRepositoryWithoutRelaxingUrlValidation() {
        assertEquals("ZohanDam/Onl_Offline-Bible-Audio-Android-apk", AppUpdateClient.REPOSITORY)
        assertEquals("https://api.github.com/repositories/1258404001/releases/latest",
            AppUpdateClient.LATEST_RELEASE_URL)
        assertEquals(assetUrl, release().apkUrl)
        assertThrows(UpdateSourceException::class.java) {
            AppUpdateClient.parseManifest(manifest().put("apkUrl",
                "https://github.com/ZohanDam/BibleAudioVIE1934-Android-Apk/releases/download/v2.24/app.apk"), context.packageName)
        }
    }

    @Test fun manifestRejectsInvalidSizeAndDigest() {
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateClient.parseManifest(manifest().put("sha256", "wrong"), context.packageName)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateClient.parseManifest(manifest().put("sizeBytes", 0), context.packageName)
        }
    }

    @Test fun signingKeyVersionAndAndroidRequirementsAreEnforced() {
        val installed = info(20)
        AppUpdateClient.verifyPackageInfo(info(21), installed, release(), 34)
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateClient.verifyPackageInfo(info(21, "deadbeef"), installed, release(), 34)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateClient.verifyPackageInfo(info(21), info(21), release(), 34)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateClient.verifyPackageInfo(info(21).apply { applicationInfo!!.minSdkVersion = 35 }, installed, release(), 34)
        }
    }

    @Test fun successfulDownloadIsVerifiedThenExposedAsReadOnlyApk() {
        val client = AppUpdateClient(context) { url ->
            val pending = File(context.cacheDir, "app-updates").listFiles()!!.single { it.name.startsWith("pending-") }
            shadowOf(context.packageManager).setPackageArchiveInfo(pending.absolutePath, info(21))
            Response(url, bytes)
        }
        val output = client.download(release(), { false }, {})
        assertArrayEquals(bytes, output.readBytes())
        assertEquals("update.apk", output.name)
        val provider = Robolectric.buildContentProvider(UpdateApkProvider::class.java).create().get()
        provider.openFile(UpdateApkProvider.uri(context), "r").use { assertEquals(bytes.size.toLong(), it.statSize) }
        assertThrows(SecurityException::class.java) { provider.openFile(UpdateApkProvider.uri(context), "rw") }
        assertThrows(java.io.FileNotFoundException::class.java) {
            provider.openFile(Uri.parse("content://${context.packageName}.updates/../bible.db"), "r")
        }
    }

    @Test fun corruptedDownloadNeverReplacesPreviouslyVerifiedFile() {
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val previous = File(directory, "update.apk").apply { writeText("previous") }
        val client = AppUpdateClient(context) { Response(it, "corrupted data!!".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { client.download(release(), { false }, {}) }
        assertEquals("previous", previous.readText())
        assertFalse(directory.listFiles()!!.any { it.name.startsWith("pending-") })
    }

    @Test fun cancelledDownloadDoesNotCreateInstallableApk() {
        val client = AppUpdateClient(context) { Response(it, bytes) }
        assertThrows(CancellationException::class.java) { client.download(release(), { true }, {}) }
        assertFalse(File(context.cacheDir, "app-updates/update.apk").exists())
    }

    @Test fun redirectsCannotLeaveTrustedHttpsHosts() {
        val client = AppUpdateClient(context) { Response(it, byteArrayOf(), 302, mapOf("Location" to "http://example.com/update.json")) }
        assertThrows(UpdateSourceException::class.java) { client.latest() }
    }

    @Test fun noPublishedReleaseDoesNotClaimVersionIsCurrent() {
        assertNull(AppUpdateClient(context) {
            if (it.toString() == AppUpdateClient.REPOSITORY_API_URL) Response(it, repository().toString().toByteArray())
            else Response(it, byteArrayOf(), 404)
        }.latest())
    }

    @Test fun latestReleaseAndManifestMustReferenceSameAsset() {
        val metaUrl = "https://github.com/${AppUpdateClient.REPOSITORY}/releases/download/v2.19/update.json"
        val apiJson = JSONObject().put("assets", JSONArray()
            .put(JSONObject().put("name", "update.json").put("browser_download_url", metaUrl))
            .put(JSONObject().put("name", "app.apk").put("browser_download_url", assetUrl).put("size", bytes.size)))
        val client = AppUpdateClient(context) { url ->
            Response(url, when (url.toString()) {
                AppUpdateClient.REPOSITORY_API_URL -> repository()
                AppUpdateClient.LATEST_RELEASE_URL -> apiJson
                else -> manifest()
            }.toString().toByteArray())
        }
        assertEquals(21L, client.latest()!!.versionCode)
        apiJson.getJSONArray("assets").remove(1)
        assertThrows(IllegalArgumentException::class.java) { client.latest() }
    }

    @Test fun verifiedRepositoryRenameSupportsManifestAndVerifiedDownload() {
        val renamed = "ZohanDam/Bible-Audio-Renamed"
        val metaUrl = "https://github.com/$renamed/releases/download/v2.19/update.json"
        val apkUrl = "https://github.com/$renamed/releases/download/v2.19/app.apk"
        val api = JSONObject().put("assets", JSONArray()
            .put(JSONObject().put("name", "update.json").put("browser_download_url", metaUrl))
            .put(JSONObject().put("name", "app.apk").put("browser_download_url", apkUrl).put("size", bytes.size)))
        val requested = mutableListOf<String>()
        val client = AppUpdateClient(context) { url ->
            requested.add(url.toString())
            when (url.toString()) {
                AppUpdateClient.REPOSITORY_API_URL -> Response(url, repository(renamed).toString().toByteArray())
                AppUpdateClient.LATEST_RELEASE_URL -> Response(url, api.toString().toByteArray())
                metaUrl -> Response(url, manifest().put("apkUrl", apkUrl).toString().toByteArray())
                apkUrl -> {
                    val pending = File(context.cacheDir, "app-updates").listFiles()!!.single { it.name.startsWith("pending-") }
                    shadowOf(context.packageManager).setPackageArchiveInfo(pending.absolutePath, info(21))
                    Response(url, bytes)
                }
                else -> throw AssertionError("Unexpected URL: $url")
            }
        }
        val release = client.latest()!!
        assertEquals(apkUrl, release.apkUrl)
        assertArrayEquals(bytes, client.download(release, { false }, {}).readBytes())
        assertEquals(listOf(AppUpdateClient.REPOSITORY_API_URL, AppUpdateClient.LATEST_RELEASE_URL, metaUrl, apkUrl), requested)
    }

    @Test fun wrongRepositoryIdentityStopsBeforeReleaseLookup() {
        val requested = mutableListOf<String>()
        val client = AppUpdateClient(context) { url ->
            requested.add(url.toString())
            Response(url, repository().put("id", 999).toString().toByteArray())
        }
        assertThrows(UpdateSourceException::class.java) { client.latest() }
        assertEquals(listOf(AppUpdateClient.REPOSITORY_API_URL), requested)
    }

    @Test fun externalManifestIsRejectedBeforeFetchingIt() {
        val requested = mutableListOf<String>()
        val client = AppUpdateClient(context) { url ->
            requested.add(url.toString())
            val json = if (url.toString() == AppUpdateClient.REPOSITORY_API_URL) repository() else
                JSONObject().put("assets", JSONArray().put(JSONObject().put("name", "update.json")
                    .put("browser_download_url", "https://github.com/other/repo/releases/download/v2/update.json")))
            Response(url, json.toString().toByteArray())
        }
        assertThrows(UpdateSourceException::class.java) { client.latest() }
        assertEquals(listOf(AppUpdateClient.REPOSITORY_API_URL, AppUpdateClient.LATEST_RELEASE_URL), requested)
    }

    @Test fun releaseUrlAllowsRepositoryCasingButRejectsAmbiguousPaths() {
        AppUpdateClient.requireReleaseUrl(assetUrl.replace(AppUpdateClient.REPOSITORY, AppUpdateClient.REPOSITORY.lowercase())
            .replace("github.com", "GITHUB.COM"))
        val prefix = "https://github.com/${AppUpdateClient.REPOSITORY}/releases/download/"
        for (url in listOf("not a URL", "$prefix/v2/app.apk", "${prefix}v2/app.apk/extra",
            "${prefix}../app.apk", "${prefix}v2/%61pp.apk", "${prefix}v2/app.apk?redirect=1",
            "${prefix}v2/app.apk#fragment", assetUrl.replace("github.com/", "user@github.com/"),
            assetUrl.replace("github.com/", "github.com:444/"), assetUrl.replace("releases/download", "RELEASES/download"),
            assetUrl.replace("/releases/", "-other/releases/"), "${prefix}v2/..\\app.apk")) {
            assertThrows(url, UpdateSourceException::class.java) { AppUpdateClient.requireReleaseUrl(url) }
        }
    }

    @Test fun rejectedSourceOffersOfficialRecoveryWithoutDownloadingOrInstalling() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val client = AppUpdateClient(context) { url ->
            Response(url, repository().put("id", 999).toString().toByteArray())
        }
        val controller = AppUpdateController(activity.get(), client, ImmediateExecutor())
        try {
            controller.check()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(File(context.cacheDir, "app-updates/update.apk").exists())
            assertNull(shadowOf(activity.get()).nextStartedActivity)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val browser = shadowOf(activity.get()).nextStartedActivity
            assertNotNull("Recovery must open the official download page", browser)
            assertEquals(Intent.ACTION_VIEW, browser.action)
            assertEquals(AppUpdateClient.LATEST_RELEASE_PAGE, browser.data.toString())
            assertNull(browser.type)
        } finally {
            controller.close()
            activity.pause().stop().destroy()
        }
    }

    private class ImmediateExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
    }

    private fun verifyInstallFlow(permissionGranted: Boolean) {
        val metaUrl = "https://github.com/${AppUpdateClient.REPOSITORY}/releases/download/v2.19/update.json"
        val api = JSONObject().put("assets", JSONArray()
            .put(JSONObject().put("name", "update.json").put("browser_download_url", metaUrl))
            .put(JSONObject().put("name", "app.apk").put("browser_download_url", assetUrl).put("size", bytes.size)))
        val client = AppUpdateClient(context) { url ->
            when (url.toString()) {
                AppUpdateClient.REPOSITORY_API_URL -> Response(url, repository().toString().toByteArray())
                AppUpdateClient.LATEST_RELEASE_URL -> Response(url, api.toString().toByteArray())
                metaUrl -> Response(url, manifest().toString().toByteArray())
                else -> {
                    val directory = File(context.cacheDir, "app-updates")
                    val pending = directory.listFiles()!!.single { it.name.startsWith("pending-") }
                    shadowOf(context.packageManager).setPackageArchiveInfo(pending.absolutePath, info(21))
                    shadowOf(context.packageManager).setPackageArchiveInfo(File(directory, "update.apk").absolutePath, info(21))
                    Response(url, bytes)
                }
            }
        }
        shadowOf(context.packageManager).setCanRequestPackageInstalls(permissionGranted)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val controller = AppUpdateController(activity.get(), client, ImmediateExecutor())
        try {
            controller.check()
            shadowOf(Looper.getMainLooper()).idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            if (!permissionGranted) {
                ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val settings = shadowOf(activity.get()).nextStartedActivity
                assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
                shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
                controller.onResume()
                shadowOf(Looper.getMainLooper()).idle()
            }
            val install = shadowOf(activity.get()).nextStartedActivity
            assertNotNull("Installer must be launched only after verification", install)
            assertEquals(Intent.ACTION_VIEW, install.action)
            assertEquals(UpdateApkProvider.uri(context), install.data)
            assertEquals(UpdateApkProvider.APK_MIME, install.type)
            assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            controller.close()
            activity.pause().stop().destroy()
        }
    }

    @Test fun verifiedUpdateLaunchesAndroidInstaller() = verifyInstallFlow(true)
    @Test fun installPermissionReturnsToVerifiedInstallerFlow() = verifyInstallFlow(false)
}
