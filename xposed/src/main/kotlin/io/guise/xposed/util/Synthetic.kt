package io.guise.xposed.util

import java.security.MessageDigest

/**
 * Deterministic synthetic identifiers.
 *
 * These must be *stable*, not random. A spoofed identifier that changes between launches
 * is a louder signal than one that is simply wrong: a device whose serial number changes
 * every time is behaving like nothing real. So every synthetic value here is derived from
 * the profile key, which means it is reproducible without having to be stored.
 */
object Synthetic {

    /**
     * Luhn check digit, used by IMEI/MEID and by credit cards.
     *
     * @param payload digits *without* the check digit
     */
    fun luhnCheckDigit(payload: String): Int {
        var sum = 0
        var double = true // the digit immediately left of the check digit is doubled
        for (i in payload.indices.reversed()) {
            var d = payload[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return (10 - (sum % 10)) % 10
    }

    private fun digest(seed: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))

    private fun bytesToDigits(bytes: ByteArray, count: Int): String = buildString(count) {
        var i = 0
        while (length < count) {
            append((bytes[i % bytes.size].toInt() and 0xFF) % 10)
            i++
        }
    }

    /**
     * A structurally valid 15-digit IMEI derived from [seed].
     *
     * Deceptive-but-plausible is the requirement: the TAC prefix comes from a real
     * reporting body, the serial is derived from the seed, and the check digit is a real
     * Luhn digit. An IMEI that fails its own checksum is trivially detected.
     */
    fun imei(seed: String): String {
        // 86 is the TAC reporting body for China; 35 is the original UK BABT range.
        val tac = if (digest("tac:$seed")[0].toInt() and 1 == 0) "86123456" else "35123456"
        val serial = bytesToDigits(digest("sn:$seed"), 6)
        val payload = tac + serial
        return payload + luhnCheckDigit(payload)
    }

    /**
     * A 16-hex-character SSAID (`Settings.Secure.ANDROID_ID`) derived from [seed].
     *
     * SSAID is the one identifier that genuinely has no ground truth -- the platform
     * generates it as a random number on first request and stores it in a lookup table.
     * There is therefore nothing to contradict, which is why spoofing it costs nothing.
     */
    fun androidId(seed: String): String {
        val d = digest("ssaid:$seed")
        return buildString(16) {
            for (i in 0 until 8) append("%02x".format(d[i]))
        }
    }

    /** A locally-administered unicast MAC (second nibble is 2, 6, A or E). */
    fun macAddress(seed: String): String {
        val d = digest("mac:$seed")
        val b = IntArray(6) { d[it].toInt() and 0xFF }
        b[0] = (b[0] and 0xFE) or 0x02
        return b.joinToString(":") { "%02x".format(it) }
    }

    /** A 20-digit ICCID-ish SIM serial derived from [seed]. */
    fun simSerial(seed: String): String {
        val body = "8986" + bytesToDigits(digest("sim:$seed"), 15)
        return body + luhnCheckDigit(body)
    }
}
