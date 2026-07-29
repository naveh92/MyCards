package com.mycards.ui;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

/**
 * Shows the system unlock prompt before a sensitive value is decrypted.
 *
 * <p>This prompt is the user-facing half of the gate; the enforcing half lives in the
 * Keystore, which refuses the key outright without a recent unlock. Skipping this call
 * therefore fails safe — the decryption throws rather than quietly succeeding.
 */
public final class BiometricGate {

    private BiometricGate() {
    }

    public interface Callback {
        void onSuccess();

        void onFailure();
    }

    /** Biometrics, with device PIN/pattern/password accepted as a fallback. */
    private static final int ALLOWED =
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    public static boolean canAuthenticate(FragmentActivity activity) {
        return BiometricManager.from(activity).canAuthenticate(ALLOWED)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static void authenticate(FragmentActivity activity,
                                    String title,
                                    String subtitle,
                                    Callback callback) {
        if (!canAuthenticate(activity)) {
            callback.onFailure();
            return;
        }

        Executor executor = androidx.core.content.ContextCompat.getMainExecutor(activity);
        BiometricPrompt prompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        callback.onFailure();
                    }

                    // A single bad fingerprint read is not a failure; the prompt retries
                    // on its own and only reports through onAuthenticationError when done.
                });

        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(ALLOWED)
                .build());
    }
}
