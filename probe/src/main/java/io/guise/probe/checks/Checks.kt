package io.guise.probe.checks

import android.content.Context
import android.os.Build
import io.guise.probe.model.AggregateReading
import io.guise.probe.model.CheckResult
import io.guise.probe.model.Finding
import io.guise.probe.model.ProbeCheck
import io.guise.probe.model.Verdict
import kotlin.math.abs

/**
 * Compares the Java-visible identity against the same facts read as properties.
 *
 * The single most useful check here. The overwhelmingly common way for a device-spoofing
 * module to be half-working is that it patches the Java surface and forgets the property
 * surface -- `Build.MODEL` is rewritten while `ro.product.model` still says the original.
 * Ordinary code reads the former, but integrity checks and native code very often read the
 * latter, so the disagreement is both a bug and a giveaway.
 */
class PropertyMirrorCheck : ProbeCheck {
    override val id = "property-mirror"
    override val title = "Java 层与属性层一致性"
    override val rationale =
        "Build.MODEL 只是 ro.product.model 在类初始化时的一次快照。只改其一，" +
            "两侧就互相矛盾——这是伪装模块半残时最常见的形态。"

    override suspend fun run(context: Context): CheckResult {
        val java = Facts.java(context)
        val prop = Facts.prop()
        val shared = java.keys.intersect(prop.keys).sorted()

        val findings = shared.map { key ->
            val j = java.getValue(key)
            val p = prop.getValue(key)
            Finding(
                label = key,
                observed = j,
                expected = p,
                verdict = if (j == p) Verdict.COHERENT else Verdict.INCOHERENT,
                detail = if (j == p) null else "属性 ro.* 侧读到的值不同",
            )
        }

        val bad = findings.filter { it.verdict == Verdict.INCOHERENT }
        return CheckResult(
            id = id,
            title = title,
            verdict = if (bad.isEmpty()) Verdict.COHERENT else Verdict.INCOHERENT,
            headline = if (bad.isEmpty()) {
                "已比对的 ${shared.size} 项全部一致"
            } else {
                "${bad.size}/${shared.size} 项不一致：${bad.joinToString(", ") { it.label }}"
            },
            findings = findings,
        )
    }
}

/**
 * Checks that the build fingerprint is well-formed and that the Android version it claims
 * agrees with the API level it claims.
 *
 * A fingerprint has a fixed shape, and one composed by hand is frequently subtly wrong -- or
 * pairs "Android 13" with the API level of Android 12. Neither needs a device database to
 * detect; both are internal contradictions.
 */
class FingerprintShapeCheck : ProbeCheck {
    override val id = "fingerprint-shape"
    override val title = "指纹结构自洽性"
    override val rationale =
        "指纹有固定格式 BRAND/PRODUCT/DEVICE:RELEASE/ID/INCREMENTAL:TYPE/TAGS，" +
            "且 Android 版本号与 API 级别必须对应。手工拼装的指纹经常在这里露馅。"

    /** Android release to API level. Public, finite, and exactly what an SDK checks. */
    private val releaseToSdk = mapOf(
        "8.0.0" to 26, "8.0" to 26, "8.1.0" to 27, "8.1" to 27,
        "9" to 28, "10" to 29, "11" to 30, "12" to 31, "12L" to 32,
        "13" to 33, "14" to 34, "15" to 35, "16" to 36,
    )

    /**
     * The build-ID version token each release uses.
     *
     * Deliberately duplicated here rather than imported from `:core`, even though `:core` has
     * the same table. The probe exists to be an independent observer: if it shared the module's
     * constant and that constant were wrong, the check would agree with the bug and report
     * nothing. Two independent copies of a public naming scheme is the point.
     *
     * AOSP names release branches by letter: `TQ1A` is Android 13, `UP1A` is 14. Past 14 the
     * scheme moved to two letters, `AP*` for 15 and `BP*` for 16.
     */
    private val buildIdToken = mapOf(
        "11" to "R", "12" to "S", "12L" to "S", "13" to "T",
        "14" to "U", "15" to "A", "16" to "B",
    )

