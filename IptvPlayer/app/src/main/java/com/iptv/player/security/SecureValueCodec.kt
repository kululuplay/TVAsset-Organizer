package com.iptv.player.security

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.iptv.player.util.Logger
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * Small field-encryption primitive backed by Android Keystore.
 *
 * A random AES key is wrapped by a non-exportable Keystore RSA key and only the
 * wrapped bytes are kept in private SharedPreferences. Keeping the unwrapped AES
 * key in process memory avoids one slow Keystore IPC operation per row when a
 * 20k-title catalog is mapped. Values use a versioned AES-GCM envelope, giving
 * both confidentiality and tamper detection without changing Room schemas.
 */
class SecureValueCodec(context: Context) {

    private val appContext = context.applicationContext
    private val legacyPrefs by lazy {
        appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedKey: SecretKey? = null

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /**
     * Encrypt a value unless it is blank or already in this codec's envelope.
     * Failure is never downgraded to plaintext storage.
     */
    fun encrypt(value: String): String {
        if (value.isBlank() || isEncrypted(value)) return value
        return try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            PREFIX + encode(iv) + ":" + encode(encrypted)
        } catch (t: Throwable) {
            Logger.e(TAG, "secure value encryption failed", t)
            throw IllegalStateException("Secure storage is unavailable", t)
        }
    }

    /**
     * Decrypt an envelope. Plain legacy values pass through so callers can read
     * and lazily migrate existing installs. A corrupt/inaccessible encrypted value
     * returns empty rather than ever exposing the envelope as a credential/URL.
     */
    fun decrypt(value: String?): String {
        if (value.isNullOrEmpty() || !isEncrypted(value)) return value.orEmpty()
        return runCatching {
            val parts = value.removePrefix(PREFIX).split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, decode(parts[0])),
            )
            String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
        }.getOrElse {
            Logger.e(TAG, "secure value decryption failed")
            ""
        }
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        val key = getOrCreateWrappedKey()
        cachedKey = key
        return key
    }

    private fun getOrCreateWrappedKey(): SecretKey {
        val wrapped = legacyPrefs.getString(LEGACY_WRAPPED_KEY, null)
        if (!wrapped.isNullOrBlank()) {
            return SecretKeySpec(rsaDecrypt(decode(wrapped)), "AES")
        }

        ensureRsaKeyPair()
        val raw = ByteArray(AES_KEY_BYTES).also(SecureRandom()::nextBytes)
        val encoded = encode(rsaEncrypt(raw))
        check(legacyPrefs.edit().putString(LEGACY_WRAPPED_KEY, encoded).commit()) {
            "Unable to persist wrapped secure-storage key"
        }
        return SecretKeySpec(raw, "AES")
    }

    private fun ensureRsaKeyPair() {
        val store = androidKeyStore()
        if (store.containsAlias(RSA_ALIAS)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            generateModernRsaKeyPair()
        } else {
            generateLegacyRsaKeyPair()
        }
    }

    @SuppressLint("NewApi")
    private fun generateModernRsaKeyPair() {
        val spec = KeyGenParameterSpec.Builder(
            RSA_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    @Suppress("DEPRECATION")
    private fun generateLegacyRsaKeyPair() {
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
        val spec = KeyPairGeneratorSpec.Builder(appContext)
            .setAlias(RSA_ALIAS)
            .setSubject(X500Principal("CN=Kululu Secure Storage"))
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(start.time)
            .setEndDate(end.time)
            .build()
        KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun rsaEncrypt(raw: ByteArray): ByteArray {
        val publicKey = androidKeyStore().getCertificate(RSA_ALIAS).publicKey
        return Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, publicKey)
            doFinal(raw)
        }
    }

    private fun rsaDecrypt(wrapped: ByteArray): ByteArray {
        val privateKey = androidKeyStore().getKey(RSA_ALIAS, null)
        return Cipher.getInstance(RSA_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, privateKey)
            doFinal(wrapped)
        }
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val TAG = "SecureValueCodec"
        const val PREFIX = "encv1:"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val RSA_ALIAS = "kululu_secure_values_rsa_v1"
        const val LEGACY_PREFS = "secure_key_material"
        const val LEGACY_WRAPPED_KEY = "wrapped_aes_v1"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
        const val AES_KEY_BYTES = 16
        const val GCM_TAG_BITS = 128
    }
}
