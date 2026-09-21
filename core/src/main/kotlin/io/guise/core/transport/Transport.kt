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

    /**
     * Remote file holding the *downloaded* catalog. Optional.
     *
     * A file rather than a preference because a catalog is bulk data and remote preferences are
     * the small key/value channel; a file rather than an APK asset because the point of the whole
     * exercise is to refresh the catalog without shipping a new build. `XposedInterface` exposes
     * `openRemoteFile`, so the hooked process reads it with no transport of our own.
     */
    const val REMOTE_FILE_DOWNLOADED_CATALOG = "catalog.remote.json"

    /**
     * Remote preference holding the serialized [io.guise.core.profile.CatalogMeta] describing
     * [REMOTE_FILE_DOWNLOADED_CATALOG].
     *
     * Kept beside the file so the UI can report the source and hash without re-reading and
     * re-hashing a 74 KB file on every screen.
     */
    const val KEY_CATALOG_META = "catalogMeta"
}
