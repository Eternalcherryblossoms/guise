package io.guise.xposed.channel

import android.telephony.TelephonyManager
import io.guise.xposed.HookContext
import io.guise.xposed.util.Reflect
import io.guise.xposed.util.Synthetic
import io.github.libxposed.api.XposedInterface

/**
 * Device-bound radio identifiers.
 *
 * Scope note, and this is a deliberate departure from Guise: only identifiers that
 * belong to the *handset* are spoofed here. The IMSI, SIM serial and operator codes are
 * properties of the SIM card, not the phone. The device profile carries no carrier
 * identity, so any value invented for them would be arbitrary -- and, worse, an IMSI whose
 * MCC/MNC disagrees with the operator code the real SIM reports is a contradiction rather
 * than a disguise. SIM-bound identifiers are therefore left untouched.
 *
 * These getters are permission-gated on API 29+, where an ordinary app receives `null`.
 * Hooking them is still meaningful: it is precisely the cases where the platform returns
 * `null` that a spoofed value is most conspicuous.
 */
class TelephonyChannel : Channel {

    override val id = "telephony"

    override fun install(ctx: HookContext): Int {
        val profile = ctx.profile.get() ?: return 0

        val imei = Synthetic.imei(ctx.seed("imei"))
        // MEID is the CDMA equivalent; a real handset exposes exactly one of the two
        // non-null, so mirror that by deriving a distinct but stable value.
        val meid = Synthetic.imei(ctx.seed("meid")).substring(0, 14)

        val replacements = mapOf(
            "getDeviceId" to imei,          // deprecated, still widely called
            "getImei" to imei,
            "getMeid" to meid,
        )

        var installed = 0
        replacements.forEach { (methodName, value) ->
            // Several overloads exist (slot index variants); cover the ones present.
            val noArg = Reflect.method(TelephonyManager::class.java, methodName)
            val intArg = Reflect.method(TelephonyManager::class.java, methodName, Int::class.javaPrimitiveType!!)

            listOfNotNull(noArg, intArg).forEach { m ->
                ctx.module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { value }
                installed++
            }
        }

        ctx.log("telephony: installed $installed hooks")
        return installed
    }
}
