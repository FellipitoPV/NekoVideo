package com.nkls.nekovideo.components.helpers

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** RFC 8018 section 5.2, one 256-bit block. HMAC remains a platform cryptographic primitive. */
internal object PortableKeyDerivation {
    fun derive256(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0 && salt.isNotEmpty())
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(password))
            ByteArray(encoded.remaining()).also { encoded.get(it); if (encoded.hasArray()) encoded.array().fill(0) }
        } catch (_: CharacterCodingException) { return provider(password, salt, iterations) }
        try {
            // SecretKeySpec rejects empty keys; preserve the provider's existing behavior.
            if (bytes.isEmpty()) return provider(password, salt, iterations)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(bytes, "HmacSHA256"))
            check(mac.macLength == 32)
            val block = salt.copyOf(salt.size + 4).also { it[it.lastIndex] = 1 }
            var u = mac.doFinal(block)
            val result = u.copyOf()
            try {
                repeat(iterations - 1) {
                    val next = mac.doFinal(u)
                    for (index in result.indices) result[index] = (result[index].toInt() xor next[index].toInt()).toByte()
                    u.fill(0)
                    u = next
                }
                return result
            } catch (failure: Throwable) {
                result.fill(0)
                throw failure
            } finally { u.fill(0); block.fill(0) }
        } finally { bytes.fill(0) }
    }

    private fun provider(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
