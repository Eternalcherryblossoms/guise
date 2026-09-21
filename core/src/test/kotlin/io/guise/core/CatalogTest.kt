package io.guise.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the shipped catalog. A malformed entry used to be a silent runtime failure in
 * Guise -- you would just get an inconsistent device. Here it fails the build.
 */
class CatalogTest {

    @Test
    fun `bundled catalog is structurally valid`() {
        val errors = TestCatalog.load().validate()
        assertTrue("catalog validation errors:\n" + errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun `bundled catalog is not empty`() {
        val catalog = TestCatalog.load()
        assertTrue("expected some SoCs", catalog.socs.isNotEmpty())
        assertTrue("expected some devices", catalog.devices.isNotEmpty())
    }

    @Test
    fun `every device points at a real soc`() {
        val catalog = TestCatalog.load()
        catalog.devices.values.forEach { device ->
            assertTrue(
                "device '${device.key}' references missing soc '${device.socKey}'",
                catalog.socs.containsKey(device.socKey),
            )
        }
    }

    @Test
    fun `derived fingerprint matches canonical android layout`() {
        // BRAND/PRODUCT/DEVICE:RELEASE/ID/INCREMENTAL:TYPE/TAGS
        val catalog = TestCatalog.load()
        catalog.devices.values.forEach { d ->
            val parts = d.fingerprint.split(":")
            assertEquals("wrong ':' count in ${d.fingerprint}", 3, parts.size)

            val (brand, product, device) = parts[0].split("/")
            assertEquals("brand mismatch in ${d.fingerprint}", d.brand, brand)
            assertEquals("product mismatch in ${d.fingerprint}", d.product, product)
            assertEquals("device mismatch in ${d.fingerprint}", d.device, device)

            val (release, id, incremental) = parts[1].split("/")
            assertEquals("release mismatch in ${d.fingerprint}", d.androidRelease, release)
            assertEquals("id mismatch in ${d.fingerprint}", d.buildId, id)
            assertEquals("incremental mismatch in ${d.fingerprint}", d.buildIncremental, incremental)

            val (type, tags) = parts[2].split("/")
            assertEquals("type mismatch in ${d.fingerprint}", d.buildType, type)
            assertEquals("tags mismatch in ${d.fingerprint}", d.buildTags, tags)
        }
    }

    @Test
    fun `fingerprint is never stored, always derived from identity`() {
        val catalog = TestCatalog.load()
        catalog.devices.values.forEach { d ->
            if (d.fingerprintOverride == null) {
                assertTrue(
                    "fingerprint of '${d.key}' should contain its model-era release",
                    d.fingerprint.contains(d.androidRelease),
                )
            }
        }
    }

    @Test
    fun `brand grouping and search work`() {
        val catalog = TestCatalog.load()
        assertTrue(catalog.byBrand().containsKey("google"))
        assertTrue(catalog.byBrand().containsKey("Xiaomi"))
        assertTrue(catalog.search("pixel").all { it.name.contains("Pixel") })
        assertEquals(1, catalog.search("M2011K2C").size)
    }

    @Test
    fun `every bundled device declares the memory it shipped with`() {
        val catalog = TestCatalog.load()
        val unspecified = catalog.devices.values.filter { it.ramBytes <= 0L }.map { it.key }
        assertTrue(
            "memory is a compatibility constraint, so a shipped entry must state it: $unspecified",
            unspecified.isEmpty(),
        )
    }

    @Test
    fun `bundled catalog covers three memory tiers`() {
        // Counting devices is the wrong measure and this is why: fourteen devices serve three
        // memory tiers, because twelve of them inherited one figure from their SoC.
        val catalog = TestCatalog.load()
        assertEquals(listOf(6, 8, 12), catalog.coveredRamGiB())
    }

    @Test
    fun `the bundled catalog's 16 GB hole is pinned, not forgotten`() {
        // A tripwire rather than an endorsement. Device B reports 14 GB -- the 16 GB tier -- and
        // nothing in the shipped catalog can serve it. The catalog generator is meant to close
        // this hole from real Pixel build data; whichever change does so must also replace this
        // test with an assertion that the hole is gone. Asserting the gap keeps it visible
        // instead of letting it read as "the catalog is fine".
        val catalog = TestCatalog.load()
        assertEquals(listOf(16), catalog.uncoveredRamGiB(listOf(6, 8, 12, 16)))
    }
}
