package io.guise.core.transport

/**
 * Shared constants for the module <-> framework bridge.
 *
 * The transport is LSPosed's "remote preferences": the module app writes, the hooked
 * process reads. This replaces Guise's original mechanism (a JSON file in the module's
 * data directory, plus a world-readable copy in `/data/local/tmp`) which Android's
 * per-app SELinux labelling and scoped storage had already killed.
 *
 * Contract:
 *  - The hooked process may only *read*. Writes go through [io.github.libxposed.service.XposedService].
 *  - The whole config travels as one JSON string under [KEY_CONFIG] so that a single
 *    preference-change notification is enough to invalidate every cached value.
 */
object Transport {
    /** Remote-preferences group name. */
    const val PREFS_GROUP = "Guise"

    /** Key holding the serialized [io.guise.core.config.ModuleConfig]. */
    const val KEY_CONFIG = "config"

    /** Asset path of the bundled device catalog, read out of the module APK. */
    const val ASSET_CATALOG = "catalog.json"

    /** Remote file holding a user-defined catalog overlay. Optional. */
    const val REMOTE_FILE_USER_CATALOG = "catalog.user.json"
}
