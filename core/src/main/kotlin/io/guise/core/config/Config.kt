package io.guise.core.config

import io.guise.core.profile.DeviceCatalog
import io.guise.core.profile.EffectiveProfile
import io.guise.core.profile.RuntimeVersion
import io.guise.core.privacy.PrivacyDomain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What to do to one target package.
 *
 * [profileKey] selects a device from the catalog. [overrides] then pins individual
 * [io.guise.core.profile.FieldKey]s away from that profile. Overrides are keyed by
 * [io.guise.core.profile.FieldKey.id] and stored as strings so the blob stays readable
 * and forward-compatible.
 *
 * The ordering matters: the profile is the base, overrides are the exception. The old
 * Guise had no base at all, which is why every field was free to contradict every other.
 */
@Serializable
data class TargetConfig(
    @SerialName("packageName") val packageName: String,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("profileKey") val profileKey: String? = null,
    @SerialName("overrides") val overrides: Map<String, String> = emptyMap(),
    /**
     * Hide codecs whose vendor prefix contradicts the claimed SoC.
     *
     * Off by default, and that default is a deliberate trade rather than caution.
     * `MediaCodecList` names its components with the silicon vendor in the prefix --
     * `c2.mtk.*` on a MediaTek part, `c2.qti.*` on a Qualcomm one -- and that list comes from
     * the platform's vendor configuration, so a Java-layer identity rewrite does not touch it.
     * A handset claiming to be a Pixel while listing MediaTek decoders is caught immediately.
     *
     * The catch is that the list can only be *filtered*, never extended: codecs the device does
     * not have cannot be invented. Filtering therefore makes the reported list disagree with
     * the hardware in a different way, and can hide a codec a target app actually needs, which
     * shows up as broken playback. A visibly broken app is worse than a detectable one, so this
     * stays opt-in per target.
     */
    @SerialName("stripForeignCodecs") val stripForeignCodecs: Boolean = false,

    /**
     * Privacy domains this target should be answered with nothing.
     *
     * Stored as domain ids rather than as an enum so an older build tolerates a config written
     * by a newer one: unknown ids are dropped by
     * [io.guise.core.privacy.PrivacyDomain.parse] instead of failing to deserialise.
     *
     * Note what this does **not** do: it never touches the app's permissions. The permission
     * really is granted, and the data source is emptied -- see
     * [io.guise.core.privacy.PrivacyDomain] for why that distinction is the whole point.
     */
    @SerialName("emptiedDomains") val emptiedDomains: Set<String> = emptySet(),
)

@Serializable
data class ModuleConfig(
    @SerialName("version") val version: Int = CURRENT_VERSION,
    @SerialName("globallyEnabled") val globallyEnabled: Boolean = true,
    @SerialName("targets") val targets: Map<String, TargetConfig> = emptyMap(),
) {
    fun target(packageName: String): TargetConfig? = targets[packageName]

    fun withTarget(target: TargetConfig): ModuleConfig =
        copy(targets = targets + (target.packageName to target))

    fun withoutTarget(packageName: String): ModuleConfig =
        copy(targets = targets - packageName)

    companion object {
        const val CURRENT_VERSION = 1
        val EMPTY = ModuleConfig()
    }
}

/**
 * JSON codec for the config blob.
 *
 * `ignoreUnknownKeys` is deliberate: a newer module version must be able to write a
 * config that an older one still loads without crashing.
 */
object ConfigCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(config: ModuleConfig): String = json.encodeToString(ModuleConfig.serializer(), config)

    fun decode(text: String?): ModuleConfig {
        if (text.isNullOrBlank()) return ModuleConfig.EMPTY
        return runCatching { json.decodeFromString(ModuleConfig.serializer(), text) }
            .getOrElse { ModuleConfig.EMPTY }
    }

    fun encodeCatalog(catalog: DeviceCatalog): String =
        json.encodeToString(DeviceCatalog.serializer(), catalog)

    fun decodeCatalog(text: String?): DeviceCatalog {
        if (text.isNullOrBlank()) return DeviceCatalog()
        return runCatching { json.decodeFromString(DeviceCatalog.serializer(), text) }
            .getOrElse { DeviceCatalog() }
    }
}

/**
 * Turns persisted config into the one object the hook layer consumes.
 *
 * Returns `null` when this package should not be touched at all, which is the common
 * case -- the module is installed but the user has not configured this app.
 */
object ConfigResolver {
    /**
     * @param runtime the Android version this process is actually running, so that the
     *   reported release, API level and fingerprint all agree with the device. Passing null
     *   falls back to the profile's own version, which is only appropriate for preview.
     */
    fun resolve(
        config: ModuleConfig,
        catalog: DeviceCatalog,
        packageName: String,
        runtime: RuntimeVersion? = null,
    ): EffectiveProfile? {
        if (!config.globallyEnabled) return null
        val target = config.target(packageName) ?: return null
        if (!target.enabled) return null
        val key = target.profileKey ?: return null
        val device = catalog.devices[key] ?: return null
        return EffectiveProfile.of(
            device = device,
            catalog = catalog,
            overrides = target.overrides,
            runtime = runtime ?: RuntimeVersion.of(device),
            stripForeignCodecs = target.stripForeignCodecs,
            emptiedDomains = PrivacyDomain.parse(target.emptiedDomains),
        )
    }
}