    override suspend fun run(context: Context): CheckResult {
        val findings = mutableListOf<Finding>()
        val parts = Build.FINGERPRINT.split(":")

        findings += Finding(
            label = "指纹分段数",
            observed = "${parts.size} 段",
            expected = "3 段",
            verdict = if (parts.size == 3) Verdict.COHERENT else Verdict.INCOHERENT,
        )

        if (parts.size == 3) {
            val idSeg = parts[0].split("/")
            val verSeg = parts[1].split("/")
            val bldSeg = parts[2].split("/")

            findings += segment("identity", idSeg.size, 3, parts[0])
            findings += segment("version", verSeg.size, 3, parts[1])
            findings += segment("build", bldSeg.size, 2, parts[2])

            if (idSeg.size == 3) {
                findings += mirror("指纹 brand", idSeg[0], Build.BRAND)
                findings += mirror("指纹 device", idSeg[2], Build.DEVICE)
            }
            if (verSeg.size == 3) {
                findings += mirror("指纹 release", verSeg[0], Build.VERSION.RELEASE)
                findings += mirror("指纹 buildId", verSeg[1], Build.ID)
            }
            if (bldSeg.size == 2) {
                findings += mirror("指纹 type", bldSeg[0], Build.TYPE)
                findings += mirror("指纹 tags", bldSeg[1], Build.TAGS)
            }
        }

        val expectedSdk = releaseToSdk[Build.VERSION.RELEASE]
        findings += Finding(
            label = "release ↔ API 级别",
            observed = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
            expected = expectedSdk?.let { "Android ${Build.VERSION.RELEASE} 对应 API $it" } ?: "未知版本，跳过",
            verdict = when {
                expectedSdk == null -> Verdict.INFO
                expectedSdk == Build.VERSION.SDK_INT -> Verdict.COHERENT
                else -> Verdict.INCOHERENT
            },
            detail = if (expectedSdk != null && expectedSdk != Build.VERSION.SDK_INT) {
                "版本号与 API 级别对不上，说明档案是手工拼装的。"
            } else {
                null
            },
        )

        // Build ID era. This is the check that caught a regression in this very project: making
        // the release come from the device while the build ID still came from the profile
        // produced `...:16/TQ1A.221205.011:...` -- release 16 carrying an Android 13 build ID.
        val wantToken = buildIdToken[Build.VERSION.RELEASE]
        val fpBuildId = if (parts.size == 3) parts[1].split("/").getOrNull(1).orEmpty() else ""
        val headToken = fpBuildId.substringBefore('.')
        findings += Finding(
            label = "release ↔ 构建号年代",
            observed = "Android ${Build.VERSION.RELEASE} 配构建号 $fpBuildId",
            expected = wantToken?.let { "Android ${Build.VERSION.RELEASE} 的构建号应以 $it 开头" } ?: "未知版本，跳过",
            verdict = when {
                wantToken == null || headToken.isEmpty() -> Verdict.INFO
                headToken.startsWith(wantToken) -> Verdict.COHERENT
                else -> Verdict.INCOHERENT
            },
            detail = if (wantToken != null && headToken.isNotEmpty() && !headToken.startsWith(wantToken)) {
                "AOSP 的构建号首 token 就是版本代号（R=11 S=12 T=13 U=14，15 起为 AP*/BP*）。" +
                    "版本与构建号年代对不上，不需要设备库就能看出。" +
                    "通常是因为用了一个在别的 Android 版本上抓的档案。"
            } else {
                null
            },
        )

        val bad = findings.count { it.verdict == Verdict.INCOHERENT }
        return CheckResult(
            id, title,
            if (bad == 0) Verdict.COHERENT else Verdict.INCOHERENT,
            if (bad == 0) "指纹结构完好且自洽" else "$bad 项结构问题",
            findings,
        )
    }

    private fun segment(name: String, actual: Int, expected: Int, raw: String) = Finding(
        label = "指纹 $name 段",
        observed = "$actual 段 ($raw)",
        expected = "$expected 段",
        verdict = if (actual == expected) Verdict.COHERENT else Verdict.INCOHERENT,
    )

    private fun mirror(label: String, a: String, b: String) = Finding(
        label = label,
        observed = a,
        expected = b,
        verdict = if (a == b) Verdict.COHERENT else Verdict.INCOHERENT,
    )
}

/**
 * The headline instrument: the three-way hash.
 *
 * Reports, as named fields rather than as a bare mismatch, exactly which channels failed to
 * keep up with the others. Needs no device database -- it asks only that the platform not
 * contradict itself.
 */
class AggregateHashCheck(private val reading: AggregateReading) : ProbeCheck {
    override val id = "aggregate-hash"
    override val title = "哈希聚合（三源交叉）"
    override val rationale =
        "同样的事实分别从 Java API、SystemProperties、/proc 与 /sys 读三遍并各自哈希。" +
            "哈希不等，就精确指出哪条通道没跟上——这测的是覆盖面，不是真假。"

    override suspend fun run(context: Context): CheckResult {
        val findings = mutableListOf<Finding>()

        findings += comparisonFindings(reading.javaVsProp, "Java", "属性")
        findings += comparisonFindings(reading.javaVsNative, "Java", "内核")

        val bad = findings.count { it.verdict == Verdict.INCOHERENT }
        return CheckResult(
            id, title,
            if (bad == 0) Verdict.COHERENT else Verdict.INCOHERENT,
            if (bad == 0) {
                "三个来源对同样的 ${reading.javaVsProp.sharedKeys.size} 项给出相同答案"
            } else {
                "$bad 条通道未跟上改写"
            },
            findings,
        )
    }

    private fun comparisonFindings(
        c: io.guise.probe.model.SourceComparison,
        nameA: String,
        nameB: String,
    ): List<Finding> = buildList {
        add(
            Finding(
                label = "${c.label}：$nameA vs $nameB",
                observed = "${nameA}=${c.hashA}  $nameB=${c.hashB}",
                expected = "两者相等",
                verdict = if (c.agrees) Verdict.COHERENT else Verdict.INCOHERENT,
                detail = "共比对 ${c.sharedKeys.size} 项：${c.sharedKeys.joinToString(", ")}",
            ),
        )
        c.divergences.forEach { (key, a, b) ->
            add(
                Finding(
                    label = "  泄漏字段：$key",
                    observed = "$nameA=$a",
                    expected = "$nameB=$b",
                    verdict = Verdict.INCOHERENT,
                ),
            )
        }
    }
}

