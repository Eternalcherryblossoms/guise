package io.guise.app.ui

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.guise.app.data.AppScanner
import io.guise.app.data.CatalogRepository
import io.guise.app.data.ConfigRepository
import io.guise.app.data.DeviceCapture
import io.guise.app.data.InstalledApp
import io.guise.app.service.XposedBridgeClient
import io.guise.core.config.ModuleConfig
import io.guise.core.config.TargetConfig
import io.guise.core.profile.DeviceCatalog
import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.EffectiveProfile
import io.guise.core.profile.FieldKey
import io.guise.core.profile.RuntimeVersion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object AppPicker : Screen
    data object DevicePicker : Screen
    data class Detail(val packageName: String) : Screen
}

class GuiseViewModel(app: Application) : AndroidViewModel(app) {

    private val configRepo = ConfigRepository(app)
    private val catalogRepo = CatalogRepository(app, configRepo)
    private val scanner = AppScanner(app)

    var screen by mutableStateOf<Screen>(Screen.Home)
    var config by mutableStateOf(ModuleConfig.EMPTY)
    var frameworkInfo by mutableStateOf<String?>(null)
    var frameworkConnected by mutableStateOf(false)
    var apps by mutableStateOf<List<InstalledApp>>(emptyList())
    var search by mutableStateOf("")
    var message by mutableStateOf<String?>(null)

    /** Which package the device picker is choosing a profile for. */
    private var pendingPackage: String? = null

    /** Set while re-pointing an existing target at a different profile. */
    private var changingProfileFor: String? = null

    /** Bumped to force recomposition after catalog mutation. */
    var catalogRevision by mutableStateOf(0)
        private set

    init {
        viewModelScope.launch {
            XposedBridgeClient.service.collectLatest { svc ->
                frameworkConnected = svc != null
                frameworkInfo = XposedBridgeClient.describe()
                configRepo.load()
                config = configRepo.config.value
            }
        }
        viewModelScope.launch {
            apps = runCatching { scanner.installedApps() }.getOrDefault(emptyList())
        }
    }

    /** Re-reads the framework connection so the status card reflects a late bind. */
    fun refreshFrameworkStatus() {
        frameworkConnected = XposedBridgeClient.isConnected
        frameworkInfo = XposedBridgeClient.describe()
    }

    // ---- catalog ------------------------------------------------------------

    fun catalog(): DeviceCatalog = catalogRepo.catalog()

    fun devices(): List<DeviceProfile> = catalogRepo.search(search)

    fun brands(): Map<String, List<DeviceProfile>> {
        val all = catalogRepo.byBrand()
        if (search.isBlank()) return all
        val q = search.trim().lowercase()
        return all.mapValues { (_, list) ->
            list.filter {
                it.name.lowercase().contains(q) ||
                    it.model.lowercase().contains(q) ||
                    it.brand.lowercase().contains(q)
            }
        }.filterValues { it.isNotEmpty() }
    }

    fun labelOf(packageName: String): String = scanner.labelOf(packageName)

    fun profileFor(packageName: String): DeviceProfile? {
        val key = config.target(packageName)?.profileKey ?: return null
        return catalogRepo.catalog().devices[key]
    }

    fun effectiveFor(packageName: String): EffectiveProfile? =
        runCatching {
            io.guise.core.config.ConfigResolver.resolve(
                config,
                catalogRepo.catalog(),
                packageName,
                // Preview must show what the module will actually report, and the module takes
                // the Android version from the device rather than the profile.
                RuntimeVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
            )
        }.getOrNull()

    /** True when this profile was captured on a different Android version than this device. */
    fun versionMismatch(packageName: String): String? {
        val device = profileFor(packageName) ?: return null
        if (device.sdkInt == Build.VERSION.SDK_INT) return null
        return "该档案记录于 Android ${device.androidRelease} (API ${device.sdkInt})，" +
            "本机为 Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})。" +
            "运行时将采用本机版本——API 级别无法安全改写，且 release 必须与之一致。"
    }

    // ---- navigation ---------------------------------------------------------

    fun goHome() {
        search = ""
        screen = Screen.Home
    }

