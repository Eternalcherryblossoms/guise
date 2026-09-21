package io.guise.core.profile

import io.guise.core.privacy.PrivacyDomain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The bundled device database.
 *
 * Split into [socs] and [devices] so that adding a handset usually means adding a
 * handful of lines that point at an existing SoC, rather than re-entering GPU, ABI and
 * codec data that must then be kept in sync by hand.
 */
@Serializable
data class DeviceCatalog(
    @SerialName("version") val version: Int = 1,
    @SerialName("socs") val socs: Map<String, SocProfile> = emptyMap(),
    @SerialName("devices") val devices: Map<String, DeviceProfile> = emptyMap(),
) {
    fun soc(key: String): SocProfile? = socs[key]

    /** Devices grouped by brand, for the picker UI. */
    fun byBrand(): Map<String, List<DeviceProfile>> =
        devices.values.groupBy { it.brand }.toSortedMap()

    fun search(query: String): List<DeviceProfile> {
        if (query.isBlank()) return devices.values.sortedBy { it.name }
        val q = query.trim().lowercase()
        return devices.values
            .filter {
                it.name.lowercase().contains(q) ||
                    it.model.lowercase().contains(q) ||
                    it.brand.lowercase().contains(q)
            }
            .sortedBy { it.name }
    }

    /** Memory tiers, in GiB, that at least one device actually covers. Ascending. */
    fun coveredRamGiB(): List<Int> = devices.values
        .mapNotNull { d -> RamTier.nominalGiB(d.ramBytes) }
        .distinct()
        .sorted()

    /**
     * Memory tiers among [among] that **no** device covers.
     *
     * This is the number that matters, and it is not the device count. A catalog of fourteen
     * devices can cover two memory tiers, which is exactly what the shipped catalog did: every
     * handset in it declared 8 GiB or 12 GiB, so a user on a 16 GB phone had no coherent profile
     * at all and nothing said so. Coverage is a property of the tiers, not of the list length.
     */
    fun uncoveredRamGiB(among: Collection<Int> = RamTier.capacitiesGiB): List<Int> =
        among.filter { tier -> devices.values.none { RamTier.nominalGiB(it.ramBytes) == tier } }
            .sorted()

    /**
     * Structural validation of the whole catalog. Run as a unit test against the
     * bundled asset so a bad entry cannot ship.
     */
    fun validate(): List<String> = buildList {
        devices.forEach { (key, d) ->
            if (key != d.key) add("device map key '$key' != profile key '${d.key}'")
            d.invariants().forEach { add("device '$key': $it") }
            if (socs[d.socKey] == null) add("device '$key' references unknown soc '${d.socKey}'")
            if (!RamTier.isKnownCapacity(d.ramBytes)) {
                add(
                    "device '$key': ramBytes ${d.ramBytes} is not a capacity handsets ship with " +
                        "(allowed: 0 for unknown, or one of ${RamTier.capacitiesGiB} GiB)",
                )
            }
        }
        socs.forEach { (key, s) ->
            if (key != s.key) add("soc map key '$key' != profile key '${s.key}'")
            if (s.gpuRenderer.isBlank()) add("soc '$key' has a blank gpuRenderer")
            if (s.abis.isEmpty()) add("soc '$key' has no ABIs")
        }
    }
}

/**
 * A [DeviceProfile] paired with its SoC and the user's per-target overrides.
 *
 * This is the *only* thing the hook layer is permitted to read. Channels ask it for a
 * value; they never touch [DeviceProfile] directly and never hardcode a fallback. That
 * restriction is what makes cross-channel coherence structural rather than aspirational.
 */
