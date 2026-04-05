package com.nkls.nekovideo.components.helpers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

object BiometricHelper {

    private const val KEY_NAME = "neko_biometric_key"
    private const val PREFS_NAME = "neko_biometric_prefs"
    private const val PREF_ENABLED = "biometric_enabled"
    private const val PREF_ENCRYPTED_PASSWORD = "encrypted_password"
    private const val PREF_IV = "encryption_iv"
    private const val TRANSFORMATION =
        "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}"

    fun isBiometricAvailable(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun isBiometricEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_ENABLED, false) &&
            prefs.getString(PREF_ENCRYPTED_PASSWORD, null) != null
    }

    /**
     * Prompts biometric and, on success, encrypts [password] in the Keystore.
     * Call this when the user chooses to enable biometric unlock.
     */
    fun enable(
        activity: FragmentActivity,
        password: String,
        title: String,
        subtitle: String,
        negativeText: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val cipher = getCipherForEncryption() ?: run { onError("Cipher setup failed"); return }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val encrypted = result.cryptoObject?.cipher?.doFinal(
                            password.toByteArray(Charsets.UTF_8)
                        ) ?: run { onError("Encryption failed"); return }
                        val iv = result.cryptoObject?.cipher?.iv
                            ?: run { onError("IV error"); return }

                        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                            putBoolean(PREF_ENABLED, true)
                            putString(PREF_ENCRYPTED_PASSWORD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                            putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                        }
                        onSuccess()
                    } catch (e: Exception) {
                        onError(e.message ?: "Unknown error")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {}
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    /**
     * Prompts biometric and, on success, decrypts the stored password.
     * [onFallback] is called when user cancels or taps the negative button (use password instead).
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        negativeText: String,
        onSuccess: (String) -> Unit,
        onFallback: () -> Unit,
        onError: (String) -> Unit
    ) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encB64 = prefs.getString(PREF_ENCRYPTED_PASSWORD, null) ?: run { onFallback(); return }
        val ivB64 = prefs.getString(PREF_IV, null) ?: run { onFallback(); return }

        val encBytes = Base64.decode(encB64, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val cipher = getCipherForDecryption(iv) ?: run { onFallback(); return }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        val decrypted = result.cryptoObject?.cipher?.doFinal(encBytes)
                            ?: run { onError("Decryption failed"); return }
                        onSuccess(String(decrypted, Charsets.UTF_8))
                    } catch (e: Exception) {
                        onError(e.message ?: "Decryption error")
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode in listOf(
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED
                        )
                    ) {
                        onFallback()
                    } else {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {}
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    /** Removes all stored biometric data and deletes the Keystore key. */
    fun disable(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (ks.containsAlias(KEY_NAME)) ks.deleteEntry(KEY_NAME)
        } catch (_: Exception) {}
    }

    private fun generateOrGetKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        ks.getKey(KEY_NAME, null)?.let { return it as SecretKey }

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_NAME,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return kg.generateKey()
    }

    private fun getCipherForEncryption(): Cipher? = try {
        val key = generateOrGetKey()
        Cipher.getInstance(TRANSFORMATION).also { it.init(Cipher.ENCRYPT_MODE, key) }
    } catch (_: Exception) {
        null
    }

    private fun getCipherForDecryption(iv: ByteArray): Cipher? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val key = ks.getKey(KEY_NAME, null) as? SecretKey ?: return null
        Cipher.getInstance(TRANSFORMATION).also {
            it.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        }
    } catch (_: Exception) {
        null
    }
}
