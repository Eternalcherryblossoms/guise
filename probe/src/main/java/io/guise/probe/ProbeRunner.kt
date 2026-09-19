package io.guise.probe

import android.content.Context
import android.os.Build
import io.guise.probe.checks.AggregateHashCheck
import io.guise.probe.checks.AttestationCheck
import io.guise.probe.checks.CodecVendorCheck
import io.guise.probe.checks.Facts
import io.guise.probe.checks.FingerprintShapeCheck
import io.guise.probe.checks.InjectionCheck
import io.guise.probe.checks.NativeSoCCheck
import io.guise.probe.checks.PhysicalCheck
import io.guise.probe.checks.PropertyMirrorCheck
import io.guise.probe.checks.RootDetectionCheck
import io.guise.probe.model.AggregateReading
import io.guise.probe.model.CheckResult
import io.guise.probe.model.ProbeCheck
import io.guise.probe.model.ProbeReport
import io.guise.probe.model.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs every check and assembles the report.
 *
 * Also keeps a baseline of the first reading it ever took. That matters because the probe
 * cannot know whether it is currently being spoofed -- it has no ground truth -- so the only
 * honest way to answer "did enabling the module change anything?" is to compare against what
 * this same app saw before. Without a baseline, a coherent reading is indistinguishable from
 * an untouched device, which is exactly the property that makes the module work.
 */
class ProbeRunner(private val context: Context) {

    private val prefs = context.getSharedPreferences("guise.probe.baseline", Context.MODE_PRIVATE)

    suspend fun run(): ProbeReport = withContext(Dispatchers.IO) {
        val java = Facts.java(context)
        val prop = Facts.prop()
        val native = Facts.native(context)

        val aggregate = AggregateReading(
            javaFacts = java,
            propFacts = prop,
            nativeFacts = native,
            javaVsProp = Facts.compare("身份面", java, prop),
            javaVsNative = Facts.compare("物理面", java, native),
        )

        val checks: List<ProbeCheck> = listOf(
            AggregateHashCheck(aggregate),
            PropertyMirrorCheck(),
            FingerprintShapeCheck(),
            NativeSoCCheck(),
            CodecVendorCheck(),
            PhysicalCheck(),
            InjectionCheck(),
            RootDetectionCheck(),
            AttestationCheck(),
        )

        val results = checks.map { check ->
            runCatching { check.run(context) }.getOrElse { t ->
                CheckResult(
                    id = check.id,
                    title = check.title,
                    verdict = Verdict.UNSUPPORTED,
                    headline = "检查执行失败：${t.message}",
                )
            }
        }

        ProbeReport(
            results = results,
            aggregate = aggregate,
            packageName = context.packageName,
            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}",
            baselineNote = baselineNote(java),
        )
    }

    /** First run records; later runs diff. */
    private fun baselineNote(java: Map<String, String>): String {
        val current = Facts.hash(java)
        val storedHash = prefs.getString(KEY_HASH, null)
        val storedFacts = prefs.getString(KEY_FACTS, null)

        if (storedHash == null) {
            prefs.edit()
                .putString(KEY_HASH, current)
                .putString(KEY_FACTS, encode(java))
                .apply()
            return "已记录基线（身份哈希 $current，机型 ${java["model"]}）。" +
                "启用伪装模块后再次运行，即可看到具体哪些字段发生了变化。"
        }

        if (storedHash == current) {
            return "与本机基线一致（$current）——当前身份未被改写，或改写值与基线恰好相同。"
        }

        val before = storedFacts?.let(::decode).orEmpty()
        val changed = java.keys.intersect(before.keys)
            .filter { java[it] != before[it] }
            .sorted()

        return buildString {
            append("与基线不同：$storedHash → $current。")
            if (changed.isEmpty()) {
                append("（哈希变了但没有字段差异，可能是读取失败）")
            } else {
                append("变化字段：")
                append(changed.joinToString("；") { "$it ${before[it]} → ${java[it]}" })
            }
        }
    }

    fun resetBaseline() = prefs.edit().clear().apply()

    private fun encode(facts: Map<String, String>) =
        facts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

    private fun decode(text: String) =
        text.lines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()

    private companion object {
        const val KEY_HASH = "identityHash"
        const val KEY_FACTS = "identityFacts"
    }
}
