package io.guise.core.profile

/**
 * Physical memory: the one hardware fact no layer of Guise can rewrite.
 *
 * `ActivityManager.MemoryInfo.totalMem` is a binder call into `system_server`, and the direct
 * reading (`/proc/meminfo`) is no better -- both report what the kernel counted. There is no
 * Java API to intercept on the way out, so a profile cannot *lie* about memory. It can only
 * *disagree* with the handset, and a disagreement is a tell.
 *
 * This was measured, not assumed. On the two handsets available here the platform reports 11 GB
 * and 14 GB, while every profile in the shipped catalog declared 8 GiB. That is not a
 * hypothetical: it was the state of the catalog, and nothing in the code said a word about it,
 * because `totalRamBytes` was declared on [SocProfile] and never read by anything.
 *
 * ## Why this is not a SoC property
 *
 * A SoC is a chip. The same chip ships in handsets configured with 6, 8, 12 and 16 GB -- the
 * Snapdragon 865 alone was sold in all four. Modelling memory per SoC collapsed those variants
 * into a single figure, and because nine of the eleven catalog SoCs happened to carry 8 GiB,
 * twelve of the fourteen devices inherited it. The catalog covered two memory tiers while
 * appearing to cover fourteen devices.
 *
 * Memory therefore lives on [DeviceProfile] as the *nominal capacity that handset shipped with*,
 * and is used only as a compatibility constraint. It is deliberately **not** a [FieldKey]: there
 * is nothing to report, so offering it as an overridable field would invite a lie the platform
 * immediately contradicts.
 *
 * ## Reading a reported figure
 *
 * The reported number is always below the nominal one, because the kernel reserves memory before
 * the platform can count it. A handset sold as 12 GB reports roughly 11. So the mapping rounds
 * *up*: 11 GB belongs to the 12 GB tier, and only a device that genuinely has 8 GB reports a
 * figure at or below 8. See [nominalGiB].
 */
object RamTier {

    /** Capacities handsets are actually sold with, in GiB, ascending. */
    val capacitiesGiB: List<Int> = listOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32)

    const val GIB: Long = 1024L * 1024 * 1024

    fun bytes(gib: Int): Long = gib.toLong() * GIB

    /** The largest capacity in the table; anything above it cannot be placed. */
    val maxGiB: Int get() = capacitiesGiB.last()

    /**
     * The nominal capacity a handset reporting [reportedBytes] shipped with: the smallest
     * capacity in [capacitiesGiB] that is not below what was reported.
     *
     * Returns null when [reportedBytes] is not positive or exceeds every known capacity. Callers
     * must treat that as *unknown*, never as a match -- "I could not tell" and "they agree" are
     * different answers, and conflating them is how a probe starts reporting reassurance it has
     * not earned.
     */
    fun nominalGiB(reportedBytes: Long): Int? {
        if (reportedBytes <= 0L) return null
        return capacitiesGiB.firstOrNull { bytes(it) >= reportedBytes }
    }

    /** Nominal capacity in bytes, or 0 when [reportedBytes] cannot be placed. */
    fun nominalBytes(reportedBytes: Long): Long =
        nominalGiB(reportedBytes)?.let(::bytes) ?: 0L

    /** True when [nominalBytes] is a capacity in the table. Zero means "unknown" and is allowed. */
    fun isKnownCapacity(nominalBytes: Long): Boolean =
        nominalBytes == 0L || capacitiesGiB.any { bytes(it) == nominalBytes }

    /** Nominal capacity for a person, e.g. `12 GB`. */
    fun label(nominalBytes: Long): String =
        if (nominalBytes <= 0L) "未知" else "${nominalBytes / GIB} GB"

    /** Reported capacity for a person, e.g. `10.9 GB`. */
    fun reportedLabel(reportedBytes: Long): String {
        if (reportedBytes <= 0L) return "未知"
        val tenths = Math.round(reportedBytes.toDouble() * 10.0 / GIB)
        return "${tenths / 10}.${tenths % 10} GB"
    }
}

/**
 * What the *handset* says about itself, as opposed to what a profile claims.
 *
 * These are precisely the facts no layer of Guise can change, which makes them the only honest
 * yardstick for judging whether a profile fits the machine it would be worn on. All three are
 * cheap to read from an ordinary app: `ActivityManager.MemoryInfo`, `Build.SUPPORTED_ABIS` and
 * `Build.VERSION`.
 */
