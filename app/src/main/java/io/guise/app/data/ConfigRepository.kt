package io.guise.app.data

import android.content.Context
import io.guise.app.service.XposedBridgeClient
import io.guise.core.config.ConfigCodec
import io.guise.core.config.ModuleConfig
import io.guise.core.config.TargetConfig
import io.guise.core.transport.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single writer for the module configuration.
 *
 * Config lives in two places on purpose:
 *
 *  - **Remote preferences** (`Transport.PREFS_GROUP`) are what the hooked process reads.
 *    They are the authoritative copy at runtime.
 *  - **Local SharedPreferences** are a staging copy so the user can set the module up
 *    before the framework is connected, or while it is briefly unavailable.
 *
 * On service bind, if the remote copy is empty and a local one exists, the local copy is
 * pushed up. That is the only reconciliation needed because this app is the sole writer.
 */
class ConfigRepository(private val context: Context) {

    private val local = context.getSharedPreferences("Guise.config", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(ModuleConfig.EMPTY)
    val config: StateFlow<ModuleConfig> = _config.asStateFlow()

    fun load() {
        val remote = readRemote()
        val stored = remote ?: ConfigCodec.decode(local.getString(KEY_LOCAL, null))

        // Reconcile: local edits made while offline win, but only if remote is still empty.
        if (remote == null) {
            val localConfig = ConfigCodec.decode(local.getString(KEY_LOCAL, null))
            if (localConfig.targets.isNotEmpty()) {
                pushRemote(localConfig)
            }
        }
        _config.value = stored
    }

    private fun readRemote(): ModuleConfig? {
        val svc = XposedBridgeClient.service.value ?: return null
        return runCatching {
            val raw = svc.getRemotePreferences(Transport.PREFS_GROUP)
                .getString(Transport.KEY_CONFIG, null)
            if (raw.isNullOrBlank()) null else ConfigCodec.decode(raw)
        }.getOrNull()
    }

    private fun pushRemote(config: ModuleConfig) {
        val svc = XposedBridgeClient.service.value ?: return
        runCatching {
            svc.getRemotePreferences(Transport.PREFS_GROUP)
                .edit()
                .putString(Transport.KEY_CONFIG, ConfigCodec.encode(config))
                .apply()
        }
    }

    private fun persist(config: ModuleConfig) {
        local.edit().putString(KEY_LOCAL, ConfigCodec.encode(config)).apply()
        pushRemote(config)
        _config.value = config
    }

    fun upsert(target: TargetConfig) = persist(_config.value.withTarget(target))

    fun remove(packageName: String) = persist(_config.value.withoutTarget(packageName))

    fun setGloballyEnabled(enabled: Boolean) = persist(_config.value.copy(globallyEnabled = enabled))

    fun catalogOverlay(): io.guise.core.profile.DeviceCatalog? {
        val svc = XposedBridgeClient.service.value ?: return null
        return runCatching {
            val pfd = svc.openRemoteFile(Transport.REMOTE_FILE_USER_CATALOG)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                ConfigCodec.decodeCatalog(stream.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrNull()
    }

    /**
     * The downloaded catalog, if one has been installed.
     *
     * Read through the same remote file the hooked process uses rather than from a local copy, so
     * the UI is describing what the module will actually load. When the framework is not
     * connected this returns null and the UI falls back to the bundled catalog -- which is also
     * what the hook does, so the two stay in step.
     */
    fun downloadedCatalog(): io.guise.core.profile.DeviceCatalog? {
        val svc = XposedBridgeClient.service.value ?: return null
        return runCatching {
            val pfd = svc.openRemoteFile(Transport.REMOTE_FILE_DOWNLOADED_CATALOG)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                val bytes = stream.readBytes()
                if (bytes.isEmpty()) null else ConfigCodec.decodeCatalog(bytes.toString(Charsets.UTF_8))
            }
        }.getOrNull()
    }

    /**
     * Persist user-defined catalog entries (currently: captured devices).
     *
     * These travel over remote *files* rather than remote preferences because a catalog is
     * bulk data; remote preferences are the small key/value channel.
     */
    fun saveCatalogOverlay(catalog: io.guise.core.profile.DeviceCatalog): Boolean {
        val svc = XposedBridgeClient.service.value ?: return false
        return runCatching {
            val pfd = svc.openRemoteFile(Transport.REMOTE_FILE_USER_CATALOG)
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { stream ->
                stream.write(ConfigCodec.encodeCatalog(catalog).toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val KEY_LOCAL = "module_config"
    }
}
