package io.guise.cataloggen

import io.guise.core.profile.DeviceCatalog
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * `:catalog-gen` -- the only thing allowed to write `catalog.json`.
 *
 * Three modes, all of them run from the repository root:
 *
 *  - `--check`  regenerate in memory and compare with what is committed. Non-zero exit on any
 *               gate failure or on drift. This is the CI gate: it makes "the shipped catalog is
 *               a function of the seed" a fact rather than a convention.
 *  - `--write`  regenerate and write both the catalog and `catalog/PROVENANCE.md`.
 *  - `--adopt`  bootstrap the seed corpus from an existing `catalog.json`. A migration tool, run
 *               once; afterwards the seed is the source of truth and the asset is derived.
 *
 * The parsing here is strict on purpose. `ConfigCodec.decodeCatalog` is tolerant -- it has to be,
 * it runs in an app that must not crash on a malformed config -- and tolerance is wrong for a
 * tool whose job is to notice that something is off. A strict reader turns "the file is corrupt"
 * into an error instead of into an empty catalog that then generates an empty seed.
 */
private val strictJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "--check"
    val root = File(".").absoluteFile
    val seedFile = File(root, Generator.SEED_PATH)
    val catalogFile = File(root, Generator.CATALOG_PATH)
    val provenanceFile = File(root, Generator.PROVENANCE_PATH)

    when (mode) {
        "--adopt" -> adopt(seedFile, catalogFile)
        "--write" -> write(seedFile, catalogFile, provenanceFile)
        "--check" -> check(seedFile, catalogFile)
        else -> {
            System.err.println("usage: catalog-gen [--check|--write|--adopt]")
            exitProcess(2)
        }
    }
}

private fun loadSeed(seedFile: File): SeedCorpus {
    if (!seedFile.exists()) {
        System.err.println("seed corpus not found at ${seedFile.path}")
        System.err.println("bootstrap it once with: ./gradlew :catalog-gen:run --args=\"--adopt\"")
        exitProcess(2)
    }
    return try {
        strictJson.decodeFromString(SeedCorpus.serializer(), seedFile.readText())
    } catch (e: Exception) {
        System.err.println("seed corpus at ${seedFile.path} could not be parsed: ${e.message}")
        exitProcess(2)
    }
}

private fun adopt(seedFile: File, catalogFile: File) {
    if (!catalogFile.exists()) {
        System.err.println("nothing to adopt: ${catalogFile.path} does not exist")
        exitProcess(2)
    }
    val catalog: DeviceCatalog = strictJson.decodeFromString(
        DeviceCatalog.serializer(),
        catalogFile.readText(),
    )

    // Provenance is intentionally left blank. Filling it in is a human act -- the point of the
    // field is that somebody has to know where the numbers came from -- and `--check` will refuse
    // to emit anything until it is done.
    val corpus = SeedCorpus(
        socs = catalog.socs,
        devices = catalog.devices.values
            .sortedBy { it.key }
            .map { DeviceSeed(profile = it) },
        policy = CoveragePolicy(
            waivedRamGiB = mapOf(
                "16" to "no verified 16 GB entry yet; the catalog generator is meant to close " +
                    "this from real Pixel build data",
            ),
        ),
    )

    seedFile.parentFile.mkdirs()
    seedFile.writeText(strictJson.encodeToString(SeedCorpus.serializer(), corpus).trimEnd() + "\n")
    println("adopted ${catalog.socs.size} SoCs and ${catalog.devices.size} devices into ${seedFile.path}")
    println("every device now needs a `source`. Then run --write.")
}

private fun write(seedFile: File, catalogFile: File, provenanceFile: File) {
    val output = report(loadSeed(seedFile))
    if (output.errors.isNotEmpty()) {
        System.err.println("\nrefusing to write: the seed corpus does not pass its own gates")
        exitProcess(1)
    }
    catalogFile.parentFile.mkdirs()
    catalogFile.writeText(output.catalogJson)
    provenanceFile.parentFile.mkdirs()
    provenanceFile.writeText(output.provenance)
    println("\nwrote ${catalogFile.path} (${output.catalogJson.length} bytes)")
    println("wrote ${provenanceFile.path}")
}

private fun check(seedFile: File, catalogFile: File) {
    val output = report(loadSeed(seedFile))
    if (output.errors.isNotEmpty()) exitProcess(1)

    if (!catalogFile.exists()) {
        System.err.println("${catalogFile.path} does not exist; run --write")
        exitProcess(1)
    }

    // Compared semantically, not byte-for-byte. Whitespace and key order are the encoder's
    // business, and a check that fails on formatting teaches people to regenerate blindly
    // instead of reading the diff.
    val committed = strictJson.decodeFromString(
        DeviceCatalog.serializer(),
        catalogFile.readText(),
    )
    if (committed != output.catalog) {
        System.err.println(
            "catalog drift: ${catalogFile.path} is not what the seed generates.\n" +
                "  committed: ${committed.devices.size} devices, " +
                "${committed.socs.size} SoCs, tiers ${committed.coveredRamGiB()}\n" +
                "  generated: ${output.catalog.devices.size} devices, " +
                "${output.catalog.socs.size} SoCs, tiers ${output.catalog.coveredRamGiB()}\n" +
                "  run: ./gradlew :catalog-gen:run --args=\"--write\"",
        )
        exitProcess(1)
    }
    println("\ncatalog matches the seed")
}

private fun report(corpus: SeedCorpus): Generator.Output {
    val output = Generator.generate(corpus)
    println(
        "generated ${output.catalog.devices.size} devices over ${output.catalog.socs.size} SoCs; " +
            "memory tiers ${output.catalog.coveredRamGiB().joinToString(", ")}",
    )
    output.notes.forEach { println("  note: $it") }
    if (output.errors.isEmpty()) {
        println("  gates: clean")
    } else {
        output.errors.forEach { System.err.println("  error: $it") }
        System.err.println("  gates: ${output.errors.size} failure(s)")
    }
    return output
}
