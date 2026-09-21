package io.guise.cataloggen

import io.guise.core.config.ConfigCodec
import io.guise.core.profile.DeviceCatalog
import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.RamTier

/**
 * Turns a [SeedCorpus] into the catalog that ships, or refuses to.
 *
 * The generator is deliberately small and has no cleverness in it. Everything it knows about
 * coherence lives in `:core`, so the checks that gate a generated catalog are the same ones the
 * test suite already runs against the shipped one. If the generator carried its own copy of the
 * rules, a wrong rule would validate its own output.
 *
 * Three gates, in order, and none of them is advisory:
 *
 *  1. **Every entry is attributed.** A device with no `source` cannot be emitted, because the
 *     alternative is a fingerprint that corresponds to no shipped build -- see [DeviceSeed].
 *  2. **`DeviceCatalog.validate()` is clean.** The same structural checks the app's catalog test
 *     enforces, plus the memory-capacity check.
 *  3. **The coverage policy is met**, except for tiers that carry a written waiver.
 */
object Generator {

    const val SEED_PATH = "catalog/seed/corpus.json"
    const val CATALOG_PATH = "xposed/src/main/assets/catalog.json"
    const val PROVENANCE_PATH = "catalog/PROVENANCE.md"

    /** Everything the generator produces, so a caller can write it or diff it. */
    data class Output(
        val catalog: DeviceCatalog,
        val catalogJson: String,
        val provenance: String,
        /** Gate failures. Empty means the corpus is fit to ship. */
        val errors: List<String>,
        /** Non-fatal observations worth printing: waivers, thin coverage. */
        val notes: List<String>,
    )

    fun generate(corpus: SeedCorpus): Output {
        val errors = mutableListOf<String>()
        val notes = mutableListOf<String>()

        // Gate 1: attribution. Checked before anything else is built, because an unattributed
        // entry is a provenance problem rather than a data problem and the message should say so.
        corpus.devices.forEachIndexed { index, seed ->
            if (seed.source.isBlank()) {
                errors += "device #$index ('${seed.profile.key}') has no source; " +
                    "every entry must say where its build values came from " +
                    "(a capture, a stock image, a contributor) -- an unattributed value is " +
                    "indistinguishable from an invented one"
            }
        }

        val duplicateKeys = corpus.devices.groupBy { it.profile.key }.filterValues { it.size > 1 }.keys
        duplicateKeys.forEach { errors += "device key '$it' appears more than once" }

        val devices: Map<String, DeviceProfile> = corpus.devices
            .associate { it.profile.key to it.profile }

        val catalog = DeviceCatalog(
            // Left at 1 deliberately. The field is the *format* version and will gate remote
            // catalogs once those exist; bumping it for an additive field would teach readers to
            // ignore it. Adding `ramBytes` is additive in both directions -- an older catalog
            // decodes to 0, which means "unknown", which is a state the app already handles.
            version = 1,
            socs = corpus.socs,
            devices = devices,
        )

        // Gate 2: the shared structural validator.
        errors += catalog.validate()

        // Gate 3: coverage, waivers honoured but reported.
        val waived = corpus.policy.waivedRamGiB.keys.mapNotNull(String::toIntOrNull).toSet()
        val uncovered = catalog.uncoveredRamGiB(corpus.policy.requiredRamGiB)
        uncovered.forEach { tier ->
            if (tier in waived) {
                notes += "memory tier ${tier} GB is uncovered by agreement: " +
                    corpus.policy.waivedRamGiB[tier.toString()].orEmpty()
            } else {
                errors += "no device covers the ${tier} GB memory tier, and no waiver explains " +
                    "why; a handset in that tier would have nothing coherent to wear"
            }
        }
        corpus.policy.waivedRamGiB.keys.forEach { key ->
            if (key.toIntOrNull() !in corpus.policy.requiredRamGiB) {
                notes += "waiver for '$key' is stale: it is not in requiredRamGiB"
            }
        }
        val required = corpus.policy.requiredRamGiB.toSet()
        corpus.policy.outOfScopeRamGiB.forEach { (key, why) ->
            if (key.toIntOrNull() in required) {
                errors += "tier '$key' is both required and out of scope; pick one ($why)"
            }
        }

        // Coverage is only half the story, and the other half is the one that quietly makes old
        // entries unusable: a profile can only be worn on an Android release its handset actually
        // received. The catalog records one build per device, so a Pixel 3a entry used on an
        // Android 16 handset produces a fingerprint for a combination that never shipped. That is
        // reported here rather than silently accepted, and closing it means one build entry per
        // (device, release) -- see docs/FINDINGS.md.
        val releasesCovered = corpus.devices.map { it.profile.androidRelease }.distinct().sorted()
        notes += "catalog entries exist for Android $releasesCovered only; a profile worn on a " +
            "release its handset never received (e.g. a 2021 mid-range entry on Android 16) is " +
            "an impossible combination, and per-release build entries are still to come"

        corpus.policy.requiredAbis.forEach { abi ->
            if (corpus.socs.values.none { abi in it.abis }) {
                errors += "no SoC provides the required ABI $abi"
            }
        }

        // Dead weight is worth naming: an SoC nothing points at is usually a half-finished
        // device entry rather than a deliberate spare.
        val unusedSocs = corpus.socs.keys - devices.values.map { it.socKey }.toSet()
        if (unusedSocs.isNotEmpty()) {
            notes += "SoCs with no device: ${unusedSocs.sorted().joinToString(", ")}"
        }

        // Reported rather than enforced. A hard gate on this would have to be set at zero to mean
        // anything, and that would block the corpus from being written at all -- the honest move
        // is to keep the number visible until it is zero, not to make it invisible by fiat.
        val unverified = corpus.devices.filter { !it.verified }
        if (unverified.isNotEmpty()) {
            notes += "${unverified.size} of ${corpus.devices.size} entries are unverified " +
                "(hand-entered, not read off a published build or a handset): " +
                unverified.map { it.profile.key }.sorted().joinToString(", ")
        }

        return Output(
            catalog = catalog,
            catalogJson = ConfigCodec.encodeCatalog(catalog).trimEnd() + "\n",
            provenance = provenanceOf(corpus, catalog),
            errors = errors,
            notes = notes,
        )
    }

