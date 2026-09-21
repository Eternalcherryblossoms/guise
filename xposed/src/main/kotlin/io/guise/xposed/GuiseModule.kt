package io.guise.xposed

import android.os.Build
import android.util.Log
import io.guise.core.config.ConfigCodec
import io.guise.core.config.ConfigResolver
import io.guise.core.profile.CatalogMerge
import io.guise.core.profile.CatalogSchema
import io.guise.core.profile.DeviceCatalog
import io.guise.core.profile.EffectiveProfile
import io.guise.core.profile.RuntimeVersion
import io.guise.core.transport.Transport
import io.guise.xposed.channel.BuildChannel
import io.guise.xposed.channel.Channel
import io.guise.xposed.channel.DisplayChannel
import io.guise.xposed.channel.GpuChannel
import io.guise.xposed.channel.MediaCodecChannel
import io.guise.xposed.channel.PrivacyChannel
import io.guise.xposed.channel.SettingsChannel
import io.guise.xposed.channel.SystemPropertyChannel
import io.guise.xposed.channel.TelephonyChannel
import io.guise.xposed.channel.WifiChannel
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Module entry point, named by `META-INF/xposed/java_init.list`.
 *
 * Lifecycle, as required by the modern API: nothing is touched before [onModuleLoaded],
 * and the framework injects the [XposedInterface] via `attachFramework` rather than
 * passing it to a constructor.
 *
 * Config arrives over LSPosed's remote preferences. That is the substantive repair in this
 * rewrite: Guise shipped config as a JSON file in the module's own data directory (with a
 * world-readable copy in `/data/local/tmp` as a fallback), and Android's per-app SELinux
 * labelling had already made the first choice unreadable from another process, which left
 * the module silently inert for most users.
 */
class GuiseModule : XposedModule() {

    private val channels: List<Channel> = listOf(
        BuildChannel(),
        SystemPropertyChannel(),
        SettingsChannel(),
        GpuChannel(),
        TelephonyChannel(),
        WifiChannel(),
        DisplayChannel(),
        // Opt-in per target: see the class comment for why filtering codecs is not free.
        MediaCodecChannel(),
        // Answers configured content providers with nothing. Never touches permissions.
        PrivacyChannel(),
    )

    /** Packages already handled in this process; a process may host several. */
    private val handled = ConcurrentHashMap.newKeySet<String>()

    private val profileStores = ConcurrentHashMap<String, ProfileStore>()

    /**
     * The device catalog the hook will use, assembled from the same three sources the app's
     * picker shows.
     *
     * The bundled copy is read straight out of the module APK: the hooked process belongs to some
     * other app, so it cannot use that app's `AssetManager`, but `getModuleApplicationInfo()
     * .sourceDir` is the module's own path and APKs are world-readable. That keeps 74 KB of
     * static data out of the remote-preferences channel, which is meant for small values.
     *
     * On top of it sit two optional remote sources, and the merge is deliberately the *same*
     * function the app calls ([CatalogMerge]) rather than a second implementation. If the two
     * diverged, the picker would offer a profile this process has never heard of -- and the user
     * would have no way to tell that from "the module is broken".
     *
     * Every failure here falls back toward the bundled catalog, in this order:
     * downloaded (hash-checked by the app before it was written) -> bundled -> empty. A missing
     * or unreadable remote file is normal: it means nothing has been downloaded, not that
     * anything is wrong.
     */
    private val catalog: DeviceCatalog by lazy {
        val bundled = readBundledCatalog()
        CatalogMerge.merge(
            bundled = bundled,
            downloaded = readRemoteCatalog(Transport.REMOTE_FILE_DOWNLOADED_CATALOG),
            overlay = readRemoteCatalog(Transport.REMOTE_FILE_USER_CATALOG),
        ).also { merged ->
            val meta = catalogMeta()
            log(
                Log.INFO,
                HookContext.TAG,
                "catalog: ${merged.devices.size} profiles, ${merged.socs.size} SoCs " +
                    "(" + (if (meta != null) {
                        "downloaded ${meta.shortHash}, ${meta.profiles} profiles, " +
                            "schema ${meta.schemaVersion}"
                    } else {
                        "bundled"
                    }) + ")",
            )
        }
    }

    /** The descriptor the app wrote beside the downloaded catalog, if any. */
    private fun catalogMeta(): io.guise.core.profile.CatalogMeta? = runCatching {
        ConfigCodec.decodeCatalogMeta(
            getRemotePreferences(Transport.PREFS_GROUP).getString(Transport.KEY_CATALOG_META, null),
        )
    }.getOrNull()

