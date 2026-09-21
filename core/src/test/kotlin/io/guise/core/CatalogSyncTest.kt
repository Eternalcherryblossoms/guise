package io.guise.core

import io.guise.core.profile.CatalogAsset
import io.guise.core.profile.CatalogMerge
import io.guise.core.profile.CatalogMeta
import io.guise.core.profile.CatalogRelease
import io.guise.core.profile.CatalogSchema
import io.guise.core.profile.DeviceCatalog
import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.DisplayProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide which catalog the module actually uses.
 *
 * These matter more than most tests here, because the inputs are remote. Everything else in the
 * project is validated before it ships; a downloaded catalog arrives after the fact, and the only
 * things standing between it and a device identity are the checks exercised below.
 */
class CatalogSyncTest {

    @Test
    fun `an unknown format version is refused, in both directions`() {
        assertTrue(CatalogSchema.isSupported(CatalogSchema.VERSION))
        assertTrue("older is fine: additive fields decode to their defaults", CatalogSchema.isSupported(1))
        assertTrue("a version below the range is not a catalog", !CatalogSchema.isSupported(0))
        assertTrue(
            "a newer format may mean fields this build would misread, and misreading a device " +
                "identity is worse than staying on the bundled copy",
            !CatalogSchema.isSupported(CatalogSchema.VERSION + 1),
        )
    }

    @Test
    fun `a downloaded catalog replaces the bundled one rather than merging with it`() {
        // If it merged, an entry a correction meant to delete would live on forever -- an update
        // that cannot remove anything is not an update.
        val bundled = DeviceCatalog(devices = mapOf("old" to device("old")))
        val downloaded = DeviceCatalog(devices = mapOf("new" to device("new")))
        val merged = CatalogMerge.merge(bundled, downloaded, overlay = null)
        assertEquals(setOf("new"), merged.devices.keys)
    }

    @Test
    fun `a user capture always wins`() {
        // A capture is ground truth about a real handset. Nothing published should displace it,
        // which is why the overlay is merged last and unconditionally.
        val bundled = DeviceCatalog(devices = mapOf("a" to device("a", model = "BUNDLED")))
        val downloaded = DeviceCatalog(devices = mapOf("a" to device("a", model = "DOWNLOADED")))
        val overlay = DeviceCatalog(devices = mapOf("a" to device("a", model = "CAPTURED")))
        val merged = CatalogMerge.merge(bundled, downloaded, overlay)
        assertEquals("CAPTURED", merged.devices.getValue("a").model)
    }

    @Test
    fun `an empty download is treated as no download`() {
        // "The update removed every device" is far more likely to be a bad publish than an
        // intent, and adopting it would silently empty the picker.
        val bundled = DeviceCatalog(devices = mapOf("a" to device("a")))
        val merged = CatalogMerge.merge(bundled, DeviceCatalog(), overlay = null)
        assertEquals(setOf("a"), merged.devices.keys)
    }

    @Test
    fun `an overlay survives when there is no download`() {
        val bundled = DeviceCatalog(devices = mapOf("a" to device("a")))
        val overlay = DeviceCatalog(devices = mapOf("mine" to device("mine")))
        val merged = CatalogMerge.merge(bundled, downloaded = null, overlay = overlay)
        assertEquals(setOf("a", "mine"), merged.devices.keys)
    }

    @Test
    fun `rejection names the reason rather than a boolean`() {
        val good = TestCatalog.load()
        assertNull(CatalogMerge.rejectionReason(good))

        assertNotNull(
            "a catalog with no devices would empty the picker",
            CatalogMerge.rejectionReason(good.copy(devices = emptyMap())),
        )
        assertNotNull(
            "a catalog with no SoCs cannot resolve any entry",
            CatalogMerge.rejectionReason(good.copy(socs = emptyMap())),
        )
        assertNotNull(
            "a newer schema is refused before anything is read from it",
            CatalogMerge.rejectionReason(good, declaredSchema = CatalogSchema.VERSION + 1),
        )
    }

    @Test
    fun `one malformed entry rejects the whole catalog`() {
        // Not "mostly valid": a single entry that contradicts itself is a fingerprint an
        // observer can catch, and the bundled catalog is a better answer than a partial update.
        val good = TestCatalog.load()
        val first = good.devices.keys.first()
        val broken = good.devices.getValue(first).copy(socKey = "no_such_soc")
        val reason = CatalogMerge.rejectionReason(good.copy(devices = good.devices + (first to broken)))
        assertNotNull(reason)
        assertTrue("reason should name the offending entry: $reason", reason!!.contains(first))
    }

    @Test
    fun `the metadata carries enough for the UI to describe the source`() {
        val meta = CatalogMeta(
            schemaVersion = 1,
            sha256 = "a".repeat(64),
            profiles = 62,
            fetchedAt = "2026-09-21T06:00:00Z",
            sourceUrl = "https://example.invalid/catalog-aaa.json",
            releaseTag = "5.6.0",
        )
        assertEquals("aaaaaaaaaaaa", meta.shortHash)
        assertEquals(62, meta.profiles)
    }

