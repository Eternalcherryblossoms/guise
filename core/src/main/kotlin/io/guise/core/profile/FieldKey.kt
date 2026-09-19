package io.guise.core.profile

/**
 * Every observable a hook channel is allowed to report, and therefore every value a
 * user is allowed to override.
 *
 * The important property is that this list is *closed*: a channel cannot invent a new
 * observable without adding it here, and adding it here forces it to be resolved from
 * the profile. That is what keeps the profile the single source of truth.
 *
 * [id] is the persistence key. It is part of the on-disk format, so it must stay stable.
 */
enum class FieldKey(
    val id: String,
    val label: String,
    val kind: Kind,
) {
    // ---- identity -----------------------------------------------------------
    BUILD_BRAND("build.brand", "品牌 Build.BRAND", Kind.STRING),
    BUILD_MANUFACTURER("build.manufacturer", "厂商 Build.MANUFACTURER", Kind.STRING),
    BUILD_MODEL("build.model", "型号 Build.MODEL", Kind.STRING),
    BUILD_DEVICE("build.device", "设备代号 Build.DEVICE", Kind.STRING),
    BUILD_PRODUCT("build.product", "产品代号 Build.PRODUCT", Kind.STRING),
    BUILD_BOARD("build.board", "主板 Build.BOARD", Kind.STRING),
    BUILD_HARDWARE("build.hardware", "硬件 Build.HARDWARE", Kind.STRING),
    BUILD_FINGERPRINT("build.fingerprint", "指纹 Build.FINGERPRINT", Kind.STRING),
    BUILD_BOOTLOADER("build.bootloader", "引导器 Build.BOOTLOADER", Kind.STRING),
    BUILD_DISPLAY("build.display", "显示版本 Build.DISPLAY", Kind.STRING),
    BUILD_ID("build.id", "构建 ID Build.ID", Kind.STRING),
    BUILD_TAGS("build.tags", "标签 Build.TAGS", Kind.STRING),
    BUILD_TYPE("build.type", "类型 Build.TYPE", Kind.STRING),
    BUILD_HOST("build.host", "构建主机 Build.HOST", Kind.STRING),
    BUILD_USER("build.user", "构建用户 Build.USER", Kind.STRING),
    BUILD_TIME("build.time", "构建时间 Build.TIME", Kind.LONG),
    BUILD_SERIAL("build.serial", "序列号 Build.getSerial()", Kind.STRING),

    // ---- version ------------------------------------------------------------
    VERSION_RELEASE("version.release", "安卓版本 Build.VERSION.RELEASE", Kind.STRING),
    VERSION_SDK_INT("version.sdkInt", "API 级别 Build.VERSION.SDK_INT", Kind.INT),
    VERSION_INCREMENTAL("version.incremental", "增量版本 Build.VERSION.INCREMENTAL", Kind.STRING),
    VERSION_SECURITY_PATCH("version.securityPatch", "安全补丁级别", Kind.STRING),

    // ---- display ------------------------------------------------------------
    DISPLAY_WIDTH("display.width", "屏幕宽度 (px)", Kind.INT),
    DISPLAY_HEIGHT("display.height", "屏幕高度 (px)", Kind.INT),
    DISPLAY_DENSITY("display.density", "屏幕密度 (dpi)", Kind.INT),

    // ---- soc ----------------------------------------------------------------
    SOC_PLATFORM("soc.platform", "芯片平台 ro.board.platform", Kind.STRING),
    SOC_GPU_VENDOR("soc.gpuVendor", "GPU 厂商 GL_VENDOR", Kind.STRING),
    SOC_GPU_RENDERER("soc.gpuRenderer", "GPU 型号 GL_RENDERER", Kind.STRING),
    SOC_CPU_ABI("soc.cpuAbi", "首选 ABI", Kind.STRING),
    ;

    enum class Kind { STRING, INT, LONG }

    companion object {
        private val byId = entries.associateBy(FieldKey::id)
        fun of(id: String): FieldKey? = byId[id]
    }
}
