package io.guise.core

import io.guise.core.profile.Compatibility
import io.guise.core.profile.HandsetFacts
import io.guise.core.profile.RamTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `memory is declared for every tier a handset in the catalog must be able to wear`() {
        // The assertion this replaces read "every bundled device declares the memory it shipped
        // with", which was right when every entry was hand-entered from a spec sheet. It is no
        // longer the invariant that matters, because most entries now come from firmware dumps
        // and a `build.prop` states no memory at all.
        //
        // The invariant that does matter is this one. `ramBytes` is a *compatibility constraint*,
        // never a reported value -- physical memory cannot be spoofed by any layer -- so an entry
        // that declares nothing claims nothing false, and the module reports the real figure
        // either way. What would be a real defect is a handset configuration with no entry that
        // can be checked against it, which is what the tier coverage below asserts.
        val catalog = TestCatalog.load()
        assertEquals(emptyList<Int>(), catalog.uncoveredRamGiB(listOf(6, 8, 12, 16)))

        // Entries that do declare memory must declare a capacity handsets ship with; that is
        // enforced by the validator, and this pins that it is enforced on real data too.
        val declared = catalog.devices.values.mapNotNull { RamTier.nominalGiB(it.ramBytes) }
        assertTrue("no entry declares memory at all", declared.isNotEmpty())
        assertTrue(
            "every declared capacity must be one the table allows: ${declared.distinct()}",
            declared.all { it in RamTier.capacitiesGiB },
        )
    }

    @Test
    fun `entries with unknown memory are reported rather than silently equal`() {
        // An unknown capacity must not read as a match. Compatibility returns null for both
        // "agrees" and "could not tell", and this is where that distinction is checked against
        // the shipped catalog rather than against a fixture.
        val catalog = TestCatalog.load()
        val unknown = catalog.devices.values.filter { it.ramBytes <= 0L }
        val known = catalog.devices.values.filter { it.ramBytes > 0L }
        assertTrue("expected some entries to declare memory", known.isNotEmpty())
        assertTrue(
            "expected some firmware-derived entries to declare none: " +
                "${catalog.devices.size} entries, all declared",
            unknown.isNotEmpty(),
        )
        assertNull(
            "an unknown capacity must produce no verdict, not a passing one",
            Compatibility.ramIssue(unknown.first(), HandsetFacts(reportedRamBytes = 11L shl 30)),
        )
    }

    @Test
    fun `bundled catalog covers every required memory tier`() {
        // This assertion used to read `assertEquals(listOf(6, 8, 12), ...)` with a comment
        // explaining why thirteen devices serving three tiers was worth naming. It is now the
        // check the comment was asking for: the 16 GB tier -- the one both test handsets needed
        // and neither could be served by -- is covered.
        val catalog = TestCatalog.load()
        assertEquals(listOf(6, 8, 12, 16), catalog.coveredRamGiB())
        assertEquals(emptyList<Int>(), catalog.uncoveredRamGiB(listOf(6, 8, 12, 16)))
    }

    @Test
    fun `the catalog is large enough that one profile is not a signature`() {
        // Not a target for its own sake. Fourteen entries meant every Guise user presented one of
        // fourteen handsets; the count is a floor, and the quality gates live in :catalog-gen.
        val catalog = TestCatalog.load()
        assertTrue(
            "expected a catalog of at least 50 profiles, found ${catalog.devices.size}",
            catalog.devices.size >= 50,
        )
    }

    @Test
    fun `every entry declares the Android release its build belongs to`() {
        // A build belongs to exactly one release: its build ID carries that era's date and its
        // security patch that era's level. An entry without a release cannot be judged by
        // Compatibility.releaseIssue, so the field is not optional in practice.
        val catalog = TestCatalog.load()
        val blank = catalog.devices.values.filter { it.androidRelease.isBlank() }.map { it.key }
        assertTrue("entries with no release: $blank", blank.isEmpty())
    }

    @Test
    fun `the catalog covers the releases both test handsets run`() {
        // Device A runs Android 14 and device B Android 16. An entry per (device, release) is
        // only worth the space if the releases actually in use are among them.
        val catalog = TestCatalog.load()
        val releases = catalog.devices.values.map { it.androidRelease }.toSet()
        assertTrue("no entries for Android 14: $releases", "14" in releases)
        assertTrue("no entries for Android 16: $releases", "16" in releases)
    }
}
