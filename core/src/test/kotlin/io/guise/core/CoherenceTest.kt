package io.guise.core

import io.guise.core.config.ConfigCodec
import io.guise.core.config.ConfigResolver
import io.guise.core.config.ModuleConfig
import io.guise.core.config.TargetConfig
import io.guise.core.profile.EffectiveProfile
import io.guise.core.profile.FieldKey
import io.guise.core.profile.RuntimeVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The central claim of this rewrite: cross-channel coherence is structural, not a matter
 * of the user filling in fields carefully.
 *
 * Guise let you set MODEL, BRAND and DEVICE independently, so a user could easily
 * produce a "Pixel" whose fingerprint, product codename and GPU all said Xiaomi. These
 * tests pin down that the profile-driven design cannot express that state.
 */
class CoherenceTest {

    private val catalog = TestCatalog.load()
    private val device = catalog.devices.getValue("xiaomi_venus")

    private fun profile(overrides: Map<String, String> = emptyMap()) =
        EffectiveProfile.of(device, catalog, overrides)

    @Test
    fun `every field resolves from the profile alone`() {
        val p = profile()
        // BUILD_SERIAL is the one field a profile may legitimately leave unset.
        val optional = setOf(FieldKey.BUILD_SERIAL)
        FieldKey.entries.filterNot { it in optional }.forEach { key ->
            assertTrue("field ${key.id} resolved blank", p.string(key).isNotBlank())
        }
    }

    @Test
    fun `soc-derived fields come from the soc, not the handset`() {
        val p = profile()
        assertEquals(device.socKey, device.socKey)
        assertEquals(catalog.socs.getValue(device.socKey).gpuRenderer, p.string(FieldKey.SOC_GPU_RENDERER))
        assertEquals(catalog.socs.getValue(device.socKey).platform, p.string(FieldKey.SOC_PLATFORM))
        // Mi 11 is a Snapdragon 888 handset; if this ever says something else the
        // catalog is lying to itself.
        assertEquals("Adreno (TM) 660", p.string(FieldKey.SOC_GPU_RENDERER))
    }

    @Test
    fun `overriding the model keeps the fingerprint coherent with it`() {
        val p = profile(mapOf(FieldKey.BUILD_MODEL.id to "FAKE-MODEL-1"))
        assertEquals("FAKE-MODEL-1", p.string(FieldKey.BUILD_MODEL))

        // The fingerprint is derived, so it cannot keep claiming the old identity.
        // Note Build.MODEL is *not* part of the fingerprint layout -- the codename is.
        // What must hold is that the fingerprint still agrees with brand/product/device.
        val fp = p.string(FieldKey.BUILD_FINGERPRINT)
        assertTrue(fp.startsWith("${device.brand}/${device.product}/${device.device}:"))
        assertEquals(3, fp.split(":").size)
    }

    @Test
    fun `overriding a fingerprint component propagates into the fingerprint`() {
        val p = profile(
            mapOf(
                FieldKey.BUILD_BRAND.id to "Acme",
                FieldKey.BUILD_PRODUCT.id to "acmeprod",
                FieldKey.BUILD_DEVICE.id to "acmedev",
                FieldKey.VERSION_RELEASE.id to "14",
                FieldKey.BUILD_ID.id to "UP1A.231005.007",
                FieldKey.VERSION_INCREMENTAL.id to "1234567",
            ),
        )
        assertEquals(
            "Acme/acmeprod/acmedev:14/UP1A.231005.007/1234567:user/release-keys",
            p.string(FieldKey.BUILD_FINGERPRINT),
        )
    }

    @Test
    fun `explicit fingerprint override wins over derivation`() {
        val custom = "Custom/thing/thing:14/UP1A.231005.007/1:user/release-keys"
        val p = profile(mapOf(FieldKey.BUILD_FINGERPRINT.id to custom))
        assertEquals(custom, p.string(FieldKey.BUILD_FINGERPRINT))
    }

    @Test
    fun `overriding one field does not disturb unrelated fields`() {
        val base = profile()
        val tweaked = profile(mapOf(FieldKey.BUILD_MODEL.id to "SOMETHING-ELSE"))
        FieldKey.entries
            .filterNot { it == FieldKey.BUILD_MODEL }
            .forEach { key ->
                assertEquals(
                    "overriding MODEL unexpectedly changed ${key.id}",
                    base.string(key),
                    tweaked.string(key),
                )
            }
    }

    @Test
    fun `overridden keys are reported so the ui can show them`() {
        val p = profile(mapOf(FieldKey.BUILD_MODEL.id to "X", FieldKey.DISPLAY_DENSITY.id to "480"))
        assertEquals(setOf(FieldKey.BUILD_MODEL, FieldKey.DISPLAY_DENSITY), p.overriddenKeys)
        assertTrue(p.isOverridden(FieldKey.BUILD_MODEL))
        assertFalse(p.isOverridden(FieldKey.BUILD_BRAND))
    }

    @Test
    fun `typed accessors parse ints and longs`() {
        val p = profile(mapOf(FieldKey.VERSION_SDK_INT.id to "34", FieldKey.BUILD_TIME.id to "1700000000000"))
        assertEquals(34, p.int(FieldKey.VERSION_SDK_INT))
        assertEquals(1700000000000L, p.long(FieldKey.BUILD_TIME))
        assertEquals(515, p.int(FieldKey.DISPLAY_DENSITY))
    }

    // ---- config plumbing ----------------------------------------------------

