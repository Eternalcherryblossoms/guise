package io.guise.probe.model

import android.content.Context

/**
 * What a probe check can conclude.
 *
 * Note what is *absent*: there is no TRUE or FALSE. A client cannot tell a coherent lie
 * from the truth -- that is the central claim of this project, and a diagnostic tool that
 * pretended otherwise would be lying about its own capabilities.
 *
 * What a client *can* determine is whether the platform's answers agree with each other,
 * and whether the machinery doing the rewriting is itself visible.
 */
enum class Verdict {
    /** Every channel that should agree, agrees. */
    COHERENT,

    /** Two channels that describe the same fact disagree. This is the failure that matters. */
    INCOHERENT,

    /** The rewriting machinery is visible to an ordinary app. */
    EXPOSED,

    /** No rewriting machinery found from this vantage point. */
    CONCEALED,

    /** A measurement, not a judgement. */
    INFO,

    /** The platform would not tell us. */
    UNSUPPORTED,
    ;

    val isProblem: Boolean get() = this == INCOHERENT || this == EXPOSED
}

/** One observed value, and what it should have been. */
data class Finding(
    val label: String,
    val observed: String,
    val expected: String? = null,
    val verdict: Verdict = Verdict.INFO,
    val detail: String? = null,
)

data class CheckResult(
    val id: String,
    val title: String,
    val verdict: Verdict,
    val headline: String,
    val findings: List<Finding> = emptyList(),
)

/**
 * A single diagnostic.
 *
 * [rationale] is shown in the UI because a diagnostic whose purpose is not stated is just
 * a number, and the interesting part of this tool is *why* each measurement matters.
 */
interface ProbeCheck {
    val id: String
    val title: String
    val rationale: String

    suspend fun run(context: Context): CheckResult
}

/**
 * One source compared against another, restricted to the keys they both carry.
 *
 * Hashing the *whole* of each source would not be comparable, because the sources describe
 * different facts. Restricting to the shared keys is what makes it a fair test: the same
 * question asked of two independent readers.
 */
data class SourceComparison(
    val label: String,
    val sharedKeys: List<String>,
    val hashA: String,
    val hashB: String,
    val divergences: List<Triple<String, String, String>>,
) {
    val agrees: Boolean get() = sharedKeys.isNotEmpty() && hashA == hashB
}

/**
 * The headline instrument.
 *
 * Three independent readings of the platform's own answers:
 *
 *  - Java APIs  -- `Build.*`, `Runtime`, `ActivityManager`
 *  - Properties -- the same identity facts read as `ro.*`
 *  - Native     -- `/proc`, `/sys`, the kernel's own description
 *
 * A module that rewrites only the Java surface -- by far the most common way for one of
 * these to be half-working -- leaves the other two saying something else, and the
 * disagreement names the leaking field rather than merely flagging that one exists.
 *
 * None of this needs a device database: it asks only that the platform not contradict
 * itself, which is a fair thing to ask of any device, spoofed or not.
 */
data class AggregateReading(
    val javaFacts: Map<String, String>,
    val propFacts: Map<String, String>,
    val nativeFacts: Map<String, String>,
    /** Identity surface: does the property layer keep up with the Java layer? */
    val javaVsProp: SourceComparison,
    /** Physical surface: does the kernel agree with what the framework claims? */
    val javaVsNative: SourceComparison,
) {
    val fullyCoherent: Boolean get() = javaVsProp.agrees && javaVsNative.agrees
}

data class ProbeReport(
    val results: List<CheckResult>,
    val aggregate: AggregateReading?,
    val packageName: String,
    val deviceLabel: String,
    val baselineNote: String? = null,
) {
    val problems: List<CheckResult> get() = results.filter { it.verdict.isProblem }
    val incoherentCount: Int get() = results.count { it.verdict == Verdict.INCOHERENT }
    val exposedCount: Int get() = results.count { it.verdict == Verdict.EXPOSED }
}

/**
 * Renders the whole report as plain text.
 *
 * Exists because the summary screen shows counts, and counts are not actionable: "1 divergence"
 * does not say *which* field diverged, and a named field is the only thing that can be acted
 * on. A report that can be copied out of the device is the difference between a bug report and
 * a shrug.
 */
fun ProbeReport.toPlainText(): String = buildString {
    appendLine("=== Guise 探针报告 ===")
    appendLine("设备   : $deviceLabel")
    appendLine("进程   : $packageName")
    appendLine(
        "时间   : " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date()),
    )
    appendLine()

    aggregate?.let { agg ->
        appendLine("-- 三源哈希 --")
        appendLine(
            "身份面  Java=${agg.javaVsProp.hashA}  属性=${agg.javaVsProp.hashB}  " +
                if (agg.javaVsProp.agrees) "一致" else "不一致",
        )
        appendLine(
            "物理面  Java=${agg.javaVsNative.hashA}  内核=${agg.javaVsNative.hashB}  " +
                if (agg.javaVsNative.agrees) "一致" else "不一致",
        )
        appendLine(
            "        比对键: ${agg.javaVsProp.sharedKeys.joinToString(", ")}",
        )
        appendLine()
    }

    baselineNote?.let {
        appendLine("-- 基线 --")
        appendLine(it)
        appendLine()
    }

    appendLine("-- 合计: 不一致 ${incoherentCount} 项, 痕迹暴露 ${exposedCount} 项 --")
    appendLine()

    results.forEach { r ->
        appendLine("[${r.verdict}] ${r.title}")
        appendLine("    ${r.headline}")
        r.findings.forEach { f ->
            val expected = f.expected?.takeIf { it != f.observed }?.let { "  (应为 $it)" }.orEmpty()
            appendLine("    · ${f.label}: ${f.observed}$expected")
            f.detail?.let { appendLine("      $it") }
        }
        appendLine()
    }
}
