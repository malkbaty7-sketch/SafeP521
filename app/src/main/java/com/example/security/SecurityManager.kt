package com.example.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import com.example.security.data.ProtectedItem
import com.example.security.data.SecurityAuditLog
import com.example.security.data.SecurityConfig
import com.example.security.data.SecurityDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec


enum class SecurityAction {
    PLAY,
    EDIT,
    EXPORT,
    DELETE,
    SHARE,
    ADD_SOURCE,
    MODIFY_SETTINGS
}

enum class VaultAuthResult {
    REAL_UNLOCKED,
    DECOY_UNLOCKED,
    INVALID
}

class SecurityManager(
    private val context: Context,
    private val securityDao: SecurityDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authMutex = Mutex()
    private val vaultPrefs: SharedPreferences =
        context.getSharedPreferences("safe_vault_pro_prefs", Context.MODE_PRIVATE)

    val securityConfig: Flow<SecurityConfig?> = securityDao.getSecurityConfigFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _isParentAuthorized = MutableStateFlow(false)
    val isParentAuthorized: StateFlow<Boolean> = _isParentAuthorized.asStateFlow()

    private val _isVaultUnlocked = MutableStateFlow(false)
    val isVaultUnlocked: StateFlow<Boolean> = _isVaultUnlocked.asStateFlow()

    private val _isDecoyModeActive = MutableStateFlow(false)
    val isDecoyModeActive: StateFlow<Boolean> = _isDecoyModeActive.asStateFlow()

    private val _isDecoyConfigured = MutableStateFlow(
        vaultPrefs.getString("decoy_pin_hash", null) != null
    )
    val isDecoyConfigured: StateFlow<Boolean> = _isDecoyConfigured.asStateFlow()

    @Volatile
    private var lastActivityTime = System.currentTimeMillis()

    @Volatile
    private var failedAttempts = 0

    @Volatile
    private var lockoutUntilTimestamp = 0L

    val protectedMediaIds: Flow<Set<Int>> = securityDao.getProtectedItemIdsFlow("MEDIA")
        .map { it.toSet() }

    val protectedProjectIds: Flow<Set<Int>> = securityDao.getProtectedItemIdsFlow("PROJECT")
        .map { it.toSet() }

    val protectedPlaylistIds: Flow<Set<Int>> = securityDao.getProtectedItemIdsFlow("PLAYLIST")
        .map { it.toSet() }

    init {
        scope.launch {
            if (securityDao.getSecurityConfig() == null) {
                securityDao.insertDefaultSecurityConfig(SecurityConfig())
            }
            securityDao.getSecurityConfig()?.let { config ->
                failedAttempts = config.failedAttempts
                lockoutUntilTimestamp = config.lockoutUntilTimestamp
                if (!config.isAppLockEnabled) {
                    _isUnlocked.value = true
                    _isVaultUnlocked.value = true
                }
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    fun recordUserActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    suspend fun checkAutoLock() {
        val config = securityDao.getSecurityConfig() ?: return
        if (!config.isAppLockEnabled || !_isUnlocked.value) return
        val timeoutMs = config.autoLockTimeoutSeconds.coerceAtLeast(0) * 1000L
        if (System.currentTimeMillis() - lastActivityTime >= timeoutMs) {
            lockApp("Auto-lock timeout reached")
        }
    }

    fun lockApp(reason: String = "Manual lock") {
        _isUnlocked.value = false
        _isParentAuthorized.value = false
        _isVaultUnlocked.value = false
        _isDecoyModeActive.value = false
        scope.launch { logAudit("APP_LOCKED", "App locked: $reason") }
    }

    fun lockVault() {
        _isVaultUnlocked.value = false
        _isDecoyModeActive.value = false
    }

    suspend fun setupAppLock(
        pin: String,
        enableBiometric: Boolean,
        autoLockSeconds: Int,
        currentPin: String? = null
    ): Boolean {
        if (!isValidPin(pin) || !canPerformAction(SecurityAction.MODIFY_SETTINGS)) return false
        val current = securityDao.getSecurityConfig() ?: SecurityConfig()
        if (current.isAppLockEnabled && current.pinHash != null) {
            if (currentPin.isNullOrBlank() || !verifyAppPin(currentPin)) return false
        }

        val decoyHash = vaultPrefs.getString("decoy_pin_hash", null)
        val decoySalt = vaultPrefs.getString("decoy_salt", null)
        if (decoyHash != null && decoySalt != null && verifyStoredPin(pin, decoySalt, decoyHash)) {
            return false
        }

        val salt = generateSalt()
        securityDao.saveSecurityConfig(
            current.copy(
                isAppLockEnabled = true,
                pinHash = hashPin(pin, salt),
                salt = salt,
                isBiometricEnabled = enableBiometric && hasBiometricEnrolled(),
                autoLockTimeoutSeconds = autoLockSeconds.coerceIn(0, 3600),
                failedAttempts = 0,
                lockoutUntilTimestamp = 0L
            )
        )
        failedAttempts = 0
        lockoutUntilTimestamp = 0L
        _isUnlocked.value = true
        _isVaultUnlocked.value = true
        _isDecoyModeActive.value = false
        recordUserActivity()
        logAudit("SETTINGS_CHANGED", "App & Vault lock enabled with PIN")
        return true
    }

    suspend fun setupDecoyPin(decoyPin: String?): Boolean {
        if (!canPerformAction(SecurityAction.MODIFY_SETTINGS)) return false
        val config = securityDao.getSecurityConfig() ?: return false
        if (!config.isAppLockEnabled || config.pinHash == null || config.salt == null) return false

        if (decoyPin.isNullOrBlank()) {
            vaultPrefs.edit().remove("decoy_pin_hash").remove("decoy_salt").apply()
            _isDecoyConfigured.value = false
            logAudit("SETTINGS_CHANGED", "Decoy PIN removed")
            return true
        }
        if (!isValidPin(decoyPin)) return false
        if (verifyStoredPin(decoyPin, config.salt, config.pinHash)) return false

        val salt = generateSalt()
        val hash = hashPin(decoyPin, salt)
        vaultPrefs.edit()
            .putString("decoy_pin_hash", hash)
            .putString("decoy_salt", salt)
            .apply()
        _isDecoyConfigured.value = true
        logAudit("SETTINGS_CHANGED", "Decoy PIN configuration updated")
        return true
    }

    suspend fun verifyVaultOrAppPin(enteredPin: String): VaultAuthResult =
        verifyPin(enteredPin, allowDecoy = true)

    private suspend fun verifyPin(enteredPin: String, allowDecoy: Boolean): VaultAuthResult =
        authMutex.withLock {
            val config = securityDao.getSecurityConfig()
            if (config == null || !config.isAppLockEnabled || config.pinHash == null || config.salt == null) {
                _isUnlocked.value = true
                _isVaultUnlocked.value = true
                _isDecoyModeActive.value = false
                recordUserActivity()
                return@withLock VaultAuthResult.REAL_UNLOCKED
            }

            if (!isValidPin(enteredPin)) return@withLock VaultAuthResult.INVALID
            val until = maxOf(lockoutUntilTimestamp, config.lockoutUntilTimestamp)
            if (System.currentTimeMillis() < until) return@withLock VaultAuthResult.INVALID

            if (verifyStoredPin(enteredPin, config.salt, config.pinHash)) {
                val upgraded = upgradeLegacyHash(config, enteredPin, config.pinHash)
                resetFailedAttempts(upgraded)
                _isUnlocked.value = true
                _isVaultUnlocked.value = true
                _isDecoyModeActive.value = false
                recordUserActivity()
                logAudit("VAULT_UNLOCKED", "Real vault unlocked via PIN")
                return@withLock VaultAuthResult.REAL_UNLOCKED
            }

            if (allowDecoy) {
                val decoyHash = vaultPrefs.getString("decoy_pin_hash", null)
                val decoySalt = vaultPrefs.getString("decoy_salt", null)
                if (decoyHash != null && decoySalt != null &&
                    verifyStoredPin(enteredPin, decoySalt, decoyHash)
                ) {
                    if (!isModernHash(decoyHash)) {
                        val newSalt = generateSalt()
                        vaultPrefs.edit()
                            .putString("decoy_pin_hash", hashPin(enteredPin, newSalt))
                            .putString("decoy_salt", newSalt)
                            .apply()
                    }
                    resetFailedAttempts(config)
                    _isUnlocked.value = true
                    _isVaultUnlocked.value = true
                    _isDecoyModeActive.value = true
                    recordUserActivity()
                    logAudit("DECOY_VAULT_OPENED", "Decoy mode activated")
                    return@withLock VaultAuthResult.DECOY_UNLOCKED
                }
            }

            recordFailedAttempt(config)
            VaultAuthResult.INVALID
        }

    suspend fun verifyAppPin(enteredPin: String): Boolean =
        verifyPin(enteredPin, allowDecoy = false) == VaultAuthResult.REAL_UNLOCKED

    suspend fun disableAppLock(currentPin: String): Boolean {
        if (currentPin.isBlank() || !canPerformAction(SecurityAction.MODIFY_SETTINGS) || !verifyAppPin(currentPin)) return false
        val current = securityDao.getSecurityConfig() ?: return false
        securityDao.saveSecurityConfig(
            current.copy(
                isAppLockEnabled = false,
                pinHash = null,
                salt = null,
                isBiometricEnabled = false
            )
        )
        _isUnlocked.value = true
        _isVaultUnlocked.value = true
        _isDecoyModeActive.value = false
        logAudit("SETTINGS_CHANGED", "App and vault lock disabled after PIN verification")
        return true
    }

    suspend fun setBiometricEnabled(enabled: Boolean): Boolean {
        if (!canPerformAction(SecurityAction.MODIFY_SETTINGS)) return false
        val current = securityDao.getSecurityConfig() ?: return false
        if (!current.isAppLockEnabled) return false
        if (enabled && !hasBiometricEnrolled()) return false
        securityDao.saveSecurityConfig(current.copy(isBiometricEnabled = enabled))
        logAudit("SETTINGS_CHANGED", "Biometric unlock set to $enabled")
        return true
    }

    fun getLockoutRemainingSeconds(): Long {
        val remaining = lockoutUntilTimestamp - System.currentTimeMillis()
        return if (remaining > 0) (remaining + 999) / 1000 else 0L
    }

    /** Call only after AndroidX BiometricPrompt reports onAuthenticationSucceeded. */
    fun onBiometricAuthSucceeded() {
        scope.launch {
            authMutex.withLock {
                val config = securityDao.getSecurityConfig() ?: return@withLock
                if (!config.isAppLockEnabled || !config.isBiometricEnabled) return@withLock

                resetFailedAttempts(config)
                _isUnlocked.value = true
                _isVaultUnlocked.value = true
                _isDecoyModeActive.value = false
                recordUserActivity()
                logAudit("APP_UNLOCKED", "BiometricPrompt authentication succeeded")
            }
        }
    }

    suspend fun setupParentalControl(
        parentPin: String,
        blockDelete: Boolean,
        blockExport: Boolean,
        blockShare: Boolean,
        blockEdit: Boolean,
        blockAddSource: Boolean,
        blockSettings: Boolean
    ): Boolean {
        if (!isValidPin(parentPin) || !canPerformAction(SecurityAction.MODIFY_SETTINGS)) return false
        val current = securityDao.getSecurityConfig() ?: SecurityConfig()
        if (current.isParentalControlEnabled && !_isParentAuthorized.value) return false
        if (current.pinHash != null && current.salt != null &&
            verifyStoredPin(parentPin, current.salt, current.pinHash)
        ) return false
        val decoyHash = vaultPrefs.getString("decoy_pin_hash", null)
        val decoySalt = vaultPrefs.getString("decoy_salt", null)
        if (decoyHash != null && decoySalt != null && verifyStoredPin(parentPin, decoySalt, decoyHash)) {
            return false
        }

        val salt = generateSalt()
        val hash = hashPin(parentPin, salt)
        securityDao.saveSecurityConfig(
            current.copy(
                isParentalControlEnabled = true,
                parentPinHash = hash,
                parentPinSalt = salt,
                userRole = "RESTRICTED",
                blockDelete = blockDelete,
                blockExport = blockExport,
                blockShare = blockShare,
                blockEdit = blockEdit,
                blockAddSource = blockAddSource,
                blockSettings = blockSettings
            )
        )
        _isParentAuthorized.value = true
        logAudit("PARENTAL_CONTROL_CHANGED", "Parental controls configured")
        return true
    }

    suspend fun disableParentalControl(parentPin: String): Boolean {
        val current = securityDao.getSecurityConfig() ?: return false
        if (!current.isParentalControlEnabled || !verifyParentPin(parentPin)) return false
        securityDao.saveSecurityConfig(
            current.copy(isParentalControlEnabled = false, userRole = "OWNER")
        )
        _isParentAuthorized.value = false
        logAudit("PARENTAL_CONTROL_CHANGED", "Parental controls disabled after parent PIN verification")
        return true
    }

    suspend fun verifyParentPin(enteredPin: String): Boolean = authMutex.withLock {
        if (!isValidPin(enteredPin)) return@withLock false
        val config = securityDao.getSecurityConfig() ?: return@withLock false
        if (!config.isParentalControlEnabled || config.parentPinHash == null || config.parentPinSalt == null) {
            return@withLock false
        }

        val until = maxOf(lockoutUntilTimestamp, config.lockoutUntilTimestamp)
        if (System.currentTimeMillis() < until) {
            logAudit("FAILED_UNLOCK", "Parent authorization attempted during lockout cooldown")
            return@withLock false
        }

        if (verifyStoredPin(enteredPin, config.parentPinSalt, config.parentPinHash)) {
            val upgraded = if (isModernHash(config.parentPinHash)) config else {
                val salt = generateSalt()
                config.copy(parentPinSalt = salt, parentPinHash = hashPin(enteredPin, salt))
            }
            resetFailedAttempts(upgraded)
            _isParentAuthorized.value = true
            recordUserActivity()
            logAudit("APP_UNLOCKED", "Parent authorization verified")
            true
        } else {
            recordFailedAttempt(config)
            logAudit("FAILED_UNLOCK", "Parent authorization failed")
            false
        }
    }

    fun clearParentAuthorization() {
        _isParentAuthorized.value = false
    }

    suspend fun canPerformAction(action: SecurityAction): Boolean {
        if (_isDecoyModeActive.value) return false
        val config = securityDao.getSecurityConfig() ?: return true
        if (config.isAppLockEnabled && !_isUnlocked.value) return false
        if (!config.isParentalControlEnabled || _isParentAuthorized.value) return true
        val isBlocked = when (action) {
            SecurityAction.DELETE -> config.blockDelete
            SecurityAction.EXPORT -> config.blockExport
            SecurityAction.SHARE -> config.blockShare
            SecurityAction.EDIT -> config.blockEdit
            SecurityAction.ADD_SOURCE -> config.blockAddSource
            SecurityAction.MODIFY_SETTINGS -> config.blockSettings
            SecurityAction.PLAY -> false
        }
        if (isBlocked) logAudit("ACTION_BLOCKED", "Blocked action: ${action.name} by Parental Controls")
        return !isBlocked
    }

    suspend fun toggleItemProtection(itemType: String, itemId: Int): Boolean {
        if (_isDecoyModeActive.value || !canPerformAction(SecurityAction.MODIFY_SETTINGS)) return false
        val isProtected = securityDao.isItemProtected(itemType, itemId)
        val config = securityDao.getSecurityConfig()
        if (isProtected && config?.isAppLockEnabled == true && !_isVaultUnlocked.value) return false

        return if (isProtected) {
            securityDao.removeProtection(itemType, itemId)
            logAudit("VAULT_ITEM_REMOVED", "Removed protection for $itemType $itemId")
            false
        } else {
            securityDao.protectItem(ProtectedItem(itemType, itemId))
            logAudit("VAULT_ITEM_ADDED", "Added protection for $itemType $itemId")
            true
        }
    }

    suspend fun isMediaProtected(mediaId: Int): Boolean =
        securityDao.isItemProtected("MEDIA", mediaId)

    suspend fun canAccessMedia(mediaId: Int): Boolean {
        if (_isDecoyModeActive.value) return false
        if (!securityDao.isItemProtected("MEDIA", mediaId)) return true
        val config = securityDao.getSecurityConfig() ?: return false
        return !config.isAppLockEnabled || (_isUnlocked.value && _isVaultUnlocked.value)
    }

    suspend fun setScreenshotProtection(enabled: Boolean): Boolean {
        if (!canPerformAction(SecurityAction.MODIFY_SETTINGS)) return false
        val current = securityDao.getSecurityConfig() ?: SecurityConfig()
        securityDao.saveSecurityConfig(current.copy(isScreenshotProtectionEnabled = enabled))
        logAudit("SETTINGS_CHANGED", "Screenshot protection set to $enabled")
        return true
    }

    suspend fun logAudit(eventType: String, details: String) {
        val config = securityDao.getSecurityConfig()
        securityDao.insertAuditLog(
            SecurityAuditLog(
                eventType = eventType,
                timestamp = System.currentTimeMillis(),
                details = details,
                userRole = if (_isParentAuthorized.value) "PARENT" else (config?.userRole ?: "OWNER")
            )
        )
    }

    private suspend fun recordFailedAttempt(config: SecurityConfig) {
        failedAttempts = maxOf(failedAttempts, config.failedAttempts) + 1
        lockoutUntilTimestamp = if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            System.currentTimeMillis() + LOCKOUT_DURATION_MS
        } else {
            config.lockoutUntilTimestamp
        }
        securityDao.saveSecurityConfig(
            config.copy(
                failedAttempts = failedAttempts,
                lockoutUntilTimestamp = lockoutUntilTimestamp
            )
        )
        if (failedAttempts == MAX_FAILED_ATTEMPTS) {
            logAudit("FAILED_UNLOCK", "Maximum failed PIN attempts reached; cooldown activated")
        }
    }

    private suspend fun resetFailedAttempts(config: SecurityConfig) {
        failedAttempts = 0
        lockoutUntilTimestamp = 0L
        // Always persist: successful authentication can also be migrating a legacy PIN hash.
        securityDao.saveSecurityConfig(
            config.copy(failedAttempts = 0, lockoutUntilTimestamp = 0L)
        )
    }

    private fun isValidPin(pin: String): Boolean =
        pin.length in 4..6 && pin.all { it in '0'..'9' }

    private fun hasBiometricEnrolled(): Boolean = runCatching {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    private fun generateSalt(): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return toHex(salt)
    }

    private suspend fun hashPin(pin: String, salt: String): String = withContext(Dispatchers.Default) {
        val algorithm = availablePbkdf2Algorithm()
        val iterations = PBKDF2_ITERATIONS
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(StandardCharsets.UTF_8), iterations, HASH_BITS)
        try {
            val derived = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
            "${HASH_PREFIX}:$algorithm:$iterations:${toHex(derived)}"
        } finally {
            spec.clearPassword()
        }
    }

    private suspend fun verifyStoredPin(pin: String, salt: String, storedHash: String): Boolean =
        withContext(Dispatchers.Default) {
            if (!isModernHash(storedHash)) {
                val legacy = MessageDigest.getInstance("SHA-256")
                    .digest((salt + pin).toByteArray(StandardCharsets.UTF_8))
                return@withContext MessageDigest.isEqual(toHex(legacy).toByteArray(), storedHash.toByteArray())
            }

            val pieces = storedHash.split(':')
            if (pieces.size != 4 || pieces[0] != HASH_PREFIX) return@withContext false
            val iterations = pieces[2].toIntOrNull()?.takeIf { it in 1..MAX_HASH_ITERATIONS }
                ?: return@withContext false
            val expected = fromHex(pieces[3]) ?: return@withContext false
            val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(StandardCharsets.UTF_8), iterations, expected.size * 8)
            try {
                val actual = SecretKeyFactory.getInstance(pieces[1]).generateSecret(spec).encoded
                MessageDigest.isEqual(actual, expected)
            } catch (_: Exception) {
                false
            } finally {
                spec.clearPassword()
            }
        }

    private suspend fun upgradeLegacyHash(
        config: SecurityConfig,
        pin: String,
        storedHash: String
    ): SecurityConfig {
        if (isModernHash(storedHash)) return config
        val newSalt = generateSalt()
        return config.copy(salt = newSalt, pinHash = hashPin(pin, newSalt))
    }

    private fun isModernHash(value: String): Boolean = value.startsWith("$HASH_PREFIX:")

    private fun availablePbkdf2Algorithm(): String = try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        "PBKDF2WithHmacSHA256"
    } catch (_: Exception) {
        "PBKDF2WithHmacSHA1"
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun fromHex(value: String): ByteArray? {
        if (value.length % 2 != 0 || value.any { it.digitToIntOrNull(16) == null }) return null
        return runCatching {
            ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    companion object {
        private const val HASH_PREFIX = "pbkdf2"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val MAX_HASH_ITERATIONS = 1_000_000
        private const val HASH_BITS = 256
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L
    }
}
