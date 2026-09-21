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
 * permission gate and then finds no rows, which is an ordinary state: plenty of real people
 * have an empty address book.
 *
 * The [authorities] are content-provider authorities, matched case-insensitively. Several
 * authorities are listed per domain because both the public and the provider-internal name turn
 * up in the wild (`com.android.contacts` and `com.android.providers.contacts`).
 *
 * [permission] is informational -- it is what the user should grant in Android settings for the
 * app to get past its own gate. Guise never reads or changes it.
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
        explanation = "应用会读到 0 条通话记录。注意与通讯录同时开启才自洽——" +
            "只清空联系人却留着通话记录，等于告诉对方「这个人有来电但没有联系人」。",
    ),

    CALENDAR(
        id = "calendar",
        label = "日历",
        authorities = listOf("com.android.calendar", "com.android.providers.calendar"),
        permission = "android.permission.READ_CALENDAR",
        explanation = "应用会读到 0 个日历、0 条日程。",
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
