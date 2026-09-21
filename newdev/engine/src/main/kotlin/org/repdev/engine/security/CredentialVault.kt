package org.repdev.engine.security

import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * On-disk shape of the vault (the JSON written to vault.json). Nothing in
 * here is plaintext: [validator] and every value in [secrets] are AES-GCM
 * ciphertext. [saltBase64] is the one field that's *not* secret — a salt
 * never is, see [Vault].
 */
@Serializable
data class VaultFile(
    val saltBase64: String,
    val validator: String,
    val secrets: MutableMap<String, String> = mutableMapOf(),
)

/**
 * Master-password-gated credential store — the direct replacement for
 * RepDev SSO's "RepDev password unlocks your saved AIX passwords" feature
 * (preserved per MODERNIZATION_PLAN.md §3), with the crypto flaws in
 * RepDev_SSO.java fixed (see [Vault]).
 *
 * Secrets are addressed by an opaque string key — callers use something
 * like "sym:1999:password" / "sym:1999:userid" (the Symitar "user ID" field
 * was also encrypted in the original, alongside the AIX password).
 */
class CredentialVault private constructor(
    private val salt: ByteArray,
    private val masterPassword: CharArray,
    private val secrets: MutableMap<String, String>,
) {
    fun getSecret(key: String): String? = secrets[key]?.let { Vault.decrypt(masterPassword, salt, it) }

    fun setSecret(key: String, value: String) {
        secrets[key] = Vault.encrypt(masterPassword, salt, value)
    }

    fun removeSecret(key: String) {
        secrets.remove(key)
    }

    fun toFile(): VaultFile = VaultFile(
        saltBase64 = Base64.getEncoder().encodeToString(salt),
        validator = Vault.encrypt(masterPassword, salt, VALIDATOR_PLAINTEXT),
        secrets = secrets,
    )

    companion object {
        private const val VALIDATOR_PLAINTEXT = "repdev-vault-v1"

        fun create(masterPassword: CharArray): CredentialVault =
            CredentialVault(Vault.newSalt(), masterPassword, mutableMapOf())

        /** @throws Vault.WrongPasswordException if [masterPassword] doesn't unlock [file]. */
        fun open(file: VaultFile, masterPassword: CharArray): CredentialVault {
            val salt = Base64.getDecoder().decode(file.saltBase64)
            Vault.decrypt(masterPassword, salt, file.validator) // throws on mismatch; result unused
            return CredentialVault(salt, masterPassword, file.secrets.toMutableMap())
        }
    }
}
