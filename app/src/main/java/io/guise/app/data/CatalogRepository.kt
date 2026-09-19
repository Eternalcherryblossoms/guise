package io.guise.app.data

import android.content.Context
import io.guise.core.config.ConfigCodec
import io.guise.core.profile.DeviceCatalog
import io.guise.core.profile.DeviceProfile
import io.guise.core.transport.Transport

/**
 * The device catalog, as seen by the management app.
 *
 * Two sources are merged: the catalog bundled in the APK's assets (the same file the hook
 * reads out of the module APK, so the UI and the hook can never disagree about what a
 * profile means), plus an optional user overlay delivered over remote files.
 *
 * User entries win on key collision, which makes the overlay a way to correct a bundled
 * entry without a new release.
 */
class CatalogRepository(private val context: Context, private val configRepo: ConfigRepository) {

    private var cached: DeviceCatalog? = null

    fun catalog(): DeviceCatalog {
        cached?.let { return it }
        val bundled = runCatching {
            context.assets.open(Transport.ASSET_CATALOG).use { stream ->
                ConfigCodec.decodeCatalog(stream.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrElse { DeviceCatalog() }

        val overlay = configRepo.catalogOverlay() ?: DeviceCatalog()
        val merged = DeviceCatalog(
            version = maxOf(bundled.version, overlay.version),
            socs = bundled.socs + overlay.socs,
            devices = bundled.devices + overlay.devices,
        )
        cached = merged
        return merged
    }

    fun invalidate() {
        cached = null
    }

    fun search(query: String): List<DeviceProfile> = catalog().search(query)

    fun byBrand(): Map<String, List<DeviceProfile>> = catalog().byBrand()

    /** Structural problems in the catalog; surfaced in the UI so bad data is visible. */
    fun validationErrors(): List<String> = catalog().validate()
}
