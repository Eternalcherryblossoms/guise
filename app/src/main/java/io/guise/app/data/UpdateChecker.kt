package io.guise.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Project identity, in one place.
 *
 * Kept as constants rather than scattered string literals because the About screen, the update
 * check and the release tooling all have to agree on them.
 */
object Project {
    const val GITHUB_OWNER = "Eternalcherryblossoms"
    const val GITHUB_REPO = "guise"
    const val GITHUB_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val RELEASES_URL = "$GITHUB_URL/releases"
    const val ISSUES_URL = "$GITHUB_URL/issues"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
}

data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
    /** Direct download for the signed APK asset, when the release carries one. */
    val apkUrl: String?,
    val notes: String?,
) {
    /** True when [latestVersion] is strictly newer than [currentVersion]. */
    fun isNewerThan(currentVersion: String): Boolean =
        UpdateChecker.compareVersions(latestVersion, currentVersion) > 0
}

/**
 * Checks GitHub Releases for a newer build.
 *
 * There is no server and no push channel here, and that is a deliberate limit rather than an
 * omission. True push on Android means Firebase Cloud Messaging, which means Google Play
 * services, an account, and a permanent identifier tied to the device -- all of it out of place
 * in a module whose entire purpose is to hand out fewer identifiers. Every open-source Android
 * project solves this the same way: the client asks the releases endpoint.
 *
 * ## What this sends
 *
 * One unauthenticated HTTPS GET to `api.github.com`. No device identifier, no account, no
 * query string, no cookies. The request is indistinguishable from a browser visiting the
 * releases page, and the About screen says so, because an app that quietly phones home would
 * be a poor look for this one.
 *
 * ## Failure is silent and expected
 *
 * A private repository, a repository with no releases yet, no network, and a rate limit all
 * look the same from here: no update information. None of them is an error worth showing.
 */
class UpdateChecker(private val context: Context) {

    /** @return the latest release when it is newer than the installed build, else null. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val payload = runCatching { fetchLatestRelease() }.getOrNull() ?: return@withContext null
        val info = runCatching { parse(payload) }.getOrNull() ?: return@withContext null
        info.takeIf { it.isNewerThan(installedVersionName()) }
    }

    private fun installedVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun fetchLatestRelease(): String? {
        val connection = (URL(Project.LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            // GitHub rejects requests without one.
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Guise-Updater")
        }
        return try {
            // 404 covers both "private repository" and "no releases yet"; neither is an error.
            if (connection.responseCode != 200) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): UpdateInfo {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").removePrefix("v").removePrefix("V")
        val pageUrl = root.optString("html_url").ifBlank { Project.RELEASES_URL }

        // Prefer an asset whose name looks like the module APK; fall back to any .apk.
        val assets = root.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            val urls = (0 until assets.length()).mapNotNull { i ->
                assets.optJSONObject(i)?.let { it.optString("name") to it.optString("browser_download_url") }
            }
            apkUrl = urls.firstOrNull { it.first.startsWith("Guise-", ignoreCase = true) && it.first.endsWith(".apk") }?.second
                ?: urls.firstOrNull { it.first.endsWith(".apk", ignoreCase = true) }?.second
        }

        return UpdateInfo(
            latestVersion = tag,
            releaseUrl = pageUrl,
            apkUrl = apkUrl,
            notes = root.optString("body").takeIf { it.isNotBlank() },
        )
    }

    companion object {
        /**
         * Numeric comparison, segment by segment, tolerating a missing or non-numeric segment.
         *
         * Deliberately not a string comparison: "5.10.0" is newer than "5.9.0" and sorts the
         * other way lexically, which is the classic way an updater stops offering updates.
         */
        fun compareVersions(a: String, b: String): Int {
            fun segments(v: String) = v.trim().split('.', '-', '+')
                .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val sa = segments(a)
            val sb = segments(b)
            for (i in 0 until maxOf(sa.size, sb.size)) {
                val d = (sa.getOrElse(i) { 0 }) - (sb.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
