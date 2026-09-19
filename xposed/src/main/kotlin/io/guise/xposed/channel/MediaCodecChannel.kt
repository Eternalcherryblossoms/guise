package io.guise.xposed.channel

import android.media.MediaCodecList
import io.guise.xposed.HookContext
import io.guise.xposed.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * `MediaCodecList` vendor prefixes.
 *
 * Codec component names carry the silicon vendor: `c2.qti.*` and `OMX.qcom.*` for Qualcomm,
 * `c2.mtk.*` for MediaTek, `c2.exynos.*` for Samsung, `OMX.google.*` and `c2.android.*` for
 * the AOSP software codecs every device has. The list is built from the platform's vendor
 * configuration, so it describes the real chip and a Java-layer identity rewrite does not
 * touch it. A handset claiming to be a Pixel while listing MediaTek decoders is caught by
 * anything that enumerates codecs.
 *
 * Off unless the target opts in -- see
 * [io.guise.core.config.TargetConfig.stripForeignCodecs] for why the default is off.
 *
 * ## Why this filters instead of rewriting
 *
 * The list can be *filtered* but never *extended*: codecs the device does not physically have
 * cannot be invented. So the honest options are to hide the contradictory names or to leave
 * them, and hiding has a real cost -- an app that needs a hidden codec fails to find it, which
 * surfaces as broken playback. Rewriting the names instead would be worse: `getName()` would
 * return a string that `createByCodecName()` rejects, breaking the API contract outright.
 */
class MediaCodecChannel : Channel {

    override val id = "media-codec"

    /** Prefixes present on every Android device, so they reveal nothing about the vendor. */
    private val generic = setOf("c2.android.", "OMX.google.", "c2.google.")

    override fun install(ctx: HookContext): Int {
        val profile = ctx.profile.get() ?: return 0
        if (!profile.stripForeignCodecs) {
            ctx.log("media-codec: not enabled for ${ctx.packageName}, codec list untouched")
            return 0
        }

        val allowed = generic + profile.soc.codecPrefixes
        val listClass = Reflect.findClass("android.media.MediaCodecList") ?: return 0
        val infoClass = Reflect.findClass("android.media.MediaCodecInfo") ?: return 0
        val getName = Reflect.method(infoClass, "getName") ?: return 0
        val getCodecInfos = Reflect.method(listClass, "getCodecInfos") ?: return 0

        fun prefixOf(name: String): String =
            name.split(".").let { if (it.size >= 2) "${it[0]}.${it[1]}." else "$name." }

        // Snapshot the real list BEFORE installing any hook.
        //
        // This ordering is load-bearing. Once getCodecInfos is hooked, calling it through
        // reflection re-enters the hook, so the "original" list would be the filtered one and
        // the filter would compound on every call. Reading once up front also matches reality:
        // the platform's codec list does not change while the process runs.
        val snapshot: Array<*> = runCatching {
            val ctor = listClass.getConstructor(Int::class.javaPrimitiveType)
            val instance = ctor.newInstance(MediaCodecList.ALL_CODECS)
            getCodecInfos.invoke(instance) as? Array<*> ?: emptyArray<Any>()
        }.onFailure {
            ctx.warn("media-codec: could not snapshot the codec list", it)
        }.getOrDefault(emptyArray())

        if (snapshot.isEmpty()) {
            ctx.warn("media-codec: empty codec snapshot, skipping")
            return 0
        }

        val filtered = snapshot.filter { codec ->
            val name = codec?.let { runCatching { getName.invoke(it) as? String }.getOrNull() }
            name != null && prefixOf(name) in allowed
        }.toTypedArray()

        val removed = snapshot.size - filtered.size
        var installed = 0

        ctx.module.hook(getCodecInfos)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { filtered }
        installed++

        // The pre-API-21 accessors. They are deprecated but still called by older SDKs, and a
        // filtered getCodecInfos alongside an unfiltered getCodecInfoAt would be its own
        // contradiction.
        Reflect.method(listClass, "getCodecCount")?.let { m ->
            ctx.module.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { filtered.size }
            installed++
        }
        Reflect.method(listClass, "getCodecInfoAt", Int::class.javaPrimitiveType!!)?.let { m ->
            ctx.module.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val index = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                    filtered.getOrNull(index) ?: chain.proceed()
                }
            installed++
        }

        ctx.log(
            "media-codec: installed $installed hooks, hid $removed of ${snapshot.size} codecs " +
                "not matching ${profile.soc.key}",
        )
        return installed
    }
}
