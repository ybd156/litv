package com.litv.tv.data

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

/** OS-keystore encryption: AES-GCM on API 23+, RSA only for Android 5.x. */
internal class KeystoreTokenCipher(context: Context) : TokenCipher {
    private val context = context.applicationContext
    private val keys: KeyStore
        get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun encrypt(plaintext: String): String {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        // Device tokens are 43-byte base64url strings; cap legacy RSA plaintext safely.
        require(bytes.size in 1..190)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            encryptAes(bytes)
        } else {
            encryptLegacy(bytes)
        }
    }

    override fun decrypt(encrypted: String): String {
        require(encrypted.length <= 4096)
        val parts = encrypted.split(':')
        val bytes = when {
            parts.size == 3 && parts[0] == "aes1" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                decryptAes(parts[1], parts[2])
            parts.size == 2 && parts[0] == "rsa1" -> decryptLegacy(parts[1])
            else -> throw IllegalStateException("Unsupported credential format")
        }
        require(bytes.size in 1..190)
        return bytes.toString(Charsets.UTF_8)
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun aesKey(create: Boolean): SecretKey {
        val store = keys
        (store.getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun encryptAes(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey(create = true))
        cipher.updateAAD(AAD)
        return "aes1:${encode(cipher.iv)}:${encode(cipher.doFinal(bytes))}"
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun decryptAes(iv: String, encrypted: String): ByteArray {
        val nonce = decode(iv)
        require(nonce.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(create = false), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        return cipher.doFinal(decode(encrypted))
    }

    @Suppress("DEPRECATION")
    private fun encryptLegacy(bytes: ByteArray): String {
        var store = keys
        if (!store.containsAlias(RSA_ALIAS)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                initialize(
                    KeyPairGeneratorSpec.Builder(context)
                        .setAlias(RSA_ALIAS)
                        .setKeySize(2048)
                        .setSubject(X500Principal("CN=LITV Device Credential"))
                        .setSerialNumber(BigInteger.ONE)
                        .setStartDate(start.time)
                        .setEndDate(end.time)
                        .build(),
                )
            }.generateKeyPair()
            store = keys
        }
        // API 21-22 Keystore has no AES support. This branch never runs on newer devices.
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, store.getCertificate(RSA_ALIAS).publicKey)
        return "rsa1:${encode(cipher.doFinal(bytes))}"
    }

    private fun decryptLegacy(encrypted: String): ByteArray {
        val key = keys.getKey(RSA_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("Credential key is unavailable")
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(decode(encrypted))
    }

    private companion object {
        const val AES_ALIAS = "litv.device.token.aes.v1"
        const val RSA_ALIAS = "litv.device.token.rsa.v1"
        val AAD = "com.litv.tv/device-token/v1".toByteArray(Charsets.UTF_8)
        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    }
}
