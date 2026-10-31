package com.rfwatch.app.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Supplies the SQLCipher database passphrase, protected by the Android Keystore.
 *
 * A 32-byte random passphrase is generated once and stored only in encrypted form: it is
 * wrapped with an AES-GCM key that lives inside the Keystore (hardware-backed where the
 * device supports it) and never leaves it. The wrapped blob sits in SharedPreferences; the
 * raw passphrase exists only transiently in memory when the database is opened.
 */
class KeystoreKeyManager(context: Context) {

    private val prefs = context.getSharedPreferences("rfwatch_secure", Context.MODE_PRIVATE)

    fun getOrCreatePassphrase(): ByteArray {
        val stored = prefs.getString(PREF_WRAPPED, null)
        if (stored != null) {
            return runCatching { unwrap(stored) }.getOrElse { regenerate() }
        }
        return regenerate()
    }

    private fun regenerate(): ByteArray {
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREF_WRAPPED, wrap(passphrase)).apply()
        return passphrase
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(data: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(data)
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun unwrap(blob: String): ByteArray {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, GCM_IV_LEN)
        val ct = bytes.copyOfRange(GCM_IV_LEN, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rfwatch_db_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
        private const val PREF_WRAPPED = "db_pass_wrapped"
    }
}
