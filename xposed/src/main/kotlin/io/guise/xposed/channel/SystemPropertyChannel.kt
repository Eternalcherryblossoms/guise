package io.guise.xposed.channel

import io.guise.core.profile.FieldKey
import io.guise.xposed.HookContext
import io.guise.xposed.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * `android.os.SystemProperties`.
 *
 * `Build.MODEL` is only ever a *snapshot* of `ro.product.model` taken at class-init time.
 * Apps that want to catch a spoofed Build very often read the properties directly
 * instead, and native integrity checks frequently do the same. Covering only `Build`
 * therefore covers only half the surface, which is what Guise did.
 *
 * Note also that the map is honest about which property means what. `ro.product.name` is
 * the product codename and `ro.product.device` is the device codename; neither is the
 * marketing model. Guise conflated all three.
 */
class SystemPropertyChannel : Channel {

    override val id = "system-properties"

    /** Properties that are always safe to rewrite and are implied by the profile. */
    private val baseMap = mapOf(
        "ro.product.brand" to FieldKey.BUILD_BRAND,
        "ro.product.manufacturer" to FieldKey.BUILD_MANUFACTURER,
        "ro.product.model" to FieldKey.BUILD_MODEL,
        "ro.product.name" to FieldKey.BUILD_PRODUCT,
        "ro.product.device" to FieldKey.BUILD_DEVICE,
        "ro.product.board" to FieldKey.BUILD_BOARD,
        "ro.board.platform" to FieldKey.SOC_PLATFORM,
        "ro.hardware" to FieldKey.BUILD_HARDWARE,
        "ro.bootloader" to FieldKey.BUILD_BOOTLOADER,
        "ro.serialno" to FieldKey.BUILD_SERIAL,
        "ro.build.id" to FieldKey.BUILD_ID,
        "ro.build.display.id" to FieldKey.BUILD_DISPLAY,
        "ro.build.fingerprint" to FieldKey.BUILD_FINGERPRINT,
        "ro.build.tags" to FieldKey.BUILD_TAGS,
        "ro.build.type" to FieldKey.BUILD_TYPE,
        "ro.build.host" to FieldKey.BUILD_HOST,
        "ro.build.user" to FieldKey.BUILD_USER,
        "ro.build.version.release" to FieldKey.VERSION_RELEASE,
        "ro.build.version.incremental" to FieldKey.VERSION_INCREMENTAL,
        "ro.build.version.security_patch" to FieldKey.VERSION_SECURITY_PATCH,
        "ro.product.cpu.abi" to FieldKey.SOC_CPU_ABI,
    )

    override fun install(ctx: HookContext): Int {
        val sysProps = Reflect.findClass("android.os.SystemProperties")
        if (sysProps == null) {
            ctx.warn(
                "system-properties: android.os.SystemProperties is not resolvable in this " +
                    "process (hidden-API restriction). Skipping the channel; the Build " +
                    "channel still covers Build.* reads.",
            )
            return 0
        }

        val profile = ctx.profile.get() ?: return 0

        // Resolve once per property rather than per call.
        val resolved: Map<String, String> = buildMap {
            baseMap.forEach { (prop, key) ->
                val value = profile.string(key)
                if (value.isNotBlank()) put(prop, value)
            }
            // ro.product.model is aliased by some OEMs; keep the aliases coherent.
            put("ro.product.vendor.model", profile.string(FieldKey.BUILD_MODEL))
            put("ro.product.odm.model", profile.string(FieldKey.BUILD_MODEL))
            put("ro.build.description", profile.device.description)
        }

        var installed = 0

        fun hookGet(signature: List<Class<*>>, convert: (String) -> Any) {
            val method = Reflect.method(sysProps, "get", *signature.toTypedArray()) ?: return
            ctx.module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val name = chain.getArg(0) as? String
                    val replacement = name?.let(resolved::get)
                    if (replacement != null) convert(replacement) else chain.proceed()
                }
            installed++
        }

        hookGet(listOf(String::class.java)) { it }
        // The two-arg overload takes a default; only override when the property is ours.
        hookGet(listOf(String::class.java, String::class.java)) { it }

        // Typed accessors: apps and SDKs use these to read e.g. ro.build.version.sdk.
        Reflect.method(sysProps, "getInt", String::class.java, Int::class.javaPrimitiveType!!)?.let { m ->
            ctx.module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val name = chain.getArg(0) as? String
                    val v = name?.let(resolved::get)?.trim()?.toIntOrNull()
                    if (v != null) v else chain.proceed()
                }
            installed++
        }
        Reflect.method(sysProps, "getLong", String::class.java, Long::class.javaPrimitiveType!!)?.let { m ->
            ctx.module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val name = chain.getArg(0) as? String
                    val v = name?.let(resolved::get)?.trim()?.toLongOrNull()
                    if (v != null) v else chain.proceed()
                }
            installed++
        }
        Reflect.method(sysProps, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!)?.let { m ->
            ctx.module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val name = chain.getArg(0) as? String
                    val v = name?.let(resolved::get)?.trim()
                    val b = when (v) {
                        "1", "true", "y", "yes" -> true
                        "0", "false", "n", "no" -> false
                        else -> null
                    }
                    if (b != null) b else chain.proceed()
                }
            installed++
        }

        ctx.log("system-properties: installed $installed hooks over ${resolved.size} properties")
        return installed
    }
}
