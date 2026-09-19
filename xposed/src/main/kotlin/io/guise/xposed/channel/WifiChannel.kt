package io.guise.xposed.channel

import android.net.wifi.WifiInfo
import io.guise.xposed.HookContext
import io.guise.xposed.util.Reflect
import io.guise.xposed.util.Synthetic
import io.github.libxposed.api.XposedInterface

/**
 * Wi-Fi hardware address.
 *
 * Again scope-limited on purpose: only the *device* MAC is spoofed. SSID and BSSID
 * describe the network the user is attached to, which is an environmental fact rather
 * than a property of the handset. Guise let users set those too, but a fabricated SSID
 * that does not correspond to the access point actually associated is not a disguise --
 * it is an inconsistency, and one that is trivially checked against the real scan results.
 *
 * Note that the platform already lies about this to ordinary apps (`02:00:00:00:00:00`),
 * and on Android 10+ the reported MAC is randomised per network. Spoofing it therefore
 * mostly matters for code that reaches past `WifiInfo` -- hence the
 * `NetworkInterface.getHardwareAddress` hook as well.
 */
class WifiChannel : Channel {

    override val id = "wifi"

    override fun install(ctx: HookContext): Int {
        ctx.profile.get() ?: return 0
        val mac = Synthetic.macAddress(ctx.seed("wlan0"))

        var installed = 0

        Reflect.method(WifiInfo::class.java, "getMacAddress")?.let { m ->
            ctx.module.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { mac }
            installed++
        }

        // NetworkInterface.getHardwareAddress() is native and returns the interface's real
        // address for wlan0. Only substitute for the Wi-Fi interface; leaving others alone
        // keeps the change narrow.
        val niClass = Reflect.findClass("java.net.NetworkInterface")
        val getHwAddr = niClass?.let { Reflect.method(it, "getHardwareAddress") }
        val getName = niClass?.let { Reflect.method(it, "getName") }
        if (getHwAddr != null && getName != null) {
            val parsed = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
            ctx.module.hook(getHwAddr)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val self = chain.getThisObject()
                    val name = runCatching { getName.invoke(self) as? String }.getOrNull()
                    if (name == "wlan0") parsed else chain.proceed()
                }
            installed++
        }

        ctx.log("wifi: installed $installed hooks")
        return installed
    }
}
