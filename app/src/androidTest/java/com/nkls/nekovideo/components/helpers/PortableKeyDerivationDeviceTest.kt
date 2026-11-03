package com.nkls.nekovideo.components.helpers

import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortableKeyDerivationDeviceTest {
    @Test fun productionRoundsMatchAndroidProviderIncludingUnicodeAndNul() {
        val salt = ByteArray(16) { (it * 7).toByte() }
        for (password in listOf("1234", "á漢字🔒", "a\u0000b", "long-password".repeat(10), "", "\uD800")) {
            val spec = PBEKeySpec(password.toCharArray(), salt, 210000, 256)
            val old = try { runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } }
                finally { spec.clearPassword() }
            val optimized = runCatching { PortableKeyDerivation.derive256(password.toCharArray(), salt, 210000) }
            if (old.isSuccess) assertArrayEquals(old.getOrThrow(), optimized.getOrThrow())
            else assertEquals(old.exceptionOrNull()!!::class.java, optimized.exceptionOrNull()?.javaClass)
        }
    }
}