    @Test
    fun `the asset name convention carries a full digest or nothing`() {
        // The publisher is a shell script and the client is Kotlin; this pattern is what they
        // agree on, and it is the only thing between a URL and an unverified device identity.
        val digest = "4fe72710177226ce5d0c7b7e20ebbc820bca215256e1d6bd0dbd01077f029297"
        val name = CatalogAsset.name(digest)
        assertEquals("catalog-$digest.json", name)
        assertEquals(digest, CatalogAsset.digestOf(name))

        // Case is normalised, because a digest is hex and either case means the same bytes.
        assertEquals(digest, CatalogAsset.digestOf("catalog-${digest.uppercase()}.json"))

        // A truncated digest must be refused rather than accepted as a prefix: that would
        // quietly turn a 256-bit integrity check into a 64-bit one.
        assertNull(CatalogAsset.digestOf("catalog-${digest.take(16)}.json"))
        assertNull(CatalogAsset.digestOf("catalog.json"))
        assertNull(CatalogAsset.digestOf("Catalog-$digest.json"))
        assertNull(CatalogAsset.digestOf("catalog-$digest.json.sha256"))
        assertNull(CatalogAsset.digestOf("catalog-${digest}z.json"))
        assertNull(CatalogAsset.digestOf("Guise-debug-5.6.0.apk"))
    }

    @Test
    fun `a real release payload yields the catalog and ignores the APKs`() {
        // Modelled on an actual releases API response, including the fields that are irrelevant
        // here. The APKs must not be mistaken for a catalog, and an asset that merely *looks*
        // like one -- a short digest, a wrong prefix -- must be skipped rather than trusted.
        val digest = "4fe72710177226ce5d0c7b7e20ebbc820bca215256e1d6bd0dbd01077f029297"
        val payload = """
            {
              "tag_name": "v5.6.0",
              "name": "v5.6.0",
              "draft": false,
              "prerelease": false,
              "body": "notes",
              "assets": [
                { "name": "Guise-debug-5.6.0.apk",
                  "browser_download_url": "https://example.invalid/Guise-debug-5.6.0.apk",
                  "size": 29380436 },
                { "name": "GuiseProbe-debug-5.6.0.apk",
                  "browser_download_url": "https://example.invalid/GuiseProbe-debug-5.6.0.apk" },
                { "name": "catalog-${digest.take(16)}.json",
                  "browser_download_url": "https://example.invalid/short.json" },
                { "name": "catalog-aaa.json",
                  "browser_download_url": "https://example.invalid/wrong.json" },
                { "name": "catalog-$digest.json",
                  "browser_download_url": "https://example.invalid/catalog-$digest.json" }
              ]
            }
        """.trimIndent()

        val found = CatalogRelease.parse(payload)
        assertNotNull(found)
        assertEquals(digest, found!!.sha256)
        assertEquals("5.6.0", found.releaseTag)
        assertEquals("https://example.invalid/catalog-$digest.json", found.url)
    }

    @Test
    fun `a release with no catalog asset yields nothing rather than a guess`() {
        val payload = """
            { "tag_name": "v1.0.0", "assets": [
              { "name": "Guise-debug-1.0.0.apk", "browser_download_url": "https://example.invalid/a.apk" } ] }
        """.trimIndent()
        assertNull(CatalogRelease.parse(payload))

        // A payload that is not JSON, and one with no assets at all, are both "no catalog" --
        // never an exception on a code path the About screen runs on a tap.
        assertNull(CatalogRelease.parse("not json"))
        assertNull(CatalogRelease.parse("""{"tag_name":"v1.0.0"}"""))
        assertNull(CatalogRelease.parse(""))
    }

    @Test
    fun `a catalog asset with a non-https url is skipped`() {
        // Relative or plain-http URLs would turn into a confusing download failure rather than
        // an honest "no catalog in this release".
        val digest = "b".repeat(64)
        val payload = """
            { "tag_name": "v1.0.0", "assets": [
              { "name": "catalog-$digest.json", "browser_download_url": "/relative/catalog.json" },
              { "name": "catalog-$digest.json", "browser_download_url": "http://example.invalid/c.json" } ] }
        """.trimIndent()
        assertNull(CatalogRelease.parse(payload))
    }

    @Test
    fun `the shipped catalog round trips through the verification chain`() {
        // The end-to-end shape of a catalog update, minus the network: hash the real shipped
        // catalog, name it the way CI does, find it in a payload, and confirm the bytes that
        // would be downloaded hash to the digest the name claimed.
        val bytes = TestCatalog.file().readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val payload = """
            { "tag_name": "v9.9.9", "assets": [
              { "name": "${CatalogAsset.name(digest)}",
                "browser_download_url": "https://example.invalid/${CatalogAsset.name(digest)}" } ] }
        """.trimIndent()

        val found = CatalogRelease.parse(payload)
        assertNotNull(found)
        assertEquals(digest, found!!.sha256)

        // And the catalog that name points at passes the adoption gate unchanged.
        assertNull(CatalogMerge.rejectionReason(TestCatalog.load()))
    }

    // ---- fixtures -----------------------------------------------------------

    private fun device(key: String, model: String = "M") = DeviceProfile(
        key = key,
        name = key,
        brand = "test",
        manufacturer = "test",
        model = model,
        product = key,
        device = key,
        androidRelease = "14",
        sdkInt = 34,
        buildId = "UQ1A.240205.004",
        buildIncremental = "1",
        socKey = "test_soc",
        ramBytes = 8L * 1024 * 1024 * 1024,
        display = DisplayProfile(1080, 2400, 420),
    )
}