    fun openAppPicker() {
        search = ""
        screen = Screen.AppPicker
    }

    fun openDevicePicker(packageName: String) {
        pendingPackage = packageName
        changingProfileFor = null
        search = ""
        screen = Screen.DevicePicker
    }

    fun changeProfile(packageName: String) {
        changingProfileFor = packageName
        pendingPackage = packageName
        search = ""
        screen = Screen.DevicePicker
    }

    fun openDetail(packageName: String) {
        screen = Screen.Detail(packageName)
    }

    fun back() {
        screen = when (val s = screen) {
            is Screen.DevicePicker -> if (changingProfileFor != null) Screen.Detail(changingProfileFor!!) else Screen.AppPicker
            Screen.AppPicker -> Screen.Home
            is Screen.Detail -> Screen.Home
            Screen.Home -> Screen.Home
        }
    }

    // ---- mutations ----------------------------------------------------------

    fun pickDevice(device: DeviceProfile) {
        val pkg = pendingPackage ?: return
        val existing = config.target(pkg)
        configRepo.upsert(
            TargetConfig(
                packageName = pkg,
                enabled = existing?.enabled ?: true,
                profileKey = device.key,
                // Keep the user's field overrides when only the base profile changes.
                overrides = existing?.overrides ?: emptyMap(),
            ),
        )
        config = configRepo.config.value
        requestScope(pkg)
        changingProfileFor = null
        pendingPackage = null
        screen = Screen.Detail(pkg)
    }

    fun setOverride(packageName: String, key: FieldKey, value: String?) {
        val target = config.target(packageName) ?: return
        val overrides = target.overrides.toMutableMap()
        if (value.isNullOrBlank()) overrides.remove(key.id) else overrides[key.id] = value
        configRepo.upsert(target.copy(overrides = overrides))
        config = configRepo.config.value
    }

    fun setTargetEnabled(packageName: String, enabled: Boolean) {
        val target = config.target(packageName) ?: return
        configRepo.upsert(target.copy(enabled = enabled))
        config = configRepo.config.value
    }

    /**
     * Opt-in because filtering the codec list can hide a codec the target app needs, which
     * shows up as broken playback. See TargetConfig.stripForeignCodecs.
     */
    fun setStripForeignCodecs(packageName: String, enabled: Boolean) {
        val target = config.target(packageName) ?: return
        configRepo.upsert(target.copy(stripForeignCodecs = enabled))
        config = configRepo.config.value
        message = if (enabled) {
            "已启用编解码器过滤：将隐藏与档案芯片不符的厂商前缀。若目标应用出现播放异常，请关闭此项。"
        } else {
            "已关闭编解码器过滤"
        }
    }

    fun removeTarget(packageName: String) {
        configRepo.remove(packageName)
        config = configRepo.config.value
        goHome()
    }

    fun setGlobalEnabled(enabled: Boolean) {
        configRepo.setGloballyEnabled(enabled)
        config = configRepo.config.value
    }

    /** Snapshot this handset and add it to the user's catalog overlay. */
    fun captureCurrentDevice() {
        val app = getApplication<Application>()
        runCatching {
            val base = catalogRepo.catalog()
            val device = DeviceCapture.capture(app, base)
            val soc = DeviceCapture.socFor(app, base)

            val existing = configRepo.catalogOverlay() ?: DeviceCatalog()
            val updated = DeviceCatalog(
                version = existing.version + 1,
                socs = existing.socs + (soc.key to soc),
                devices = existing.devices + (device.key to device),
            )
            if (!configRepo.saveCatalogOverlay(updated)) {
                message = "抓取成功，但写入失败：Xposed 框架未连接"
                return@runCatching
            }
            catalogRepo.invalidate()
            catalogRevision++
            message = "已抓取本机档案：${device.name}"
        }.onFailure {
            message = "抓取失败：${it.message}"
        }
    }

    private fun requestScope(packageName: String) {
        XposedBridgeClient.requestScope(packageName) { result ->
            message = result.fold(
                onSuccess = { "已将 $packageName 加入模块作用域" },
                onFailure = { "作用域请求失败：${it.message}" },
            )
        }
    }

    fun consumeMessage() {
        message = null
    }
}