    @Test
    fun `config round-trips through json`() {
        val config = ModuleConfig.EMPTY.withTarget(
            TargetConfig(
                packageName = "com.example.app",
                profileKey = "xiaomi_venus",
                overrides = mapOf(FieldKey.BUILD_MODEL.id to "CUSTOM"),
            ),
        )
        val decoded = ConfigCodec.decode(ConfigCodec.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun `unknown json keys are tolerated so old and new versions interoperate`() {
        val decoded = ConfigCodec.decode(
            """{"version":1,"globallyEnabled":true,"futureField":42,"targets":{}}""",
        )
        assertTrue(decoded.globallyEnabled)
    }

    @Test
    fun `malformed config degrades to empty instead of throwing`() {
        assertEquals(ModuleConfig.EMPTY, ConfigCodec.decode("{ this is not json"))
        assertEquals(ModuleConfig.EMPTY, ConfigCodec.decode(null))
    }

    @Test
    fun `resolver ignores packages the user never configured`() {
        val config = ModuleConfig.EMPTY
        assertNull(ConfigResolver.resolve(config, catalog, "com.example.app"))
    }

    @Test
    fun `resolver produces a profile for a configured package`() {
        val config = ModuleConfig.EMPTY.withTarget(
            TargetConfig(packageName = "com.example.app", profileKey = "xiaomi_venus"),
        )
        val resolved = ConfigResolver.resolve(config, catalog, "com.example.app")
        assertNotNull(resolved)
        assertEquals("M2011K2C", resolved!!.string(FieldKey.BUILD_MODEL))
    }

    @Test
    fun `resolver honours disabled target and global switch`() {
        val disabledTarget = ModuleConfig.EMPTY.withTarget(
            TargetConfig(packageName = "com.example.app", profileKey = "xiaomi_venus", enabled = false),
        )
        assertNull(ConfigResolver.resolve(disabledTarget, catalog, "com.example.app"))

        val globallyOff = disabledTarget.copy(globallyEnabled = false)
        assertNull(ConfigResolver.resolve(globallyOff, catalog, "com.example.app"))
    }

    @Test
    fun `resolver tolerates a dangling profile reference`() {
        val config = ModuleConfig.EMPTY.withTarget(
            TargetConfig(packageName = "com.example.app", profileKey = "no_such_device"),
        )
        assertNull(ConfigResolver.resolve(config, catalog, "com.example.app"))
    }

    @Test
    fun `effective profile survives a full json round trip`() {
        // This is the path the hooked process actually takes: read a JSON blob out of
        // remote preferences, resolve it, and ask for values.
        val config = ModuleConfig.EMPTY.withTarget(
            TargetConfig(
                packageName = "com.example.app",
                profileKey = "google_panther",
                overrides = mapOf(FieldKey.BUILD_MODEL.id to "GVU6C-EDITED"),
            ),
        )
        val wire = ConfigCodec.encode(config)
        val resolved = ConfigResolver.resolve(ConfigCodec.decode(wire), catalog, "com.example.app")!!
        assertEquals("GVU6C-EDITED", resolved.string(FieldKey.BUILD_MODEL))
        assertEquals("google", resolved.string(FieldKey.BUILD_BRAND))
        assertTrue(resolved.string(FieldKey.BUILD_FINGERPRINT).startsWith("google/panther/panther:13/"))
    }

    // ---- runtime version ----------------------------------------------------

    @Test
    fun `runtime version wins over the profile's recorded version`() {
        // Regression guard for a real defect the probe found on-device: the module spoofed
        // Build.VERSION.RELEASE from the profile while correctly refusing to touch SDK_INT,
        // which produced "Android 13" paired with API 31 -- a self-contradiction the probe's
        // fingerprint check reported. The version now comes from the device.
        val p = EffectiveProfile.of(
            device = device,
            catalog = catalog,
            runtime = RuntimeVersion("15", 35),
        )
        assertEquals("15", p.string(FieldKey.VERSION_RELEASE))
        assertEquals(35, p.int(FieldKey.VERSION_SDK_INT))
        // The fingerprint must embed the same version, or it contradicts RELEASE.
        assertTrue(
            "fingerprint should carry the runtime release: ${p.string(FieldKey.BUILD_FINGERPRINT)}",
            p.string(FieldKey.BUILD_FINGERPRINT).contains(":15/"),
        )
    }

    @Test
    fun `release, api level and fingerprint agree when the runtime differs from the profile`() {
        val runtime = RuntimeVersion("14", 34)
        val resolved = ConfigResolver.resolve(
            config = ModuleConfig.EMPTY.withTarget(
                TargetConfig(packageName = "com.example.app", profileKey = "xiaomi_venus"),
            ),
            catalog = catalog,
            packageName = "com.example.app",
            runtime = runtime,
        )!!
        // The catalog entry was captured on Android 12; this device runs 14. All three
        // version-bearing values must reflect the device, not the catalog.
        assertEquals("14", resolved.string(FieldKey.VERSION_RELEASE))
        assertEquals(34, resolved.int(FieldKey.VERSION_SDK_INT))
        val fpParts = resolved.string(FieldKey.BUILD_FINGERPRINT).split(":")
        assertEquals("14", fpParts[1].split("/")[0])
    }

    @Test
    fun `an explicit release override still wins and is the user's responsibility`() {
        val p = EffectiveProfile.of(
            device = device,
            catalog = catalog,
            overrides = mapOf(FieldKey.VERSION_RELEASE.id to "11"),
            runtime = RuntimeVersion("15", 35),
        )
        assertEquals("11", p.string(FieldKey.VERSION_RELEASE))
        // SDK_INT stays real unless separately overridden -- the UI warns about this pairing.
        assertEquals(35, p.int(FieldKey.VERSION_SDK_INT))
    }
}
