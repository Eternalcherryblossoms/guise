package io.guise.cataloggen

import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.DisplayProfile
import java.io.File

/**
 * Turns extracted firmware builds into catalog entries.
 *
 * ## What this source contributes, and what it cannot
 *
 * A `build.prop` states a device's identity completely -- brand, codenames, build ID, incremental
 * version, security patch, type, tags, and the host and user that produced it -- which is why
 * these entries are more complete than the Pixel ones, which have to leave `ro.build.host` at a
 * default. What it does not state is anything that is not a property: **memory, panel resolution
 * and the GPU renderer**. The SoC is resolved by lookup, the density comes from
 * `ro.sf.lcd_density` when the firmware sets it, and memory and resolution are left *unknown*
 * rather than filled in.
 *
 * That last part deserves its reason. Unknown memory sounds like a gap, and for this project it
 * is not one: `ramBytes` is a **compatibility constraint, never a reported value** -- physical
 * memory cannot be spoofed by any layer, so a profile that does not claim a capacity is not
 * claiming anything false. The module reports the real figure either way. What is lost is only
 * the *warning*, which the picker gives for entries that do state a capacity. `Compatibility`
 * already treats "unknown" as "no claim" rather than as a match, so an entry like this is offered
 * without a memory verdict instead of being offered with a wrong one.
 *
 * ## One entry per (device, release)
 *
 * The corpus holds 5,085 dumps for 598 usable builds, and several dumps usually describe the same
 * handset on the same Android release -- regional variants, monthly patches. Keeping one profile
 * per (device, release) is the same rule the Pixel source follows, for the same reason: a build
 * belongs to exactly one release, so the release is part of the identity. Within a group the
 * newest build wins, dated by `ro.build.date.utc` with the security patch as a tiebreak, because
 * a handset is most plausibly running the most recent thing its vendor shipped.
 */
object BuildPropSource {

    const val SNAPSHOT_PATH = "catalog/raw/buildprops.json"

    data class Result(
        val seeds: List<DeviceSeed> = emptyList(),
        val dumpsScanned: Int = 0,
        val considered: Int = 0,
        val skippedKnownDevices: Int = 0,
        val unknownMemory: Int = 0,
        val unknownResolution: Int = 0,
    )

    fun load(snapshotFile: File = File(SNAPSHOT_PATH)): BuildPropSnapshot =
        fetchPixelJson.decodeFromString(BuildPropSnapshot.serializer(), snapshotFile.readText())

    /**
     * @param excludeDevices codenames already covered by a first-party source. A corpus row for
     *   one of those is strictly worse evidence than the vendor's own OTA metadata, so it is
     *   dropped rather than merged -- and dropping it is also what keeps the keys unique.
     */
    fun seeds(snapshot: BuildPropSnapshot, excludeDevices: Set<String> = emptySet()): Result {
        val rows = snapshot.entries.filter { it.usable }
        val kept = rows.filter { it.device !in excludeDevices }
        val skipped = rows.size - kept.size

        val seeds = ArrayList<DeviceSeed>()
        var unknownMemory = 0
        var unknownResolution = 0

        kept.groupBy { it.device to it.release }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (deviceRelease, group) ->
                val (codename, release) = deviceRelease
                // Newest build wins. `ro.build.date.utc` is the authoritative ordering, and a
                // build with no date falls back to its security patch rather than to nothing.
                val best = group.maxWithOrNull(
                    compareBy({ it.buildTimeSec }, { it.securityPatch }),
                ) ?: return@forEach

                val unknown = best.lcdDensity <= 0
                if (unknown) unknownResolution++
                // Memory is never stated by this source; counted once per emitted entry.
                unknownMemory++

                val brand = best.brand.ifBlank { "unknown" }
                seeds += DeviceSeed(
                    profile = DeviceProfile(
                        key = "${sanitize(brand)}_${sanitize(codename)}_a${release.filter(Char::isDigit)}",
                        // No marketing name exists in a build.prop, so the model code is the honest
                        // label. Inventing "Xiaomi 13" from a codename would be a guess presented
                        // as a fact, and the picker's search matches the model anyway.
                        name = "$brand ${best.model}",
                        brand = brand.lowercase(),
                        manufacturer = brand,
                        model = best.model,
                        product = best.product.ifBlank { codename },
                        device = codename,
                        androidRelease = release,
                        sdkInt = best.sdkInt,
                        securityPatch = best.securityPatch,
                        buildId = best.buildId,
                        buildIncremental = best.incremental,
                        buildType = best.type.ifBlank { "user" },
                        buildTags = best.tags.ifBlank { "release-keys" },
                        buildHost = best.buildHost.ifBlank { "localhost" },
                        buildUser = best.buildUser.ifBlank { "builder" },
                        buildTimeMs = best.buildTimeSec * 1000L,
                        displayId = best.buildId,
                        bootloader = "unknown",
                        socKey = best.socKey,
                        // Zero means unknown: the firmware states no memory, and physical memory
                        // is not spoofable, so there is nothing to claim. See the class comment.
                        ramBytes = 0L,
                        display = DisplayProfile(
                            // Density is a property; resolution is not. Left unknown rather than
                            // inferred from the density, which would be a fabricated panel.
                            widthPx = 0,
                            heightPx = 0,
                            densityDpi = best.lcdDensity,
                            refreshRates = emptyList(),
                        ),
                    ),
                    source = "firmware build.prop: ${best.dump}",
                    sourceUrl = "",
                    // Read from the firmware itself rather than transcribed, but through a third
                    // party's extraction rather than the vendor's own channel -- which is the
                    // distinction PROVENANCE.md draws, and why this is not marked verified.
                    verified = false,
                    note = "merged across the dump's partitions; " +
                        (if (best.lcdDensity > 0) "density from ro.sf.lcd_density"
                        else "no density in the props") +
                        "; memory unknown (not in any build.prop, and not spoofable)",
                )
            }

        return Result(
            seeds = seeds,
            dumpsScanned = snapshot.dumpsScanned,
            considered = kept.size,
            skippedKnownDevices = skipped,
            unknownMemory = unknownMemory,
            unknownResolution = unknownResolution,
        )
    }

    /** Keys must survive being used as JSON fields and map keys, so anything else is folded. */
    private fun sanitize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "unknown" }
}
