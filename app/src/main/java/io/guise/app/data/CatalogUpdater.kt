package io.guise.app.data

import android.content.Context
import android.os.ParcelFileDescriptor
import io.guise.app.service.XposedBridgeClient
import io.guise.core.config.ConfigCodec
import io.guise.core.profile.CatalogMerge
import io.guise.core.profile.CatalogMeta
import io.guise.core.profile.CatalogRelease
import io.guise.core.profile.CatalogSchema
import io.guise.core.profile.DeviceCatalog
import io.guise.core.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

/**
 * Fetches a newer device catalog from the project's releases.
 *
 * ## Why a catalog update exists at all
 *
 * Not to make the catalog bigger -- that is the generator's job, and it ships in the APK. This
 * exists for *freshness*: Pixel security patches are monthly, and a fingerprint whose patch level
 * stopped a year ago is a fingerprint with a date on it. Without this channel the only way to
 * roll those forward is an app release, which means every user runs a catalog frozen at whatever
 * version they happened to install.
 *
 * ## What is verified, and why so much
 *
 * A catalog is not configuration in the ordinary sense: it decides what identity the module
 * presents to other apps. So the download path fails closed at four points, and the bundled
 * catalog wins whenever any of them fails.
 *
 *  1. **The hash is in the file name.** CI publishes `catalog-<sha256>.json`. Nothing is fetched
 *     unless the name carries a full 64-hex digest, and the bytes are re-hashed and compared
 *     before parsing. A truncated download cannot be mistaken for a catalog.
 *  2. **The format version must be understood.** [CatalogSchema.isSupported] refuses a catalog
 *     written by a newer build, because guessing at fields that decide a device identity is
 *     worse than staying on the copy already on disk.
 *  3. **`DeviceCatalog.validate()` must be clean.** Not "mostly clean": one entry that
 *     contradicts itself is a fingerprint an observer can catch, and the bundled catalog is a
 *     better answer than a partially broken update.
 *  4. **The previous copy is never overwritten in place.** The write goes to the same remote file
 *     the hook reads, so a failure mid-write would leave a truncated catalog behind. Writing is
 *     therefore ordered: file first, metadata second, and the hook only trusts the file when the
 *     metadata agrees with it.
 *
 * ## What this sends
 *
 * One unauthenticated HTTPS GET to `api.github.com` to find the release, then one GET to the
 * asset. No device identifier, no account, no cookies, and the fetch happens only when the user
 * asks for it -- the same policy the app's own update check follows.
 */
class CatalogUpdater(private val context: Context) {

    /** A published catalog that this build does not currently have installed. */
    // (Available is declared below as an alias of CatalogRelease.Available.)

    /** Outcome of an install attempt, with the reason when it failed. */
    sealed interface Result {
        data class Installed(val meta: CatalogMeta) : Result
        data class Rejected(val reason: String) : Result
    }

    private val local = context.getSharedPreferences("Guise.catalog", Context.MODE_PRIVATE)

    /**
     * Finds a published catalog in the latest release, if it differs from what is installed.
     *
     * The asset name is the entire manifest: `catalog-<sha256>.json`. There is no separate index
     * to fetch, to trust, or to keep in sync with the file it describes.
     */
    suspend fun findAvailable(): Available? = withContext(Dispatchers.IO) {
        val payload = runCatching { fetch(Project.LATEST_RELEASE_API) }.getOrNull()
            ?: return@withContext null
        val parsed = runCatching { CatalogRelease.parse(payload) }.getOrNull()
            ?: return@withContext null
        // Already have exactly these bytes: nothing to do.
        if (parsed.sha256.equals(installedMeta()?.sha256, ignoreCase = true)) return@withContext null
        parsed
    }

