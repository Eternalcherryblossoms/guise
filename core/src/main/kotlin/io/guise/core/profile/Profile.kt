package io.guise.core.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The AOSP build-ID naming scheme.
 *
 * A build ID looks like `TQ1A.221205.011`: a version token, a date, and a build counter. The
 * leading letter of the version token names the Android release, and past 14 the scheme moved
 * to two-letter tokens (`AP*` for 15, `BP*` for 16).
 *
 * This matters because the fingerprint embeds the build ID, and the release it embeds comes
 * from the *running device* while the build ID comes from the *profile*. Those two drift apart
 * the moment a profile is used on a device running a different Android version, producing a
 * fingerprint like `...:16/TQ1A.221205.011:...` -- release 16 carrying an Android 13 build ID.
 * No device database is needed to spot that; the naming scheme is public and anyone who reads
 * build IDs knows it.
 */
object BuildIdScheme {

    private val tokenByRelease = mapOf(
        "11" to "R", "12" to "S", "12L" to "S", "13" to "T",
        "14" to "U", "15" to "A", "16" to "B",
    )

    /** True when the leading token of [buildId] is consistent with [release]. */
    fun isEraConsistent(buildId: String, release: String): Boolean {
        val expected = tokenByRelease[release.trim()] ?: return true
        val head = leader(buildId) ?: return true
        return head.startsWith(expected)
    }

    /**
     * Rewrites the version token of [buildId] so its era matches [release], leaving the date
     * and counter untouched.
     *
     * This is a partial repair and is documented as such: the branch token and the date still
     * come from the profile's era, so an observer who knows that Android 16 did not exist in
     * 2022 can still tell. It removes the *scheme-level* contradiction, which is the one that
     * needs no database to detect. A profile captured on a device running the same Android
     * version has no residual problem at all, which is why the UI recommends captures.
     */
    fun alignToRelease(buildId: String, release: String): String {
        val expected = tokenByRelease[release.trim()] ?: return buildId
        val dot = buildId.indexOf('.')
        if (dot <= 0) return buildId
        val head = buildId.substring(0, dot)
        val tail = buildId.substring(dot)
        if (head.isEmpty() || !head[0].isLetter()) return buildId
        if (isEraConsistent(buildId, release)) return buildId
        return expected + head.substring(1) + tail
    }

    /** The leading version token, e.g. `TQ1A` in `TQ1A.221205.011`. */
    private fun leader(buildId: String): String? {
        val dot = buildId.indexOf('.')
        if (dot <= 0) return null
        return buildId.substring(0, dot).takeIf { it.isNotEmpty() && it[0].isLetter() }
    }
}

/**
 * The Android version the process is actually running on.
 *
 * Deliberately *not* part of [DeviceProfile]. A device profile describes hardware identity --
 * which handset this claims to be. The Android version is an environment fact: a real Mi 11
 * can be running Android 11, 12, 13 or 14 depending on its ROM, so "I am a Mi 11" and "I am
 * running Android 12" are independent statements.
 *
 * The separation is load-bearing, not stylistic:
 *
 *  - `Build.VERSION.SDK_INT` governs which code paths the framework takes. Raising it makes
 *    an app run code for APIs this ROM does not have; lowering it hides real ones. Both
 *    crash, so it has to stay real.
 *  - `Build.VERSION.RELEASE` must agree with `SDK_INT`, or the pair is self-contradictory.
 *  - The build fingerprint embeds the release string, so it has to agree too.
 *
 * So the version comes from the running device, and the profile only records what its catalog
 * entry was captured on. A probe reading both then finds them consistent -- which is the
 * behaviour that matters, and the one an earlier design got wrong by spoofing the release
 * while correctly refusing to touch the API level.
 */
@Serializable
data class RuntimeVersion(
    @SerialName("release") val release: String,
    @SerialName("sdkInt") val sdkInt: Int,
) {
    companion object {
        /**
         * Fallback for off-device preview and tests, where there is no real device.
         * Callers running on a device should always pass the actual one.
         */
        fun of(device: DeviceProfile): RuntimeVersion =
            RuntimeVersion(device.androidRelease, device.sdkInt)
    }
}

/**
 * Screen characteristics.
 *
 * These belong to a concrete device, not to a SoC, so they live on [DeviceProfile].
 *
 * **Zero means unknown**, and unknown is a legitimate state rather than a gap to be filled. A
 * firmware's `build.prop` carries `ro.sf.lcd_density` but not the panel resolution, so a catalog
 * built from real firmware dumps knows the density and genuinely cannot know the resolution.
 * The alternative -- inventing one -- would put a fabricated value into a profile whose whole
 * purpose is to be self-consistent, and the values are close to inert anyway:
 * [io.guise.xposed.channel.DisplayChannel] rewrites density only when the user explicitly pins it
 * per app, and never rewrites geometry at all.
 */
