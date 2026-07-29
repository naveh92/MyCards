package com.mycards.ui.detail;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mycards.R;
import com.mycards.ui.Formats;

import java.util.Calendar;

/**
 * Collects a purchase.
 *
 * <p>The description is mandatory. An untitled row of numbers tells you nothing three months
 * later, and this log is the only record of where a gift card's money actually went.
 */
public final class AddSpendDialog {

    private AddSpendDialog() {
    }

    public interface OnSpendEntered {
        void onSpend(String title, double amount, String storeName, long spentAt);
    }

    public static void show(Activity activity, String currency, OnSpendEntered callback) {
        show(activity, currency, null, 0d, callback);
    }

    /**
     * @param prefillTitle  suggested description, used by the reconciliation flow
     * @param prefillAmount pre-filled amount; 0 leaves the field empty
     */
    public static void show(Activity activity,
                            String currency,
                            String prefillTitle,
                            double prefillAmount,
                            OnSpendEntered callback) {

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_spend, null);

        TextInputLayout titleLayout = view.findViewById(R.id.titleLayout);
        TextInputLayout amountLayout = view.findViewById(R.id.spendAmountLayout);
        TextInputEditText titleInput = view.findViewById(R.id.spendTitleInput);
        TextInputEditText amountInput = view.findViewById(R.id.spendAmountInput);
        TextInputEditText storeInput = view.findViewById(R.id.spendStoreInput);
        TextInputEditText dateInput = view.findViewById(R.id.spendDateInput);

        final long[] spentAt = {System.currentTimeMillis()};
        dateInput.setText(Formats.prettyDate(activity, spentAt[0]));

        if (prefillTitle != null) {
            titleInput.setText(prefillTitle);
        }
        if (prefillAmount > 0) {
            amountInput.setText(String.valueOf(prefillAmount));
        }

        dateInput.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(spentAt[0]);
            new DatePickerDialog(activity, (picker, year, month, day) -> {
                Calendar picked = Calendar.getInstance();
                picked.set(year, month, day, 12, 0, 0);
                spentAt[0] = picked.getTimeInMillis();
                dateInput.setText(Formats.prettyDate(activity, spentAt[0]));
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)).show();
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.add_spend)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.show();

        // Wired after show() so a validation failure does not dismiss the dialog and
        // discard everything the user just typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = text(titleInput);
            if (TextUtils.isEmpty(title)) {
                titleLayout.setError(activity.getString(R.string.spend_required_title));
                return;
            }
            titleLayout.setError(null);

            double amount;
            try {
                amount = Double.parseDouble(text(amountInput));
            } catch (NumberFormatException e) {
                amountLayout.setError(activity.getString(R.string.spend_required_amount));
                return;
            }
            if (amount <= 0) {
                amountLayout.setError(activity.getString(R.string.spend_required_amount));
                return;
            }
            amountLayout.setError(null);

            callback.onSpend(title, amount, text(storeInput), spentAt[0]);
            dialog.dismiss();
        });
    }

    private static String text(TextInputEditText input) {
        CharSequence cs = input.getText();
        return cs == null ? "" : cs.toString().trim();
    }
}