    /** Downloads and installs [available], or explains why it will not. */
    suspend fun install(available: Available): Result = withContext(Dispatchers.IO) {
        val bytes = runCatching { fetchBytes(available.url) }.getOrNull()
            ?: return@withContext Result.Rejected("下载失败")

        val actual = sha256(bytes)
        if (!actual.equals(available.sha256, ignoreCase = true)) {
            // The one check that matters most: the bytes are not the bytes that were named.
            return@withContext Result.Rejected("校验失败：文件名声明 ${available.sha256.take(12)}…，实际 ${actual.take(12)}…")
        }

        val catalog = runCatching {
            ConfigCodec.decodeCatalog(bytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return@withContext Result.Rejected("内容不是合法的机型库 JSON")

        // The format version is only knowable after parsing, so it is checked here rather than
        // before the download. The cost of a wrong guess is one wasted request.
        CatalogMerge.rejectionReason(catalog, catalog.version)?.let {
            return@withContext Result.Rejected(it)
        }

        val meta = CatalogMeta(
            schemaVersion = catalog.version,
            sha256 = actual,
            profiles = catalog.devices.size,
            socs = catalog.socs.size,
            fetchedAt = Instant.now().toString(),
            sourceUrl = available.url,
            releaseTag = available.releaseTag,
        )
        if (!writeRemoteFile(bytes)) {
            return@withContext Result.Rejected(
                "无法写入模块目录——请先在 LSPosed 里启用 Guise 并打开一次目标应用，再回来重试",
            )
        }
        writeMeta(meta)
        local.edit().putString(KEY_META, ConfigCodec.encodeCatalogMeta(meta)).apply()
        Result.Installed(meta)
    }

    /**
     * Installs a catalog from bytes already in hand, used by tests and by a future file import.
     *
     * Shares every gate with [install] except the download, so the two cannot drift apart.
     */
    fun installFromBytes(bytes: ByteArray, sourceUrl: String = "", releaseTag: String = ""): Result {
        val actual = sha256(bytes)
        val catalog = runCatching {
            ConfigCodec.decodeCatalog(bytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return Result.Rejected("内容不是合法的机型库 JSON")
        CatalogMerge.rejectionReason(catalog, catalog.version)?.let { return Result.Rejected(it) }
        val meta = CatalogMeta(
            schemaVersion = catalog.version,
            sha256 = actual,
            profiles = catalog.devices.size,
            socs = catalog.socs.size,
            fetchedAt = Instant.now().toString(),
            sourceUrl = sourceUrl,
            releaseTag = releaseTag,
        )
        if (!writeRemoteFile(bytes)) return Result.Rejected("无法写入模块目录")
        writeMeta(meta)
        local.edit().putString(KEY_META, ConfigCodec.encodeCatalogMeta(meta)).apply()
        return Result.Installed(meta)
    }

    /** What is installed, from the local copy so it is readable with the framework offline. */
    fun installedMeta(): CatalogMeta? =
        runCatching { local.getString(KEY_META, null) }
            .getOrNull()
            ?.let { ConfigCodec.decodeCatalogMeta(it) }

    /** Drops the downloaded catalog, returning the module to the bundled one. */
    fun uninstall(): Boolean {
        local.edit().remove(KEY_META).apply()
        val svc = XposedBridgeClient.service.value ?: return true
        return runCatching {
            svc.openRemoteFile(Transport.REMOTE_FILE_DOWNLOADED_CATALOG).let {
                ParcelFileDescriptor.AutoCloseOutputStream(it).use { s -> s.write(ByteArray(0)) }
            }
            svc.getRemotePreferences(Transport.PREFS_GROUP)
                .edit().remove(Transport.KEY_CATALOG_META).apply()
            true
        }.getOrDefault(false)
    }

    // ---- transport ----------------------------------------------------------

    private fun writeRemoteFile(bytes: ByteArray): Boolean {
        val svc = XposedBridgeClient.service.value ?: return false
        return runCatching {
            val pfd = svc.openRemoteFile(Transport.REMOTE_FILE_DOWNLOADED_CATALOG)
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Publishes [meta] immediately after the file it describes.
     *
     * Order matters and is the reason a half-written catalog cannot be adopted: the hook looks
     * for the metadata first and only then opens the file, so a write that died before this point
     * leaves the metadata describing the *previous* catalog, and the hash mismatch is caught.
     */
    private fun writeMeta(meta: CatalogMeta) {
        val svc = XposedBridgeClient.service.value ?: return
        runCatching {
            svc.getRemotePreferences(Transport.PREFS_GROUP)
                .edit()
                .putString(Transport.KEY_CATALOG_META, ConfigCodec.encodeCatalogMeta(meta))
                .apply()
        }
    }

    // ---- network ------------------------------------------------------------

    private fun fetch(url: String): String? {
        val bytes = fetchBytes(url) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun fetchBytes(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            setRequestProperty("User-Agent", "Guise-CatalogUpdater")
            instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode != 200) return null
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val KEY_META = "installed_meta"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * A catalog published in a release, as found by [CatalogRelease].
 *
 * Kept as an alias so the rest of this class reads naturally, and so the parsing rules -- which
 * live in `:core` beside their test -- have exactly one definition.
 */
private typealias Available = CatalogRelease.Available
