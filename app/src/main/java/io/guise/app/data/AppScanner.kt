package io.guise.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * Launchable apps, for the target picker.
 *
 * Deliberately built on an `ACTION_MAIN`/`CATEGORY_LAUNCHER` query rather than
 * `QUERY_ALL_PACKAGES`. A hook target is by definition something the user opens, so the
 * narrow query returns everything relevant and avoids a permission that Play treats as
 * sensitive.
 */
class AppScanner(private val context: Context) {

    fun installedApps(includeSystem: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)

        return resolved
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .filter { includeSystem || !it.isSystem }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun labelOf(packageName: String): String = runCatching {
        val pm: PackageManager = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
