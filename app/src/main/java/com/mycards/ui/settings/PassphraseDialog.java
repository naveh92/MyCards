package com.mycards.ui.settings;

import android.app.Activity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mycards.R;

/** Asks for the passphrase that protects a backup file. */
public final class PassphraseDialog {

    private PassphraseDialog() {
    }

    /** Short enough to type on a phone, long enough that PBKDF2 is doing real work. */
    private static final int MIN_LENGTH = 8;

    public interface OnPassphrase {
        void onPassphrase(char[] passphrase);
    }

    /** Backing out of the dialog, by the Cancel button or by the back gesture. */
    public interface OnCancel {
        void onCancel();
    }

    public static void show(Activity activity, int titleRes, boolean confirm, OnPassphrase callback) {
        show(activity, titleRes, confirm, callback, null);
    }

    /**
     * @param confirm  true when exporting — a typo in a write-once passphrase would make the
     *                 backup permanently unreadable, so it is entered twice
     * @param onCancel run when the dialog closes without a passphrase. An export has already
     *                 created its file by this point and needs to hear about it, so that an
     *                 empty one is not left behind looking like a backup.
     */
    public static void show(Activity activity, int titleRes, boolean confirm,
                            OnPassphrase callback, OnCancel onCancel) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_passphrase, null);

        TextInputLayout passLayout = view.findViewById(R.id.passphraseLayout);
        TextInputLayout confirmLayout = view.findViewById(R.id.confirmLayout);
        TextInputEditText passInput = view.findViewById(R.id.passphraseInput);
        TextInputEditText confirmInput = view.findViewById(R.id.confirmInput);

        confirmLayout.setVisibility(confirm ? View.VISIBLE : View.GONE);
        if (!confirm) {
            // Restoring only needs the passphrase; a wrong one fails loudly and harmlessly.
            view.findViewById(R.id.passphraseNote).setVisibility(View.GONE);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(confirm
                        ? R.string.backup_export_action
                        : R.string.backup_import_action, null)
                .create();

        // Covers every way out of the dialog at once — Cancel, the back gesture, a tap
        // outside — so no exit path can quietly skip the caller's cleanup.
        boolean[] accepted = {false};
        dialog.setOnDismissListener(d -> {
            if (!accepted[0] && onCancel != null) {
                onCancel.onCancel();
            }
        });

        dialog.show();

        // Wired after show() so a validation failure leaves the dialog open instead of
        // discarding what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pass = value(passInput);

            if (confirm && pass.length() < MIN_LENGTH) {
                passLayout.setError(activity.getString(R.string.backup_passphrase_too_short));
                return;
            }
            if (TextUtils.isEmpty(pass)) {
                passLayout.setError(activity.getString(R.string.backup_passphrase_too_short));
                return;
            }
            passLayout.setError(null);

            if (confirm && !pass.equals(value(confirmInput))) {
                confirmLayout.setError(activity.getString(R.string.backup_passphrase_mismatch));
                return;
            }

            accepted[0] = true;
            dialog.dismiss();
            callback.onPassphrase(pass.toCharArray());
        });
    }

    private static String value(TextInputEditText input) {
        CharSequence cs = input.getText();
        return cs == null ? "" : cs.toString();
    }
}
