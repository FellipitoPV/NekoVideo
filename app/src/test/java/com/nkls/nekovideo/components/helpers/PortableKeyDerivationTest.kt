package com.nkls.nekovideo.components.helpers

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.*
import org.junit.Test

class PortableKeyDerivationTest {
    private fun expected(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    @Test fun matchesPublishedRfc7914Vectors() {
        // First 32 bytes of the 64-byte vectors in RFC 7914 section 11.
        for ((password, salt, rounds, hex) in listOf(
            listOf("passwd", "salt", "1", "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"),
            listOf("Password", "NaCl", "80000", "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56"))) {
            val expected = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertArrayEquals(expected, PortableKeyDerivation.derive256(password.toCharArray(), salt.toByteArray(), rounds.toInt()))
        }
    }

    @Test fun matchesProviderForExistingPasswordsAndProductionRounds() {
        val salt = ByteArray(16) { (it * 7).toByte() }
        for (password in listOf("1234", "á漢字🔒", "a\u0000b", "very-long-password".repeat(10), "")) {
            assertArrayEquals(expected(password, salt, 210000), PortableKeyDerivation.derive256(password.toCharArray(), salt, 210000))
        }
    }
}
