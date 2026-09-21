package io.guise.core

import io.guise.core.profile.Compatibility
import io.guise.core.profile.DeviceProfile
import io.guise.core.profile.DisplayProfile
import io.guise.core.profile.HandsetFacts
import io.guise.core.profile.RamTier
import io.guise.core.profile.SocProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory is the one hardware fact Guise cannot rewrite, so the catalog can only be made to agree
 * with it. These tests pin the arithmetic that decides whether a profile agrees, because getting
 * it wrong in either direction is silent: too strict and every profile looks broken, too loose and
 * the mismatch ships.
 */
class RamTierTest {

    private val gib = RamTier.GIB

    @Test
    fun `a reported figure maps up to the capacity the handset shipped with`() {
        // Reserved memory is subtracted before the platform can count it, so a 12 GB handset
        // reports roughly 11. That is why the mapping rounds up rather than to the nearest tier.
        assertEquals(12, RamTier.nominalGiB(11 * gib + gib / 2))
        assertEquals(12, RamTier.nominalGiB(11 * gib))
        assertEquals(16, RamTier.nominalGiB(14 * gib))
        assertEquals(8, RamTier.nominalGiB(7 * gib + gib / 2))
        assertEquals(6, RamTier.nominalGiB(5 * gib + gib / 2))
    }

    @Test
    fun `a figure exactly at a capacity stays on that capacity`() {
        RamTier.capacitiesGiB.forEach { capacity ->
            assertEquals(capacity, RamTier.nominalGiB(capacity.toLong() * gib))
        }
    }

    @Test
    fun `an unreadable or absurd figure is unknown, never a match`() {
        assertNull("zero must not be read as a capacity", RamTier.nominalGiB(0))
        assertNull(RamTier.nominalGiB(-1))
        assertNull(
            "beyond the table there is nothing honest to say",
            RamTier.nominalGiB(RamTier.maxGiB.toLong() * gib + gib),
        )
        assertEquals(0L, RamTier.nominalBytes(0))
    }

    @Test
    fun `only real capacities are accepted into the catalog`() {
        assertTrue(RamTier.isKnownCapacity(0))
        assertTrue(RamTier.isKnownCapacity(8 * gib))
        assertTrue(RamTier.isKnownCapacity(12 * gib))
        assertTrue("3 GB handsets exist", RamTier.isKnownCapacity(3 * gib))
        assertTrue("5 GB is not a capacity anyone sells", !RamTier.isKnownCapacity(5 * gib))
        assertTrue(!RamTier.isKnownCapacity(-8 * gib))
    }

    @Test
    fun `a profile whose memory matches the handset reports no issue`() {
        val issue = Compatibility.ramIssue(device(12 * gib), facts(11 * gib + gib / 2))
        assertNull(issue)
    }

    @Test
    fun `a memory mismatch is reported, and the direction is named`() {
        // The measured case: device A reports 11 GB while every profile claimed 8 GiB.
        val smallerProfile = Compatibility.ramIssue(device(8 * gib), facts(11 * gib + gib / 2))
        assertNotNull(smallerProfile)
        assertTrue(smallerProfile!!.contains("本机内存更大"))

        val largerProfile = Compatibility.ramIssue(device(16 * gib), facts(7 * gib + gib / 2))
        assertNotNull(largerProfile)
        assertTrue(largerProfile!!.contains("本机内存更小"))
    }

    @Test
    fun `an unknown figure on either side produces no claim`() {
        // Silence is the correct answer when a fact could not be read. Reporting "consistent"
        // for a value that was never measured is how a probe starts lying.
        assertNull(Compatibility.ramIssue(device(8 * gib), facts(0)))
        assertNull(Compatibility.ramIssue(device(0), facts(8 * gib)))
    }

    @Test
    fun `the ABI list is compared by its most-preferred entry`() {
        val soc = soc(listOf("arm64-v8a", "armeabi-v7a"))
        assertNull(Compatibility.abiIssue(soc, HandsetFacts(abis = listOf("arm64-v8a"))))

        val issue = Compatibility.abiIssue(soc, HandsetFacts(abis = listOf("armeabi-v7a")))
        assertNotNull("a 32-bit handset wearing a 64-bit profile is a real mismatch", issue)
        assertNull("nothing read means nothing claimed", Compatibility.abiIssue(soc, HandsetFacts()))
    }

    // ---- fixtures -----------------------------------------------------------

    private fun device(ramBytes: Long) = DeviceProfile(
        key = "test_device",
        name = "Test Device",
        brand = "Test",
        manufacturer = "Test",
        model = "T1",
        product = "test",
        device = "test",
        androidRelease = "14",
        sdkInt = 34,
        buildId = "UQ1A.240205.004",
        buildIncremental = "1",
        socKey = "test_soc",
        ramBytes = ramBytes,
        display = DisplayProfile(1080, 2400, 420),
    )

    private fun soc(abis: List<String>) = SocProfile(
        key = "test_soc",
        vendor = "Qualcomm",
        marketingName = "Snapdragon 000",
        platform = "test",
        hardware = "qcom",
        board = "test",
        cpuImplementer = "0x41",
        cpuPart = "0xd00",
        cores = 8,
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno (TM) 000",
        abis = abis,
    )

    private fun facts(reportedRamBytes: Long) = HandsetFacts(reportedRamBytes = reportedRamBytes)
}
