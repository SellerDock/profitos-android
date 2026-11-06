package pl.profitos.app

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Native vault for the web layer's finance-vault key.
 *
 * The raw vault key arrives from JS as base64 and gets wrapped (AES/GCM) by a
 * key that lives only inside Android Keystore. That Keystore key requires a
 * fresh strong-biometric authentication for every single use
 * (setUserAuthenticationValidityDurationSeconds(-1)), so both storing and
 * reading go through BiometricPrompt with a CryptoObject.
 *
 * Only the wrapped blob (Base64(iv || ciphertext+tag)) is persisted, in
 * SharedPreferences "profitos_vault" under "blob". The raw key never touches
 * disk.
 *
 * Results are delivered asynchronously through [listener]; all listener
 * callbacks fire on the UI thread.
 */
class BiometricVault(private val activity: FragmentActivity) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "profitos_vault_wrap_v1" // gitleaks:allow (nazwa aliasu Keystore, nie sekret)
        private const val PREFS_NAME = "profitos_vault"
        private const val PREFS_BLOB = "blob"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LEN = 12

        // Error codes shared with the web layer (bridge contract).
        const val ERR_CANCELLED = "cancelled"
        const val ERR_LOCKOUT = "lockout"
        const val ERR_NO_BIOMETRIC = "no_biometric"
        const val ERR_KEY_INVALIDATED = "key_invalidated"
        const val ERR_NO_STORED_KEY = "no_stored_key"
        const val ERR_CRYPTO = "crypto_error"
    }

    private val prefs: SharedPreferences
        get() = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBiometricAvailable(): Boolean =
        BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun hasStoredKey(): Boolean = prefs.getString(PREFS_BLOB, null) != null

    fun clearKey(): Boolean {
        prefs.edit().remove(PREFS_BLOB).apply()
        deleteAlias()
        return true
    }

    /** Called from the JS bridge thread; the prompt itself is posted to the UI thread. */
    fun storeKey(keyB64: String, callback: (Boolean, String?) -> Unit) {
        val plaintext = try {
            Base64.decode(keyB64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            callback(false, ERR_CRYPTO)
            return
        }
        activity.runOnUiThread {
            if (!isBiometricAvailable()) {
                plaintext.fill(0)
                callback(false, ERR_NO_BIOMETRIC)
                return@runOnUiThread
            }
            val cipher = try {
                buildCipher(Cipher.ENCRYPT_MODE, null)
            } catch (e: KeyInvalidatedSignal) {
                plaintext.fill(0)
                wipeKeys()
                callback(false, ERR_KEY_INVALIDATED)
                return@runOnUiThread
            } catch (e: Exception) {
                plaintext.fill(0)
                callback(false, ERR_CRYPTO)
                return@runOnUiThread
            }
            showPrompt(cipher, object : PromptCallback {
                override fun onSuccess(resultCipher: Cipher) {
                    try {
                        val ciphertext = resultCipher.doFinal(plaintext)
                        val blob = resultCipher.iv + ciphertext
                        prefs.edit()
                            .putString(PREFS_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
                            .apply()
                        callback(true, null)
                    } catch (e: Exception) {
                        callback(false, ERR_CRYPTO)
                    } finally {
                        plaintext.fill(0)
                    }
                }

                override fun onError(code: String) {
                    plaintext.fill(0)
                    callback(false, code)
                }
            })
        }
    }

    /** Called from the JS bridge thread; the prompt itself is posted to the UI thread. */
    fun requestKey(callback: (String?, String?) -> Unit) {
        activity.runOnUiThread {
            val blobB64 = prefs.getString(PREFS_BLOB, null)
            if (blobB64 == null) {
                callback(null, ERR_NO_STORED_KEY)
                return@runOnUiThread
            }
            if (!isBiometricAvailable()) {
                callback(null, ERR_NO_BIOMETRIC)
                return@runOnUiThread
            }
            val blob = try {
                Base64.decode(blobB64, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                callback(null, ERR_CRYPTO)
                return@runOnUiThread
            }
            if (blob.size <= IV_LEN) {
                callback(null, ERR_CRYPTO)
                return@runOnUiThread
            }
            val iv = blob.copyOfRange(0, IV_LEN)
            val ciphertext = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = try {
                buildCipher(Cipher.DECRYPT_MODE, iv)
            } catch (e: KeyInvalidatedSignal) {
                wipeKeys()
                callback(null, ERR_KEY_INVALIDATED)
                return@runOnUiThread
            } catch (e: Exception) {
                callback(null, ERR_CRYPTO)
                return@runOnUiThread
            }
            showPrompt(cipher, object : PromptCallback {
                override fun onSuccess(resultCipher: Cipher) {
                    try {
                        val plaintext = resultCipher.doFinal(ciphertext)
                        val keyB64 = Base64.encodeToString(plaintext, Base64.NO_WRAP)
                        plaintext.fill(0)
                        callback(keyB64, null)
                    } catch (e: Exception) {
                        callback(null, ERR_CRYPTO)
                    }
                }

                override fun onError(code: String) {
                    callback(null, code)
                }
            })
        }
    }

    // ---- internals ----

    /** Internal signal: the Keystore key was permanently invalidated (biometric enrollment changed). */
    private class KeyInvalidatedSignal : Exception()

    private interface PromptCallback {
        fun onSuccess(resultCipher: Cipher)
        fun onError(code: String)
    }

    private fun showPrompt(cipher: Cipher, cb: PromptCallback) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val resultCipher = result.cryptoObject?.cipher
                    if (resultCipher != null) {
                        cb.onSuccess(resultCipher)
                    } else {
                        cb.onError(ERR_CRYPTO)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    cb.onError(mapPromptError(errorCode))
                }

                override fun onAuthenticationFailed() {
                    // Non-fatal: the same prompt stays open and lets the user retry.
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Odblokuj Sejf finansowy")
            .setSubtitle("Potwierdź odciskiem palca")
            .setNegativeButtonText("Anuluj")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun mapPromptError(errorCode: Int): String = when (errorCode) {
        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> ERR_LOCKOUT
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_HW_NOT_PRESENT -> ERR_NO_BIOMETRIC
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_TIMEOUT -> ERR_CANCELLED
        else -> ERR_CRYPTO
    }

    private fun buildCipher(mode: Int, iv: ByteArray?): Cipher {
        val key = try {
            getOrCreateSecretKey()
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyInvalidatedSignal()
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            if (mode == Cipher.ENCRYPT_MODE) {
                cipher.init(mode, key)
            } else {
                cipher.init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyInvalidatedSignal()
        }
        return cipher
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        if (prefs.getString(PREFS_BLOB, null) != null) throw KeyInvalidatedSignal()
        return generateSecretKey()
    }

    private fun generateSecretKey(): SecretKey {
        // Drop any stale/half-broken entry before generating a fresh one.
        deleteAlias()
        val strongBox = activity.packageManager
            .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        try {
            return doGenerate(strongBox)
        } catch (e: Exception) {
            deleteAlias()
            if (strongBox) {
                // Some StrongBox implementations refuse e.g. per-use auth or
                // invalidatedByBiometricEnrollment — fall back to plain TEE.
                return try {
                    doGenerate(false)
                } catch (e2: Exception) {
                    deleteAlias()
                    throw e2
                }
            }
            throw e
        }
    }

    @Suppress("DEPRECATION") // validity-duration / enrollment APIs deprecated on API 30+ but still honored
    private fun doGenerate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            // -1: every single use of the key demands fresh biometric auth.
            .setUserAuthenticationValidityDurationSeconds(-1)
        if (strongBox) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    /** Used when the Keystore key got invalidated: drop blob + alias so the web layer can re-enroll. */
    private fun wipeKeys() {
        prefs.edit().remove(PREFS_BLOB).apply()
        deleteAlias()
    }

    private fun deleteAlias() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            // Best effort; nothing useful to do if Keystore itself is broken.
        }
    }
}