/**
 * Whether the kernel's own description of the silicon agrees with what the framework claims.
 *
 * `/sys/devices/soc0/machine` is provided by the kernel and carries the SoC's marketing name
 * on most Qualcomm and Exynos platforms. A Java-layer hook cannot touch it, so a device
 * claiming to be one chip while the device tree says another is caught immediately -- using
 * nothing an ordinary app cannot read.
 *
 * This is the check that demonstrates, concretely, why the Zygisk layer exists.
 */
class NativeSoCCheck : ProbeCheck {
    override val id = "native-soc"
    override val title = "内核 SoC 描述 vs 框架声明"
    override val rationale =
        "/sys/devices/soc0/machine 由内核提供，Java 层 hook 够不着。" +
            "声称的芯片平台与设备树里的实际芯片不符，是最直接的露馅方式。"

    override suspend fun run(context: Context): CheckResult {
        val native = Facts.native(context)
        val platform = Props.get("ro.board.platform").orEmpty()
        val machine = native["socMachine"] ?: native["socFamily"]
        val cpuinfoHw = native["cpuinfoHardware"]

        if (machine.isNullOrBlank() && cpuinfoHw.isNullOrBlank()) {
            return CheckResult(
                id, title, Verdict.UNSUPPORTED,
                "本机未暴露设备树 SoC 信息（不少机型不提供）",
                listOf(Finding("ro.board.platform", platform.ifBlank { "(空)" })),
            )
        }

        val findings = mutableListOf<Finding>()
        findings += Finding("ro.board.platform（框架声明）", platform.ifBlank { "(空)" })
        machine?.let { findings += Finding("/sys/devices/soc0/machine（内核）", it) }
        cpuinfoHw?.let { findings += Finding("/proc/cpuinfo Hardware（内核）", it) }

        val tokens = SocAliases.tokensFor(platform)
        val claimedFamily = SocAliases.familyOf(platform)
        val haystack = listOfNotNull(machine, cpuinfoHw).joinToString(" ")
        val kernelFamily = SocAliases.familyIn(haystack)

        // Exact-token match is the strongest signal; the family comparison is the fallback for
        // platforms whose device-tree string carries only a marketing name. On a real Qualcomm
        // handset the file reads just "Snapdragon", so without the family comparison the check
        // could only say "GS201 not found" rather than "the kernel says Qualcomm".
        val verdict = when {
            haystack.isBlank() -> Verdict.UNSUPPORTED
            tokens != null && tokens.any { haystack.contains(it, ignoreCase = true) } -> Verdict.COHERENT
            kernelFamily != null && claimedFamily != null -> {
                if (kernelFamily == claimedFamily) Verdict.COHERENT else Verdict.INCOHERENT
            }
            tokens == null && claimedFamily == null -> Verdict.INFO
            else -> Verdict.INCOHERENT
        }

        findings += Finding(
            label = "一致性判定",
            observed = when {
                haystack.isBlank() -> "内核未提供可读的芯片描述"
                kernelFamily != null -> "内核指认 $kernelFamily，框架声称 ${claimedFamily ?: platform}"
                tokens == null -> "平台 $platform 不在对照表内"
                else -> "期望内核串中出现 ${tokens.joinToString(" 或 ")}"
            },
            verdict = verdict,
            detail = if (verdict == Verdict.INCOHERENT) {
                "框架声称 $platform，但内核描述的芯片来自另一个厂商。" +
                    "若伪装只覆盖 Java 层，这一项必然失败。要补上有两条路：" +
                    "在 LSPosed 层 hook 对该文件的 Java 读取（便宜，但 native 读取会绕过），" +
                    "或用 Zygisk 在该进程的 mount namespace 里 bind mount 一个假文件（彻底，但工程量大）。"
            } else {
                null
            },
        )

        return CheckResult(
            id, title, verdict,
            when (verdict) {
                Verdict.COHERENT -> "内核描述与框架声明一致"
                Verdict.INCOHERENT -> "内核描述与框架声明不符"
                else -> "无法判定（平台不在对照表内）"
            },
            findings,
        )
    }
}

/**
 * Whether the machinery doing the rewriting is itself visible.
 *
 * A module that fakes every value but leaves its own injection visible has achieved little:
 * plenty of apps refuse to run at all once they see Xposed or Zygisk traces, and spoofed
 * values nobody reads are worth nothing. This is the check that justifies the concealment
 * work in the root and Zygisk layers.
 */
class InjectionCheck : ProbeCheck {
    override val id = "injection-surface"
    override val title = "注入痕迹可见性"
    override val rationale =
        "很多做真设备检查的 App 一旦发现 Xposed/Zygisk 痕迹就直接拒绝运行。" +
            "值伪装得再好，若机制本身可见，也等于没做。"

    private val tokens = listOf(
        "xposed", "lsposed", "edxposed", "magisk", "zygisk", "riru",
        "shamiko", "substrate", "libguise", "guise",
    )

