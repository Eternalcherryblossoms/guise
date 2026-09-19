package io.guise.xposed.channel

import android.content.ContentResolver
import android.provider.Settings
import io.guise.xposed.HookContext
import io.guise.xposed.util.Reflect
import io.guise.xposed.util.Synthetic
import io.github.libxposed.api.XposedInterface

/**
 * `Settings.Secure`, currently the SSAID (`ANDROID_ID`).
 *
 * Guise hooked `Settings.System.getString(...)` here. ANDROID_ID has never lived in the
 * `System` namespace -- it is in `Secure` -- so that hook was dead code. It never fired
 * for a single target. This is the corrected version.
 *
 * SSAID is also the one identifier where spoofing is genuinely free: the platform
 * generates it as a random number on first request and caches it in a lookup table, so
 * there is no hardware fact for it to contradict. The only thing that can catch a spoofed
 * SSAID is server-side history, not local consistency.
 */
class SettingsChannel : Channel {

    override val id = "settings-secure"

    override fun install(ctx: HookContext): Int {
        var installed = 0
        val resolver = ctx.profile.get() ?: return 0

        // Stable per (profile, package): a synthetic SSAID that changed between launches
        // would be a stronger signal than one that is simply wrong.
        val syntheticId = Synthetic.androidId(ctx.seed("ssaid"))

        val secureClass = Reflect.findClass("android.provider.Settings\$Secure") ?: Settings.Secure::class.java
        val method = Reflect.method(
            secureClass,
            "getString",
            ContentResolver::class.java,
            String::class.java,
        )

        if (method != null) {
            ctx.module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val name = chain.getArg(1) as? String
                    if (name == Settings.Secure.ANDROID_ID) {
                        ctx.log("settings-secure: serving synthetic ANDROID_ID")
                        syntheticId
                    } else {
                        chain.proceed()
                    }
                }
            installed++
        } else {
            ctx.warn("settings-secure: Settings.Secure.getString not found")
        }

        // Some code paths read the constant off the class rather than going through the
        // resolver; nothing to hook there, but keep the profile reference alive so the
        // resolver is not optimised away in future refactors.
        require(resolver.device.key.isNotEmpty())
        return installed
    }
}
