package io.guise.core.privacy

/**
 * A class of user data an app can ask for, and which Guise can answer with nothing.
 *
 * The mechanism is deliberately **not** permission revocation. Revoking a runtime permission
 * makes an app that gates on it refuse to run at all -- which is precisely the complaint this
 * feature exists to answer ("a calculator demanding my contacts, and refusing to start
 * otherwise"). XPrivacyLua reached the same conclusion years ago and states it in its FAQ:
 * revoking causes crashes, so feed fake data instead.
 *
 * So the permission really is granted, and the *data source* is emptied. The app passes its own
 * permission gate and then finds no rows, which is an ordinary state: plenty of real people have
 * an empty address book, an empty gallery, or no text messages.
 *
 * ## Scope of this mechanism
 *
 * Every entry here is backed by a **content provider**, which is what `PrivacyChannel` hooks. That
 * covers modern apps, because scoped storage pushed them onto `MediaStore` and the Storage Access
 * Framework to reach shared storage in the first place.
 *
 * It does **not** cover an app holding `MANAGE_EXTERNAL_STORAGE` that opens `/storage/emulated/0`
 * through `java.io.File` directly. That path never touches a provider, so nothing here sees it.
 * Closing it means intercepting the file APIs themselves, which at the Java layer is both leaky
 * (native code bypasses it) and fragile (`File` is used by the framework in the same process). It
 * belongs in a mount namespace, not here -- see `docs/FINDINGS.md`.
 *
 * [authorities] are matched case-insensitively. Several are listed per domain because both the
 * public and the provider-internal names turn up, and because `MediaStore` and the Storage Access
 * Framework expose the same content under different authorities.
 */
enum class PrivacyDomain(
    val id: String,
    val label: String,
    val authorities: List<String>,
    val permission: String,
    val explanation: String,
) {
    CONTACTS(
        id = "contacts",
        label = "通讯录",
        authorities = listOf("com.android.contacts", "com.android.providers.contacts"),
        permission = "android.permission.READ_CONTACTS",
        explanation = "应用会读到 0 条联系人，就像通讯录本来就是空的。",
    ),

    CALL_LOG(
        id = "calllog",
        label = "通话记录",
        authorities = listOf("call_log", "com.android.calllogbackup", "com.android.providers.contacts"),
        permission = "android.permission.READ_CALL_LOG",
        explanation = "应用会读到 0 条通话记录。建议与「通讯录」同时开启——" +
            "只清空联系人却留着通话记录，等于告诉对方「这个人有来电但没有联系人」。",
    ),

    CALENDAR(
        id = "calendar",
        label = "日历",
        authorities = listOf("com.android.calendar", "com.android.providers.calendar"),
        permission = "android.permission.READ_CALENDAR",
        explanation = "应用会读到 0 个日历、0 条日程。",
    ),

    MEDIA(
        id = "media",
        label = "相册",
        authorities = listOf(
            "media",
            "com.android.providers.media.documents",
        ),
        permission = "android.permission.READ_MEDIA_IMAGES",
        explanation = "应用会读到 0 张图片、0 个视频、0 首音频。" +
            "Android 13 起权限拆成 READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO，" +
            "三个都要在系统设置里授予，应用才能通过它自己的权限门。",
    ),

    SMS(
        id = "sms",
        label = "短信",
        authorities = listOf("sms", "sms-sent", "mms", "mms-sent", "mms-sms"),
        permission = "android.permission.READ_SMS",
        explanation = "应用会读到 0 条短信与彩信。**但这只拦「读取」**——" +
            "短信的送达是系统推给应用的，hook 不到，所以一个等着收验证码的应用仍然收得到。" +
            "这一点没有绕过的办法，官方 FAQ 里也是同样的结论。",
    ),

    DOCUMENTS(
        id = "documents",
        label = "文件（系统选择器）",
        // Downloads' documents provider lives here rather than under media: it is a SAF
        // documents provider, so it answers "what files are there", not "what pictures are
        // there". DownloadManager's own provider beside it is deliberately not listed --
        // emptying that would break downloading inside the target app.
        authorities = listOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
        ),
        permission = "android.permission.MANAGE_EXTERNAL_STORAGE",
        explanation = "应用通过系统文件选择器（SAF）浏览时会看到 0 个文件，" +
            "包括「下载」目录。" +
            "**注意这不等于收回了「所有文件访问权限」**：直接按路径打开 /sdcard 的应用不走这里，" +
            "不受影响。要拦那类应用需要原生层，见 README 的说明。",
    ),
    ;

    companion object {
        private val byId = entries.associateBy(PrivacyDomain::id)

        fun of(id: String): PrivacyDomain? = byId[id]

        /**
         * Resolves a content-provider authority to the domain that owns it.
         *
         * Returning null means "this authority is not one Guise manages", which is the common
         * case and must stay cheap: it runs on every provider query the target app makes.
         */
        fun ofAuthority(authority: String?): PrivacyDomain? {
            if (authority.isNullOrEmpty()) return null
            return entries.firstOrNull { domain ->
                domain.authorities.any { it.equals(authority, ignoreCase = true) }
            }
        }

        /** Parses a persisted set of ids, silently dropping anything unrecognised. */
        fun parse(ids: Collection<String>): Set<PrivacyDomain> =
            ids.mapNotNull(::of).toSet()
    }
}
