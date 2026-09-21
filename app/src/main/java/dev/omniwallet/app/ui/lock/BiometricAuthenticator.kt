package dev.omniwallet.app.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Wraps [BiometricPrompt] so the lock screen does not have to know about it.
 *
 * Accepts a device credential as well as a biometric. Refusing to unlock for
 * someone who can already unlock the phone would be security theatre, and it
 * would exclude anyone whose fingerprint reader has given up.
 */
object BiometricAuthenticator {

    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Whether this device can authenticate the user at all. */
    fun isAvailable(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(ALLOWED) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isAvailable(activity)) {
            // No screen lock configured. Refusing entry would strand the user
            // out of their own library with no way back in, so say why and let
            // them through; the setting cannot be meaningfully on either.
            onSuccess()
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // A cancel is the user changing their mind, not a failure
                    // worth shouting about.
                    if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                        code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        onError(message.toString())
                    }
                }
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock OmniWallet")
                .setSubtitle("Your credential library is locked")
                .setAllowedAuthenticators(ALLOWED)
                .build(),
        )
    }
}