    /**
     * A human-readable account of every entry's origin.
     *
     * Generated rather than hand-written so it cannot fall behind the corpus, and so a reviewer
     * of a catalog diff has the evidence in the same change.
     */
    private fun provenanceOf(corpus: SeedCorpus, catalog: DeviceCatalog): String = buildString {
        appendLine("# Where the catalog came from")
        appendLine()
        appendLine("Generated by `:catalog-gen` from `$SEED_PATH`. **Do not edit by hand** -- edit")
        appendLine("the seed and re-run the generator. Regenerate with:")
        appendLine()
        appendLine("```")
        appendLine("./gradlew :catalog-gen:run --args=\"--write\"")
        appendLine("```")
        appendLine()
        appendLine("Every entry states where its build values came from. This is not bookkeeping: a")
        appendLine("fingerprint that matches no shipped build is *more* distinctive than a common real")
        appendLine("one, so an invented value would defeat the purpose of spoofing at all.")
        appendLine()
        appendLine("| Device | Key | Memory | Verified | Source | Link |")
        appendLine("|---|---|---|---|---|---|")
        corpus.devices.sortedBy { it.profile.key }.forEach { seed ->
            val p = seed.profile
            val link = if (seed.sourceUrl.isBlank()) "--" else "[evidence](${seed.sourceUrl})"
            appendLine(
                "| ${p.name} | `${p.key}` | ${RamTier.label(p.ramBytes)} | " +
                    "${if (seed.verified) "yes" else "**no**"} | " +
                    "${seed.source.replace("|", "\\|")} | $link |",
            )
        }
        appendLine()
        val unverified = corpus.devices.count { !it.verified }
        if (unverified > 0) {
            appendLine(
                "**$unverified of ${corpus.devices.size} entries are unverified.** They were",
            )
            appendLine(
                "entered by hand from published specifications and nobody has re-checked them",
            )
            appendLine(
                "against the builds they claim to be. Treat their fingerprints as plausible",
            )
            appendLine("rather than confirmed.")
            appendLine()
        }
        appendLine()
        appendLine("## Coverage")
        appendLine()
        appendLine("- Memory tiers covered: ${catalog.coveredRamGiB().joinToString(", ") { "$it GB" }}")
        appendLine("- Memory tiers required: " +
            corpus.policy.requiredRamGiB.joinToString(", ") { "$it GB" })
        val waived = corpus.policy.waivedRamGiB
        if (waived.isEmpty()) {
            appendLine("- Waivers: none")
        } else {
            appendLine("- **Waived (required but uncovered):**")
            waived.toSortedMap(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE }).forEach { (tier, why) ->
                appendLine("  - **$tier GB** -- $why")
            }
        }
        val outOfScope = corpus.policy.outOfScopeRamGiB
        if (outOfScope.isNotEmpty()) {
            appendLine("- Deliberately out of scope:")
            outOfScope.toSortedMap(compareBy { it.toIntOrNull() ?: Int.MAX_VALUE }).forEach { (tier, why) ->
                appendLine("  - **$tier GB** -- $why")
            }
        }
        appendLine(
            "- Android releases with entries: " +
                corpus.devices.map { it.profile.androidRelease }.distinct().sorted()
                    .joinToString(", ") { "Android $it" },
        )
        appendLine()
        appendLine("One build per device means one release per device. A profile worn on a release")
        appendLine("its handset never received is an impossible combination, so releases without an")
        appendLine("entry are a gap in the corpus rather than a property of the handset.")
        appendLine()
        appendLine("## SoCs")
        appendLine()
        appendLine("| SoC | Marketing name | ABI | Codec prefixes |")
        appendLine("|---|---|---|---|")
        catalog.socs.values.sortedBy { it.key }.forEach { s ->
            appendLine(
                "| `${s.key}` | ${s.marketingName} | ${s.abis.joinToString(", ")} | " +
                    "${s.codecPrefixes.joinToString(", ").ifBlank { "--" }} |",
            )
        }
    }
}
