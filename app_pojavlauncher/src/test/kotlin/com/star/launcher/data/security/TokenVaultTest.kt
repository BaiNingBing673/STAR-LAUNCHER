package com.star.launcher.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenVaultTest {

    @Test
    fun `vault encrypts secret at rest and round-trips accurately`() {
        val vault = InMemoryEncryptedTokenVault()
        val secretToken = "eyJ.secret_msa_access_token_value_12345"

        vault.putSecret("msa_account_01", secretToken)

        assertTrue(vault.containsSecret("msa_account_01"))
        assertEquals(secretToken, vault.getSecret("msa_account_01"))

        val cipherBytes = vault.rawCiphertextBytes("msa_account_01")
        assertNotNull(cipherBytes)
        val rawAsString = String(cipherBytes!!, Charsets.ISO_8859_1)
        assertFalse(rawAsString.contains("secret_msa_access_token_value"))

        vault.deleteSecret("msa_account_01")
        assertFalse(vault.containsSecret("msa_account_01"))
        assertNull(vault.getSecret("msa_account_01"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank alias is rejected`() {
        val vault = InMemoryEncryptedTokenVault()
        vault.putSecret("   ", "token")
    }
}
