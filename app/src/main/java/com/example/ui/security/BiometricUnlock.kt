package com.example.ui.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

fun isBiometricUnlockAvailable(context: Context): Boolean = runCatching {
    BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
}.getOrDefault(false)

fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context = this
    while (true) {
        if (current is FragmentActivity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
}

fun requestBiometricUnlock(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onAuthenticated: () -> Unit,
    onError: (String) -> Unit
) {
    val availability = BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
        val message = when (availability) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "لا يتوفر مستشعر بصمة أو قياسات حيوية على هذا الجهاز"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "المستشعر الحيوي غير متاح حالياً"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "أضف بصمة أو وسيلة حيوية من إعدادات الجهاز أولاً"
            else -> "تعذر استخدام المصادقة الحيوية على هذا الجهاز"
        }
        onError(message)
        return
    }

    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onAuthenticated()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    errorCode != BiometricPrompt.ERROR_CANCELED
                ) {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                onError("لم يتم التعرّف على البصمة، حاول مرة أخرى")
            }
        }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .setNegativeButtonText("إلغاء")
        .build()
    prompt.authenticate(promptInfo)
}
