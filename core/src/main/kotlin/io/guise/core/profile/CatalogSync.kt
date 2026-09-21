package io.guise.core.profile

import io.guise.core.transport.Transport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The catalog's *format* version, and the rule for deciding whether a remote one may be used.
 *
 * Separate from `DeviceCatalog.version`, which it currently mirrors, because the two answer
 * different questions and only one of them is a safety boundary. [isSupported] is the boundary:
 * a catalog written by a newer build may use fields this build does not understand, and the
 * failure mode of guessing at a device identity is worse than the failure mode of staying on the
 * bundled copy. So an unrecognised version is refused, not decoded leniently.
 */
object CatalogSchema {
    const val VERSION = 1

    /**
     * True when a catalog declaring [version] can be trusted to mean what this build thinks.
     *
     * Older is fine -- additive fields decode to their defaults, which is why the format was
     * designed that way. Newer is not.
     */
    fun isSupported(version: Int): Boolean = version in 1..VERSION
}

/**
 * The naming convention that makes a downloaded catalog self-verifying.
 *
 * `catalog-<sha256>.json`, full 64 hex characters. Putting the digest in the *name* rather than
 * in a manifest beside the file means there is no second document to fetch, to trust, or to keep
 * in sync: a client either has a name carrying a digest or it has nothing to download. A
 * truncated download cannot be mistaken for a catalog, and CI cannot publish one without
 * computing the hash first.
 *
 * This lives in `:core`, and is tested, because the publisher is a shell script while the client
 * is Kotlin, and the two agreeing is the entire security property. Keeping the pattern in one
 * tested place is the closest thing to a test for the shell half.
 */
object CatalogAsset {
    const val PREFIX = "catalog-"
    const val SUFFIX = ".json"

    private val pattern = Regex("""^catalog-([0-9a-fA-F]{64})\.json$""")

    /** The asset name for a catalog whose bytes hash to [sha256]. */
    fun name(sha256: String): String = "$PREFIX$sha256$SUFFIX"

    /**
     * The declared digest, or null when the name is not of this form.
     *
     * Null is the answer for anything unrecognised, including a shorter digest: accepting a
     * prefix would quietly reduce a 256-bit integrity check to however many characters happened
     * to be there.
     */
    fun digestOf(assetName: String): String? =
        pattern.find(assetName)?.groupValues?.get(1)?.lowercase()
}

/**
 * Finds the published catalog inside a GitHub releases payload.
 *
 * In `:core`, and using the same JSON library as everything else, for two reasons. It is the step
 * where a URL and a claimed digest are chosen, so it is worth a test that runs without a network
 * -- and `:core` has no Android dependencies, so the test needs no device either. Nothing here is
 * trusted: the digest it returns is only a *claim*, and the caller re-hashes the bytes it
 * downloads and compares.
 */
object CatalogRelease {

    /** A catalog asset found in a release. */
    data class Available(val url: String, val sha256: String, val releaseTag: String)

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("assets") val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        @SerialName("name") val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
    )

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * The first asset whose name carries a full digest, or null.
     *
     * Unknown fields are ignored rather than rejected: GitHub adds to this payload over time, and
     * refusing to read a release because it gained a field would break the channel silently.
     */
    fun parse(payload: String): Available? {
        val release = runCatching {
            json.decodeFromString(Release.serializer(), payload)
        }.getOrNull() ?: return null
        val tag = release.tagName.removePrefix("v").removePrefix("V")
        return release.assets.firstNotNullOfOrNull { asset ->
            val digest = CatalogAsset.digestOf(asset.name) ?: return@firstNotNullOfOrNull null
            // A relative or blank URL is not a catalog we can fetch, and returning it would turn
            // into a confusing download failure instead of "no catalog in this release".
            val url = asset.url.takeIf { it.startsWith("https://") } ?: return@firstNotNullOfOrNull null
            Available(url = url, sha256 = digest, releaseTag = tag)
        }
    }
}

/**
 * What is known about the downloaded catalog, without parsing it.
 *
 * Written alongside the catalog so the UI can describe it and the hook can decide whether to
 * bother opening a file. Travels as a remote preference because it is small; the catalog itself
 * travels as a remote file.
 */
@Serializable
data class CatalogMeta(
    @SerialName("schemaVersion") val schemaVersion: Int = CatalogSchema.VERSION,
    /** Full SHA-256 of the catalog bytes, as published. */
    @SerialName("sha256") val sha256: String = "",
    @SerialName("profiles") val profiles: Int = 0,
    /** ISO-8601, when this build fetched it. */
    @SerialName("fetchedAt") val fetchedAt: String = "",
    /** Where it came from, for the UI to name. */
    @SerialName("sourceUrl") val sourceUrl: String = "",
    @SerialName("releaseTag") val releaseTag: String = "",
    @SerialName("socs") val socs: Int = 0,
) {
    /** Short form of [sha256] for display. */
    val shortHash: String get() = sha256.take(12)
}

/**
 * How the three catalog sources combine.
 *
 * Three sources, and the precedence between them is not arbitrary:
 *
 *  1. **Bundled** -- shipped inside the APK. Always present, so the module always has something.
 *  2. **Downloaded** -- fetched from a release. When it is present and valid it *replaces* the
 *     bundled catalog rather than merging with it, because an update that cannot remove a device
 *     is not an update. A stale entry that a correction meant to delete would otherwise live on
 *     forever.
 *  3. **Overlay** -- the user's own captures. Always wins, on every key it mentions. A capture is
 *     ground truth about a real handset; nothing published should ever displace it.
 *
 * The rule is deliberately one-directional: the overlay can correct anything, the download can
 * correct the bundle, and nothing can correct the overlay except the user capturing again.
 */
object CatalogMerge {

    fun merge(
        bundled: DeviceCatalog,
        downloaded: DeviceCatalog? = null,
        overlay: DeviceCatalog? = null,
    ): DeviceCatalog {
        // A downloaded catalog with no devices is treated as absent. It would otherwise silently
        // empty the picker, and "the update removed everything" is far more likely to be a bad
        // publish than an intent.
        val base = downloaded?.takeIf { it.devices.isNotEmpty() } ?: bundled
        val top = overlay ?: DeviceCatalog()
        return DeviceCatalog(
            version = maxOf(base.version, top.version),
            socs = base.socs + top.socs,
            devices = base.devices + top.devices,
        )
    }

    /**
     * True when [catalog] is fit to be adopted as a replacement for the bundled one.
     *
     * Fails closed on every count. This is remote data that decides what identity the module
     * presents, so "mostly valid" is not a useful standard: a single entry that contradicts
     * itself is a fingerprint an observer can catch, and the bundled catalog is always a
     * better answer than a partially broken update.
     */
    fun rejectionReason(catalog: DeviceCatalog, declaredSchema: Int = catalog.version): String? = when {
        !CatalogSchema.isSupported(declaredSchema) ->
            "format version $declaredSchema is not one this build understands (max ${CatalogSchema.VERSION})"
        catalog.devices.isEmpty() -> "it contains no devices"
        catalog.socs.isEmpty() -> "it contains no SoCs"
        else -> catalog.validate().firstOrNull()?.let { "entry rejected by the validator: $it" }
    }
}
