package com.example.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.security.data.SecurityConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityManagerTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var securityManager: SecurityManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("safe_vault_pro_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        securityManager = SecurityManager(context, database.securityDao())
    }

    @After
    fun tearDown() {
        securityManager.close()
        database.close()
    }

    @Test
    fun correctPinUpgradesLegacyHashAndPersistsIt() = runTest {
        val salt = "legacy-salt"
        val legacyHash = MessageDigest.getInstance("SHA-256")
            .digest((salt + "1234").toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        database.securityDao().saveSecurityConfig(
            SecurityConfig(isAppLockEnabled = true, pinHash = legacyHash, salt = salt)
        )

        assertTrue(securityManager.verifyAppPin("1234"))

        val upgraded = database.securityDao().getSecurityConfig()
        assertNotNull(upgraded)
        assertTrue(upgraded!!.pinHash!!.startsWith("pbkdf2:"))
        assertEquals(0, upgraded.failedAttempts)
        assertTrue(securityManager.verifyAppPin("1234"))
    }

    @Test
    fun invalidInputDoesNotBurnAttemptsAndFiveFailuresPersistLockout() = runTest {
        assertTrue(securityManager.setupAppLock("1234", enableBiometric = false, autoLockSeconds = 60))

        assertFalse(securityManager.verifyAppPin("abcd"))
        assertEquals(0, database.securityDao().getSecurityConfig()?.failedAttempts)

        repeat(5) { assertFalse(securityManager.verifyAppPin("9999")) }
        val config = database.securityDao().getSecurityConfig()
        assertEquals(5, config?.failedAttempts)
        assertTrue((config?.lockoutUntilTimestamp ?: 0L) > System.currentTimeMillis())
        assertFalse(securityManager.verifyAppPin("1234"))
    }

    @Test
    fun decoyPinOpensOnlyDecoyAndBlocksProtectedActions() = runTest {
        assertTrue(securityManager.setupAppLock("1234", enableBiometric = false, autoLockSeconds = 60))
        assertTrue(securityManager.setupDecoyPin("4321"))

        assertEquals(VaultAuthResult.DECOY_UNLOCKED, securityManager.verifyVaultOrAppPin("4321"))
        assertFalse(securityManager.canPerformAction(SecurityAction.SHARE))
        assertTrue(securityManager.isDecoyModeActive.value)

        assertEquals(VaultAuthResult.REAL_UNLOCKED, securityManager.verifyVaultOrAppPin("1234"))
        assertFalse(securityManager.isDecoyModeActive.value)
        assertTrue(securityManager.canPerformAction(SecurityAction.SHARE))
    }

    @Test
    fun disablingAppLockRequiresCorrectCurrentPin() = runTest {
        assertTrue(securityManager.setupAppLock("1234", enableBiometric = false, autoLockSeconds = 60))

        assertFalse(securityManager.disableAppLock(""))
        assertFalse(securityManager.disableAppLock("9999"))
        assertTrue(securityManager.disableAppLock("1234"))
        assertFalse(database.securityDao().getSecurityConfig()?.isAppLockEnabled ?: true)
    }

    @Test
    fun parentalControlsRequireAuthorizationToDisable() = runTest {
        assertTrue(
            securityManager.setupParentalControl(
                parentPin = "5678",
                blockDelete = true,
                blockExport = true,
                blockShare = true,
                blockEdit = true,
                blockAddSource = true,
                blockSettings = true
            )
        )
        securityManager.clearParentAuthorization()
        assertFalse(securityManager.canPerformAction(SecurityAction.SHARE))
        assertFalse(securityManager.disableParentalControl("0000"))
        assertTrue(securityManager.disableParentalControl("5678"))
        assertFalse(database.securityDao().getSecurityConfig()?.isParentalControlEnabled ?: true)
    }
}