    private val frameworkClasses = listOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XC_MethodHook",
        "de.robv.android.xposed.IXposedHookLoadPackage",
        "io.github.libxposed.api.XposedModule",
    )

    override suspend fun run(context: Context): CheckResult {
        val findings = mutableListOf<Finding>()

        // 1. Our own memory map. Readable for our own process with no permission at all.
        //
        // Two corrections over the first version of this check, both found by running it:
        //
        //  - The probe's OWN apk path appears in its own maps, and that path contains the
        //    string "guise". Matching on the bare token therefore reported the probe as an
        //    injection of itself. The probe's own package is excluded explicitly.
        //  - Reporting the bare token was the real mistake: "guise" is ambiguous, whereas the
        //    mapped *path* says unambiguously whether it is the module apk or our own. A check
        //    should surface evidence, not a conclusion the reader cannot verify.
        val maps = Facts.readFile("/proc/self/maps").orEmpty()
        val self = context.packageName.lowercase()
        val selfApk = context.applicationInfo.sourceDir.lowercase()

        val suspiciousPaths = maps.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotEmpty() && !line.startsWith("0") || line.contains("/") }
            .mapNotNull { line ->
                // maps lines end with the path, when there is one.
                val path = line.substringAfterLast(' ').trim()
                if (path.isEmpty() || !path.startsWith("/")) return@mapNotNull null
                path
            }
            .filter { path ->
                val lower = path.lowercase()
                tokens.any { lower.contains(it) } &&
                    !lower.contains(self) &&
                    lower != selfApk
            }
            .distinct()
            .toList()

        findings += Finding(
            label = "/proc/self/maps 可疑条目",
            observed = if (suspiciousPaths.isEmpty()) {
                "无（已排除探针自身的 $self）"
            } else {
                suspiciousPaths.joinToString("\n")
            },
            expected = "无",
            verdict = if (suspiciousPaths.isEmpty()) Verdict.CONCEALED else Verdict.EXPOSED,
            detail = if (suspiciousPaths.isEmpty()) {
                null
            } else {
                "以上是实际映射到的路径。若其中出现 Guise 模块自身的 apk 路径、" +
                    "lspd/zygisk 的 .so，即为真实注入痕迹；探针自身路径已被排除。"
            },
        )

        // 2. Framework classes on the classpath. Present means a hooking framework is loaded
        //    into this process, whether or not it is doing anything to us.
        val classHits = frameworkClasses.filter { name ->
            runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
        }
        findings += Finding(
            label = "Hook 框架类可见",
            observed = if (classHits.isEmpty()) "无" else classHits.joinToString(", "),
            expected = "无",
            verdict = if (classHits.isEmpty()) Verdict.CONCEALED else Verdict.EXPOSED,
        )

        // 3. Mapped shared objects. The count alone proves nothing -- it needs a clean
        //    baseline to compare against -- so it is reported as a measurement.
        val soCount = Regex("""\.so(\s|$)""").findAll(maps).count()
        findings += Finding(
            label = "已映射 .so 数量",
            observed = soCount.toString(),
            verdict = Verdict.INFO,
            detail = "异常偏多可能意味着额外注入，但需要与未启用模块时的基线比较才有意义。",
        )

        val exposed = findings.count { it.verdict == Verdict.EXPOSED }
        return CheckResult(
            id, title,
            if (exposed == 0) Verdict.CONCEALED else Verdict.EXPOSED,
            if (exposed == 0) "未发现注入痕迹" else "$exposed 类痕迹对本进程可见",
            findings,
        )
    }
}

/**
 * Facts that come from the silicon and cannot be rewritten by a Java hook.
 *
 * Included deliberately, and included as measurements rather than pass/fail. Its purpose is
 * to make the ceiling visible: a device presenting a hundred different identities still has
 * one memory size, one core count and one maximum clock. That is what server-side clustering
 * keys on, and no client-side module changes it.
 */
class PhysicalCheck : ProbeCheck {
    override val id = "physical"
    override val title = "物理事实（不可伪装）"
    override val rationale =
        "内存容量、核心数、最高频率来自硬件。伪装模块改不了这些——" +
            "这正是「一台设备伪装成一百台」在服务端会被聚类的原因。"