@Serializable
data class DisplayProfile(
    @SerialName("widthPx") val widthPx: Int = 0,
    @SerialName("heightPx") val heightPx: Int = 0,
    @SerialName("densityDpi") val densityDpi: Int = 0,
    /**
     * Empty when unknown. Notably *not* defaulted to 60 Hz: a refresh rate that was never read
     * is a claim, and this project would rather report nothing than report a plausible guess.
     */
    @SerialName("refreshRates") val refreshRates: List<Float> = emptyList(),
) {
    /** True when nothing about the panel is known. */
    val isUnknown: Boolean get() = widthPx <= 0 && heightPx <= 0 && densityDpi <= 0
}

/**
 * Everything that follows from the chip, not from the handset.
 *
 * This is the level that makes the catalog maintainable: a few dozen SoCs cover the
 * overwhelming majority of handsets, and every device that shares a SoC shares all of these
 * derived values. If these were duplicated per handset they would drift, and drift is exactly
 * what cross-channel verification detects.
 *
 * Memory is conspicuously **absent**, and that is deliberate rather than an oversight. It was
 * here once, as `totalRamBytes`, and the field was never read by anything. A chip is not sold
 * with one memory size -- the Snapdragon 865 shipped in 6, 8, 12 and 16 GB handsets -- so
 * attaching it to the SoC flattened four configurations into one and let twelve of the fourteen
 * catalog devices inherit a figure that matched neither test handset. It lives on
 * [DeviceProfile.ramBytes] now. See [RamTier].
 */
@Serializable
data class SocProfile(
    @SerialName("key") val key: String,
    @SerialName("vendor") val vendor: String,
    /** Marketing name, e.g. "Snapdragon 888". Reference only, never reported as-is. */
    @SerialName("marketingName") val marketingName: String,
    /** `ro.board.platform`, e.g. "lahaina". */
    @SerialName("platform") val platform: String,
    /** `ro.hardware` / `Build.HARDWARE`, e.g. "qcom". */
    @SerialName("hardware") val hardware: String,
    /** `Build.BOARD`, e.g. "lahaina". */
    @SerialName("board") val board: String,
    /**
     * CPU implementer/part as they appear in /proc/cpuinfo. The kernel prints these straight
     * from the MIDR_EL1 register, so they are a hard cross-check against a spoofed
     * `Build.MODEL` -- and, since only the Zygisk layer can reach that file, a check that
     * stays red until that layer exists.
     */
    @SerialName("cpuImplementer") val cpuImplementer: String,
    @SerialName("cpuPart") val cpuPart: String,
    @SerialName("cores") val cores: Int,
    /** e.g. "Qualcomm" as reported by GL_VENDOR. */
    @SerialName("gpuVendor") val gpuVendor: String,
    /** e.g. "Adreno (TM) 660" as reported by GL_RENDERER. */
    @SerialName("gpuRenderer") val gpuRenderer: String,
    /** Ordered preferences for Build.SUPPORTED_ABIS. */
    @SerialName("abis") val abis: List<String>,
    /**
     * Vendor prefixes that appear in MediaCodecList component names for this SoC, e.g.
     * ["c2.qti.", "OMX.qcom."]. Keeps the codec channel coherent.
     */
    @SerialName("codecPrefixes") val codecPrefixes: List<String> = emptyList(),
)

/**
 * A complete, self-consistent device identity.
 *
 * The point of this class is that it is the *single* source every hook channel reads from. A
 * field cannot disagree with another field because there is only one place a field can come
 * from -- see [EffectiveProfile], the only thing the hook layer is allowed to consult.
 *
 * Values mechanically implied by other values (most importantly the build fingerprint) are
 * derived rather than stored, so they cannot drift. Where a real device genuinely deviates,
 * [fingerprintOverride] is the escape hatch.
 *
 * [androidRelease] and [sdkInt] record what this catalog entry was captured on. They are not
 * what gets reported at runtime -- see [RuntimeVersion] for why.
 */
