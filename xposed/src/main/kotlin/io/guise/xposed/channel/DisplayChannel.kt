package io.guise.xposed.channel

import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import io.guise.core.profile.FieldKey
import io.guise.xposed.HookContext
import io.guise.xposed.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * Reported screen density.
 *
 * This channel is **opt-in**: it does nothing unless the user explicitly pins
 * `display.density` for the target.
 *
 * The reason is a genuine trade-off rather than caution for its own sake. Screen geometry
 * is a strong fingerprinting signal, so a profile that claims a 1440x3200 panel while the
 * app is told 1080x2340 is inconsistent. But every app lays itself out from the values it
 * is given, so rewriting them is the single most likely thing in this module to break a
 * target's UI. Guise exposed density as a per-app switch for the same reason.
 *
 * Geometry (width/height) is not rewritten at all in this version: `DisplayMetrics` is
 * read from many places that do not funnel through `Resources.updateConfiguration`, and a
 * partial rewrite produces a half-changed display, which is worse than either extreme.
 */
class DisplayChannel : Channel {

    override val id = "display-density"

    override fun install(ctx: HookContext): Int {
        ctx.profile.get() ?: return 0

        if (!ctx.isExplicitlyOverridden(FieldKey.DISPLAY_DENSITY)) {
            ctx.log(
                "display-density: not overridden, leaving the real density untouched " +
                    "(safe default; enable per-app in the UI)",
            )
            return 0
        }

        val dpi = ctx.profile.get()!!.int(FieldKey.DISPLAY_DENSITY)
        if (dpi <= 0) return 0

        val method = Reflect.method(
            Resources::class.java,
            "updateConfiguration",
            Configuration::class.java,
            DisplayMetrics::class.java,
        ) ?: run {
            ctx.warn("display-density: Resources.updateConfiguration not found")
            return 0
        }

        ctx.module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val originalConfig = chain.getArg(0) as? Configuration
                val originalMetrics = chain.getArg(1) as? DisplayMetrics

                if (originalConfig == null || originalMetrics == null) {
                    chain.proceed()
                } else {
                    // Copy rather than mutate: the Configuration handed in may be shared
                    // with the system, and writing through it would leak the fake density
                    // into other processes' views of the device.
                    val config = Configuration(originalConfig)
                    val metrics = DisplayMetrics().apply { setTo(originalMetrics) }

                    metrics.densityDpi = dpi
                    metrics.density = dpi / 160f
                    metrics.scaledDensity = metrics.density * config.fontScale
                    metrics.xdpi = dpi.toFloat()
                    metrics.ydpi = dpi.toFloat()

                    Reflect.field(Configuration::class.java, "densityDpi")?.let {
                        runCatching { it.setInt(config, dpi) }
                    }

                    chain.proceed(arrayOf(config, metrics))
                }
            }

        ctx.log("display-density: installed (dpi=$dpi)")
        return 1
    }
}
