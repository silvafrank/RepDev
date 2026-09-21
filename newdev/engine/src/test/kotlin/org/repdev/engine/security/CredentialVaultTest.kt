package org.repdev.engine.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CredentialVaultTest {
    @Test
    fun `round-trips a secret through save and reopen with the correct password`() {
        val vault = CredentialVault.create("correct horse battery staple".toCharArray())
        vault.setSecret("sym:1999:password", "hunter2")

        val reopened = CredentialVault.open(vault.toFile(), "correct horse battery staple".toCharArray())
        assertEquals("hunter2", reopened.getSecret("sym:1999:password"))
        assertNull(reopened.getSecret("sym:1999:userid"))
    }

    @Test
    fun `wrong master password is rejected, not silently decrypted to garbage`() {
        val vault = CredentialVault.create("right-password".toCharArray())
        vault.setSecret("k", "v")

        assertFailsWith<Vault.WrongPasswordException> {
            CredentialVault.open(vault.toFile(), "wrong-password".toCharArray())
        }
    }

    @Test
    fun `tampered ciphertext is rejected instead of decrypting to wrong plaintext`() {
        val salt = Vault.newSalt()
        val password = "pw".toCharArray()
        val encrypted = Vault.encrypt(password, salt, "hunter2")
        val tampered = encrypted.dropLast(4) + "AAAA" // flips trailing ciphertext/tag bytes

        assertFailsWith<Vault.WrongPasswordException> {
            Vault.decrypt(password, salt, tampered)
        }
    }
}
