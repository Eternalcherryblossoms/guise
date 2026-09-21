package io.guise.cataloggen

import io.guise.core.config.ConfigCodec
import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.DisplayProfile
import io.guise.core.profile.RamTier
import java.io.File

/**
 * Turns fetched Pixel builds into catalog entries.
 *
 * The split of responsibility is the point of this class. The snapshot contributes what only a
 * shipped build can say -- the build ID, its era, the security patch, the incremental version,
 * the product and device codenames -- and [PixelHardware] contributes what no build file
 * contains: memory, model number and screen. Neither is allowed to guess at the other's half.
 *
 * ## One entry per (device, release, memory)
 *
 * A build belongs to exactly one Android release, and `BuildIdScheme.alignToRelease` only repairs
 * the *token* of a mismatched build ID -- the date inside it and the security patch beside it
 * still name the era they were made in. So a Pixel 6 profile captured on Android 12 and worn on
 * Android 16 would describe a handset that never existed. Emitting one entry per release removes
 * the possibility rather than repairing its symptom, and `Compatibility.releaseIssue` makes the
 * picker say so.
 *
 * Memory is a third dimension for a different reason: it does not appear in any build artifact,
 * so two SKUs of one model share a build byte for byte and differ only in `ramBytes`.
 */
object PixelSource {

    const val SNAPSHOT_PATH = "catalog/raw/pixel-ota.json"

    /**
     * @param seeds complete catalog entries
     * @param unusable codenames the snapshot has builds for but the seed cannot describe, with
     *   the reason. Reported rather than dropped: this list is the honest size of the gap, and it
     *   is exactly the set a contributor with the hardware could close.
     */
    data class Result(
        val seeds: List<DeviceSeed> = emptyList(),
        val unusable: List<Pair<String, String>> = emptyList(),
        val buildsAvailable: Int = 0,
    )

    fun load(snapshotFile: File = File(SNAPSHOT_PATH)): PixelOtaSnapshot {
        if (!snapshotFile.exists()) {
            throw IllegalStateException(
                "no Pixel snapshot at ${snapshotFile.path}; run " +
                    "./gradlew :catalog-gen:fetchPixel first (see fetchPixel for the network note)",
            )
        }
        return fetchPixelJson.decodeFromString(PixelOtaSnapshot.serializer(), snapshotFile.readText())
    }

    fun seeds(snapshot: PixelOtaSnapshot, hardware: List<PixelHardware>): Result {
        val usable = snapshot.builds.filter { it.usable }
        val byCodename = usable.groupBy { it.codename }
        val seeds = ArrayList<DeviceSeed>()
        val unusable = ArrayList<Pair<String, String>>()

        hardware.forEach { hw ->
            val builds = byCodename[hw.codename].orEmpty()
            if (builds.isEmpty()) {
                unusable += hw.codename to
                    "the seed describes it but the snapshot has no usable build"
                return@forEach
            }
            if (hw.socKey !in setOf("") && hw.ramGiB.isEmpty()) {
                unusable += hw.codename to "no memory configuration declared"
                return@forEach
            }
            // Newest build per release. The index lists several builds per release -- monthly
            // patches and carrier variants -- and the newest is the one a handset would most
            // plausibly be running.
            val newestPerRelease = builds
                .groupBy { it.release }
                .mapValues { (_, group) -> group.maxBy { it.timestamp } }

            newestPerRelease.entries
                .sortedWith(compareBy({ releaseOrder(it.key) }, { it.key }))
                .forEach { (release, build) ->
                    hw.ramGiB.forEach { gib ->
                        seeds += seed(hw, build, release, gib)
                    }
                }
        }

        // Codenames with builds but no hardware entry: the size of the gap, named.
        val described = hardware.map { it.codename }.toSet()
        byCodename.keys.filter { it !in described }.sorted().forEach { codename ->
            unusable += codename to
                "builds are available but no SoC facts exist for it, and the GPU renderer string " +
                "cannot be obtained from any published file"
        }

        return Result(seeds = seeds, unusable = unusable, buildsAvailable = usable.size)
    }

    private fun seed(
        hw: PixelHardware,
        build: OtaBuild,
        release: String,
        ramGiB: Int,
    ): DeviceSeed {
        val multiSku = hw.ramGiB.size > 1
        val suffix = if (multiSku) "_${ramGiB}gb" else ""
        val marketing = build.marketingName.ifBlank { hw.codename }
        return DeviceSeed(
            profile = DeviceProfile(
                key = "google_${hw.codename}_a${release.filter { it.isDigit() }}$suffix",
                // The release is part of the name because it is part of the identity: one entry
                // per release means the picker must be able to tell them apart.
                name = "${if (marketing.startsWith("Pixel")) "Google $marketing" else marketing} · " +
                    "Android $release",
                brand = "google",
                manufacturer = "Google",
                model = hw.model,
                product = build.codename,
                device = build.codename,
                androidRelease = release,
                sdkInt = build.sdkInt,
                securityPatch = build.securityPatch,
                buildId = build.buildId,
                buildIncremental = build.incremental,
                buildType = build.type.ifBlank { "user" },
                buildTags = build.tags.ifBlank { "release-keys" },
                // Not present in OTA metadata. Left at the model defaults rather than filled with
                // a plausible-looking Google build-farm hostname: inventing one would be exactly
                // the kind of fabrication the whole pipeline exists to avoid, and the value is
                // weak evidence compared with the fingerprint. `ro.build.host` and `ro.build.user`
                // can be supplied later from a build.prop source.
                buildHost = "localhost",
                buildUser = "builder",
                // The one build field the metadata does carry: post-timestamp, in seconds.
                buildTimeMs = build.timestamp * 1000L,
                displayId = build.buildId,
                bootloader = "unknown",
                socKey = hw.socKey,
                ramBytes = RamTier.bytes(ramGiB),
                display = DisplayProfile(
                    widthPx = hw.widthPx,
                    heightPx = hw.heightPx,
                    densityDpi = hw.densityDpi,
                    refreshRates = hw.refreshRates,
                ),
            ),
            source = "Google OTA metadata (post-build / post-security-patch-level), read from " +
                "the first 2 KB of the shipped OTA zip for ${build.buildId}",
            // The index's own host, rewritten to the one that actually serves ranged requests.
            // Recorded as evidence, so it should be a URL a reader can fetch: the .cn file host
            // answers every Range request with 429, and the path is otherwise identical.
            sourceUrl = rewriteHost(build.otaUrl, DEFAULT_DOWNLOAD_HOST),
            // The build half is read from a published artifact, so it is verified. The model
            // number, memory and display come from published specifications -- stated in `note`
            // so the distinction survives into PROVENANCE.md instead of being flattened.
            verified = true,
            note = "build from OTA metadata; ${hw.source}",
        )
    }

    /** Orders release strings for presentation: `14` before `14L`, `16` before `8.1.0`. */
    private fun releaseOrder(release: String): Double =
        release.substringBefore('.').toDoubleOrNull()
            ?: release.filter { it.isDigit() }.take(2).toDoubleOrNull()
            ?: 0.0
}

/** Same strict decoder the rest of the pipeline uses. */
internal val fetchPixelJson = kotlinx.serialization.json.Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
