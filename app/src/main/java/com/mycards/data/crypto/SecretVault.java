package com.mycards.data.crypto;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts card numbers and CVVs with a key held in the Android Keystore.
 *
 * <p>The key material never leaves the hardware-backed keystore, so the ciphertext in the
 * database is worthless on its own — copying {@code mycards.db} off the device reveals
 * nothing sensitive.
 *
 * <p>Access is gated by the OS rather than by the UI. The secret key is generated with
 * {@code setUserAuthenticationRequired(true)}, so any attempt to use it without a recent
 * unlock throws {@link UserNotAuthenticatedException} from the platform itself — a bug in
 * an activity cannot leak a card number by forgetting to show a prompt. Callers are expected
 * to catch {@link AuthRequiredException}, show a biometric prompt, and retry.
 *
 * <p>If the device has no secure lock screen an auth-bound key cannot exist at all. Rather
 * than refuse to work, the vault falls back to an unbound key and reports it via
 * {@link #isBiometricProtectionAvailable()} so the UI can say so plainly.
 */
public final class SecretVault {

    private static final String KEYSTORE = "AndroidKeyStore";

    /** Auth-bound: guards card numbers, CVVs and card expiry dates. */
    private static final String KEY_SECRET = "mycards_secret_v1";

    /**
     * Not auth-bound: cached data, and the gift link.
     *
     * <p>The gift link is as spendable as a card number and would otherwise belong under the
     * auth-bound key. It cannot go there: {@code BalanceCheckWorker} reads it from the
     * background, where no unlock prompt can be shown, so an auth-bound gift link would
     * disable the balance check entirely. The protection is applied where the link is
     * <em>used</em> instead — opening it from the card screen goes through the same unlock as
     * revealing the card number.
     */
    private static final String KEY_DATA = "mycards_data_v1";

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    /**
     * How long a single unlock authorises the key for. Long enough to reveal a number, copy
     * it and type it in; short enough that a phone left on a counter re-locks quickly.
     */
    private static final int AUTH_VALIDITY_SECONDS = 30;

    private final Context appContext;

    public SecretVault(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Raised when the OS refused the key because the user has not authenticated recently. */
    public static class AuthRequiredException extends Exception {
        public AuthRequiredException(Throwable cause) {
            super("Recent user authentication is required to use this key", cause);
        }
    }

    public static class VaultException extends Exception {
        public VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * True when the device can actually enforce the biometric gate. False means there is no
     * secure lock screen, the sensitive key is unbound, and the UI must not imply otherwise.
     */
    public boolean isBiometricProtectionAvailable() {
        KeyguardManager km = (KeyguardManager) appContext.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    // --- sensitive values: card number, CVV, gift link ---

    public String encryptSecret(String plaintext) throws AuthRequiredException, VaultException {
        return encrypt(plaintext, keyAliasForSecrets());
    }

    public String decryptSecret(String stored) throws AuthRequiredException, VaultException {
        return decrypt(stored, keyAliasForSecrets());
    }

    // --- non-sensitive cached data ---

    public String encryptData(String plaintext) throws VaultException {
        try {
            return encrypt(plaintext, KEY_DATA);
        } catch (AuthRequiredException impossible) {
            // KEY_DATA is never auth-bound, so the platform cannot raise this.
            throw new VaultException("unexpected auth requirement on the data key", impossible);
        }
    }

    public String decryptData(String stored) throws VaultException {
        try {
            return decrypt(stored, KEY_DATA);
        } catch (AuthRequiredException impossible) {
            throw new VaultException("unexpected auth requirement on the data key", impossible);
        }
    }

    private String keyAliasForSecrets() {
        // Without a secure lock screen an auth-bound key cannot be generated.
        return isBiometricProtectionAvailable() ? KEY_SECRET : KEY_DATA;
    }

    // --- implementation ---

    private String encrypt(String plaintext, String alias)
            throws AuthRequiredException, VaultException {
        if (plaintext == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias));

            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend the IV: GCM needs it to decrypt and it is not itself secret.
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);

            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (UserNotAuthenticatedException e) {
            throw new AuthRequiredException(e);
        } catch (Exception e) {
            throw new VaultException("failed to encrypt", e);
        }
    }

    private String decrypt(String stored, String alias)
            throws AuthRequiredException, VaultException {
        if (stored == null) {
            return null;
        }
        try {
            byte[] combined = Base64.decode(stored, Base64.NO_WRAP);
            if (combined.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext too short to contain an IV");
            }

            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            byte[] ct = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, IV_BYTES, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));

            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (UserNotAuthenticatedException e) {
            throw new AuthRequiredException(e);
        } catch (Exception e) {
            throw new VaultException("failed to decrypt", e);
        }
    }

    private SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);

        KeyStore.Entry existing = ks.getEntry(alias, null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);

        if (KEY_SECRET.equals(alias)) {
            builder.setUserAuthenticationRequired(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
            } else {
                //noinspection deprecation
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS);
            }
        }

        generator.init(builder.build());
        return generator.generateKey();
    }
}