@Serializable
data class DeviceProfile(
    @SerialName("key") val key: String,
    /** Human-readable catalog name, e.g. "Xiaomi Mi 11". Never reported to apps. */
    @SerialName("name") val name: String,

    // ---- identity -----------------------------------------------------------
    /** `ro.product.brand` / `Build.BRAND`, e.g. "Xiaomi". */
    @SerialName("brand") val brand: String,
    /** `ro.product.manufacturer` / `Build.MANUFACTURER`, e.g. "Xiaomi". */
    @SerialName("manufacturer") val manufacturer: String,
    /** `ro.product.model` / `Build.MODEL`, e.g. "M2011K2C". */
    @SerialName("model") val model: String,
    /** `ro.product.name` -- the product codename, e.g. "venus". NOT the model. */
    @SerialName("product") val product: String,
    /** `ro.product.device` -- the device codename, e.g. "venus". NOT the model. */
    @SerialName("device") val device: String,

    // ---- build --------------------------------------------------------------
    /** Release the catalog entry was captured on. Informational; see [RuntimeVersion]. */
    @SerialName("androidRelease") val androidRelease: String,
    /** API level the catalog entry was captured on. Informational; see [RuntimeVersion]. */
    @SerialName("sdkInt") val sdkInt: Int,
    /** `ro.build.version.security_patch`. */
    @SerialName("securityPatch") val securityPatch: String = "",
    /** `Build.ID` / `ro.build.id`. */
    @SerialName("buildId") val buildId: String,
    /** `ro.build.version.incremental` -- the third component of the fingerprint. */
    @SerialName("buildIncremental") val buildIncremental: String,
    /** `Build.TYPE` / `ro.build.type`, almost always "user". */
    @SerialName("buildType") val buildType: String = "user",
    /** `Build.TAGS` / `ro.build.tags`, almost always "release-keys". */
    @SerialName("buildTags") val buildTags: String = "release-keys",
    @SerialName("buildHost") val buildHost: String = "localhost",
    @SerialName("buildUser") val buildUser: String = "builder",
    @SerialName("buildTimeMs") val buildTimeMs: Long = 0L,
    /** `ro.build.display.id`, e.g. "V13.0.4.0.SKBCNXM". */
    @SerialName("displayId") val displayId: String = "",
    @SerialName("bootloader") val bootloader: String = "unknown",

    // ---- components ---------------------------------------------------------
    @SerialName("socKey") val socKey: String,
    @SerialName("display") val display: DisplayProfile,
    /**
     * Nominal memory this handset shipped with, in bytes; 0 when unknown.
     *
     * A **compatibility constraint, not a reported value**. Total memory is the one hardware
     * fact that survives every layer of Guise -- `ActivityManager.MemoryInfo` is a binder call
     * and `/proc/meminfo` is the kernel's own count -- so there is nothing to spoof. What the
     * catalog can do is refuse to offer a profile whose memory contradicts the handset, which is
     * what [Compatibility] does and what the picker shows.
     *
     * Must be one of [RamTier.capacitiesGiB] times [RamTier.GIB], or 0. A device entry
     * represents one SKU: if a handset was sold in several memory configurations, add an entry
     * per configuration rather than picking one and hoping.
     */
    @SerialName("ramBytes") val ramBytes: Long = 0L,

    // ---- optional -----------------------------------------------------------
    @SerialName("serial") val serial: String? = null,
    @SerialName("fingerprintOverride") val fingerprintOverride: String? = null,
) {
    /**
     * `ro.build.fingerprint`, derived from [runtime].
     *
     * The canonical layout is `BRAND/PRODUCT/DEVICE:RELEASE/ID/INCREMENTAL:TYPE/TAGS`. It is
     * computed rather than stored precisely so that it can never contradict [brand],
     * [product], [device] and the release. A stored fingerprint that disagrees with the rest
     * of the identity is one of the easiest things for a fingerprinting SDK to catch.
     */
    fun fingerprint(runtime: RuntimeVersion = RuntimeVersion.of(this)): String =
        fingerprintOverride ?: buildString {
            append(brand).append('/').append(product).append('/').append(device)
            append(':')
            append(runtime.release).append('/').append(buildId).append('/').append(buildIncremental)
            append(':')
            append(buildType).append('/').append(buildTags)
        }

    /** For catalog preview, where no runtime version is available. */
    val fingerprint: String get() = fingerprint()

    /** `ro.build.description`, the fingerprint in space-separated form. */
    val description: String
        get() = "$product-$buildType $androidRelease $buildId $buildIncremental $buildTags"

    /**
     * Structural self-checks. These are the invariants the old field-by-field design could not
     * express at all -- in AppEnv nothing stopped you setting MODEL to a Pixel while leaving
     * FINGERPRINT pointing at a Xiaomi.
     */
    fun invariants(): List<String> = buildList {
        if (brand.isBlank()) add("brand is blank")
        if (model.isBlank()) add("model is blank")
        if (product.isBlank()) add("product is blank")
        if (device.isBlank()) add("device is blank")
        if (socKey.isBlank()) add("socKey is blank")
        if (sdkInt !in 27..40) add("sdkInt $sdkInt outside the supported range 27..40")
        if (buildIncremental.isBlank()) add("buildIncremental is blank (fingerprint would be malformed)")
        // Zero is "unknown" and allowed; negative is a bug. Resolution is either known on both
        // axes or on neither -- a width without a height is not a panel that exists.
        if (display.widthPx < 0 || display.heightPx < 0 || display.densityDpi < 0) {
            add("display values must not be negative")
        }
        if ((display.widthPx > 0) != (display.heightPx > 0)) {
            add(
                "display resolution must be known on both axes or neither, got " +
                    "${display.widthPx}x${display.heightPx}",
            )
        }
        if (fingerprint.count { it == ':' } != 2) {
            add("fingerprint must contain exactly two ':' separators, got: $fingerprint")
        }
        // Five, not four: brand/product, product/device, release/id, id/incremental,
        // and type/tags. Verified against the canonical layout in CatalogTest.
        if (fingerprint.count { it == '/' } != 5) {
            add("fingerprint must contain exactly five '/' separators, got: $fingerprint")
        }
    }
}
