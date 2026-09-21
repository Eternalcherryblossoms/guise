package io.guise.probe.privacy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import io.guise.probe.model.Finding
import io.guise.probe.model.Verdict

/**
 * The three data classes this screen can inspect.
 *
 * Deliberately *not* imported from `:core`, for the same reason the build-ID token table is not:
 * the probe exists to be an independent observer, and a shared authority list would make the
 * probe agree with a bug in that list instead of exposing it. Reading through the framework's own
 * `ContactsContract` / `CallLog` / `CalendarContract` URIs is genuine independence -- if Guise
 * emptied the wrong authority, the probe reads real rows and says so.
 */
enum class ProbeDomain(
    val id: String,
    val label: String,
    val permission: String,
    val authority: String,
) {
    CONTACTS("contacts", "通讯录", Manifest.permission.READ_CONTACTS, "com.android.contacts"),
    CALL_LOG("calllog", "通话记录", Manifest.permission.READ_CALL_LOG, "call_log"),
    CALENDAR("calendar", "日历", Manifest.permission.READ_CALENDAR, "com.android.calendar"),
}

data class DomainReading(
    val domain: ProbeDomain,
    val granted: Boolean,
    /** Rows the framework returned. Null when the domain could not be read at all. */
    val rowCount: Int?,
    val note: String? = null,
)

data class PrivacyReport(
    val readings: List<DomainReading>,
    val findings: List<Finding>,
) {
    val anyGranted: Boolean get() = readings.any { it.granted }
}

/**
 * Reads the privacy domains and judges whether what it finds is coherent.
 *
 * ## Why this is a separate screen
 *
 * The rest of the probe claims no permissions at all, on purpose: it must see exactly what an
 * ordinary fingerprinting SDK sees, and a privileged probe would report a reassuring picture no
 * real observer would get.
 *
 * This screen is the exception, and it is an explicit one. It needs to read the real data to say
 * anything about whether that data was replaced, so the user grants each permission deliberately,
 * one domain at a time. Nothing here runs until they do.
 *
 * ## What it is actually judging
 *
 * Not "is the data fake" -- a client cannot know that, any more than it can know whether
 * `Build.MODEL` is fake. It judges **whether the data contradicts itself**, which is the same
 * question the rest of the probe asks about the device, applied to a different subject.
 *
 * A call log with entries beside an empty address book is the clearest case. It is not impossible
 * in the real world -- a new phone restores the call log before the contacts, and a person can
 * ignore unknown numbers -- but at any volume it stops being plausible, and it is exactly what
 * happens when someone empties one domain and forgets the other.
 */
class PrivacyProbe(private val context: Context) {

    fun granted(domain: ProbeDomain): Boolean =
        ContextCompat.checkSelfPermission(context, domain.permission) ==
            PackageManager.PERMISSION_GRANTED

    fun read(): PrivacyReport {
        val readings = ProbeDomain.entries.map { domain ->
            if (!granted(domain)) {
                DomainReading(domain, granted = false, rowCount = null, note = "未授权，无法读取")
            } else {
                DomainReading(domain, granted = true, rowCount = countRows(domain))
            }
        }
        return PrivacyReport(readings, coherence(readings))
    }

    private fun countRows(domain: ProbeDomain): Int? = runCatching {
        val uri = when (domain) {
            ProbeDomain.CONTACTS -> ContactsContract.Contacts.CONTENT_URI
            ProbeDomain.CALL_LOG -> CallLog.Calls.CONTENT_URI
            ProbeDomain.CALENDAR -> CalendarContract.Calendars.CONTENT_URI
        }
        context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.count }
    }.getOrNull()

    /**
     * Cross-domain judgement.
     *
     * Only pairs where a contradiction is actually meaningful are checked. Contacts beside an
     * empty call log is normal -- a new phone, or someone who does not call -- so it is not
     * flagged. The reverse is not.
     */
    private fun coherence(readings: List<DomainReading>): List<Finding> {
        val findings = mutableListOf<Finding>()

        readings.forEach { reading ->
            findings += Finding(
                label = reading.domain.label,
                observed = when {
                    !reading.granted -> "未授权"
                    reading.rowCount == null -> "读取失败"
                    else -> "${reading.rowCount} 条"
                },
                verdict = if (reading.granted && reading.rowCount == 0) Verdict.INFO else Verdict.INFO,
                detail = reading.note,
            )
        }

        val contacts = readings.first { it.domain == ProbeDomain.CONTACTS }
        val callLog = readings.first { it.domain == ProbeDomain.CALL_LOG }

        if (contacts.granted && callLog.granted && contacts.rowCount != null && callLog.rowCount != null) {
            val c = contacts.rowCount
            val l = callLog.rowCount
            when {
                c == 0 && l > 0 -> findings += Finding(
                    label = "通话记录 vs 通讯录",
                    observed = "通话记录 $l 条，通讯录 $c 条",
                    expected = "两者应当相称",
                    verdict = Verdict.INCOHERENT,
                    detail = "有来电却没有任何联系人。真实设备上并非不可能——新机可能先恢复了通话记录——" +
                        "但在这个量级上就不像了。这通常意味着「通话记录」被单独清空了而没有一起清空，" +
                        "或者反过来。两者要么都开，要么都关。",
                )

                c > 0 && l == 0 -> findings += Finding(
                    label = "通话记录 vs 通讯录",
                    observed = "通话记录 $l 条，通讯录 $c 条",
                    verdict = Verdict.INFO,
                    detail = "有联系人但没有通话记录，这在真实设备上很常见（新设备、或很少打电话），不作为矛盾。",
                )

                else -> findings += Finding(
                    label = "通话记录 vs 通讯录",
                    observed = "通话记录 $l 条，通讯录 $c 条",
                    verdict = Verdict.COHERENT,
                )
            }
        }

        if (readings.none { it.granted }) {
            findings += Finding(
                label = "尚未检查",
                observed = "三个域都未授权",
                verdict = Verdict.UNSUPPORTED,
                detail = "点每一项旁边的按钮逐个授权。探针不会自己申请权限。",
            )
        }

        return findings
    }
}