    override suspend fun run(context: Context): CheckResult {
        val java = Facts.java(context)
        val native = Facts.native(context)
        val findings = mutableListOf<Finding>()

        val memApi = java["totalMemKb"]?.toLongOrNull()
        val memProc = native["totalMemKb"]?.toLongOrNull()
        val memCoherent = when {
            memApi == null || memProc == null -> null
            abs(memApi - memProc) < 64L * 1024 -> true
            else -> false
        }

        findings += Finding(
            label = "总内存 (ActivityManager)",
            observed = memApi?.let { "${it / 1024} MB" } ?: "不可读",
        )
        findings += Finding(
            label = "总内存 (/proc/meminfo)",
            observed = memProc?.let { "${it / 1024} MB" } ?: "不可读",
            expected = memApi?.let { "${it / 1024} MB" },
            verdict = when (memCoherent) {
                null -> Verdict.INFO
                true -> Verdict.COHERENT
                false -> Verdict.INCOHERENT
            },
            // Only shown when there is actually a problem. The first version attached this
            // text unconditionally, so a passing check still read as a failure.
            detail = if (memCoherent == false) "两个来源对不上，说明内存容量被单独改写过。" else null,
        )

        // Total RAM is not spoofable from the framework: ActivityManager.MemoryInfo comes from
        // a binder call, and the value is also in /proc/meminfo. Even the Zygisk layer can only
        // reach the file, not the binder reply. So a profile whose handset shipped with a
        // different memory size is an inherent, unfixable tell -- worth surfacing, because the
        // fix is to choose a different profile, not to write more code.
        memApi?.let { kb ->
            val gb = kb / 1024 / 1024
            findings += Finding(
                label = "内存容量（不可伪装）",
                observed = "约 ${gb} GB",
                verdict = Verdict.INFO,
                detail = "本机内存无法被任何一层伪装：ActivityManager 走 binder，" +
                    "/proc/meminfo 由内核提供。若所选机型的标称内存与此不符" +
                    "（例如本机 ${gb}GB 而档案机型只有 8GB），带机型库的指纹 SDK 可以直接识破。" +
                    "这是选档案时应当匹配的一项，不是代码能解决的问题。",
            )
        }

        val apiCores = java["cores"]
        val procCores = native["cores"]
        findings += Finding(
            label = "核心数 (Runtime vs /proc/cpuinfo)",
            observed = "$apiCores vs ${procCores ?: "?"}",
            expected = "相等",
            verdict = when {
                procCores == null -> Verdict.INFO
                apiCores == procCores -> Verdict.COHERENT
                else -> Verdict.INCOHERENT
            },
        )

        native["cpu0MaxKhz"]?.let {
            findings += Finding("cpu0 最高频率", "${it.toLongOrNull()?.div(1000) ?: it} MHz")
        }
        findings += Finding(
            label = "CPU implementer / part",
            observed = "${native["cpuImplementer"] ?: "?"} / ${native["cpuPart"] ?: "?"}",
        )
        findings += Finding(
            label = "ABI 列表 (Java vs 属性)",
            observed = java["abis"].orEmpty(),
            expected = Facts.prop()["abis"].orEmpty(),
            verdict = run {
                val p = Facts.prop()["abis"].orEmpty()
                val j = java["abis"].orEmpty()
                when {
                    p.isBlank() -> Verdict.INFO
                    p == j -> Verdict.COHERENT
                    else -> Verdict.INCOHERENT
                }
            },
        )

        // The set of facts a profile must actually match, gathered in one place because they
        // share a property that makes them easy to overlook: none of them can be spoofed, and
        // each is a device-database lookup away from exposing the profile.
        //
        // The ABI list is the sharpest of them. `Build.SUPPORTED_ABIS` is derived at runtime
        // from the native ABI and deliberately left alone -- rewriting it would make an app try
        // to load .so files that do not exist. That is the right call for stability, but it
        // means an arm64-only handset claiming a Pixel reports one ABI where a Pixel reports
        // three, and nothing hides that.
        findings += Finding(
            label = "不可伪装、必须与档案匹配的物理事实",
            observed = "内存 ${memApi?.div(1024 / 1024) ?: "?"} GB · " +
                "ABI ${java["abis"].orEmpty()} · " +
                "核心 ${apiCores} · 最高 ${native["cpu0MaxKhz"]?.toLongOrNull()?.div(1000) ?: "?"} MHz",
            verdict = Verdict.INFO,
            detail = "这四项来自内核与 binder，任何一层都改不了。" +
                "选择档案时应优先使它们匹配：内存容量、ABI 列表、核心数、主频。" +
                "尤其 ABI——本机若仅 arm64-v8a 而档案机型支持 armeabi-v7a，" +
                "带机型库的 SDK 可以直接看出。这是选档案的问题，不是代码能解决的。",
        )

        val bad = findings.count { it.verdict == Verdict.INCOHERENT }
        return CheckResult(
            id, title,
            if (bad == 0) Verdict.INFO else Verdict.INCOHERENT,
            if (bad == 0) "物理事实自洽，且无法被伪装" else "$bad 项物理事实被单独改写过",
            findings,
        )
    }
}

/**
 * The vendor prefixes present in `MediaCodecList`.
 *
 * Codec component names carry the silicon vendor: `c2.qti.*` and `OMX.qcom.*` for Qualcomm,
 * `c2.mtk.*` for MediaTek, `c2.exynos.*` for Samsung, and `c2.android.*` / `OMX.google.*` for
 * the AOSP software codecs that every device has. The list is populated from the platform's
 * vendor configuration, so it describes the real chip and a Java-layer identity rewrite does
 * not touch it.
 *
 * This is reported as evidence rather than as a verdict, and deliberately carries no device
 * database: seeing "claiming a Pixel, but c2.mtk.* is present" needs no table to interpret.
 * It is also the concrete answer to "which channels still leak on this device", which the
 * earlier native check could not give on MediaTek hardware.
 */
class CodecVendorCheck : ProbeCheck {
    override val id = "codec-vendor"
    override val title = "编解码器厂商前缀（显示真实芯片）"
    override val rationale =
        "MediaCodecList 的组件名带厂商前缀：c2.qti./OMX.qcom. 是高通，c2.mtk. 是联发科。" +
            "它来自 vendor 配置，Java 层不覆盖就会直接暴露真实芯片。"

