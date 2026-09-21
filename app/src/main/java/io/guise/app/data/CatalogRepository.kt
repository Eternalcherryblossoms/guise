package io.guise.app.data

import android.content.Context
import io.guise.core.config.ConfigCodec
import io.guise.core.profile.CatalogMerge
import io.guise.core.profile.DeviceCatalog
import io.guise.core.profile.DeviceProfile
import io.guise.core.transport.Transport

/**
 * The device catalog, as seen by the management app.
 *
 * Three sources, combined by [CatalogMerge] so that the UI and the hook can never disagree about
 * what a profile means:
 *
 *  - the catalog bundled in the APK's assets;
 *  - an optional **downloaded** catalog, which replaces the bundled one when present and valid;
 *  - an optional **user overlay** of captured devices, which always wins on collision.
 *
 * The merge lives in `:core` rather than here because the hooked process performs the same one,
 * on the same inputs, from the same remote files. Two implementations would drift, and the
 * symptom of drift is a picker that offers a profile the hook has never heard of.
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

        val merged = CatalogMerge.merge(
            bundled = bundled,
            downloaded = configRepo.downloadedCatalog(),
            overlay = configRepo.catalogOverlay(),
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
