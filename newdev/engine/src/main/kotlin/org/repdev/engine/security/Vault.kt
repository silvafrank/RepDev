package org.repdev.engine.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Replaces RepDev_SSO.java's crypto. What was wrong with it (see DECISIONS.md):
 *  - salt was the constant "RepDev" for every install ever shipped — a salt's
 *    entire job is to be different per-secret; a hardcoded one does nothing.
 *  - the master password was pre-hashed with MD5 before being handed to
 *    PBKDF2, for no benefit (PBEKeySpec already takes the raw password).
 *  - AES/CBC with a hand-rolled "does it start with the string 'repdev'"
 *    integrity check, instead of an AEAD mode that actually detects tampering.
 *
 * Fixed here with AES-256-GCM (authenticated: a wrong key or a flipped byte
 * throws, no magic-prefix guessing) and a random salt generated per vault,
 * stored alongside the ciphertext like any salt should be — it's not secret,
 * only the password is.
 */
object Vault {
    private const val KEY_LEN_BITS = 256
    private const val ITERATIONS = 600_000 // OWASP 2023 PBKDF2-HMAC-SHA256 minimum
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12 // 96-bit GCM nonce, the recommended size

    class WrongPasswordException : Exception("Incorrect master password")

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun deriveKey(masterPassword: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secret = factory.generateSecret(PBEKeySpec(masterPassword, salt, ITERATIONS, KEY_LEN_BITS))
        return SecretKeySpec(secret.encoded, "AES")
    }

    fun encrypt(masterPassword: CharArray, salt: ByteArray, plainText: String): String {
        val key = deriveKey(masterPassword, salt)
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipherText)
    }

    /** @throws WrongPasswordException if [masterPassword]/[salt] don't match what [encrypt] used, or [encoded] was tampered with. */
    fun decrypt(masterPassword: CharArray, salt: ByteArray, encoded: String): String {
        val raw = Base64.getDecoder().decode(encoded)
        val iv = raw.copyOfRange(0, IV_LEN)
        val cipherText = raw.copyOfRange(IV_LEN, raw.size)
        val key = deriveKey(masterPassword, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return try {
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw WrongPasswordException()
        }
    }
}