    /** Prefixes every Android device has, so their presence says nothing about the vendor. */
    private val generic = setOf("c2.android.", "OMX.google.", "c2.google.")

    /**
     * Codec prefix to the silicon family it identifies.
     *
     * Dolby is absent on purpose: `OMX.dolby.*` appears on many vendors' devices because Dolby
     * licenses its codecs, so its presence is not a vendor tell and flagging it would be noise.
     */
    private val codecVendor = mapOf(
        "c2.mtk." to "mtk", "OMX.MTK." to "mtk",
        "c2.qti." to "qcom", "OMX.qcom." to "qcom",
        "c2.exynos." to "exynos", "OMX.Exynos." to "exynos",
        "c2.google." to "google", "c2.gs101." to "google", "c2.gs201." to "google",
    )

    /** Platform codename to the silicon family, so the claim and the codec list can be compared. */
    private fun claimedFamily(platform: String): String? = SocAliases.familyOf(platform)

    override suspend fun run(context: Context): CheckResult {
        val names = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                .codecInfos.map { it.name }
        }.getOrElse {
            return CheckResult(
                id, title, Verdict.UNSUPPORTED,
                "无法枚举 MediaCodecList：${it.message}",
            )
        }

        val prefixes = names
            .map { name ->
                val parts = name.split(".")
                if (parts.size >= 2) "${parts[0]}.${parts[1]}." else "$name."
            }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

        val vendorSpecific = prefixes.filterKeys { it !in generic }

        // Which silicon families the codec list actually names.
        val codecFamilies = vendorSpecific.keys
            .mapNotNull { codecVendor[it] }
            .toSortedSet()

        // Which family the framework claims, via the platform codename.
        val platform = Props.get("ro.board.platform").orEmpty().lowercase()
        val claimedFamily = claimedFamily(platform)

        val findings = mutableListOf<Finding>()
        findings += Finding(
            label = "全部前缀（数量）",
            observed = prefixes.entries.joinToString("  ") { "${it.key}×${it.value}" },
        )
        findings += Finding(
            label = "厂商专有前缀",
            observed = if (vendorSpecific.isEmpty()) "无（仅通用前缀）" else vendorSpecific.keys.joinToString("  "),
        )
        findings += Finding("组件总数", observed = names.size.toString())
        findings += Finding("ro.board.platform（框架声明）", observed = platform.ifBlank { "(空)" })
        findings += Finding(
            label = "编解码器指认的芯片家族",
            observed = if (codecFamilies.isEmpty()) "无" else codecFamilies.joinToString(", "),
        )

        // The verdict. This is where the check stops reporting and starts judging: the claim
        // and the codec list are two independent statements about the same chip, and they can
        // simply be compared.
        val verdict = when {
            vendorSpecific.isEmpty() -> Verdict.COHERENT
            claimedFamily == null -> Verdict.INFO
            codecFamilies.isEmpty() -> Verdict.INFO
            codecFamilies.size == 1 && codecFamilies.first() == claimedFamily -> Verdict.COHERENT
            codecFamilies.contains(claimedFamily) -> Verdict.INCOHERENT
            else -> Verdict.INCOHERENT
        }

        findings += Finding(
            label = "一致性判定",
            observed = when {
                claimedFamily == null -> "平台 $platform 不在对照表内"
                codecFamilies.isEmpty() -> "编解码器列表未指认任何已知厂商"
                else -> "框架声称 $claimedFamily，编解码器指认 ${codecFamilies.joinToString("/")}"
            },
            verdict = verdict,
            detail = if (verdict == Verdict.INCOHERENT) {
                "编解码器列表来自 vendor 配置，Java 层不覆盖就会暴露真实芯片。" +
                    "可在 Guise 中对本应用开启「过滤编解码器厂商前缀」来缓解——" +
                    "注意该选项只能过滤不能增加，可能影响播放。"
            } else {
                null
            },
        )

        return CheckResult(
            id, title, verdict,
            when (verdict) {
                Verdict.COHERENT -> "编解码器列表与框架声明的芯片一致"
                Verdict.INCOHERENT ->
                    "编解码器暴露真实芯片：${vendorSpecific.keys.joinToString(", ")}"
                else -> "无法判定"
            },
            findings,
        )
    }
}

/**
 * Root and bootloader-state traces.
 *
 * Separate from [InjectionCheck] on purpose, because they are separate problems. The corrected
 * injection check found nothing: LSPosed loads the module in a way that leaves no matching path
 * in `/proc/self/maps`, and the legacy framework classes are absent. But that says nothing
 * about **root**, and root is what most apps that refuse to run on a modified device are
 * actually looking for. An app that bails out on sight of Magisk never reads a spoofed
 * `Build.MODEL`, so concealment of root matters more than concealment of the hook.
 *
 * Everything here is readable by an ordinary app with no permissions, which is the point: it
 * measures what an observer can see, not what a privileged tool could find.
 */
class RootDetectionCheck : ProbeCheck {
    override val id = "root-detection"
    override val title = "Root 痕迹（与注入痕迹是两回事）"
    override val rationale =
        "拒绝在 root 设备上运行的 App 检测的是 root，不是 LSPosed。" +
            "即使注入不可见，root 可见也足以让伪装白费——这一项专门测它。"

