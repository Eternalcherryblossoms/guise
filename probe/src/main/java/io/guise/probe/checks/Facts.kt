package io.guise.probe.checks

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.guise.probe.model.SourceComparison
import java.io.File

/**
 * Reads `ro.*` properties.
 *
 * Two routes on purpose. Reflection into `android.os.SystemProperties` is the fast path and
 * usually works, but it is a hidden API and can be refused. `getprop` is a world-executable
 * binary and almost always works. A diagnostic that silently reads nothing because a hidden
 * API was blocked would be worse than useless.
 */
object Props {

    // A null value is meaningful here ("property absent"), so this cannot be a
    // ConcurrentHashMap -- that rejects nulls. A synchronised HashMap keeps the nulls and
    // stays safe across the IO dispatcher's threads.
    private val cache: MutableMap<String, String?> =
        java.util.Collections.synchronizedMap(HashMap())

    @Volatile
    private var bulk: Map<String, String>? = null

    private val systemProperties: Class<*>? by lazy {
        runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
    }

    fun get(name: String): String? = cache.getOrPut(name) {
        viaReflection(name) ?: viaGetprop(name)
    }

    private fun viaReflection(name: String): String? = runCatching {
        val m = systemProperties?.getMethod("get", String::class.java) ?: return@runCatching null
        (m.invoke(null, name) as? String)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun viaGetprop(name: String): String? {
        bulk?.let { return it[name] }
        val parsed = readGetpropBounded() ?: emptyMap()
        bulk = parsed
        return parsed[name]
    }

    /**
     * Runs `getprop` with a hard bound on the whole interaction.
     *
     * A timeout on `waitFor` alone is not enough: `readText()` blocks until the stream reaches
     * EOF, so a `getprop` that starts but never exits hangs before `waitFor` is ever reached.
     * The read and the wait therefore have to happen on a worker that we can abandon. The
     * thread is a daemon so an abandoned one cannot keep the process alive.
     */
    private fun readGetpropBounded(): Map<String, String>? {
        var parsed: Map<String, String>? = null
        val worker = Thread {
            parsed = runCatching {
                val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                // Lines look like: [ro.product.model]: [M2011K2C]
                Regex("""\[([^\]]+)]:\s*\[([^\]]*)]""").findAll(text)
                    .associate { it.groupValues[1] to it.groupValues[2] }
            }.getOrNull()
        }
        worker.isDaemon = true
        worker.start()
        worker.join(GETPROP_TIMEOUT_MS)
        if (worker.isAlive) {
            worker.interrupt()
            return null
        }
        return parsed
    }

    private const val GETPROP_TIMEOUT_MS = 2000L
}

/**
 * The same identity facts, gathered from three independent sources.
 *
 * This is the heart of the diagnostic. A rewriting module that covers only the Java surface
 * leaves the property and native readings untouched, so the three disagree -- and because
 * the sources are keyed identically, the disagreement can be named rather than merely
 * detected.
 */
object Facts {

    /**
     * Facts read through the Java API. Keys are shared with [prop] so the two maps can be
     * compared entry by entry.
     */
    fun java(context: Context): Map<String, String> = buildMap {
        putAll(
            mapOf(
                "brand" to Build.BRAND,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "product" to Build.PRODUCT,
                "board" to Build.BOARD,
                "hardware" to Build.HARDWARE,
                "fingerprint" to Build.FINGERPRINT,
                "bootloader" to Build.BOOTLOADER,
                "buildId" to Build.ID,
                "display" to Build.DISPLAY,
                "tags" to Build.TAGS,
                "type" to Build.TYPE,
                "host" to Build.HOST,
                "user" to Build.USER,
                "release" to Build.VERSION.RELEASE,
                "incremental" to Build.VERSION.INCREMENTAL,
                // Shared key with the property reading below -- this is the ABI cross-check.
                "abis" to Build.SUPPORTED_ABIS.joinToString(","),
            ),
        )
        // Shared keys with the native reading: the physical facts.
        put("cores", Runtime.getRuntime().availableProcessors().toString())
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            // Both sides normalised to kB so they are directly comparable.
            put("totalMemKb", (info.totalMem / 1024).toString())
        }
    }

