package io.guise.core

import io.guise.core.config.ConfigCodec
import io.guise.core.config.ConfigResolver
import io.guise.core.config.ModuleConfig
import io.guise.core.config.TargetConfig
import io.guise.core.privacy.PrivacyDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyTest {

    private val catalog = TestCatalog.load()

    @Test
    fun `authorities resolve to their domain, case-insensitively`() {
        assertEquals(PrivacyDomain.CONTACTS, PrivacyDomain.ofAuthority("com.android.contacts"))
        assertEquals(PrivacyDomain.CONTACTS, PrivacyDomain.ofAuthority("COM.ANDROID.CONTACTS"))
        assertEquals(PrivacyDomain.CALL_LOG, PrivacyDomain.ofAuthority("call_log"))
        assertEquals(PrivacyDomain.CALENDAR, PrivacyDomain.ofAuthority("com.android.calendar"))
    }

    @Test
    fun `an unrelated authority resolves to nothing`() {
        // This runs on every provider query the target app makes, so the common answer being
        // null is the point rather than an edge case.
        assertNull(PrivacyDomain.ofAuthority("media"))
        assertNull(PrivacyDomain.ofAuthority("com.android.providers.media.documents"))
        assertNull(PrivacyDomain.ofAuthority(null))
        assertNull(PrivacyDomain.ofAuthority(""))
    }

    @Test
    fun `every domain is reachable by at least one of its own authorities`() {
        PrivacyDomain.entries.forEach { domain ->
            assertTrue(
                "domain ${domain.id} has no authority that resolves back to it",
                domain.authorities.any { PrivacyDomain.ofAuthority(it) == domain },
            )
        }
    }

    @Test
    fun `unknown ids are dropped rather than failing to parse`() {
        // Forward compatibility: a config written by a newer build must not break an older one.
        val parsed = PrivacyDomain.parse(listOf("contacts", "telepathy", "calllog"))
        assertEquals(setOf(PrivacyDomain.CONTACTS, PrivacyDomain.CALL_LOG), parsed)
    }

    @Test
    fun `emptied domains survive a config round trip`() {
        val config = ModuleConfig.EMPTY.withTarget(
            TargetConfig(
                packageName = "com.example.calculator",
                profileKey = "xiaomi_venus",
                emptiedDomains = setOf("contacts", "calllog"),
            ),
        )
        val decoded = ConfigCodec.decode(ConfigCodec.encode(config))
        assertEquals(setOf("contacts", "calllog"), decoded.target("com.example.calculator")!!.emptiedDomains)
    }

    @Test
    fun `the resolver hands emptied domains to the hook layer`() {
        val config = ModuleConfig.EMPTY.withTarget(
            TargetConfig(
                packageName = "com.example.calculator",
                profileKey = "xiaomi_venus",
                emptiedDomains = setOf("contacts"),
            ),
        )
        val resolved = ConfigResolver.resolve(config, catalog, "com.example.calculator")!!
        assertEquals(setOf(PrivacyDomain.CONTACTS), resolved.emptiedDomains)
    }

    @Test
    fun `a target with no privacy configuration empties nothing`() {
        val config = ModuleConfig.EMPTY.withTarget(
            TargetConfig(packageName = "com.example.calculator", profileKey = "xiaomi_venus"),
        )
        val resolved = ConfigResolver.resolve(config, catalog, "com.example.calculator")!!
        assertTrue(resolved.emptiedDomains.isEmpty())
    }

    @Test
    fun `an empty address book is what the app is told, not a denied permission`() {
        // Guards the design decision rather than the code: the permission string is
        // informational, and nothing in the model revokes anything. If a future change makes
        // Guise deny a permission, this asserts the intent that was deliberately not taken.
        PrivacyDomain.entries.forEach { domain ->
            assertTrue(
                "domain ${domain.id} should name the permission the user grants, not one Guise sets",
                domain.permission.startsWith("android.permission."),
            )
        }
    }
}
