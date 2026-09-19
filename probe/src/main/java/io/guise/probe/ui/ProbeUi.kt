package io.guise.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.guise.probe.model.CheckResult
import io.guise.probe.model.Finding
import io.guise.probe.model.ProbeReport
import io.guise.probe.model.Verdict
import io.guise.probe.model.toPlainText

@Composable
fun ProbeScreen(
    report: ProbeReport?,
    running: Boolean,
    error: String?,
    onRerun: () -> Unit,
    onResetBaseline: () -> Unit,
    onExport: (String) -> Unit,
) {
    when {
        // A diagnostic tool that swallows its own failures is worse than useless: you are
        // left staring at an empty screen with no idea whether the device is clean or the
        // tool is broken. Any failure to collect is shown verbatim.
        error != null -> ErrorCard(error, onRerun)

        running && report == null -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在采集…", style = MaterialTheme.typography.bodyMedium)
        }

        report == null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("尚未采集", style = MaterialTheme.typography.titleMedium)
        }

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryCard(report, onRerun, onResetBaseline, onExport) }

            report.baselineNote?.let { note ->
                item { NoteCard("基线对照", note) }
            }

            item {
                Text(
                    "检查项",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(report.results, key = { it.id }) { result -> CheckCard(result) }
        }
    }
}

@Composable
private fun ErrorCard(error: String, onRerun: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("采集失败", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRerun) { Text("重试") }
        }
    }
}

@Composable
private fun SummaryCard(
    report: ProbeReport,
    onRerun: () -> Unit,
    onResetBaseline: () -> Unit,
    onExport: (String) -> Unit,
) {
    val agg = report.aggregate
    val tone = when {
        agg != null && agg.fullyCoherent -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = tone)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (agg?.fullyCoherent == true) "各来源自洽" else "存在不自洽",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(report.deviceLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                report.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "不一致 ${report.incoherentCount} 项 ·  痕迹暴露 ${report.exposedCount} 项",
                style = MaterialTheme.typography.bodyMedium,
            )

            agg?.let {
                Spacer(Modifier.height(12.dp))
                HashLine("身份面 Java", it.javaVsProp.hashA, "属性", it.javaVsProp.hashB, it.javaVsProp.agrees)
                HashLine("物理面 Java", it.javaVsNative.hashA, "内核", it.javaVsNative.hashB, it.javaVsNative.agrees)
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRerun) { Text("重新采集") }
                TextButton(onClick = onResetBaseline) { Text("重置基线") }
                TextButton(onClick = { onExport(report.toPlainText()) }) { Text("复制报告") }
            }
        }
    }
}

@Composable
private fun HashLine(labelA: String, hashA: String, labelB: String, hashB: String, agrees: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$labelA $hashA",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (agrees) "= " else "≠ ",
            style = MaterialTheme.typography.labelLarge,
            color = if (agrees) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(
            "$labelB $hashB",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun NoteCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CheckCard(result: CheckResult) {
    val accent = verdictColor(result.verdict)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        result.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(result.headline, style = MaterialTheme.typography.bodySmall, color = accent)
                }
                if (result.verdict.isProblem) {
                    Text("问题", style = MaterialTheme.typography.labelSmall, color = accent)
                }
            }

            if (result.findings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                // A plain Column, NOT a scrollable one.
                //
                // An earlier version wrapped this in Modifier.verticalScroll, which is a hard
                // error: a vertically scrollable child measured inside a vertically scrolling
                // LazyColumn receives infinite max-height constraints and Compose throws
                // "Vertically scrollable component was measured with an infinity maximum
                // height constraints". On device that surfaced as the probe hanging on
                // "collecting" and then dying as soon as the report rendered.
                Column {
                    result.findings.forEach { FindingRow(it) }
                }
            }
        }
    }
}

@Composable
private fun FindingRow(finding: Finding) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            finding.label,
            style = MaterialTheme.typography.labelMedium,
            color = verdictColor(finding.verdict),
        )
        Text(
            finding.observed,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        finding.expected?.takeIf { it != finding.observed }?.let {
            Text(
                "应为 $it",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        finding.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun verdictColor(verdict: Verdict): Color = when (verdict) {
    Verdict.COHERENT, Verdict.CONCEALED -> MaterialTheme.colorScheme.primary
    Verdict.INCOHERENT, Verdict.EXPOSED -> MaterialTheme.colorScheme.error
    Verdict.UNSUPPORTED -> MaterialTheme.colorScheme.outline
    Verdict.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}
