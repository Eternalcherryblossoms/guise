package io.guise.app.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.guise.core.profile.DeviceCatalog
import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.DisplayProfile
import io.guise.core.profile.HandsetFacts
import io.guise.core.profile.RamTier
import io.guise.core.profile.SocProfile

/**
 * Snapshots the *current* handset into a [DeviceProfile].
 *
 * This exists because a catalog entry transcribed from a spec sheet is a guess about build
 * metadata, whereas a capture is ground truth for the machine it ran on. Everything the
 * platform will tell an app is read here: `Build`, `Build.VERSION`, the display and the
 * CPU description in `/proc/cpuinfo`.
 *
 * Two fields cannot be captured from an ordinary app and are carried over from the matched
 * SoC instead: the GPU renderer, which needs a live GL context, and the codec vendor
 * prefixes, which come from the platform codec list. If the SoC does not match any catalog
 * entry a synthetic one is created with those fields left explicit rather than invented.
 */
object DeviceCapture {

    fun capture(context: Context, catalog: DeviceCatalog): DeviceProfile {
        val cpu = readCpuInfo()
        val matched = matchSoc(catalog)
        val soc = matched ?: syntheticSoc(cpu)

        val display = captureDisplay(context)

        return DeviceProfile(
            key = "captured_${Build.DEVICE.lowercase().replace(Regex("[^a-z0-9_]"), "_")}",
            name = "${Build.MANUFACTURER} ${Build.MODEL} (本机抓取)",
            brand = Build.BRAND,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = Build.PRODUCT,
            device = Build.DEVICE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Build.VERSION.SECURITY_PATCH.orEmpty()
            } else {
                ""
            },
            buildId = Build.ID,
            buildIncremental = Build.VERSION.INCREMENTAL,
            buildType = Build.TYPE,
            buildTags = Build.TAGS,
            buildHost = Build.HOST,
            buildUser = Build.USER,
            buildTimeMs = Build.TIME,
            displayId = Build.DISPLAY,
            bootloader = Build.BOOTLOADER,
            socKey = soc.key,
            display = display,
            // Not a value to report -- nothing can report it, the kernel owns the figure. It is
            // recorded so the catalog can refuse to offer this profile to a handset whose memory
            // visibly differs. See RamTier.
            ramBytes = ramTierFor(context),
            serial = runCatching { Build.getSerial() }.getOrNull()
                ?.takeIf { it != Build.UNKNOWN },
        )
    }

    /**
     * What this handset reports about itself in the two facts Guise cannot rewrite.
     *
     * Read here rather than in the UI so the picker and the detail screen judge profiles against
     * one snapshot of the machine.
     */
    fun handsetFacts(context: Context): HandsetFacts = HandsetFacts(
        reportedRamBytes = reportedRamBytes(context),
        abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        release = Build.VERSION.RELEASE.orEmpty(),
    )

    /** `ActivityManager.MemoryInfo.totalMem`, or 0 when it cannot be read. */
    private fun reportedRamBytes(context: Context): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)

    /** Nominal capacity this handset shipped with, in bytes; 0 when it cannot be placed. */
    private fun ramTierFor(context: Context): Long =
        RamTier.nominalBytes(reportedRamBytes(context))

    /** The SoC entry to merge alongside a captured device. */
    fun socFor(context: Context, catalog: DeviceCatalog): SocProfile {
        val cpu = readCpuInfo()
        return matchSoc(catalog) ?: syntheticSoc(cpu)
    }

    private fun matchSoc(catalog: DeviceCatalog): SocProfile? =
        catalog.socs.values.firstOrNull { soc ->
            soc.board.equals(Build.BOARD, ignoreCase = true) ||
                soc.hardware.equals(Build.HARDWARE, ignoreCase = true) ||
                soc.platform.equals(Build.BOARD, ignoreCase = true)
        }

    private fun syntheticSoc(cpu: CpuInfo): SocProfile {
        val key = "captured_" + Build.BOARD.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        return SocProfile(
            key = key,
            vendor = Build.MANUFACTURER,
            marketingName = "本机抓取 (${Build.BOARD})",
            platform = Build.BOARD,
            hardware = Build.HARDWARE,
            board = Build.BOARD,
            cpuImplementer = cpu.implementer,
            cpuPart = cpu.part,
            cores = Runtime.getRuntime().availableProcessors(),
            // Not obtainable without a GL context from a plain app; left blank rather than
            // guessed, and the UI marks it as needing to be filled in.
            gpuVendor = "",
            gpuRenderer = "",
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        )
    }

    private fun captureDisplay(context: Context): DisplayProfile {
        val metrics = context.resources.displayMetrics
        val rates = runCatching {
            context.display?.supportedModes
                ?.map { it.refreshRate }
                ?.distinct()
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())

        return DisplayProfile(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            refreshRates = rates.ifEmpty { listOf(60f) },
        )
    }

    private data class CpuInfo(val implementer: String, val part: String)

    private fun readCpuInfo(): CpuInfo {
        val text = runCatching { java.io.File("/proc/cpuinfo").readText() }.getOrDefault("")
        fun firstValue(label: String): String =
            Regex("$label\\s*:\\s*(\\S+)").find(text)?.groupValues?.get(1).orEmpty()
        return CpuInfo(
            implementer = firstValue("CPU implementer"),
            part = firstValue("CPU part"),
        )
    }
}