    /** The same facts, read from `SystemProperties` rather than the cached Build fields. */
    fun prop(): Map<String, String> {
        val pairs = listOf(
            "brand" to "ro.product.brand",
            "manufacturer" to "ro.product.manufacturer",
            "model" to "ro.product.model",
            "device" to "ro.product.device",
            "product" to "ro.product.name",
            "board" to "ro.product.board",
            "hardware" to "ro.hardware",
            "fingerprint" to "ro.build.fingerprint",
            "bootloader" to "ro.bootloader",
            "buildId" to "ro.build.id",
            "display" to "ro.build.display.id",
            "tags" to "ro.build.tags",
            "type" to "ro.build.type",
            "host" to "ro.build.host",
            "user" to "ro.build.user",
            "release" to "ro.build.version.release",
            "incremental" to "ro.build.version.incremental",
            // Shared key with the Java reading: the framework computes SUPPORTED_ABIS from
            // this property at class-init, so a rewrite of one without the other shows up.
            "abis" to "ro.product.cpu.abilist",
        )
        return pairs.mapNotNull { (key, prop) -> Props.get(prop)?.let { key to it } }.toMap()
    }

    /**
     * Facts that do not come from the framework at all.
     *
     * These are the ones a Java-layer hook cannot reach: the kernel's own description of the
     * silicon, and the device tree. `/sys/devices/soc0/machine` in particular carries the
     * SoC's marketing name on Qualcomm and Exynos platforms, which makes it the single most
     * useful leak detector available to an ordinary app.
     */
    fun native(context: Context): Map<String, String> = buildMap {
        readFile("/sys/devices/soc0/machine")?.let { put("socMachine", it) }
        readFile("/sys/devices/soc0/family")?.let { put("socFamily", it) }
        readFile("/sys/devices/soc0/soc_id")?.let { put("socId", it) }
        readFile("/proc/cpuinfo")?.let { cpuinfo ->
            Regex("""(?m)^Hardware\s*:\s*(.+)$""").find(cpuinfo)
                ?.let { put("cpuinfoHardware", it.groupValues[1].trim()) }
            Regex("""(?m)^CPU part\s*:\s*(\S+)""").find(cpuinfo)
                ?.let { put("cpuPart", it.groupValues[1].trim()) }
            Regex("""(?m)^CPU implementer\s*:\s*(\S+)""").find(cpuinfo)
                ?.let { put("cpuImplementer", it.groupValues[1].trim()) }
            // Key shared with the Java reading (Runtime.availableProcessors).
            put("cores", Regex("""(?m)^processor\s*:""").findAll(cpuinfo).count().toString())
        }
        readFile("/sys/class/net/wlan0/address")?.let { put("wlan0Mac", it) }
        readFile("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")?.let { put("cpu0MaxKhz", it) }
        // Key shared with the Java reading (ActivityManager.MemoryInfo.totalMem / 1024).
        readFile("/proc/meminfo")?.let { mem ->
            Regex("""(?m)^MemTotal:\s*(\d+)""").find(mem)?.let { put("totalMemKb", it.groupValues[1]) }
        }
    }