    private fun readBundledCatalog(): DeviceCatalog {
        val apkPath = runCatching { getModuleApplicationInfo().sourceDir }.getOrNull()
        if (apkPath == null) {
            log(Log.ERROR, HookContext.TAG, "catalog: module sourceDir unavailable")
            return DeviceCatalog()
        }
        return runCatching {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("assets/${Transport.ASSET_CATALOG}")
                    ?: return@use DeviceCatalog()
                zip.getInputStream(entry).use { stream ->
                    ConfigCodec.decodeCatalog(stream.readBytes().toString(Charsets.UTF_8))
                }
            }
        }.onFailure {
            log(Log.ERROR, HookContext.TAG, "catalog: failed to read assets/${Transport.ASSET_CATALOG}", it)
        }.getOrElse { DeviceCatalog() }
    }

    /**
     * Reads a catalog from the module's remote files.
     *
     * The descriptor is consulted first and only as a gate on the *format*, not as a hash check:
     * the hash was verified by the app against the published name before the bytes were written,
     * and re-hashing 74 KB inside every hooked process on every launch would cost more than it
     * could catch. What this does catch is a descriptor that disagrees with the file, which is
     * what a half-finished update looks like.
     */
    private fun readRemoteCatalog(name: String): DeviceCatalog? = runCatching {
        val raw = openRemoteFile(name).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        if (raw.isEmpty()) return@runCatching null
        val parsed = ConfigCodec.decodeCatalog(raw.toString(Charsets.UTF_8))
        if (name == Transport.REMOTE_FILE_DOWNLOADED_CATALOG) {
            val meta = catalogMeta()
            if (meta != null && !CatalogSchema.isSupported(meta.schemaVersion)) {
                log(
                    Log.WARN,
                    HookContext.TAG,
                    "catalog: downloaded catalog declares schema ${meta.schemaVersion}, " +
                        "which this build does not understand; ignoring it",
                )
                return@runCatching null
            }
        }
        parsed.takeIf { it.devices.isNotEmpty() }
    }.onFailure {
        // Expected whenever nothing has been downloaded or captured yet.
        log(Log.INFO, HookContext.TAG, "catalog: no remote catalog at $name (${it.javaClass.simpleName})")
    }.getOrNull()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        val info = runCatching {
            "${getFrameworkName()} ${getFrameworkVersion()} (api ${getApiVersion()})"
        }.getOrDefault("unknown framework")
        log(Log.INFO, HookContext.TAG, "loaded into process '${param.processName}' via $info")
        if (param.isSystemServer) {
            // Nothing here targets system_server yet. system_server is not a normal package
            // and has no entry in the config, so bail out rather than hook blind.
            log(Log.INFO, HookContext.TAG, "system_server: no channels applicable, standing down")
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        // PackageLoadedParam.getDefaultClassLoader() is @RequiresApi(Q); minSdk here is 28,
        // so the callback is only actionable from API 29 up.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        install(param.packageName, runCatching { param.defaultClassLoader }.getOrNull())
    }

    override fun onPackageReady(param: PackageReadyParam) {
        install(param.packageName, runCatching { param.classLoader }.getOrNull())
    }

    private fun install(packageName: String, classLoader: ClassLoader?) {
        // Never hook ourselves. The modern API already stops the manager from hooking the
        // module app, but an explicit guard costs nothing and makes the intent obvious.
        val self = runCatching { getModuleApplicationInfo().packageName }.getOrNull()
        if (packageName == self) return

        if (!handled.add(packageName)) return

        val profile = loadProfile(packageName)
        if (profile == null) {
            log(Log.DEBUG, HookContext.TAG, "$packageName: not configured, leaving untouched")
            handled.remove(packageName)
            return
        }

        val store = profileStores.computeIfAbsent(packageName) {
            ProfileStore { loadProfile(packageName) }
        }
        store.invalidate()

        val ctx = HookContext(
            module = this,
            classLoader = classLoader ?: javaClass.classLoader!!,
            packageName = packageName,
            profile = store,
        )

        log(
            Log.INFO,
            HookContext.TAG,
            "$packageName -> ${profile.device.name} (${profile.device.model}), " +
                "overrides=${profile.overriddenKeys.size}",
        )

        var total = 0
        channels.forEach { channel ->
            val count = runCatching { channel.install(ctx) }
                .onFailure { ctx.error("${channel.id}: install failed", it) }
                .getOrDefault(0)
            total += count
            if (count == 0) {
                log(Log.WARN, HookContext.TAG, "$packageName: channel '${channel.id}' installed nothing")
            }
        }
        log(Log.INFO, HookContext.TAG, "$packageName: $total hooks installed across ${channels.size} channels")

        watchForConfigChanges(packageName, store)
    }

    /** Re-read config when the manager app writes, so edits apply without a force-stop. */
    private fun watchForConfigChanges(packageName: String, store: ProfileStore) {
        runCatching {
            getRemotePreferences(Transport.PREFS_GROUP)
                .registerOnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key == Transport.KEY_CONFIG) {
                        log(Log.INFO, HookContext.TAG, "$packageName: config changed, invalidating profile")
                        store.invalidate()
                    }
                }
        }.onFailure {
            // Not fatal: the value is simply picked up on next process start.
            log(Log.DEBUG, HookContext.TAG, "$packageName: no change listener available", it)
        }
    }

    private fun loadProfile(packageName: String): EffectiveProfile? = runCatching {
        val raw = getRemotePreferences(Transport.PREFS_GROUP)
            .getString(Transport.KEY_CONFIG, null)
        if (raw.isNullOrBlank()) return@runCatching null
        // The Android version is taken from the running device, not from the profile. The API
        // level cannot be safely rewritten and the release has to agree with it, so a profile
        // claiming a different version would be an immediate self-contradiction -- exactly what
        // the probe's fingerprint check catches.
        val runtime = RuntimeVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        ConfigResolver.resolve(ConfigCodec.decode(raw), catalog, packageName, runtime)
    }.onFailure {
        log(Log.ERROR, HookContext.TAG, "$packageName: failed to resolve config", it)
    }.getOrNull()
}
