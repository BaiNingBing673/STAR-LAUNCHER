package com.star.launcher.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.star.launcher.core.AetherSettingsRepository
import com.star.launcher.data.db.AetherDatabase
import com.star.launcher.data.security.InMemoryEncryptedTokenVault
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AetherDatabase
    private lateinit var accountsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountsDir = tempFolder.newFolder("accounts")
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `discovers existing upstream account metadata mirrors secrets to vault and selects cleanly`() = runTest {
        val msaJson = JSONObject().apply {
            put("username", "AetherPilot")
            put("profileId", "12345678-1234-1234-1234-1234567890ab")
            put("isMicrosoft", true)
            put("accessToken", "super-secret-mc-jwt")
            put("msaRefreshToken", "super-secret-refresh-token")
            put("clientToken", "0")
        }
        val upstreamFile = File(accountsDir, "AetherPilot.json")
        upstreamFile.writeText(msaJson.toString())
        val originalJsonText = upstreamFile.readText()

        val vault = InMemoryEncryptedTokenVault()
        val settingsRepo = AetherSettingsRepository(db.settingsDao()) { 100L }
        val repo = AccountRepository(context, vault, settingsRepo) { accountsDir }

        val initial = repo.listAccounts()
        assertEquals(1, initial.size)
        assertTrue(initial.first().isMicrosoft)
        assertFalse(initial.first().hasVaultBackup)

        assertTrue(repo.mirrorSecretsToVault("AetherPilot"))
        // Upstream JSON remains completely untouched
        assertEquals(originalJsonText, upstreamFile.readText())
        assertEquals("super-secret-mc-jwt", vault.getSecret(AccountRepository.vaultAccessKey("AetherPilot")))

        repo.selectAccount("AetherPilot")
        val updated = repo.listAccounts()
        assertTrue(updated.first().isSelected)
        assertTrue(updated.first().hasVaultBackup)

        // Room settings hold only active username, never tokens
        assertEquals("AetherPilot", settingsRepo.getString(AccountRepository.SETTING_ACTIVE_ACCOUNT))
        assertEquals(null, db.settingsDao().get(AccountRepository.vaultAccessKey("AetherPilot")))
    }

    @Test
    fun `creates sanitized offline account and selects it`() = runTest {
        val vault = InMemoryEncryptedTokenVault()
        val settingsRepo = AetherSettingsRepository(db.settingsDao()) { 100L }
        val repo = AccountRepository(context, vault, settingsRepo) { accountsDir }

        val created = repo.createOfflineAccount("  Builder Zero! ")
        assertEquals("Builder_Zero_", created.username)
        assertTrue(created.isLocal)
        assertTrue(File(accountsDir, "Builder_Zero_.json").isFile)
        assertEquals("Builder_Zero_", repo.resolveSelectedAccountName())
    }
}
