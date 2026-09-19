package io.guise.xposed.channel

import android.os.Build
import io.guise.core.profile.FieldKey
import io.guise.xposed.HookContext
import io.guise.xposed.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * `android.os.Build` and `android.os.Build.VERSION`.
 *
 * This is the surface Guise actually implemented, and it implemented it wrongly in two
 * ways worth recording:
 *
 *  1. It wrote `ro.product.manufacturer` into PRODUCT and BRAND as well as MANUFACTURER,
 *     and `ro.product.model` into DEVICE as well as MODEL. Those properties are the
 *     *product* and *device* codenames, not the brand and model, so the module was
 *     manufacturing the exact inconsistency a fingerprinting SDK looks for.
 *  2. It set `Build.SERIAL`, which has read `"UNKNOWN"` since API 26 and is not the
 *     accessor apps use. The real one is the permission-gated `Build.getSerial()`.
 *
 * Both are fixed here by deriving every field from its own profile field and hooking the
 * method rather than the vestigial constant.
 */
class BuildChannel : Channel {

    override val id = "build"

    private data class StaticField(val javaName: String, val key: FieldKey)

    private val buildFields = listOf(
        StaticField("BRAND", FieldKey.BUILD_BRAND),
        StaticField("MANUFACTURER", FieldKey.BUILD_MANUFACTURER),
        StaticField("MODEL", FieldKey.BUILD_MODEL),
        StaticField("DEVICE", FieldKey.BUILD_DEVICE),
        StaticField("PRODUCT", FieldKey.BUILD_PRODUCT),
        StaticField("BOARD", FieldKey.BUILD_BOARD),
        StaticField("HARDWARE", FieldKey.BUILD_HARDWARE),
        StaticField("FINGERPRINT", FieldKey.BUILD_FINGERPRINT),
        StaticField("BOOTLOADER", FieldKey.BUILD_BOOTLOADER),
        StaticField("DISPLAY", FieldKey.BUILD_DISPLAY),
        StaticField("ID", FieldKey.BUILD_ID),
        StaticField("TAGS", FieldKey.BUILD_TAGS),
        StaticField("TYPE", FieldKey.BUILD_TYPE),
        StaticField("HOST", FieldKey.BUILD_HOST),
        StaticField("USER", FieldKey.BUILD_USER),
    )

    override fun install(ctx: HookContext): Int {
        var installed = 0

        // ---- android.os.Build ------------------------------------------------
        buildFields.forEach { sf ->
            val value = ctx.string(sf.key) ?: return@forEach
            if (Reflect.setStatic(Build::class.java, sf.javaName, value)) installed++
        }

        // Build.TIME is a long.
        ctx.profile.get()?.let { p ->
            val time = p.long(FieldKey.BUILD_TIME)
            if (time > 0 && Reflect.setStatic(Build::class.java, "TIME", time)) installed++
        }

        // Build.SUPPORTED_ABIS / CPU_ABI are arrays and Strings derived from the SoC;
        // spoofing the ABI list would make the app load libraries that do not exist, so
        // only the *reported* first ABI is adjusted, and only via the property channel.

        // ---- android.os.Build.VERSION ----------------------------------------
        ctx.string(FieldKey.VERSION_RELEASE)?.let {
            if (Reflect.setStatic(Build.VERSION::class.java, "RELEASE", it)) installed++
        }
        ctx.string(FieldKey.VERSION_INCREMENTAL)?.let {
            if (Reflect.setStatic(Build.VERSION::class.java, "INCREMENTAL", it)) installed++
        }
        ctx.string(FieldKey.VERSION_SECURITY_PATCH)?.let {
            // SECURITY_PATCH only exists from API 23; absent elsewhere.
            if (Reflect.setStatic(Build.VERSION::class.java, "SECURITY_PATCH", it)) installed++
        }

        // SDK_INT is deliberately NOT applied from the profile.
        //
        // It is normally a `static final int` that the compiler inlines into dex call
        // sites, and the whole framework branches on it. Raising it makes the app take
        // code paths for APIs this ROM does not have; lowering it hides real ones. Both
        // crash. It is applied only when a user pins it explicitly, and the UI flags it.
        if (ctx.isExplicitlyOverridden(FieldKey.VERSION_SDK_INT)) {
            val sdk = ctx.profile.get()?.int(FieldKey.VERSION_SDK_INT) ?: 0
            if (sdk > 0) {
                if (Reflect.setStatic(Build.VERSION::class.java, "SDK_INT", sdk)) {
                    ctx.warn(
                        "SDK_INT spoofed to $sdk for ${ctx.packageName}; " +
                            "this is known to break apps that branch on API level",
                    )
                    installed++
                }
            }
        }

        // ---- Build.getSerial() -----------------------------------------------
        // The real accessor, permission-gated, returning "unknown" when denied. Hooking
        // the method covers apps that call it; the constant is vestigial but harmless.
        val serial = ctx.string(FieldKey.BUILD_SERIAL)
        if (!serial.isNullOrBlank()) {
            Reflect.setStatic(Build::class.java, "SERIAL", serial)
            val method = Reflect.method(Build::class.java, "getSerial")
            if (method != null) {
                ctx.module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { serial }
                installed++
            }
        }

        ctx.log("build: installed $installed field hooks")
        return installed
    }
}