data class HandsetFacts(
    /** `ActivityManager.MemoryInfo.totalMem`. Zero when it could not be read. */
    val reportedRamBytes: Long = 0L,
    /** `Build.SUPPORTED_ABIS`, most-preferred first. */
    val abis: List<String> = emptyList(),
    /**
     * `Build.VERSION.RELEASE`.
     *
     * Carried here because a build belongs to exactly one Android release. See
     * [Compatibility.releaseIssue] for why a profile recorded on a different release is unusable
     * rather than merely imperfect.
     */
    val release: String = "",
)

/**
 * Judges a catalog profile against the handset it would be used on.
 *
 * This lives in `:core` rather than in the app because three different callers need the same
 * answer and must not drift: the picker annotates rows with it, the detail screen warns with it,
 * and the catalog generator uses it to prove that every handset configuration has at least one
 * profile that fits.
 */
object Compatibility {

    /**
     * Everything that would be visibly inconsistent if [device] were worn on [handset]. An empty
     * list means the profile fits. Each string is written for the user, in the same voice as the
     * rest of the module's explanations.
     */
    fun issues(device: DeviceProfile, soc: SocProfile, handset: HandsetFacts): List<String> =
        buildList {
            releaseIssue(device, handset)?.let(::add)
            ramIssue(device, handset)?.let(::add)
            abiIssue(soc, handset)?.let(::add)
        }

    /**
     * Whether the profile was recorded on the Android release this handset runs.
     *
     * This is the strictest of the three checks and the least obvious. `RuntimeVersion` already
     * keeps the *reported* release honest, and `BuildIdScheme.alignToRelease` repairs the
     * version token of a build ID that names the wrong era -- but a repair is not a match. The
     * date inside the build ID, the security patch level and the incremental version all still
     * belong to the release the build was made for, so a profile captured on Android 12 worn on
     * Android 16 describes a handset that never existed: no Pixel 6 ran Android 16 with a 2022
     * security patch.
     *
     * The catalog answers this by carrying one entry per (device, release), which is why the
     * check is worth making: it turns "pick a plausible-looking profile" into "pick one that is
     * actually possible", and the picker can say how many of those exist.
     */
    fun releaseIssue(device: DeviceProfile, handset: HandsetFacts): String? {
        if (handset.release.isBlank() || device.androidRelease.isBlank()) return null
        if (device.androidRelease == handset.release) return null
        return "版本不符：该档案来自 Android ${device.androidRelease}，本机是 Android " +
            "${handset.release}。构建号的日期、安全补丁和增量版本都属于 Android " +
            "${device.androidRelease}——穿在别的版本上会得到一台从没出厂过的机器。"
    }

    fun ramIssue(device: DeviceProfile, handset: HandsetFacts): String? {
        if (device.ramBytes <= 0L || handset.reportedRamBytes <= 0L) return null
        val actualGiB = RamTier.nominalGiB(handset.reportedRamBytes) ?: return null
        val claimedGiB = (device.ramBytes / RamTier.GIB).toInt()
        if (claimedGiB == actualGiB) return null
        val direction = if (claimedGiB < actualGiB) {
            "本机内存更大"
        } else {
            "本机内存更小"
        }
        return "内存不符：档案「${device.name}」出厂为 $claimedGiB GB，" +
            "本机报告 ${RamTier.reportedLabel(handset.reportedRamBytes)}（$actualGiB GB 档）。" +
            "$direction，而总内存由内核决定、任何一层都改不了——" +
            "应用读到的是一个和机型不匹配的数字。"
    }

    fun abiIssue(soc: SocProfile, handset: HandsetFacts): String? {
        if (handset.abis.isEmpty()) return null
        val profileAbi = soc.abis.firstOrNull() ?: return null
        val handsetAbi = handset.abis.firstOrNull() ?: return null
        if (profileAbi == handsetAbi) return null
        return "ABI 不符：档案芯片为 $profileAbi，本机为 $handsetAbi。" +
            "ABI 列表来自原生层，没有可拦截的 Java 读取路径。"
    }
}
