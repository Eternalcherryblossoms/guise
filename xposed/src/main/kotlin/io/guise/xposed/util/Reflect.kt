package io.guise.xposed.util

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Reflection helpers.
 *
 * The modern libxposed API deliberately ships no `XposedHelpers` equivalent, so this
 * replaces the handful of things Guise used: finding members on framework classes and,
 * critically, overwriting `static final` fields on `android.os.Build`.
 *
 * Why reflection at all for Build: `Build.MODEL` and friends are `static final` Strings
 * assigned in the class's static initialiser from system properties. By the time any hook
 * callback runs, `Build` has long been initialised (the framework itself reads
 * `Build.VERSION.SDK_INT` during startup), so `hookClassInitializer(Build::class.java)`
 * would never fire. Overwriting the field is the only mechanism available.
 */
object Reflect {

    private val fieldCache = HashMap<String, Field?>()
    private val methodCache = HashMap<String, Method?>()

    fun field(clazz: Class<*>, name: String): Field? =
        fieldCache.getOrPut("${clazz.name}#$name") {
            runCatching {
                clazz.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
        }

    fun method(clazz: Class<*>, name: String, vararg params: Class<*>): Method? =
        methodCache.getOrPut("${clazz.name}#$name(${params.joinToString { it.name }})") {
            runCatching {
                clazz.getDeclaredMethod(name, *params).apply { isAccessible = true }
            }.getOrNull()
        }

    /**
     * Overwrite a static field.
     *
     * ART accepts `Field.set` on a `static final` reference field once the field has been
     * made accessible -- this is the same mechanism the legacy `setStaticObjectField`
     * helper used. Some builds instead reject it with `IllegalAccessException`; in that
     * case the FINAL modifier bit is cleared first and the write retried. The modifier
     * field is named differently across runtimes, so both spellings are attempted.
     */
    fun setStatic(clazz: Class<*>, name: String, value: Any?): Boolean {
        val f = field(clazz, name) ?: return false
        if (runCatching { f.set(null, value) }.isSuccess) return true

        return runCatching {
            val modifiersField = runCatching { Field::class.java.getDeclaredField("accessFlags") }
                .recoverCatching { Field::class.java.getDeclaredField("modifiers") }
                .getOrNull() ?: return false

            modifiersField.isAccessible = true
            val current = (modifiersField.get(f) as? Int) ?: return false
            modifiersField.setInt(f, current and java.lang.reflect.Modifier.FINAL.inv())
            f.set(null, value)
            true
        }.getOrDefault(false)
    }

    fun getStatic(clazz: Class<*>, name: String): Any? =
        field(clazz, name)?.let { runCatching { it.get(null) }.getOrNull() }

    /** Resolve a class without initialising it, tolerating absence. */
    fun findClass(name: String, loader: ClassLoader? = null): Class<*>? =
        runCatching { Class.forName(name, false, loader) }.getOrNull()
}
