package io.github.umbraproxy

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

internal enum class ApkVariant(val id: String) {
    ARM64("arm64-v8a"), ARMV7("armeabi-v7a"), UNIVERSAL("universal");

    companion object {
        fun fromLibraries(abis: Set<String>): ApkVariant? = when {
            "arm64-v8a" in abis && "armeabi-v7a" in abis -> UNIVERSAL
            "arm64-v8a" in abis -> ARM64
            "armeabi-v7a" in abis -> ARMV7
            else -> null
        }
    }
}

internal data class ReleaseAsset(val name: String, val url: String)
internal data class AppRelease(val tag: String, val asset: ReleaseAsset?, val isNewer: Boolean)
internal sealed interface UpdateResult {
    data class Success(val release: AppRelease) : UpdateResult
    data object SignInRequired : UpdateResult
    data object Failed : UpdateResult
}

internal object AppUpdates {
    private const val REPOSITORY = "https://github.com/RHineIx/UmbraProxy"
    const val RELEASES_PAGE = "$REPOSITORY/releases/latest"
    private const val API = "https://api.github.com/repos/RHineIx/UmbraProxy/releases/latest"

    fun installedVariant(context: Context): ApkVariant {
        val abis = mutableSetOf<String>()
        val application = context.applicationInfo
        val apks = listOf(application.sourceDir) + application.splitSourceDirs.orEmpty()
        for (apk in apks) {
            try {
                ZipFile(apk).use { zip ->
                    for (variant in listOf(ApkVariant.ARM64, ApkVariant.ARMV7)) {
                        if (zip.getEntry("lib/${variant.id}/libumbraproxy.so") != null) abis += variant.id
                    }
                }
            } catch (_: IOException) {
                // Fall back to the installed process ABI if package metadata cannot be read.
            }
        }
        return ApkVariant.fromLibraries(abis) ?: if (Process.is64Bit()) ApkVariant.ARM64 else ApkVariant.ARMV7
    }

    fun latestDownload(variant: ApkVariant): String = "$RELEASES_PAGE/download/UmbraProxy-${variant.id}.apk"

    suspend fun check(variant: ApkVariant, installedVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(API).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "UmbraProxy/$installedVersion")
                when (connection.responseCode) {
                    401, 404 -> UpdateResult.SignInRequired
                    200 -> {
                        val bytes = ByteArray(1_048_577)
                        var length = 0
                        connection.inputStream.use { input ->
                            while (length < bytes.size) {
                                val count = input.read(bytes, length, bytes.size - length)
                                if (count == -1) break
                                length += count
                            }
                        }
                        if (length > 1_048_576) return@withContext UpdateResult.Failed
                        val json = JSONObject(String(bytes, 0, length, Charsets.UTF_8))
                        val tag = json.getString("tag_name")
                        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext UpdateResult.Failed
                        val newer = isNewerVersion(tag, installedVersion) ?: return@withContext UpdateResult.Failed
                        val assets = json.getJSONArray("assets")
                        val candidates = (0 until assets.length()).map { index ->
                            val item = assets.getJSONObject(index)
                            ReleaseAsset(item.getString("name"), item.getString("browser_download_url"))
                        }
                        UpdateResult.Success(AppRelease(tag, selectAsset(candidates, tag, variant), newer))
                    }
                    else -> UpdateResult.Failed
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            UpdateResult.Failed
        } catch (_: org.json.JSONException) {
            UpdateResult.Failed
        }
    }

    fun selectAsset(assets: List<ReleaseAsset>, tag: String, variant: ApkVariant): ReleaseAsset? {
        val variants = listOf(variant, ApkVariant.UNIVERSAL).distinct()
        for (candidate in variants) {
            for (name in listOf("UmbraProxy-${candidate.id}.apk", "UmbraProxy-$tag-${candidate.id}.apk")) {
                assets.firstOrNull {
                    it.name == name && it.url.startsWith("$REPOSITORY/releases/download/") && it.url.endsWith("/$name")
                }?.let { return it }
            }
        }
        return null
    }

    fun enqueueDownload(context: Context, asset: ReleaseAsset): Boolean {
        // Older Android versions delegate to the browser, which owns storage permissions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val request = DownloadManager.Request(Uri.parse(asset.url))
                .setTitle(asset.name)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "${asset.name.removeSuffix(".apk")}-${System.currentTimeMillis()}.apk"
                )
            val manager = context.getSystemService(DownloadManager::class.java) ?: return false
            manager.enqueue(request)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }
}

private data class ReleaseVersion(val numbers: List<Long>, val prerelease: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for ((left, right) in numbers.zip(other.numbers)) {
            left.compareTo(right).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        for ((left, right) in prerelease.zip(other.prerelease)) {
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            val order = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (order != 0) return order
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$").matchEntire(value) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (prerelease.any { it.isEmpty() || (it.all(Char::isDigit) && (it.length > 1 && it.startsWith('0') || it.toLongOrNull() == null)) }) return null
            return ReleaseVersion(numbers, prerelease)
        }
    }
}

internal fun isNewerVersion(remote: String, installed: String): Boolean? {
    val next = ReleaseVersion.parse(remote) ?: return null
    val current = ReleaseVersion.parse(installed) ?: return null
    return next > current
}
