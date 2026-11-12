package com.litv.tv.data

/** Atomic persistence boundary: credentials never fall back to plaintext. */
internal interface TokenStorage {
    fun encrypted(): String?
    fun legacyPlaintext(): String?
    fun replace(encrypted: String?): Boolean
}

internal interface TokenCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(encrypted: String): String
}

/**
 * Migrates the old plaintext token with a single remove+put transaction.
 * If the OS key is unavailable or the ciphertext is corrupt, return signed-out state.
 * Keystore errors are deliberately not logged: provider exceptions may contain secrets.
 */
internal class SecureTokenStore(
    private val storage: TokenStorage,
    private val cipher: TokenCipher,
) {
    private var cached: String? = null

    @Synchronized
    fun read(): String {
        cached?.let { return it }
        val token = try {
            val encrypted = storage.encrypted()
            if (encrypted != null) {
                cipher.decrypt(encrypted).also { require(it.isNotBlank()) }
            } else {
                val legacy = storage.legacyPlaintext().orEmpty()
                if (legacy.isBlank()) "" else {
                    check(storage.replace(cipher.encrypt(legacy)))
                    legacy
                }
            }
        } catch (_: Exception) {
            // Never use the legacy value when secure persistence failed.
            runCatching { storage.replace(null) }
            ""
        }
        cached = token
        return token
    }

    @Synchronized
    fun write(token: String) {
        if (token.isBlank()) {
            clear()
            return
        }
        try {
            check(storage.replace(cipher.encrypt(token)))
            cached = token
        } catch (_: Exception) {
            cached = ""
            storage.replace(null)
            throw IllegalStateException("设备安全存储不可用，无法保存绑定凭据，请重试或检查系统安全设置")
        }
    }

    @Synchronized
    fun clear() {
        cached = ""
        check(storage.replace(null)) { "无法清除本机绑定凭据，请重试" }
    }
}