class EffectiveProfile(
    val device: DeviceProfile,
    val soc: SocProfile,
    private val overrides: Map<String, String> = emptyMap(),
    /**
     * The Android version the process is actually running.
     *
     * Defaults to the profile's own version so that catalog preview and tests work without a
     * device, but callers on a real device must pass the real one: the API level cannot be
     * safely rewritten, and the release and the fingerprint have to agree with it.
     */
    val runtime: RuntimeVersion = RuntimeVersion.of(device),
    /**
     * Per-target policy, carried here because every consumer already holds the resolved
     * profile. See [io.guise.core.config.TargetConfig.stripForeignCodecs] for the trade-off.
     */
    val stripForeignCodecs: Boolean = false,
    /**
     * Per-target privacy domains to answer with nothing.
     *
     * Carried here for the same reason as [stripForeignCodecs]: every consumer already holds the
     * resolved profile, and a second plumbing path for per-target policy would cost more than the
     * mild impurity of a resolved-values object holding one. If a third policy appears, this and
     * [stripForeignCodecs] should move into a `TargetPolicy` holder.
     */
    val emptiedDomains: Set<PrivacyDomain> = emptySet(),
) {
    /** Effective value for [key] as a string, honouring any override. */
    fun string(key: FieldKey): String = overrides[key.id] ?: derived(key)

    fun int(key: FieldKey): Int =
        overrides[key.id]?.trim()?.toIntOrNull() ?: derived(key).trim().toIntOrNull() ?: 0

    fun long(key: FieldKey): Long =
        overrides[key.id]?.trim()?.toLongOrNull() ?: derived(key).trim().toLongOrNull() ?: 0L

    /** True when the user has pinned this field away from the profile's value. */
    fun isOverridden(key: FieldKey): Boolean = overrides.containsKey(key.id)

    val overriddenKeys: Set<FieldKey>
        get() = overrides.keys.mapNotNull(FieldKey::of).toSet()

    /**
     * The profile's own value for [key], before any override.
     *
     * Note that [DeviceProfile.fingerprint] is computed, not stored, so that it stays
     * consistent when a user overrides e.g. [FieldKey.BUILD_MODEL]. Callers that need
     * the recomputed fingerprint after overrides should use [recomputeFingerprint].
     */
    fun derived(key: FieldKey): String = when (key) {
        FieldKey.BUILD_BRAND -> device.brand
        FieldKey.BUILD_MANUFACTURER -> device.manufacturer
        FieldKey.BUILD_MODEL -> device.model
        FieldKey.BUILD_DEVICE -> device.device
        FieldKey.BUILD_PRODUCT -> device.product
        FieldKey.BUILD_BOARD -> soc.board
        FieldKey.BUILD_HARDWARE -> soc.hardware
        FieldKey.BUILD_FINGERPRINT -> recomputeFingerprint()
        FieldKey.BUILD_BOOTLOADER -> device.bootloader
        FieldKey.BUILD_DISPLAY -> eraAligned(device.displayId, effectiveRelease())
        FieldKey.BUILD_ID -> eraAligned(device.buildId, effectiveRelease())
        FieldKey.BUILD_TAGS -> device.buildTags
        FieldKey.BUILD_TYPE -> device.buildType
        FieldKey.BUILD_HOST -> device.buildHost
        FieldKey.BUILD_USER -> device.buildUser
        FieldKey.BUILD_TIME -> device.buildTimeMs.toString()
        FieldKey.BUILD_SERIAL -> device.serial.orEmpty()

        // Version comes from the running device, not the profile. See RuntimeVersion.
        FieldKey.VERSION_RELEASE -> runtime.release
        FieldKey.VERSION_SDK_INT -> runtime.sdkInt.toString()
        FieldKey.VERSION_INCREMENTAL -> device.buildIncremental
        FieldKey.VERSION_SECURITY_PATCH -> device.securityPatch

        FieldKey.DISPLAY_WIDTH -> device.display.widthPx.toString()
        FieldKey.DISPLAY_HEIGHT -> device.display.heightPx.toString()
        FieldKey.DISPLAY_DENSITY -> device.display.densityDpi.toString()

        FieldKey.SOC_PLATFORM -> soc.platform
        FieldKey.SOC_GPU_VENDOR -> soc.gpuVendor
        FieldKey.SOC_GPU_RENDERER -> soc.gpuRenderer
        FieldKey.SOC_CPU_ABI -> soc.abis.firstOrNull().orEmpty()
    }

    /** The release that will actually be reported: the override if pinned, else the device's. */
    private fun effectiveRelease(): String =
        overrides[FieldKey.VERSION_RELEASE.id] ?: runtime.release

    /**
     * Aligns a build ID's version token with the release that will actually be reported.
     *
     * Without this, using a profile on a device with a different Android version produces a
     * fingerprint whose build ID names one release while its release segment names another --
     * a contradiction that needs no device database to detect, only knowledge of the public
     * build-ID naming scheme.
     *
     * Aligning to [effectiveRelease] rather than to `runtime.release` matters: a user who pins
     * `version.release` changes the era the build ID has to match, and an earlier version of
     * this function aligned to the runtime and silently produced a mismatched pair. The core
     * test suite caught it.
     */
    private fun eraAligned(raw: String, release: String): String =
        BuildIdScheme.alignToRelease(raw, release)

    /**
     * The fingerprint rebuilt from the *effective* values, so overriding MODEL or RELEASE
     * produces a fingerprint that still agrees with them.
     *
     * An explicit `build.fingerprint` override always wins; that is the escape hatch for
     * handsets whose real fingerprint does not follow the canonical layout.
     */
    fun recomputeFingerprint(): String {
        overrides[FieldKey.BUILD_FINGERPRINT.id]?.let { return it }
        device.fingerprintOverride?.let { return it }
        val brand = overrides[FieldKey.BUILD_BRAND.id] ?: device.brand
        val product = overrides[FieldKey.BUILD_PRODUCT.id] ?: device.product
        val dev = overrides[FieldKey.BUILD_DEVICE.id] ?: device.device
        // Falls back to the runtime release so the fingerprint cannot claim a version the
        // device is not running.
        val release = overrides[FieldKey.VERSION_RELEASE.id] ?: runtime.release
        // Era-aligned so the build ID and the release segment agree.
        val id = eraAligned(overrides[FieldKey.BUILD_ID.id] ?: device.buildId, release)
        val incr = overrides[FieldKey.VERSION_INCREMENTAL.id] ?: device.buildIncremental
        val type = overrides[FieldKey.BUILD_TYPE.id] ?: device.buildType
        val tags = overrides[FieldKey.BUILD_TAGS.id] ?: device.buildTags
        return "$brand/$product/$dev:$release/$id/$incr:$type/$tags"
    }

    /** Snapshot of every resolved value, handy for logging and for the UI preview. */
    fun snapshot(): Map<FieldKey, String> = FieldKey.entries.associateWith(::string)

    companion object {
        fun of(
            device: DeviceProfile,
            catalog: DeviceCatalog,
            overrides: Map<String, String> = emptyMap(),
            runtime: RuntimeVersion = RuntimeVersion.of(device),
            stripForeignCodecs: Boolean = false,
            emptiedDomains: Set<PrivacyDomain> = emptySet(),
        ): EffectiveProfile {
            val soc = catalog.soc(device.socKey)
                ?: error("device '${device.key}' references unknown soc '${device.socKey}'")
            return EffectiveProfile(
                device, soc, overrides, runtime, stripForeignCodecs, emptiedDomains,
            )
        }
    }
}
