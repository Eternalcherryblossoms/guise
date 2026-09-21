package io.guise.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.guise.app.data.InstalledApp
import io.guise.app.data.Project
import io.guise.core.config.TargetConfig
import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.FieldKey
import io.guise.core.profile.RamTier
import io.guise.core.privacy.PrivacyDomain

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
        Screen.About -> "关于"
        is Screen.Detail -> vm.labelOf(s.packageName)
        is Screen.Privacy -> "隐私数据"
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
                actions = {
                    if (vm.screen == Screen.Home) {
                        TextButton(onClick = vm::openAbout) { Text("关于") }
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
                Screen.About -> AboutScreen(vm)
                is Screen.Detail -> DetailScreen(vm, s.packageName)
                is Screen.Privacy -> PrivacyScreen(vm, s.packageName)
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

        // The count is the point of this banner. A catalog can look large and still leave this
        // particular handset with nothing coherent to wear -- fourteen devices covering three
        // memory tiers, and a 16 GB phone fitting none of them. Showing it up front turns that
        // from a silent lie into a visible gap.
        Text(
            vm.handsetSummary() + " 本机可穿的档案：${vm.fittingCount()} / ${vm.catalog().devices.size}。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

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
                    DeviceRow(device, vm.compatibilityFor(device)) { vm.pickDevice(device) }
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
private fun DeviceRow(device: DeviceProfile, issues: List<String>, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (issues.isEmpty()) "相容" else "与本机不符",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (issues.isEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Text(
            "${device.model}  ·  ${device.product}  ·  Android ${device.androidRelease} " +
                "(API ${device.sdkInt})  ·  ${RamTier.label(device.ramBytes)}",
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

        // Memory and the ABI list are the two facts no layer of Guise can rewrite, so a profile
        // that disagrees with them is a contradiction the target app reads for itself. Saying so
        // here rather than blocking the choice is deliberate: the user may have a reason to want
        // a particular model, and the honest move is to name the trade rather than make it for
        // them.
        vm.compatibilityFor(packageName).forEach { issue ->
            item { NoteCard("这台设备穿不上这个档案", issue) }
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

        // A summary that opens its own screen rather than six switches inline. The list grows
        // every time a data class is added, and this screen already carries a profile, a version
        // note, sixteen identity fields and thirty overrides.
        item {
            val count = vm.emptiedCount(packageName)
            Card(
                Modifier.fillMaxWidth().padding(top = 16.dp).clickable { vm.openPrivacy(packageName) },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("隐私数据", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (count == 0) "未清空任何数据" else "已清空 $count 类数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (count == 0) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        Text(
                            "权限不会被撤销——应用会正常启动，只是读到 0 条数据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        item {
            Text(
                "字段覆盖（留空表示跟随档案）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
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
// About
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Privacy
// ---------------------------------------------------------------------------

@Composable
private fun PrivacyScreen(vm: GuiseViewModel, packageName: String) {
    val target = vm.config.target(packageName)
    if (target == null) {
        Text("配置已移除", Modifier.padding(16.dp))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            NoteCard(
                title = "这不是「拒绝权限」",
                body = "撤销权限会让应用在自己的权限门上直接拒绝运行——" +
                    "「不给通讯录就不让用」正是要解决的问题，撤销解决不了它。\n\n" +
                    "所以这里做的是反过来的事：**权限你在系统设置里正常授予，Guise 只把数据源掏空。**" +
                    "应用正常启动，然后读到 0 条数据。\n\n" +
                    "空的通讯录、空的相册都是**常见状态**（真有人就是没有照片），" +
                    "所以没有东西可以被交叉比对。这也意味着它比「伪造一批假数据」更难被发现——" +
                    "前提是你别只清一半。",
            )
        }

        item {
            Text(
                "数据域",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        items(PrivacyDomain.entries.toList(), key = { it.id }) { domain ->
            PrivacyRow(
                domain = domain,
                enabled = target.emptiedDomains.contains(domain.id),
                onToggle = { vm.setPrivacyDomain(packageName, domain, it) },
            )
            HorizontalDivider()
        }

        item {
            NoteCard(
                title = "有一类应用不受这里影响",
                body = "上面每一项都作用在**内容提供器**上（ContentProvider），" +
                    "这也是现代应用读共享数据要走的路。\n\n" +
                    "但持有「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE）的应用会**直接用路径**" +
                    "打开 /storage/emulated/0/...，完全不经过提供器，所以拦不住。" +
                    "要拦它得在原生层给应用挂一个假的外部存储——那是另一个量级的工程，" +
                    "目前没有做，也没有假装做了。",
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AboutScreen(vm: GuiseViewModel) {
    val context = LocalContext.current
    val open: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Guise · 拟态", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "版本 ${vm.currentVersionName()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "零手工编码 · 全科技制造 · 由 DeepSeek 生成",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "按「设备档案」而非单个字段重写系统上报的硬件信息，" +
                            "让各通道之间保持自洽。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item { UpdateCard(vm, open) }

        item {
            NoteCard(
                title = "这个项目是怎么造出来的",
                body = "每一行代码、每一次架构决策、每一个 CI 配置，都由 DeepSeek 编写。\n\n" +
                    "人类提供的是另外两样东西：方向，和真机验证。\n\n" +
                    "后者不可替代。这个项目里有好几处结论是被真机推翻的——探针在设备上跑出的报告，" +
                    "否掉了「需要 Zygisk 层」和「需要 root 层」这两个已经论证过的设计，" +
                    "还揪出了一个自己引入的构建号年代 bug。" +
                    "README 里那张「实测发现」表记录的就是这些，包括否定我自己的那些。\n\n" +
                    "所以更准确的说法不是「AI 写的」，而是：AI 推断，真机证伪。" +
                    "再会写代码的模型，也替代不了把 APK 装到手机上、点开、然后把报告拿回来这一步。",
            )
        }

        item {
            NoteCard(
                title = "它做什么，不做什么",
                body = "做：让 Build.*、SystemProperties、Settings.Secure、GPU 字符串、" +
                    "编解码器列表等通道报告一份互相自洽的设备身份。\n\n" +
                    "不做：伪造硬件证明。Key Attestation 的证书在 TEE 内签名，" +
                    "rootOfTrust 由 bootloader 提供——这一层不是本地伪装能解决的。" +
                    "内存容量、ABI 列表、核心数同样无法伪装，选档案时必须匹配。\n\n" +
                    "也不需要 root。模块运行在 LSPosed 之上，仅此一层。",
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("链接", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { open(Project.GITHUB_URL) }) { Text("GitHub 仓库") }
                    TextButton(onClick = { open(Project.RELEASES_URL) }) { Text("下载最新版本") }
                    TextButton(onClick = { open(Project.ISSUES_URL) }) { Text("反馈问题") }
                    Text(
                        Project.GITHUB_URL,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            NoteCard(
                title = "许可",
                body = "LGPL-3.0。本项目是对 kingsollyu/AppEnv 的重建，" +
                    "沿用了它的许可证；没有共享任何代码。",
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun UpdateCard(vm: GuiseViewModel, open: (String) -> Unit) {
    val info = vm.updateInfo
    when {
        vm.updateState == UpdateState.CHECKING -> NoteCard("检查更新", "正在查询 GitHub 发布页…")

        info != null -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "有新版本 ${info.latestVersion}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前版本 ${vm.currentVersionName()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                info.notes?.take(400)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The asset URL downloads the APK directly; the release page is the
                    // fallback when a release carries no asset.
                    (info.apkUrl ?: info.releaseUrl).let { url ->
                        TextButton(onClick = { open(url) }) {
                            Text(if (info.apkUrl != null) "下载安装包" else "打开发布页")
                        }
                    }
                    TextButton(onClick = { open(info.releaseUrl) }) { Text("查看发布页") }
                }
            }
        }

        vm.updateState == UpdateState.DONE -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("未发现新版本", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "若仓库为私有、尚无 Release，或当前无网络，这里同样不会显示新版本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::checkForUpdate) { Text("重新检查") }
                    TextButton(onClick = { open(Project.RELEASES_URL) }) { Text("打开发布页") }
                }
            }
        }

        else -> NoteCard("检查更新", "尚未检查。")
    }
}

// ---------------------------------------------------------------------------

/**
 * One privacy domain's switch.
 *
 * The explanation is always visible rather than behind a tap: the difference between "denied"
 * and "granted but empty" is the entire feature, and a user who reads only "清空通讯录" will
 * reasonably assume the permission is being blocked, then wonder why the app still starts.
 */
@Composable
private fun PrivacyRow(
    domain: PrivacyDomain,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(domain.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                domain.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                domain.permission,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

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
