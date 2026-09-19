package io.guise.xposed

import android.util.Log
import io.guise.core.profile.EffectiveProfile
import io.guise.core.profile.FieldKey
import io.github.libxposed.api.XposedModule

/**
 * Holds the resolved profile for the current target package.
 *
 * The value is fetched lazily and can be invalidated when the module app writes new
 * config, so a hook that already ran does not pin a stale identity. Reads are on the hot
 * path (every `Build.MODEL` access funnels here), hence the volatile cache rather than
 * re-reading remote preferences each time.
 */
class ProfileStore(private val load: () -> EffectiveProfile?) {

    @Volatile
    private var value: EffectiveProfile? = null

    @Volatile
    private var loaded = false

    fun get(): EffectiveProfile? {
        if (loaded) return value
        synchronized(this) {
            if (!loaded) {
                value = runCatching(load).getOrNull()
                loaded = true
            }
        }
        return value
    }

    fun invalidate() {
        synchronized(this) {
            loaded = false
            value = null
        }
    }
}

/**
 * Everything a [Channel] is allowed to touch.
 *
 * Note what is *not* here: channels cannot read the config, cannot see other channels, and
 * cannot obtain a raw `DeviceProfile`. They resolve values exclusively through
 * [EffectiveProfile], which is what keeps coherence structural.
 */
class HookContext(
    val module: XposedModule,
    /** The target package's classloader. Framework classes resolve from the boot loader. */
    val classLoader: ClassLoader,
    val packageName: String,
    val profile: ProfileStore,
) {
    fun log(message: String) = runCatching { module.log(Log.INFO, TAG, message) }

    fun warn(message: String, t: Throwable? = null) =
        runCatching { module.log(Log.WARN, TAG, message, t) }

    fun error(message: String, t: Throwable? = null) =
        runCatching { module.log(Log.ERROR, TAG, message, t) }

    /** Convenience: the effective value, or null when this package is not configured. */
    fun string(key: FieldKey): String? = profile.get()?.string(key)

    fun isExplicitlyOverridden(key: FieldKey): Boolean =
        profile.get()?.isOverridden(key) ?: false

    /**
     * Seed for synthetic identifiers. Including the package name means two apps hooked
     * with the same profile receive different synthetic serials, which matches how
     * per-app identifiers behave on a real device.
     */
    fun seed(salt: String): String {
        val p = profile.get()
        val base = p?.device?.key ?: "unknown"
        return "$base|$packageName|$salt"
    }

    companion object {
        const val TAG = "Guise"
    }
}
