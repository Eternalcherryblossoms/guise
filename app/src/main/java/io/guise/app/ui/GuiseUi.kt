package io.guise.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.guise.app.data.InstalledApp
import io.guise.core.config.TargetConfig
import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.FieldKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuiseRoot(vm: GuiseViewModel) {

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val title = when (val s = vm.screen) {
        Screen.Home -> "Guise"
        Screen.AppPicker -> "选择应用"
        Screen.DevicePicker -> "选择机型档案"
        is Screen.Detail -> vm.labelOf(s.packageName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (vm.screen != Screen.Home) {
                        TextButton(onClick = vm::back) { Text("返回") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            when (vm.screen) {
                Screen.Home -> FloatingActionButton(onClick = vm::openAppPicker) { Text("+") }
                Screen.DevicePicker -> FloatingActionButton(onClick = vm::captureCurrentDevice) { Text("抓取") }
                else -> Unit
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = vm.screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.AppPicker -> AppPickerScreen(vm)
                Screen.DevicePicker -> DevicePickerScreen(vm)
                is Screen.Detail -> DetailScreen(vm, s.packageName)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
private fun HomeScreen(vm: GuiseViewModel) {
    val config = vm.config
    val targets = config.targets.values.sortedBy { it.packageName }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { FrameworkCard(vm) }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("模块总开关", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = config.globallyEnabled,
                    onCheckedChange = vm::setGlobalEnabled,
                )
            }
        }

        if (targets.isEmpty()) {
            item {
                Text(
                    "还没有配置任何应用。\n点击右下角 + 选择要伪装的 App。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(targets, key = { it.packageName }) { target ->
                TargetRow(vm, target)
            }
        }
    }
}

/**
 * Whether the module is live.
 *
 * Deliberately just one line. An earlier version showed a layer breakdown (Hook / Root /
 * Zygisk) because the architecture was going to grow a root component and a native component.
 * That plan was dropped on evidence -- the probe showed Magisk already conceals the traces a
 * root layer would have addressed, and the one remaining leak is a `PackageManager` question
 * that belongs to this layer anyway. Once root is not needed, showing "Root not enabled" is
 * worse than showing nothing: it implies a missing capability the user should go and enable.
 *
 * What remains is the one fact that actually matters in the UI: did the framework bind.
 */
@Composable
private fun FrameworkCard(vm: GuiseViewModel) {
    val connected = vm.frameworkConnected
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (connected) "模块运行中" else "未检测到 LSPosed 框架",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                vm.frameworkInfo
                    ?: "请在 LSPosed 中启用本模块，并在模块作用域中勾选目标应用，然后重启目标应用。" +
                        "配置仍可编辑，会在框架连接后同步。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = vm::refreshFrameworkStatus) { Text("重新检测") }
        }
    }
}

/** A bordered explanatory note. Used where a behaviour needs its reason stated inline. */
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
private fun TargetRow(vm: GuiseViewModel, target: TargetConfig) {
    val profile = vm.profileFor(target.packageName)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(target.packageName) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    vm.labelOf(target.packageName),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    target.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    profile?.let { "${it.name}  ·  ${it.model}" } ?: "未选择档案",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (profile == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                if (target.overrides.isNotEmpty()) {
                    Text(
                        "${target.overrides.size} 项字段覆盖",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Switch(
                checked = target.enabled,
                onCheckedChange = { vm.setTargetEnabled(target.packageName, it) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// App picker
// ---------------------------------------------------------------------------

@Composable
private fun AppPickerScreen(vm: GuiseViewModel) {
    val query = vm.search
    val apps = vm.apps.filter {
        query.isBlank() ||
            it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(query, { vm.search = it }, "搜索应用")
        LazyColumn(Modifier.fillMaxSize()) {
            items(apps, key = { it.packageName }) { app ->
                AppRow(app) { vm.openDevicePicker(app.packageName) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    ) {
        Text(app.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            app.packageName,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Device picker
// ---------------------------------------------------------------------------

@Composable
private fun DevicePickerScreen(vm: GuiseViewModel) {
    val brands = vm.brands()
    val query = vm.search

    Column(Modifier.fillMaxSize()) {
        SearchField(query, { vm.search = it }, "搜索机型、型号或品牌")
        LazyColumn(Modifier.fillMaxSize()) {
            brands.forEach { (brand, devices) ->
                item(key = "hdr-$brand") {
                    Text(
                        brand,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    )
                }
                items(devices, key = { it.key }) { device ->
                    DeviceRow(device) { vm.pickDevice(device) }
                    HorizontalDivider()
                }
            }
            if (brands.isEmpty()) {
                item {
                    Text(
                        "机型库为空。可点右下角「抓取」把本机存为档案。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceProfile, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(device.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${device.model}  ·  ${device.product}  ·  Android ${device.androidRelease} (API ${device.sdkInt})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            device.fingerprint,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ---------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------

@Composable
private fun DetailScreen(vm: GuiseViewModel, packageName: String) {
    val target = vm.config.target(packageName)
    val effective = vm.effectiveFor(packageName)
    val profile = vm.profileFor(packageName)

    if (target == null) {
        Text("配置已移除", Modifier.padding(16.dp))
        return
    }

    var editing by remember { mutableStateOf<FieldKey?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { FrameworkCard(vm) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("机型档案", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        profile?.name ?: "未选择",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    profile?.let {
                        Text(
                            "${it.brand} / ${it.model} / ${it.device}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.changeProfile(packageName) }) { Text("更换档案") }
                }
            }
        }

        // The profile's Android version is informational. Reporting the device's real one is
        // not a limitation but the only coherent choice: the API level cannot be rewritten
        // safely, and the release and fingerprint have to agree with it. Surfaced here because
        // the probe flags the mismatch otherwise, and the reason is not obvious.
        vm.versionMismatch(packageName)?.let { note ->
            item { NoteCard("Android 版本取自本机", note) }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = target.enabled,
                    onCheckedChange = { vm.setTargetEnabled(packageName, it) },
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("过滤编解码器厂商前缀", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "MediaCodecList 的组件名带芯片厂商前缀（c2.mtk. 等），" +
                            "它来自 vendor 配置，不覆盖就会暴露真实芯片。" +
                            "但列表只能过滤不能增加，隐藏后可能让目标应用找不到它需要的解码器（表现为播放异常）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = target.stripForeignCodecs,
                    onCheckedChange = { vm.setStripForeignCodecs(packageName, it) },
                )
            }
        }

        item {
            Text(
                "字段覆盖（留空表示跟随档案）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        items(FieldKey.entries.toList(), key = { it.id }) { key ->
            val value = effective?.string(key).orEmpty()
            val overridden = effective?.isOverridden(key) == true
            FieldRow(
                key = key,
                value = value,
                overridden = overridden,
                onEdit = { editing = key },
            )
            HorizontalDivider()
        }

        item {
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { vm.removeTarget(packageName) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("移除该应用", color = MaterialTheme.colorScheme.error)
            }
        }

        item { Spacer(Modifier.height(72.dp)) }
    }

    editing?.let { key ->
        OverrideDialog(
            key = key,
            current = effective?.string(key).orEmpty(),
            derived = effective?.derived(key).orEmpty(),
            onDismiss = { editing = null },
            onConfirm = { value ->
                vm.setOverride(packageName, key, value)
                editing = null
            },
        )
    }
}

@Composable
private fun FieldRow(
    key: FieldKey,
    value: String,
    overridden: Boolean,
    onEdit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                key.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (overridden) {
                Text(
                    "已覆盖",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Text(
            value.ifBlank { "（空）" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (overridden) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun OverrideDialog(
    key: FieldKey,
    current: String,
    derived: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var text by remember(key) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.label) },
        text = {
            Column {
                Text(
                    "档案默认值：$derived",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("覆盖值") },
                )
                if (key == FieldKey.VERSION_SDK_INT) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "警告：修改 API 级别会让应用走它在本机不存在的代码路径，极易崩溃。" +
                            "仅在确有需要时使用，且必须同时修改 release 与之匹配。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (key == FieldKey.VERSION_RELEASE) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "注意：release 默认为本机真实版本。单独修改它会让 Android 版本号与 " +
                            "API 级别对不上——这正是指纹结构检测会报的不一致。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (key == FieldKey.DISPLAY_DENSITY) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "注意：屏幕密度会改变目标应用的布局；此通道仅在显式覆盖时才会启用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm(null) }) { Text("跟随档案") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

// ---------------------------------------------------------------------------

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
    )
}
