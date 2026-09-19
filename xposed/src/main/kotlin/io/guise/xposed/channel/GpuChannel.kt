package io.guise.xposed.channel

import io.guise.core.profile.FieldKey
import io.guise.xposed.HookContext
import io.guise.xposed.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * OpenGL driver strings.
 *
 * `GL_RENDERER` is one of the cheapest cross-checks against a spoofed `Build.MODEL`: it
 * comes from the vendor's userspace driver, which is selected by the actual silicon, so
 * a handset claiming to be a Snapdragon while reporting a Mali renderer is immediately
 * inconsistent. It is derived from the SoC half of the profile, which is exactly why the
 * catalog splits at the SoC boundary.
 *
 * `GL_VERSION` is deliberately left alone. The driver version string embeds the vendor
 * blob's build number, and a fabricated one that does not match the driver actually
 * loaded is both less plausible and riskier than the real string.
 */
class GpuChannel : Channel {

    override val id = "gpu"

    private companion object {
        const val GL_VENDOR = 0x1F00
        const val GL_RENDERER = 0x1F01
    }

    override fun install(ctx: HookContext): Int {
        val profile = ctx.profile.get() ?: return 0
        val vendor = profile.string(FieldKey.SOC_GPU_VENDOR)
        val renderer = profile.string(FieldKey.SOC_GPU_RENDERER)

        var installed = 0
        // GLES20 is the path the overwhelming majority of fingerprinting code uses;
        // GLES30/31 inherit the same query but may have their own entry points.
        listOf("android.opengl.GLES20", "android.opengl.GLES30", "android.opengl.GLES31").forEach { className ->
            val clazz = Reflect.findClass(className) ?: return@forEach
            val method = Reflect.method(clazz, "glGetString", Int::class.javaPrimitiveType!!) ?: return@forEach

            ctx.module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    when (chain.getArg(0) as? Int) {
                        GL_VENDOR -> vendor
                        GL_RENDERER -> renderer
                        else -> chain.proceed()
                    }
                }
            installed++
        }

        ctx.log("gpu: installed $installed hooks (renderer=$renderer)")
        return installed
    }
}
