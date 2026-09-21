package io.guise.probe

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.guise.probe.model.ProbeReport
import io.guise.probe.privacy.PrivacyProbe
import io.guise.probe.privacy.PrivacyReport
import io.guise.probe.ui.PrivacyScreen
import io.guise.probe.ui.ProbeScreen
import kotlinx.coroutines.launch

private enum class Screen(val title: String) {
    REPORT("Guise 探针"),
    PRIVACY("权限与数据"),
}

/**
 * The diagnostic harness.
 *
 * The report screen claims no permissions and reads only what any ordinary app can read, because
 * its entire value depends on seeing exactly what an observer sees -- no more. A privileged probe
 * would report a reassuring picture that no real fingerprinting SDK would.
 *
 * The privacy screen is the deliberate exception. Judging whether data was replaced requires
 * being able to read it, so each permission is requested by hand from the row that needs it, and
 * nothing is asked for on launch.
 */
class ProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 makes edge-to-edge mandatory whether or not an app asks for it. Calling
        // this makes the intent explicit and gives the insets below something deterministic to
        // consume.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val runner = ProbeRunner(this)
        val privacyProbe = PrivacyProbe(this)

        setContent {
            var screen by remember { mutableStateOf(Screen.REPORT) }
            var report by remember { mutableStateOf<ProbeReport?>(null) }
            var running by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var privacy by remember { mutableStateOf<PrivacyReport?>(null) }
            val scope = rememberCoroutineScope()

            val refresh: () -> Unit = {
                scope.launch {
                    running = true
                    error = null
                    runCatching { runner.run() }
                        .onSuccess { report = it; error = null }
                        .onFailure { error = it.stackTraceToString() }
                    running = false
                }
            }

            val readPrivacy: () -> Unit = { privacy = privacyProbe.read() }

            // Launched from the row that needs it, never on start.
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { readPrivacy() }

            LaunchedEffect(Unit) { refresh() }

            MaterialTheme {
                Surface {
                    Column(
                        Modifier
                            .fillMaxSize()
                            // Without this the top bar is drawn underneath the status bar, and the
                            // system swallows every tap in that strip -- which made the "权限检查"
                            // button impossible to press on a full-screen device. Safe-drawing also
                            // covers the navigation bar and any display cutout, so the same bug is
                            // not waiting at the bottom of the list.
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        TopBar(
                            title = screen.title,
                            onBack = if (screen == Screen.REPORT) null else ({ screen = Screen.REPORT }),
                            onOpenPrivacy = if (screen == Screen.REPORT) {
                                {
                                    readPrivacy()
                                    screen = Screen.PRIVACY
                                }
                            } else {
                                null
                            },
                        )
                        HorizontalDivider()

                        when (screen) {
                            Screen.REPORT -> ProbeScreen(
                                report = report,
                                running = running,
                                error = error,
                                onRerun = refresh,
                                onResetBaseline = {
                                    runner.resetBaseline()
                                    refresh()
                                },
                                onExport = { text ->
                                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("Guise 探针报告", text),
                                    )
                                    Toast.makeText(
                                        this@ProbeActivity,
                                        "报告已复制到剪贴板",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )

                            Screen.PRIVACY -> PrivacyScreen(
                                report = privacy,
                                onRequest = { domain -> permissionLauncher.launch(domain.permission()) },
                                onRefresh = readPrivacy,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: (() -> Unit)?, onOpenPrivacy: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("返回") }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp),
        )
        if (onOpenPrivacy != null) {
            TextButton(onClick = onOpenPrivacy) { Text("权限检查") }
        }
    }
}
