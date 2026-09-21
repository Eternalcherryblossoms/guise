package io.guise.cataloggen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.system.exitProcess

/**
 * One shipped Pixel build, as described by Google's own OTA metadata.
 *
 * Every field here except [error] comes from either the OTA index or the metadata file embedded
 * at the start of the OTA zip itself. Nothing is inferred, and nothing is written by hand: that is
 * the whole point of preferring this source over transcription. See [parseMetadata] for why
 * reading 2 KB of a 2 GB file is enough.
 */
@Serializable
data class OtaBuild(
    @SerialName("codename") val codename: String,
    /** Marketing name from the index section heading, e.g. "Pixel 8a". */
    @SerialName("marketingName") val marketingName: String = "",
    /** The index's own version cell, kept verbatim as evidence of what was read. */
    @SerialName("versionLabel") val versionLabel: String = "",
    @SerialName("otaUrl") val otaUrl: String = "",
    @SerialName("sha256") val sha256: String = "",
    // ---- from META-INF/com/android/metadata ----
    @SerialName("fingerprint") val fingerprint: String = "",
    @SerialName("release") val release: String = "",
    @SerialName("buildId") val buildId: String = "",
    @SerialName("incremental") val incremental: String = "",
    @SerialName("type") val type: String = "",
    @SerialName("tags") val tags: String = "",
    @SerialName("securityPatch") val securityPatch: String = "",
    @SerialName("sdkInt") val sdkInt: Int = 0,
    @SerialName("timestamp") val timestamp: Long = 0,
    /** Non-empty when the metadata could not be read; the row is kept so the failure is visible. */
    @SerialName("error") val error: String = "",
) {
    /** True when the metadata read succeeded and produced a well-formed fingerprint. */
    val usable: Boolean get() = error.isEmpty() && fingerprint.count { it == ':' } == 2
}

@Serializable
data class PixelOtaSnapshot(
    @SerialName("source") val source: String = "",
    @SerialName("fetchedAt") val fetchedAt: String = "",
    @SerialName("indexBytes") val indexBytes: Int = 0,
    @SerialName("note") val note: String = "",
    @SerialName("builds") val builds: List<OtaBuild> = emptyList(),
)

private val snapshotJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private const val DEFAULT_INDEX = "https://developers.google.cn/android/ota?hl=en"

/**
 * The host used for the ranged metadata reads, which is deliberately not the index's host.
 *
 * The index is served from the `developers.google.cn` / `googledownloads.cn` mirror because that
 * mirror serves the table statically. Its file host answers ranged requests with **HTTP 429** --
 * observed on all twelve attempts of the first run -- while `dl.google.com` serves the identical
 * path with `206 Partial Content`. Rewriting the host and leaving the path untouched is the whole
 * fix, and it is recorded here because the failure looks like rate limiting rather than like a
 * wrong host.
 */
internal const val DEFAULT_DOWNLOAD_HOST = "dl.google.com"

private const val SNAPSHOT_PATH = "catalog/raw/pixel-ota.json"

/**
 * Fetches Google's OTA index and reads each build's metadata.
 *
 * ## Why this source
 *
 * `developers.google.com/android/ota` renders its table with JavaScript and returns a shell with
 * zero build IDs in it. The China mirror `developers.google.cn` serves the *same table statically*
 * -- 681 KB of HTML with the device sections, build IDs, download URLs and checksums in it. That
 * difference is the entire reason this pipeline exists rather than a transcription effort.
 *
 * ## Why 2 KB per build
 *
 * An A/B OTA zip begins with a plain-text `META-INF/com/android/metadata` file whose contents
 * include `post-build=<the complete 8-field fingerprint>`, `post-security-patch-level` and
 * `post-sdk-level`. A ranged GET of the first 2 KB therefore yields the entire build identity from
 * a 2 GB download that is never performed. Verified end to end before this was written; factory
 * images do **not** carry the file, so OTA zips are the only usable form.
 *
 * ## What it deliberately does not do
 *
 * It does not invent anything to fill a gap. A row whose metadata cannot be read is written to the
 * snapshot with its error attached, so a failed fetch looks like a failed fetch instead of a
 * silently missing device. It also does not dedupe: selection is the generator's job, and keeping
 * every fetched build means a later change of mind costs nothing.
 */