    /** Stable, order-independent hash of a fact set. */
    fun hash(facts: Map<String, String>): String {
        val canonical = facts.entries.sortedBy { it.key }.joinToString("\u0000") { "${it.key}=${it.value}" }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        // Mask to unsigned before formatting: a raw negative Byte formats as ffffff80.
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * Compares two readings over the keys they share.
     *
     * Restricting to the intersection is what makes the hashes meaningful: two sources that
     * describe different facts cannot be hashed wholesale and compared. Each side is hashed
     * over exactly the same key set, so equal hashes mean equal answers to equal questions.
     */
    fun compare(
        label: String,
        a: Map<String, String>,
        b: Map<String, String>,
    ): SourceComparison {
        val shared = a.keys.intersect(b.keys).sorted()
        val fa = shared.associateWith { a.getValue(it) }
        val fb = shared.associateWith { b.getValue(it) }
        val divergences = shared
            .filter { a.getValue(it) != b.getValue(it) }
            .map { Triple(it, a.getValue(it), b.getValue(it)) }
        return SourceComparison(label, shared, hash(fa), hash(fb), divergences)
    }

    fun readFile(path: String): String? =
        runCatching { File(path).takeIf { it.canRead() }?.readText()?.trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
}

/**
 * Platform codename to the SoC names a device tree would report for it.
 *
 * This is the mapping that lets the probe turn "the framework says kona" and "the kernel
 * says SM8250" into a pass or a fail. It is public, finite, and exactly the sort of table a
 * real fingerprinting SDK maintains.
 */
object SocAliases {
    private val table: Map<String, List<String>> = mapOf(
        "kona" to listOf("SM8250", "sm8250"),
        "lahaina" to listOf("SM8350", "sm8350"),
        "kalama" to listOf("SM8550", "sm8550", "SM8650"),
        "lito" to listOf("SM7250", "sm7250"),
        "bengal" to listOf("SM6115", "sm6115"),
        "holi" to listOf("SM6375", "sm6375"),
        "gs101" to listOf("GS101", "gs101", "Tensor"),
        "gs201" to listOf("GS201", "gs201"),
        "zuma" to listOf("GS301", "gs301"),
        "exynos2100" to listOf("EXYNOS2100", "exynos2100"),
        "exynos2200" to listOf("EXYNOS2200", "exynos2200"),
        "universal2100" to listOf("EXYNOS2100", "exynos2100"),
        "mt6785" to listOf("MT6785", "mt6785"),
        "mt6893" to listOf("MT6893", "mt6893"),
        "msmnile" to listOf("SM8150", "sm8150"),
    )

    /**
     * Platform codename to silicon family.
     *
     * Shared with the codec check so that "what chip does the framework claim" is answered one
     * way everywhere, rather than two checks each carrying their own table that can drift.
     */
    private val familyByPlatform: List<Pair<String, String>> = listOf(
        "mt" to "mtk", "mtk" to "mtk",
        "kona" to "qcom", "lahaina" to "qcom", "kalama" to "qcom", "lito" to "qcom",
        "bengal" to "qcom", "holi" to "qcom", "waipio" to "qcom", "cape" to "qcom",
        "pineapple" to "qcom", "msmnile" to "qcom", "sun" to "qcom", "blair" to "qcom",
        "crow" to "qcom", "parrot" to "qcom",
        "gs101" to "google", "gs201" to "google", "zuma" to "google", "gs301" to "google",
        "exynos" to "exynos", "universal" to "exynos",
    )

    /**
     * Vendor words that appear verbatim in a device-tree SoC string.
     *
     * `/sys/devices/soc0/machine` carries very different text across platforms: some report the
     * part number ("SM8650"), some only the marketing family ("Snapdragon"). Recognising the
     * family words lets the check say "the kernel identifies Qualcomm while the framework
     * claims Google", instead of the much weaker "expected GS201, not found".
     */
    private val vendorMarkers: List<Pair<String, String>> = listOf(
        "snapdragon" to "qcom", "qualcomm" to "qcom",
        "mediatek" to "mtk", "dimensity" to "mtk", "helio" to "mtk",
        "exynos" to "exynos",
        "tensor" to "google",
    )

    /** Tokens expected to appear in a device-tree SoC string for [platform]. */
    fun tokensFor(platform: String): List<String>? {
        val key = platform.trim().lowercase()
        table[key]?.let { return it }
        // Some platforms are reported with a suffix, e.g. "lahaina" vs "lahaina_qt".
        return table.entries.firstOrNull { key.startsWith(it.key) }?.value
    }

    /** Silicon family for a platform codename, or null when it is not in the table. */
    fun familyOf(platform: String): String? {
        val key = platform.trim().lowercase()
        if (key.isEmpty()) return null
        return familyByPlatform.firstOrNull { key.startsWith(it.first) }?.second
    }

    /** Silicon family named by a device-tree string, or null when no marker matches. */
    fun familyIn(text: String): String? {
        val lower = text.lowercase()
        return vendorMarkers.firstOrNull { lower.contains(it.first) }?.second
    }

    fun isKnown(platform: String): Boolean = tokensFor(platform) != null
}