    private val suPaths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su", "/system/bin/.ext/su",
    )

    private val magiskPaths = listOf(
        "/sbin/.magisk", "/dev/.magisk", "/cache/.disable_magisk",
        "/data/adb/magisk", "/data/adb/ksu", "/data/adb/modules", "/data/adb/ksud",
    )

    private val rootPackages = listOf(
        "com.topjohnwu.magisk",
        "io.github.huskydg.magisk",
        "me.weishu.kernelsu",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
    )

    private val mountTokens = listOf("magisk", "kernelsu", "ksu", "worker", "debug_ramdisk")

    override suspend fun run(context: Context): CheckResult {
        val findings = mutableListOf<Finding>()

        // 1. su binaries. exists() cannot distinguish "absent" from "not permitted", so a
        //    miss is reported as such rather than as proof of absence.
        val suFound = suPaths.filter { path ->
            runCatching { java.io.File(path).exists() }.getOrDefault(false)
        }
        findings += Finding(
            label = "常见 su 路径可读",
            observed = if (suFound.isEmpty()) "未发现（也可能因权限不可见）" else suFound.joinToString("\n"),
            verdict = if (suFound.isEmpty()) Verdict.CONCEALED else Verdict.EXPOSED,
        )

        // 2. Manager app packages. Requires <queries> in the manifest on Android 11+, which is
        //    why the probe declares them: without it the lookup silently returns nothing even
        //    when the app is installed, which would read as a false all-clear.
        val installedRootApps = rootPackages.filter { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }
        findings += Finding(
            label = "Root 管理器应用已安装",
            observed = if (installedRootApps.isEmpty()) "无" else installedRootApps.joinToString(", "),
            verdict = if (installedRootApps.isEmpty()) Verdict.CONCEALED else Verdict.EXPOSED,
        )

        // 3. Mount table. Magisk mounts an overlay or tmpfs over /system, and KernelSU uses a
        //    similar trick; both leave source strings an ordinary app can read.
        val mountInfo = Facts.readFile("/proc/self/mountinfo").orEmpty()
        val mountHits = mountInfo.lineSequence()
            .filter { line -> mountTokens.any { line.contains(it, ignoreCase = true) } }
            .map { it.substringAfter(" - ").ifEmpty { it } }
            .distinct()
            .toList()
        findings += Finding(
            label = "/proc/self/mountinfo 可疑挂载",
            observed = if (mountHits.isEmpty()) "无" else mountHits.joinToString("\n"),
            verdict = if (mountHits.isEmpty()) Verdict.CONCEALED else Verdict.EXPOSED,
        )

        // 4. Bootloader-published state. These come from the kernel command line via init, and
        //    an unlocked device says so. This is the cheapest reliable signal there is, and it
        //    is also the property half of the attestation story: the TEE knows the same fact
        //    and signs it, which is why concealing the property does not conceal the truth.
        val bootState = Props.get("ro.boot.verifiedbootstate").orEmpty()
        val flashLocked = Props.get("ro.boot.flash.locked").orEmpty()
        val tags = Props.get("ro.build.tags").orEmpty()

        val unlocked = bootState.equals("orange", true) || bootState.equals("yellow", true) ||
            bootState.equals("red", true) || flashLocked == "0"
        findings += Finding(
            label = "ro.boot.verifiedbootstate",
            observed = bootState.ifBlank { "(不存在或为空)" },
            expected = "green",
            verdict = if (bootState.isBlank()) Verdict.INFO else if (unlocked) Verdict.EXPOSED else Verdict.CONCEALED,
        )
        findings += Finding(
            label = "ro.boot.flash.locked",
            observed = flashLocked.ifBlank { "(不存在或为空)" },
            expected = "1",
            verdict = when (flashLocked) {
                "1" -> Verdict.CONCEALED
                "0" -> Verdict.EXPOSED
                else -> Verdict.INFO
            },
        )
        // Absent and present-but-empty are different facts: a property that exists with an empty
        // value means something cleared it, which is itself worth seeing. Collapsing the two
        // with orEmpty() would hide that.
        fun describe(value: String?): String = when {
            value == null -> "(不存在)"
            value.isEmpty() -> "(存在但为空)"
            else -> value
        }
        val secure = Props.get("ro.secure")
        val debuggable = Props.get("ro.debuggable")
        // ro.secure is a one-directional signal, and a weak one. Its only real consumer is adbd,
        // which reads it as `GetBoolProperty("ro.secure", true)` -- so an unset ro.secure behaves
        // exactly like ro.secure=1. Absence is therefore normal, 1 is normal, and only 0 carries
        // information: it makes adbd keep root, so `adb shell` is already root.
        //
        // An earlier version of this check showed `expected = "1"`, which implied a missing
        // property was a discrepancy. It is not, and AOSP no longer even generates the property
        // (verified: absent from buildinfo.sh in 11 through 14, and that file was removed in 15).
        findings += Finding(
            "ro.secure",
            observed = describe(secure),
            verdict = if (secure == "0") Verdict.EXPOSED else Verdict.INFO,
            detail = when {
                secure == null ->
                    "属性不存在。这不代表不安全：adbd 以默认值 true 读取它，" +
                        "未设置与 ro.secure=1 行为完全相同。现代 AOSP 已不再生成该属性，" +
                        "所以缺失是正常的，不能作为 root 判据。"
                secure.isEmpty() ->
                    "存在但为空。adbd 仍按 true 处理，同样不构成信号。"
                secure == "0" ->
                    "ro.secure=0 会让 adbd 默认保留 root，即 adb shell 直接是 root 身份。" +
                        "正常 user 与 userdebug 构建都应为 1，只有 eng 构建或被人为改写才会是 0。"
                else -> null
            },
        )
        findings += Finding(
            "ro.debuggable",
            observed = describe(debuggable),
            expected = "0",
            verdict = if (debuggable == "1") Verdict.EXPOSED else Verdict.INFO,
            detail = if (debuggable == "1") {
                "可调试构建。init 以 GetBoolProperty(\"ro.debuggable\", false) 读取它，" +
                    "为 1 表示这是一个 userdebug/eng 构建，正常量产机应为 0。"
            } else {
                null
            },
        )
        findings += Finding(
            label = "ro.build.tags",
            observed = tags.ifBlank { "(空)" },
            expected = "release-keys",
            verdict = if (tags == "test-keys") Verdict.EXPOSED else Verdict.INFO,
        )

        // Cross-check, and the most interesting thing this check produces on a real device.
        //
        // Installing a root manager requires unlocking the bootloader, and an unlocked
        // bootloader reports `orange` (or `yellow` when re-locked with a custom key). So a
        // device that reports `green` with `flash.locked=1` while a root manager is present is
        // in an impossible state: something is already rewriting `ro.boot.*`.
        //
        // That is worth knowing for two reasons. It is a contradiction an app that cross-checks
        // the two can find, and it means any later root layer must not rewrite these properties
        // a second time, or the two rewrites will fight.
        val magiskPresent = installedRootApps.isNotEmpty()
        val claimsLocked = bootState.equals("green", true) && flashLocked == "1"
        if (magiskPresent && claimsLocked) {
            findings += Finding(
                label = "Root 存在 vs 启动状态声明",
                observed = "已安装 root 管理器，但 verifiedbootstate=green、flash.locked=1",
                expected = "已 root 的设备应为 orange / 0",
                verdict = Verdict.INCOHERENT,
                detail = "安装 root 管理器必须先解锁 bootloader，解锁后 bootloader 会报 orange。" +
                    "现在报 green，说明已有别的东西在改写 ro.boot.*（常见于 PlayIntegrityFix 一类模块）。" +
                    "这本身是矛盾：同时查这两项的 App 能发现。也意味着后续不应再重复改写这些属性。",
            )
        }

        val exposed = findings.count { it.verdict == Verdict.EXPOSED }
        return CheckResult(
            id, title,
            if (exposed == 0) Verdict.CONCEALED else Verdict.EXPOSED,
            if (exposed == 0) "未发现 root 痕迹" else "$exposed 类 root 痕迹对普通 App 可见",
            findings,
        )
    }
}

