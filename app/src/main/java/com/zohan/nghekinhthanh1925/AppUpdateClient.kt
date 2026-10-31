package com.zohan.nghekinhthanh1925

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val minimumSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String
)

class UpdateSourceException(message: String) : IOException(message)

class AppUpdateClient(
    private val context: Context,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    private var releaseRepository = REPOSITORY
    fun currentVersion(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    fun latest(): AppRelease? {
        val repositoryJson = readJson(REPOSITORY_API_URL) ?: throw UpdateSourceException("Missing update repository")
        if (repositoryJson.optLong("id") != REPOSITORY_ID) throw UpdateSourceException("Update repository identity changed")
        val currentRepository = repositoryJson.optString("full_name")
        if (!currentRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            throw UpdateSourceException("Invalid update repository name")
        }
        val releaseJson = readJson(LATEST_RELEASE_URL, allowNotFound = true) ?: return null
        require(!releaseJson.optBoolean("draft") && !releaseJson.optBoolean("prerelease")) { "Not a stable release" }
        val assets = releaseJson.getJSONArray("assets")
        val manifestAsset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .singleOrNull { it.optString("name") == "update.json" }
            ?: throw IOException("Bản phát hành chưa có thông tin cập nhật.")
        val manifestUrl = manifestAsset.getString("browser_download_url")
        requireReleaseUrl(manifestUrl, currentRepository)
        val manifest = readJson(manifestUrl) ?: throw IOException("Thiếu thông tin cập nhật.")
        val release = parseManifest(manifest, context.packageName, currentRepository)
        require((0 until assets.length()).map { assets.getJSONObject(it) }.any {
            it.optString("browser_download_url") == release.apkUrl && it.optLong("size") == release.sizeBytes
        }) { "APK does not match the published release" }
        releaseRepository = currentRepository
        return release
    }

    fun download(
        release: AppRelease,
        cancelled: () -> Boolean,
        progress: (Int) -> Unit
    ): File {
        requireReleaseUrl(release.apkUrl, releaseRepository)
        val directory = File(context.cacheDir, "app-updates").apply {
            if (!mkdirs() && !isDirectory) throw IOException("Không thể tạo thư mục cập nhật.")
        }
        val target = File(directory, "update.apk")
        checkCancelled(cancelled)
        if (target.isFile && target.length() == release.sizeBytes && sha256(target) == release.sha256) {
            verifyArchive(target, release)
            return target
        }
        val temporary = File.createTempFile("pending-", ".apk", directory)
        try {
            val connection = openHttps(release.apkUrl)
            try {
                require(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
                val expectedLength = connection.contentLengthLong
                require(expectedLength <= 0 || expectedLength == release.sizeBytes) { "APK size changed" }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            checkCancelled(cancelled)
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= release.sizeBytes) { "APK exceeds advertised size" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val percent = (total * 100 / release.sizeBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                progress(percent)
                            }
                        }
                    }
                }
                require(total == release.sizeBytes && hex(digest.digest()) == release.sha256) { "APK checksum mismatch" }
            } finally {
                connection.disconnect()
            }
            verifyArchive(temporary, release)
            checkCancelled(cancelled)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
            }
            return target
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    fun verifyArchive(file: File, release: AppRelease) {
        require(file.length() == release.sizeBytes && sha256(file) == release.sha256) { "APK checksum mismatch" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Tệp tải về không phải APK hợp lệ.")
        verifyPackageInfo(archive, installed, release, Build.VERSION.SDK_INT)
    }

    private fun readJson(url: String, allowNotFound: Boolean = false): JSONObject? {
        val connection = openHttps(url)
        try {
            if (allowNotFound && connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (connection.responseCode == HttpURLConnection.HTTP_FORBIDDEN || connection.responseCode == 429) {
                throw IOException("GitHub đang giới hạn yêu cầu. Hãy thử lại sau.")
            }
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_048_576) { "Release metadata is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            if (url.protocol != "https" || DOWNLOAD_HOSTS.none { it.equals(url.host, ignoreCase = true) } ||
                url.userInfo != null || (url.port != -1 && url.port != 443)) {
                throw UpdateSourceException("Untrusted update host")
            }
            val connection = connectionFactory(url).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "NgheKinhThanh1925-Updater")
                setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "*/*")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val status = try {
                connection.responseCode
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (status !in listOf(301, 302, 303, 307, 308)) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank()) { "Missing redirect location" }
            url = URL(url, location)
        }
        throw IOException("Quá nhiều lần chuyển hướng khi tải cập nhật.")
    }

    companion object {
        const val REPOSITORY = "ZohanDam/Onl_Offline-Bible-Audio-Android-apk"
        const val REPOSITORY_ID = 1258404001L
        const val REPOSITORY_API_URL = "https://api.github.com/repositories/$REPOSITORY_ID"
        const val LATEST_RELEASE_URL = "$REPOSITORY_API_URL/releases/latest"
        const val LATEST_RELEASE_PAGE = "https://github.com/$REPOSITORY/releases/latest"
        private val DOWNLOAD_HOSTS = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

        fun parseManifest(json: JSONObject, packageName: String, repository: String = REPOSITORY): AppRelease {
            require(json.getString("packageName") == packageName) { "Wrong application package" }
            val release = AppRelease(
                json.getLong("versionCode"), json.getString("versionName"), json.getInt("minimumSdk"),
                json.getString("apkUrl"), json.getString("sha256").lowercase(), json.getLong("sizeBytes"),
                json.optString("notes").take(6000)
            )
            require(release.versionCode > 0 && release.versionName.length in 1..80 && release.minimumSdk >= 26) { "Invalid release version" }
            require(release.sizeBytes in 1..209_715_200L && release.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid APK digest or size" }
            requireReleaseUrl(release.apkUrl, repository)
            return release
        }

        @Suppress("DEPRECATION")
        fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

        @Suppress("DEPRECATION")
        internal fun verifyPackageInfo(archive: PackageInfo, installed: PackageInfo, release: AppRelease, sdk: Int) {
            require(archive.packageName == installed.packageName && versionCode(archive) == release.versionCode &&
                archive.versionName == release.versionName && release.versionCode > versionCode(installed)) { "Wrong APK package or version" }
            require(release.minimumSdk <= sdk && (archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= sdk) { "APK requires a newer Android version" }
            fun signatures(info: PackageInfo): Set<String> {
                val entries = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
                return entries.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
            }
            val expected = signatures(installed)
            require(expected.isNotEmpty() && signatures(archive) == expected) { "APK signing key does not match this installation" }
        }

        internal fun requireReleaseUrl(address: String, repository: String = REPOSITORY) {
            val url = try { URL(address) } catch (_: java.net.MalformedURLException) {
                throw UpdateSourceException("Invalid update link")
            }
            val prefix = "/$repository/releases/download/"
            val path = url.path
            val repositoryPath = "/$repository/"
            val valid = url.protocol == "https" && url.host.equals("github.com", ignoreCase = true) && url.userInfo == null &&
                (url.port == -1 || url.port == 443) && path.startsWith(repositoryPath, ignoreCase = true) &&
                path.substring(repositoryPath.length).startsWith("releases/download/") &&
                path.drop(prefix.length).split('/').let { it.size == 2 && it.all(String::isNotEmpty) } &&
                url.query == null && url.ref == null && !path.contains("..") && !path.contains('%') && !path.contains('\\')
            if (!valid) throw UpdateSourceException("The update link does not belong to the verified repository")
        }

        private fun checkCancelled(cancelled: () -> Boolean) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
        }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
