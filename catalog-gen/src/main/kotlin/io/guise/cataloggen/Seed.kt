package io.guise.cataloggen

import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.SocProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One catalog entry together with an account of where its values came from.
 *
 * The provenance fields are not decoration and they are not optional in spirit: this project's
 * rule is that a build fingerprint must correspond to a build that actually shipped. A
 * synthesised-yet-plausible fingerprint is *worse* than a common real one, because it matches no
 * handset in any database -- it is unique, and unique is the opposite of what a spoofed identity
 * is for. So every entry carries a source, [Generator] refuses to emit one that does not, and
 * `catalog/PROVENANCE.md` is generated from these fields for review.
 */
@Serializable
data class DeviceSeed(
    @SerialName("profile") val profile: DeviceProfile,
    /** Where the build values came from, in a sentence. Required. */
    @SerialName("source") val source: String = "",
    /** Direct link to the evidence, when one exists. */
    @SerialName("sourceUrl") val sourceUrl: String = "",
    /**
     * True only when the values were read off a machine or a published build that somebody can
     * point at.
     *
     * This exists because the alternative is a two-valued world where "hand-entered from a spec
     * sheet" and "captured on a handset" look the same in the file. They are not the same, and
     * the difference is precisely the one this project keeps getting burned by: of the fourteen
     * entries the catalog started with, one is a capture and thirteen are transcriptions that
     * nobody has re-checked. Saying so in the data is cheaper than discovering it again.
     */
    @SerialName("verified") val verified: Boolean = false,
    /** One memory configuration per entry: this handset's nominal capacity. */
    @SerialName("note") val note: String = "",
)

/**
 * The hand-maintained inputs the catalog is generated from.
 *
 * Kept as two files because they change at completely different rates and for different reasons.
 * The SoC table is hardware knowledge -- a chip's GPU string, its ABI list, its codec vendor
 * prefixes -- and a new entry is added when a new chip appears. The device table grows whenever
 * someone contributes a capture or a verified stock build.
 *
 * Deliberately a *seed*, not the catalog: `xposed/src/main/assets/catalog.json` is generated, and
 * `--check` fails the build when the two disagree. That is what keeps the shipped file honest
 * about being derived rather than hand-edited.
 */
@Serializable
data class SeedCorpus(
    @SerialName("socs") val socs: Map<String, SocProfile> = emptyMap(),
    @SerialName("devices") val devices: List<DeviceSeed> = emptyList(),
    @SerialName("policy") val policy: CoveragePolicy = CoveragePolicy(),
)

/**
 * What the catalog must be able to serve.
 *
 * Coverage is stated as *memory tiers*, not as a device count, because the device count was the
 * number that looked reassuring while the catalog covered two tiers out of six. A handset in an
 * uncovered tier has nothing coherent to wear, and no amount of devices in other tiers helps it.
 *
 * Three ways a tier can relate to the policy, and the distinction is the point:
 *
 *  - **required** and covered -- the goal.
 *  - **required** and [waivedRamGiB] -- a known gap with a written reason and a plan. An
 *    unexplained gap is a bug; an explained one is a schedule.
 *  - [outOfScopeRamGiB] -- deliberately not a requirement, with a reason. Saying "4 GB is not
 *    required because no 4 GB handset received a modern release in volume" is a different claim
 *    from forgetting about 4 GB, and the file should not conflate them.
 */
@Serializable
data class CoveragePolicy(
    @SerialName("requiredRamGiB") val requiredRamGiB: List<Int> = listOf(6, 8, 12, 16),
    /** tier -> why it is required but not covered yet. */
    @SerialName("waivedRamGiB") val waivedRamGiB: Map<String, String> = emptyMap(),
    /** tier -> why it is deliberately not a requirement. */
    @SerialName("outOfScopeRamGiB") val outOfScopeRamGiB: Map<String, String> = emptyMap(),
    @SerialName("requiredAbis") val requiredAbis: List<String> = listOf("arm64-v8a"),
)
