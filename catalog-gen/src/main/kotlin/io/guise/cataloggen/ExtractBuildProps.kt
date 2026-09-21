package io.guise.cataloggen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * One device build recovered from a firmware `build.prop` dump.
 *
 * The values are the firmware's own, read out of the properties the platform itself ships. What
 * this source *cannot* supply is anything that is not a property: memory, model marketing names,
 * panel resolution and the GPU renderer are all absent from every `build.prop`, which is why the
 * generator combines these with the shared SoC table instead of inventing the difference.
 */
@Serializable
data class BuildPropEntry(
    /** Repository-relative dump directory, so any entry can be traced back to its file. */
    @SerialName("dump") val dump: String,
    @SerialName("platform") val platform: String = "",
    /** Resolved SoC key, or blank when the platform is not one the table knows. */
    @SerialName("socKey") val socKey: String = "",
    @SerialName("fingerprint") val fingerprint: String = "",
    @SerialName("brand") val brand: String = "",
    @SerialName("product") val product: String = "",
    @SerialName("device") val device: String = "",
    @SerialName("model") val model: String = "",
    @SerialName("release") val release: String = "",
    @SerialName("sdkInt") val sdkInt: Int = 0,
    @SerialName("buildId") val buildId: String = "",
    @SerialName("incremental") val incremental: String = "",
    @SerialName("type") val type: String = "",
    @SerialName("tags") val tags: String = "",
    @SerialName("securityPatch") val securityPatch: String = "",
    @SerialName("lcdDensity") val lcdDensity: Int = 0,
    @SerialName("buildHost") val buildHost: String = "",
    @SerialName("buildUser") val buildUser: String = "",
    @SerialName("buildTimeSec") val buildTimeSec: Long = 0,
    /** Days since epoch from `ro.build.date.utc`, used only for picking the newest build. */
    @SerialName("error") val error: String = "",
) {
    val usable: Boolean
        get() = error.isEmpty() && fingerprint.count { it == ':' } == 2 && socKey.isNotBlank()
}

@Serializable
data class BuildPropSnapshot(
    @SerialName("source") val source: String = "",
    @SerialName("extractedAt") val extractedAt: String = "",
    @SerialName("dumpsScanned") val dumpsScanned: Int = 0,
    @SerialName("note") val note: String = "",
    /** Why the rest were dropped, as counts rather than as 4,000 rows of noise. */
    @SerialName("rejected") val rejected: Map<String, Int> = emptyMap(),
    @SerialName("unknownPlatforms") val unknownPlatforms: Map<String, Int> = emptyMap(),
    @SerialName("entries") val entries: List<BuildPropEntry> = emptyList(),
)

private const val OUT_PATH = "catalog/raw/buildprops.json"

/**
 * Recovers device builds from a corpus of firmware `build.prop` files.
 *
 * ## Why this runs locally and separately
 *
 * The corpus is ~42,000 files across ~5,000 dumps. It is read once, reduced to the dozen fields
 * the catalog needs, and committed as a small snapshot -- so the generator itself stays offline
 * and deterministic, exactly as it does for the Pixel source. Nothing about the corpus is
 * redistributed: only facts about products, which is all the catalog ever stores.
 *
 * ## Why a platform has to be resolved to an SoC
 *
 * A dump says `ro.board.platform=kona`; the catalog says what a kona's GPU, ABI list and codec
 * prefixes are. The second half cannot be read from the file, and inventing it would produce a
 * profile whose silicon contradicts its identity. So a dump whose platform does not resolve is
 * kept with its error and reported, never guessed at.
 *
 * ## The one thing this cannot tell you
 *
 * Every `build.prop` is per-partition, and the properties are spread across them: the fingerprint
 * is usually in `system`, the platform usually in `vendor` or `odm`. The extractor therefore
 * merges *all* of a dump's prop files before reading anything, which is why a first attempt that
 * looked only at `system.system.build.prop` found a fingerprint in 250 of 4,701 files when the
 * real figure is closer to 1,500 of 5,085 dumps.
 */
