package io.guise.probe

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.guise.probe.model.ProbeReport
import io.guise.probe.ui.ProbeScreen
import kotlinx.coroutines.launch

/**
 * The diagnostic harness.
 *
 * It claims no permissions and reads only what any ordinary app can read, because its entire
 * value depends on seeing exactly what an observer sees -- no more. A probe with privileged
 * access would report a reassuring picture that no real fingerprinting SDK would.
 */
class ProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runner = ProbeRunner(this)

        setContent {
            var report by remember { mutableStateOf<ProbeReport?>(null) }
            var running by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
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

            LaunchedEffect(Unit) { refresh() }

            MaterialTheme {
                Surface {
                    ProbeScreen(
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
                }
            }
        }
    }
}
