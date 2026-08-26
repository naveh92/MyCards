package com.mycards.ui.detail;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mycards.R;
import com.mycards.data.db.SpendEntity;
import com.mycards.search.StoreNameIndex;
import com.mycards.ui.Formats;

import java.util.Calendar;
import java.util.List;

/**
 * Collects or edits a purchase.
 *
 * <p>Only the amount is required — that is the one value the balance cannot be derived
 * without. The description is optional, but a blank one falls back to the shop name and then
 * to a generic label, so the log never degrades into an unreadable row of numbers.
 */
public final class AddSpendDialog {

    private AddSpendDialog() {
    }

    /**
     * How many shops to offer at once.
     *
     * <p>Three fits one line on a phone and is about as many as anyone takes in
     * before going back to typing. A longer row would need scrolling to read, which
     * is work, and this is meant to cost nothing to ignore.
     */
    private static final int MAX_SUGGESTIONS = 3;

    public interface OnSpendEntered {
        void onSpend(String title, double amount, String storeName, long spentAt);
    }

    /**
     * New purchase against a card with {@code maxAmount} left on it.
     *
     * @param suggestions shops this card is accepted in, offered while the shop is typed;
     *                    {@link StoreNameIndex#empty()} when there is no list to offer from
     */
    public static void show(Activity activity, String currency, double maxAmount,
                            StoreNameIndex suggestions, OnSpendEntered callback) {
        show(activity, currency, maxAmount, R.string.add_spend,
                null, 0d, null, System.currentTimeMillis(), suggestions, callback);
    }

    /** New purchase with a suggested description and amount, used when reconciling. */
    public static void show(Activity activity, String currency, double maxAmount,
                            String prefillTitle, double prefillAmount,
                            StoreNameIndex suggestions, OnSpendEntered callback) {
        show(activity, currency, maxAmount, R.string.add_spend,
                prefillTitle, prefillAmount, null, System.currentTimeMillis(),
                suggestions, callback);
    }

    /**
     * Edits an existing entry.
     *
     * @param remainingBalance what is left <em>excluding</em> this entry, so raising its
     *                         amount is checked against the balance it would actually leave
     *                         rather than against a total it is itself part of
     */
    public static void showEdit(Activity activity, String currency, double remainingBalance,
                                SpendEntity existing, StoreNameIndex suggestions,
                                OnSpendEntered callback) {
        show(activity, currency, remainingBalance, R.string.edit_spend,
                existing.title, existing.amount, existing.storeName, existing.spentAt,
                suggestions, callback);
    }

    private static void show(Activity activity,
                             String currency,
                             double maxAmount,
                             int titleRes,
                             String prefillTitle,
                             double prefillAmount,
                             String prefillStore,
                             long prefillDate,
                             StoreNameIndex suggestions,
                             OnSpendEntered callback) {

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_spend, null);

        TextInputLayout amountLayout = view.findViewById(R.id.spendAmountLayout);
        TextInputEditText titleInput = view.findViewById(R.id.spendTitleInput);
        TextInputEditText amountInput = view.findViewById(R.id.spendAmountInput);
        TextInputEditText storeInput = view.findViewById(R.id.spendStoreInput);
        TextInputEditText dateInput = view.findViewById(R.id.spendDateInput);

        final long[] spentAt = {prefillDate > 0 ? prefillDate : System.currentTimeMillis()};
        dateInput.setText(Formats.prettyDate(activity, spentAt[0]));

        if (prefillTitle != null) {
            titleInput.setText(prefillTitle);
        }
        if (prefillAmount > 0) {
            amountInput.setText(Formats.plainAmount(prefillAmount));
        }
        if (prefillStore != null) {
            storeInput.setText(prefillStore);
        }

        wireStoreSuggestions(activity, view, storeInput, suggestions);

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
                .setTitle(titleRes)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();

        dialog.show();

        // Wired after show() so a validation failure leaves the dialog open instead of
        // discarding what was typed.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
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
            // Tolerance of one agora, so a balance of exactly 25.00 accepts a 25.00 spend
            // rather than tripping on floating-point representation.
            if (amount > maxAmount + 0.01d) {
                amountLayout.setError(activity.getString(R.string.spend_exceeds_balance,
                        Formats.money(maxAmount, currency)));
                return;
            }
            amountLayout.setError(null);

            // The description is optional, but the log still needs something readable in
            // it months later. Fall back to the shop, then to a generic label, rather than
            // leaving a bare row of numbers.
            String store = text(storeInput);
            String title = text(titleInput);
            if (TextUtils.isEmpty(title)) {
                title = TextUtils.isEmpty(store)
                        ? activity.getString(R.string.spend_untitled)
                        : store;
            }

            callback.onSpend(title, amount, store, spentAt[0]);
            dialog.dismiss();
        });
    }

    /**
     * Offers shops this card is accepted in, quietly, while the shop name is typed.
     *
     * <p>Three rules make it something that can be ignored rather than something that has to
     * be dealt with. It never changes what was typed — the only thing that puts text in the
     * field is a deliberate tap on a chip. It says nothing until there is enough typed to
     * narrow the list down, and nothing at all when no shop matches, so someone entering a
     * shop the issuer has never heard of is never told so. And having offered a shop that
     * was taken, it stops: the row stays down until the next keystroke, instead of following
     * the choice with more of them.
     */
    private static void wireStoreSuggestions(Activity activity,
                                             View root,
                                             TextInputEditText storeInput,
                                             StoreNameIndex suggestions) {
        View row = root.findViewById(R.id.storeSuggestionRow);
        ChipGroup group = root.findViewById(R.id.storeSuggestions);

        if (suggestions == null || suggestions.isEmpty()) {
            // No merchant list for this card. Nothing to offer, and nothing to explain.
            row.setVisibility(View.GONE);
            return;
        }

        // Set by a chip tap and cleared by the render it suppresses, so choosing a shop ends
        // the conversation rather than starting another one.
        final boolean[] justPicked = {false};

        storeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (justPicked[0]) {
                    justPicked[0] = false;
                    return;
                }
                showSuggestions(activity, row, group, storeInput, suggestions, justPicked);
            }
        });
    }

    private static void showSuggestions(Activity activity,
                                        View row,
                                        ChipGroup group,
                                        TextInputEditText storeInput,
                                        StoreNameIndex suggestions,
                                        boolean[] justPicked) {
        List<String> matches = suggestions.suggest(text(storeInput), MAX_SUGGESTIONS);
        group.removeAllViews();
        if (matches.isEmpty()) {
            row.setVisibility(View.GONE);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (String name : matches) {
            Chip chip = (Chip) inflater.inflate(R.layout.item_suggestion_chip, group, false);
            chip.setText(name);
            chip.setOnClickListener(v -> {
                justPicked[0] = true;
                storeInput.setText(name);
                storeInput.setSelection(name.length());
                row.setVisibility(View.GONE);
            });
            group.addView(chip);
        }
        row.setVisibility(View.VISIBLE);
        // A narrowed list is a new list; leaving it scrolled hides the best match off-screen.
        row.scrollTo(0, 0);
    }

    private static String text(TextInputEditText input) {
        CharSequence cs = input.getText();
        return cs == null ? "" : cs.toString().trim();
    }
}