fun main(args: Array<String>) {
    var dir = "."
    var out = OUT_PATH
    var limit = Int.MAX_VALUE
    var overrides: Map<String, String> = emptyMap()

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--dir" -> dir = args[++i]
            "--out" -> out = args[++i]
            "--limit" -> limit = args[++i].toInt()
            // platform=soCKey pairs. Resolves the two cases a table lookup cannot: a platform the
            // table does not name (Snapdragon 8 Gen 1 reports `taro`, the table says `waipio`),
            // and a platform two entries legitimately share (865 and 870 are both `kona`).
            "--override" -> overrides = args[++i].split(",")
                .mapNotNull { pair ->
                    val parts = pair.split("=")
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }.toMap()
            else -> {
                System.err.println(
                    "usage: extractBuildProps --dir <corpus> [--out PATH] [--limit N] " +
                        "[--override kona=sm8250,taro=sm8450]",
                )
                exitProcess(2)
            }
        }
        i++
    }

    val root = File(dir).absoluteFile
    if (!root.isDirectory) {
        System.err.println("not a directory: ${root.path}")
        exitProcess(2)
    }

    val props = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".build.prop") }
        .toList()
    println("prop files: ${props.size}")
    if (props.isEmpty()) {
        System.err.println("no *.build.prop found under ${root.path}")
        exitProcess(1)
    }

    // Group by dump directory, then merge every partition's props within a dump. Later files do
    // not override earlier ones: the props are per-partition keys, not layers, and a key that
    // appears in two places means the same thing in both.
    val byDump = props.groupBy { it.parentFile.absolutePath }
    println("dumps: ${byDump.size}")

    val platformToSoc = platformIndex(overrides)
    println("platform -> SoC: ${platformToSoc.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }}")

    val entries = ArrayList<BuildPropEntry>(byDump.size)
    val unknownPlatforms = HashMap<String, Int>()
    byDump.entries.take(limit).forEach { (dump, files) ->
        val merged = HashMap<String, String>()
        files.forEach { file ->
            runCatching {
                file.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) return@forEachLine
                    val key = trimmed.substring(0, eq).trim()
                    if (key in WANTED && key !in merged) {
                        merged[key] = trimmed.substring(eq + 1).trim()
                    }
                }
            }
        }
        val platform = (merged["ro.board.platform"] ?: merged["ro.product.board"]
            ?: merged["ro.hardware"] ?: "").lowercase()
        val socKey = platformToSoc[platform].orEmpty()
        if (socKey.isBlank() && platform.isNotBlank()) {
            unknownPlatforms[platform] = (unknownPlatforms[platform] ?: 0) + 1
        }
        entries += build(root, dump, merged, platform, socKey)
    }

    // Only usable rows are kept, and the rejections become counts. A 4 MB snapshot that is 88%
    // unusable rows is not evidence, it is noise -- and the counts are the part that says whether
    // the source is being under-used or the corpus is genuinely thin.
    val (usableRows, rejectedRows) = entries.partition { it.usable }
    val rejected = rejectedRows.groupingBy { rejectionReason(it) }.eachCount()

    val snapshot = BuildPropSnapshot(
        source = "firmware build.prop dumps under ${root.name}",
        extractedAt = Instant.now().toString(),
        dumpsScanned = byDump.size,
        note = "One entry per dump, merged across all partitions. Read from the firmware's own " +
            "properties; nothing here is inferred except the SoC, which is a lookup on " +
            "ro.board.platform against the catalog's table. Rows that could not be used are " +
            "counted in `rejected` rather than stored.",
        rejected = rejected.entries.sortedByDescending { it.value }.associate { it.key to it.value },
        unknownPlatforms = unknownPlatforms.entries.sortedByDescending { it.value }
            .take(60).associate { it.key to it.value },
        entries = usableRows.sortedBy { it.dump },
    )
    val outFile = File(out)
    outFile.parentFile?.mkdirs()
    outFile.writeText(fetchPixelJson.encodeToString(BuildPropSnapshot.serializer(), snapshot).trimEnd() + "\n")

    val usable = usableRows.size
    println()
    println("wrote ${outFile.path} (${outFile.length()} bytes)")
    println("  entries: ${entries.size}  usable: $usable")
    println("  devices: ${usableRows.map { it.device }.distinct().size}")
    println("  releases: ${usableRows.map { it.release }.distinct().sorted()}")
    println("  with lcd density: ${usableRows.count { it.lcdDensity > 0 }}")
    println("  rejected: ${rejected.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" }}")
    println("  unresolved platforms (top): " +
        unknownPlatforms.entries.sortedByDescending { it.value }.take(12)
            .joinToString(", ") { "${it.key}:${it.value}" })
}

/** A short category for why a row was dropped, so the snapshot stays small. */
private fun rejectionReason(entry: BuildPropEntry): String = when {
    entry.socKey.isBlank() -> "platform not in the SoC table"
    entry.fingerprint.isEmpty() -> "no fingerprint in any partition"
    entry.model.isBlank() -> "no ro.product.model"
    entry.device.isBlank() -> "no codename"
    else -> entry.error.ifBlank { "malformed fingerprint" }
}