fun main(args: Array<String>) {
    var indexUrl = DEFAULT_INDEX
    var downloadHost = DEFAULT_DOWNLOAD_HOST
    var out = SNAPSHOT_PATH
    var limit = Int.MAX_VALUE
    var only: Set<String> = emptySet()

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--index" -> indexUrl = args[++i]
            "--download-host" -> downloadHost = args[++i]
            "--out" -> out = args[++i]
            "--limit" -> limit = args[++i].toInt()
            "--only" -> only = args[++i].split(",").map(String::trim).filter(String::isNotEmpty).toSet()
            else -> {
                System.err.println(
                    "usage: fetchPixel [--index URL] [--download-host HOST] [--out PATH] " +
                        "[--limit N] [--only a,b]",
                )
                exitProcess(2)
            }
        }
        i++
    }

    val client = httpClient()
    val outFile = File(out)
    // Incremental by construction: a re-run re-reads the index (cheap) but only range-reads builds
    // it does not already hold a good fingerprint for. The index is the only part that can change.
    val previous = if (outFile.exists()) {
        runCatching { snapshotJson.decodeFromString(PixelOtaSnapshot.serializer(), outFile.readText()) }
            .getOrElse { PixelOtaSnapshot() }
    } else {
        PixelOtaSnapshot()
    }
    val cached = previous.builds.filter { it.usable }.associateBy { it.otaUrl }
    println("cache: ${cached.size} usable builds from ${previous.fetchedAt.ifBlank { "nothing" }}")

    println("fetching index: $indexUrl")
    val html = get(client, indexUrl, null) ?: run {
        System.err.println("could not fetch the index")
        exitProcess(1)
    }
    println("index: ${html.length} bytes")
    println(
        "  markers: <h2 id=\" x${Regex("<h2 id=\"").findAll(html).count()}" +
            "  <tr x${Regex("<tr").findAll(html).count()}" +
            "  aosp x${Regex("aosp/").findAll(html).count()}" +
            "  data-text x${Regex("data-text=").findAll(html).count()}",
    )

    val parsed = parseIndex(html)
    val rows = if (only.isEmpty()) parsed else parsed.filter { r -> r.codename in only }
    println("index rows: ${parsed.size} parsed, ${rows.size} after --only")
    if (parsed.isEmpty()) {
        System.err.println("the index parsed to nothing; markers are above, the parser is wrong")
        exitProcess(1)
    }
    parsed.take(2).forEach { println("  e.g. ${it.codename} / ${it.marketingName} / ${it.buildId}") }

    val results = ArrayList<OtaBuild>(rows.size)
    var fetched = 0
    var failed = 0
    var reused = 0
    rows.forEachIndexed { index, row ->
        val already = cached[row.otaUrl]
        if (already != null) {
            reused++
            results += already.copy(marketingName = row.marketingName, versionLabel = row.versionLabel)
        } else if (fetched >= limit) {
            results += row
        } else {
            val text = get(client, rewriteHost(row.otaUrl, downloadHost), "bytes=0-2047")
            fetched++
            val parsed = if (text == null) {
                failed++
                row.copy(error = "range GET failed")
            } else {
                parseMetadata(text, row)
            }
            if (!parsed.usable) failed++
            results += parsed
        }
        if ((index + 1) % 100 == 0) {
            println("  ${index + 1}/${rows.size}  fetched=$fetched reused=$reused failed=$failed")
        }
    }

    val snapshot = PixelOtaSnapshot(
        source = indexUrl,
        fetchedAt = Instant.now().toString(),
        indexBytes = html.length,
        note = "META-INF/com/android/metadata is read from the first 2 KB of each OTA zip via a " +
            "ranged GET. Rows with a non-empty error could not be read and are kept so the gap " +
            "is visible rather than silently absent.",
        builds = results.sortedWith(compareBy({ it.codename }, { it.release }, { it.buildId })),
    )
    outFile.parentFile?.mkdirs()
    outFile.writeText(snapshotJson.encodeToString(PixelOtaSnapshot.serializer(), snapshot).trimEnd() + "\n")

    val usable = results.count { it.usable }
    println()
    println("wrote ${outFile.path} (${outFile.length()} bytes)")
    println("  builds: ${results.size}  usable: $usable  failed: $failed")
    println("  devices: ${results.map { it.codename }.distinct().size}")
    println("  releases: ${results.filter { it.usable }.map { it.release }.distinct().sorted()}")
    if (failed > 0) {
        println("  first failures:")
        results.filter { !it.usable }.take(5).forEach { println("    ${it.codename} ${it.buildId}: ${it.error}") }
    }
}

/**
 * Replaces a URL's host, keeping scheme and path.
 *
 * Used only to move the metadata reads off the index's CDN, which answers ranged requests with
 * 429. Deliberately not a general URL rewriter: it refuses anything that is not https and keeps
 * the path byte-identical, so the checksum in the index still describes the file being read.
 */
internal fun rewriteHost(url: String, host: String): String {
    val rest = url.removePrefix("https://")
    val slash = rest.indexOf('/')
    if (slash <= 0) return url
    return "https://$host" + rest.substring(slash)
}

private fun httpClient(): HttpClient {
    // The JVM TLS stack is used because curl and .NET's schannel fail in the development
    // environment this was built in; through a proxy it works, and in CI no proxy is set.
    val host = System.getProperty("https.proxyHost")
    val port = System.getProperty("https.proxyPort")
    val selector = if (host != null && port != null) {
        ProxySelector.of(InetSocketAddress(host, port.toInt()))
    } else {
        ProxySelector.getDefault()
    }
    return HttpClient.newBuilder()
        .proxy(selector)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()
}