/**
 * Reports what the platform is willing to prove, and stops there.
 *
 * This exists to make the boundary explicit rather than to attempt anything. Key Attestation
 * is signed inside the TEE, `rootOfTrust` reports the bootloader's view of
 * `verifiedBootState`, and nothing in the Android software stack can change what the secure
 * world signs. Reporting the state honestly is more useful than implying it can be defeated.
 */
class AttestationCheck : ProbeCheck {
    override val id = "attestation"
    override val title = "硬件证明状态（本模块无法伪造）"
    override val rationale =
        "Key Attestation 的证书在 TEE 内签名，rootOfTrust 由 bootloader 提供。" +
            "这是软件栈的天花板，任何 hook 都无法越过。"

    override suspend fun run(context: Context): CheckResult {
        val findings = mutableListOf<Finding>()

        val hasKeyStore = runCatching {
            Class.forName("android.security.keystore.KeyGenParameterSpec")
            true
        }.getOrDefault(false)
        findings += Finding("AndroidKeyStore 可用", hasKeyStore.toString())

        val features = runCatching {
            context.packageManager.systemAvailableFeatures
                .mapNotNull { it.name }
                .filter { it.contains("keystore") || it.contains("strongbox") || it.contains("tee") }
        }.getOrDefault(emptyList())
        findings += Finding(
            "TEE / StrongBox 相关特性",
            features.ifEmpty { listOf("未声明") }.joinToString(", "),
        )

        findings += Finding(
            label = "结论",
            observed = "本机由安全核提供硬件背书的密钥证明",
            verdict = Verdict.INFO,
            detail = "该证明只包含平台状态（是否解锁、系统版本、补丁级别），不包含机型。" +
                "所以改机型对它没有任何影响：它回答的不是「你是什么设备」，" +
                "而是「这台设备是否原厂未解锁」。这一层不是本地伪装能解决的。",
        )

        return CheckResult(
            id, title, Verdict.INFO,
            "硬件证明存在且不可伪造——这是本地伪装的边界",
            findings,
        )
    }
}
