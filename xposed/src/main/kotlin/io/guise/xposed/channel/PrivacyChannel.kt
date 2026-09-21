package io.guise.xposed.channel

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import io.guise.core.privacy.PrivacyDomain
import io.guise.xposed.HookContext
import io.guise.xposed.util.Reflect
import io.github.libxposed.api.XposedInterface

/**
 * Answers configured content providers with nothing.
 *
 * ## Why empty rather than denied
 *
 * The app's permission is genuinely granted. Guise never touches it -- the user grants it in
 * Android settings, which is what gets the app past its own gate. What is emptied is the *data
 * source*, so a calculator that demanded the address book starts normally and then finds zero
 * contacts.
 *
 * Revoking the permission instead would be simpler to implement and much worse: an app that
 * gates on it refuses to run, which is the exact complaint this feature answers. XPrivacyLua's
 * FAQ says the same thing about internet access -- revoking crashes apps, so fake the state
 * instead.
 *
 * ## Why empty and not plausible
 *
 * An empty address book is an ordinary state; plenty of real people have one. A fabricated
 * contact list is only better if it is *coherent* -- right name for the region, right dialling
 * code for the faked location, and consistent with the call log beside it. That needs a persona
 * model and a region corpus, which is the next step rather than this one. Empty is honest,
 * immediately correct, and impossible to catch by cross-checking, because there is nothing to
 * cross-check.
 *
 * ## The one real risk
 *
 * An app that assumes at least one row (`cursor.moveToFirst()` then `getString()` with no null
 * check) will crash on an empty cursor. Such an app also crashes for a real user with an empty
 * address book, so the bug is the app's -- but the user will see it as Guise breaking something.
 * Worth remembering when someone reports it.
 */
class PrivacyChannel : Channel {

    override val id = "privacy-content"

    /**
     * Every `ContentResolver.query` overload.
     *
     * `ContentResolver` declares three. Hooking only the five-argument one would leave a hole an
     * app can walk through simply by passing a `CancellationSignal`, which modern code does.
     */
    private val overloads: List<Array<Class<*>>> = listOf(
        arrayOf(
            Uri::class.java, Array<String>::class.java, String::class.java,
            Array<String>::class.java, String::class.java,
        ),
        arrayOf(
            Uri::class.java, Array<String>::class.java, String::class.java,
            Array<String>::class.java, String::class.java, CancellationSignal::class.java,
        ),
        arrayOf(
            Uri::class.java, Array<String>::class.java, Bundle::class.java,
            CancellationSignal::class.java,
        ),
    )

    override fun install(ctx: HookContext): Int {
        val profile = ctx.profile.get() ?: return 0
        val emptied = profile.emptiedDomains
        if (emptied.isEmpty()) {
            ctx.log("privacy-content: no domains configured for ${ctx.packageName}")
            return 0
        }

        val names = emptied.map(PrivacyDomain::id).sorted()
        var installed = 0

        overloads.forEach { signature ->
            val method = Reflect.method(ContentResolver::class.java, "query", *signature)
                ?: return@forEach

            ctx.module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val uri = chain.getArg(0) as? Uri
                    val domain = PrivacyDomain.ofAuthority(uri?.authority)
                    if (domain == null || domain !in emptied) {
                        chain.proceed()
                    } else {
                        // Column names come from the caller's projection so the cursor looks
                        // like a genuine empty result rather than a malformed one. A null
                        // projection means "all columns", which cannot be reproduced from
                        // here; an empty column set still yields the correct row count, and
                        // column-name lookups degrade to -1 exactly as they would for an app
                        // reading a table it has no columns for.
                        val projection = chain.getArg(1) as? Array<*>
                        val columns = projection
                            ?.mapNotNull { it as? String }
                            ?.toTypedArray()
                            ?: emptyArray()
                        ctx.log("privacy-content: emptied ${domain.id} for ${ctx.packageName}")
                        MatrixCursor(columns)
                    }
                }
            installed++
        }

        ctx.log(
            "privacy-content: installed $installed hooks, emptying ${names.joinToString(", ")}",
        )
        return installed
    }
}
