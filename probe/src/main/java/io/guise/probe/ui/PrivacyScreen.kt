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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.guise.probe.model.Verdict
import io.guise.probe.privacy.DomainReading
import io.guise.probe.privacy.PrivacyReport
import io.guise.probe.privacy.ProbeDomain

/**
 * Permission-gated data checks, kept off the main report on purpose.
 *
 * The rest of the probe reads only what any app can read with no permissions, because its value
 * is seeing exactly what an observer sees. This screen breaks that rule deliberately, so it says
 * so at the top and asks for each permission one at a time rather than requesting them on launch.
 */
@Composable
fun PrivacyScreen(
    report: PrivacyReport?,
    onRequest: (ProbeDomain) -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "这一页需要权限",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "探针的其余部分刻意不申请任何权限——它要看到的是「观察者能看到什么」。" +
                            "这一页是显式例外：要判断数据有没有被替换，就必须能读到真实数据。" +
                            "所以权限由你逐个授予，不会自动申请。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Text(
                "数据域",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        items(ProbeDomain.entries.toList(), key = { it.id }) { domain ->
            val reading = report?.readings?.firstOrNull { it.domain == domain }
            DomainCard(domain, reading, onRequest)
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("一致性判定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onRefresh) { Text("重新读取") }
            }
        }

        if (report == null) {
            item { Text("尚未读取。", style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(report.findings, key = { "f-${it.label}" }) { finding ->
                FindingRow(finding)
                HorizontalDivider()
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DomainCard(
    domain: ProbeDomain,
    reading: DomainReading?,
    onRequest: (ProbeDomain) -> Unit,
) {
    val granted = reading?.granted == true
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(domain.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        domain.authority,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        reading == null -> "—"
                        !granted -> "未授权"
                        reading.rowCount == null -> "读取失败"
                        else -> "${reading.rowCount} 条"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (granted && reading?.rowCount == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                domain.permission,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(4.dp))
            // The permission is requested from here, by hand, one domain at a time.
            TextButton(onClick = { onRequest(domain) }) {
                Text(if (granted) "重新读取（或撤销授权后再试）" else "申请${domain.label}权限")
            }
        }
    }
}

@Composable
private fun FindingRow(finding: io.guise.probe.model.Finding) {
    val accent = when (finding.verdict) {
        Verdict.COHERENT, Verdict.CONCEALED -> MaterialTheme.colorScheme.primary
        Verdict.INCOHERENT, Verdict.EXPOSED -> MaterialTheme.colorScheme.error
        Verdict.UNSUPPORTED -> MaterialTheme.colorScheme.outline
        Verdict.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(finding.label, style = MaterialTheme.typography.labelMedium, color = accent)
        Text(
            finding.observed,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        finding.expected?.takeIf { it != finding.observed }?.let {
            Text(
                "应为 $it",
                style = MaterialTheme.typography.bodySmall,
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