private fun get(client: HttpClient, url: String, range: String?): String? = try {
    val builder = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(120))
        .header("User-Agent", "Guise-catalog-gen/1.0 (+https://github.com/Eternalcherryblossoms/guise)")
    if (range != null) builder.header("Range", range)
    val response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    if (response.statusCode() >= 400) {
        System.err.println("  HTTP ${response.statusCode()} for $url")
        null
    } else {
        // ISO-8859-1 preserves every byte; the metadata is ASCII and the HTML is UTF-8, and this
        // only ever hands the text to regexes that look for ASCII markup.
        String(response.body(), Charsets.ISO_8859_1)
    }
} catch (e: Exception) {
    System.err.println("  ${e.javaClass.simpleName} for $url: ${e.message}")
    null
}

/**
 * Pulls the device sections and their rows out of the static index.
 *
 * The markup is stable enough to parse by section: each device is an `<h2 id="codename"
 * data-text='"codename" for Marketing Name'>` followed by a table whose rows hold the version
 * cell, the download link and the SHA-256. Marketing names come from here rather than from Play's
 * certified-device list, because that list's `Device` column is **not** a unique key -- `akita` is
 * a Pixel 8a there, and also a Trimble TSC710.
 */
internal fun parseIndex(html: String): List<OtaBuild> {
    val out = ArrayList<OtaBuild>()
    val checksum = Regex("""<td>([0-9a-f]{64})</td>""")
    val versionCell = Regex("""<td>([^<]*)</td>""")
    // Escaped rather than a raw string on purpose. A raw string cannot end in a quote without
    // running into the terminator: """...zip)"""" leaves Kotlin guessing which three of the four
    // quotes close the literal, and the guess it makes silently produced a pattern that matched
    // nothing. The parse reported zero rows, which is how it was found.
    val otaLink = Regex(
        "href=\"(https://[^\"]*?aosp/([a-z0-9_]+)-ota-([0-9a-z._]+)-([0-9a-f]{8})\\.zip)\"",
    )

    html.split("<h2 id=\"").drop(1).forEach { section ->
        val marketing = Regex("data-text='\"([^\"]+)\" for ([^']+)'").find(section)
            ?.groupValues?.get(2)?.trim().orEmpty()

        // Rows are separated first, then each one is searched for its own download link. Doing it
        // the other way round -- one regex across the section -- breaks on rows that carry more
        // than one anchor (the index has a flash.android.com link beside the download on newer
        // entries), because the first anchor is not always the one wanted.
        section.split("<tr").drop(1).forEach { row ->
            val link = otaLink.find(row) ?: return@forEach
            out += OtaBuild(
                // Group 1 is the whole href, 2 the codename, 3 the build id as it appears in the
                // file name, 4 the checksum fragment. Reading 4 as the URL made every entry fail
                // the startsWith check at the end and the parse reported zero rows.
                codename = link.groupValues[2],
                marketingName = marketing,
                versionLabel = versionCell.find(row)?.groupValues?.get(1)?.trim().orEmpty(),
                otaUrl = link.groupValues[1],
                sha256 = checksum.find(row)?.groupValues?.get(1).orEmpty(),
            )
        }
    }
    return out.filter { it.otaUrl.startsWith("https://") }
}

/**
 * Parses the plain-text metadata that begins an A/B OTA zip.
 *
 * The interesting line is `post-build=`, which is the complete canonical fingerprint:
 * `brand/product/device:release/build-id/incremental:type/tags`. One 2 KB read therefore supplies
 * every build field the catalog needs, with no transcription step to get wrong.
 */
internal fun parseMetadata(text: String, row: OtaBuild): OtaBuild {
    fun field(name: String): String =
        Regex("(?m)^" + Regex.escape(name) + "=(.*)$").find(text)?.groupValues?.get(1)?.trim().orEmpty()

    val fingerprint = field("post-build")
    val parts = fingerprint.split(":")
    if (parts.size != 3) {
        return row.copy(error = "no post-build in the first 2 KB (got ${fingerprint.take(60)})")
    }
    val identity = parts[0].split("/")
    val version = parts[1].split("/")
    val flags = parts[2].split("/")
    if (identity.size != 3 || version.size != 3 || flags.size != 2) {
        return row.copy(error = "malformed post-build: $fingerprint")
    }
    val preDevice = field("pre-device")
    if (preDevice.isNotEmpty() && !preDevice.equals(row.codename, ignoreCase = true)) {
        return row.copy(error = "pre-device '$preDevice' does not match codename '${row.codename}'")
    }
    val sdk = field("post-sdk-level")
    return row.copy(
        fingerprint = fingerprint,
        // The build ID in the URL is lowercased; the metadata gives the authoritative casing.
        release = version[0],
        buildId = version[1],
        incremental = version[2],
        type = flags[0],
        tags = flags[1],
        securityPatch = field("post-security-patch-level"),
        sdkInt = sdk.toIntOrNull() ?: 0,
        timestamp = field("post-timestamp").toLongOrNull() ?: 0L,
        error = "",
    )
}