/** Keys worth carrying. Anything else in a build.prop is not part of a device identity. */
private val WANTED = setOf(
    "ro.build.fingerprint",
    "ro.build.id",
    "ro.build.version.incremental",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.build.version.security_patch",
    "ro.build.type",
    "ro.build.tags",
    "ro.build.host",
    "ro.build.user",
    "ro.build.date.utc",
    "ro.product.brand",
    "ro.product.model",
    "ro.product.name",
    "ro.product.device",
    "ro.board.platform",
    "ro.product.board",
    "ro.hardware",
    "ro.sf.lcd_density",
)

/**
 * Builds the platform lookup from the catalog's own SoC table.
 *
 * `SocProfile.platform` and `SocProfile.board` are the kernel names a chip answers to, so the
 * table is already the mapping -- except where two entries share one platform (Snapdragon 865
 * and 870 are both `kona`) or where the dump uses a family name the table does not carry.
 * Both are resolved by an explicit `--override`, so the choice is recorded in the run rather
 * than decided by map iteration order.
 */
private fun platformIndex(overrides: Map<String, String>): Map<String, String> {
    val catalog = runCatching {
        io.guise.core.config.ConfigCodec.decodeCatalog(
            File(Generator.CATALOG_PATH).readText(),
        )
    }.getOrElse { io.guise.core.profile.DeviceCatalog() }

    val index = HashMap<String, String>()
    catalog.socs.values.forEach { soc ->
        listOf(soc.platform, soc.board)
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
            .forEach { name -> index.putIfAbsent(name, soc.key) }
    }
    // Explicit overrides win, including for a name the table never mentions.
    index.putAll(overrides)
    return index
}

/** Field-by-field extraction, preferring the fingerprint's own segmentation. */
private fun build(
    root: File,
    dump: String,
    props: Map<String, String>,
    platform: String,
    socKey: String,
): BuildPropEntry {
    val fingerprint = props["ro.build.fingerprint"].orEmpty()
    val parts = fingerprint.split(":")
    val identity = parts.getOrNull(0)?.split("/").orEmpty()
    val version = parts.getOrNull(1)?.split("/").orEmpty()
    val flags = parts.getOrNull(2)?.split("/").orEmpty()

    val entry = BuildPropEntry(
        dump = root.toPath().relativize(File(dump).toPath()).toString(),
        platform = platform,
        socKey = socKey,
        fingerprint = fingerprint,
        brand = identity.getOrNull(0) ?: props["ro.product.brand"].orEmpty(),
        product = identity.getOrNull(1) ?: props["ro.product.name"].orEmpty(),
        device = identity.getOrNull(2) ?: props["ro.product.device"].orEmpty(),
        model = props["ro.product.model"].orEmpty(),
        release = version.getOrNull(0) ?: props["ro.build.version.release"].orEmpty(),
        // Taken from the property rather than derived from the release string: the fingerprint's
        // first field is a marketing version ("8.1.0"), while SDK_INT is the API level the build
        // was made against, and only the prop states it.
        sdkInt = props["ro.build.version.sdk"]?.toIntOrNull() ?: 0,
        buildId = version.getOrNull(1) ?: props["ro.build.id"].orEmpty(),
        incremental = version.getOrNull(2) ?: props["ro.build.version.incremental"].orEmpty(),
        type = flags.getOrNull(0) ?: props["ro.build.type"].orEmpty(),
        tags = flags.getOrNull(1) ?: props["ro.build.tags"].orEmpty(),
        securityPatch = props["ro.build.version.security_patch"].orEmpty(),
        lcdDensity = props["ro.sf.lcd_density"]?.toIntOrNull() ?: 0,
        buildHost = props["ro.build.host"].orEmpty(),
        buildUser = props["ro.build.user"].orEmpty(),
        buildTimeSec = props["ro.build.date.utc"]?.toLongOrNull() ?: 0L,
    )
    if (entry.fingerprint.isEmpty()) {
        return entry.copy(error = "no ro.build.fingerprint in any partition")
    }
    if (fingerprint.count { it == ':' } != 2) {
        return entry.copy(error = "malformed fingerprint: ${fingerprint.take(80)}")
    }
    if (entry.device.isBlank()) return entry.copy(error = "no product/device codename")
    if (entry.model.isBlank()) return entry.copy(error = "no ro.product.model")
    if (socKey.isBlank()) {
        return entry.copy(error = "platform '$platform' is not in the SoC table")
    }
    return entry
}
