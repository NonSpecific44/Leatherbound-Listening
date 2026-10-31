package com.leatherbound.listening.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.leatherbound.listening.data.remote.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Checks the GitHub repository's latest release and installs its APK. The repository is private,
 * so this needs a read-only GitHub token (entered in Settings); without one there's no check.
 */
object Updates {
    const val REPO = "NonSpecific44/Leatherbound-Listening"
    /** Where to make the token: a fine-grained token with read-only Contents on this one repository. */
    const val TOKEN_PAGE = "https://github.com/settings/personal-access-tokens/new"

    @Serializable private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )
    @Serializable private data class GhAsset(val name: String, val url: String, val size: Long = 0)

    data class Release(val version: String, val notes: String, val apkUrl: String, val bytes: Long)

    private fun request(url: String, token: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")

    /** The latest published release with a phone APK, or null if there's none. Throws if GitHub can't be reached or refuses the token. */
    suspend fun latest(token: String): Release? {
        val body = try {
            Http.send(request("https://api.github.com/repos/$REPO/releases/latest", token).header("Accept", "application/vnd.github+json").build())
        } catch (e: IOException) {
            throw IOException(
                when {
                    e.message?.contains("HTTP 401") == true -> "GitHub didn't accept the token. It may have expired."
                    e.message?.contains("HTTP 404") == true -> "The token can't see the repository. Give it read access to Contents on $REPO."
                    else -> "Couldn't reach GitHub (${e.message})"
                }, e,
            )
        }
        val release: GhRelease = Http.json.decodeFromString(body)
        if (release.draft || release.prerelease) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("Wear", ignoreCase = true) } ?: return null
        return Release(release.tag.removePrefix("v"), release.body.orEmpty().trim(), apk.url, apk.size)
    }

    /** True if [candidate] (e.g. "2.3.0") is a later version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /**
     * Downloads the release APK into the cache. GitHub answers with a redirect to its file store;
     * OkHttp follows it and drops the token, which the file store mustn't receive.
     */
    suspend fun download(context: Context, release: Release, token: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "LeatherboundListening-${release.version}.apk")
        Http.client.newCall(request(release.apkUrl, token).header("Accept", "application/octet-stream").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.bytes
            var read = 0L
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        target
    }

    /** Android asks once per app before it may install updates. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the APK to Android's installer, which asks you to confirm the update. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
