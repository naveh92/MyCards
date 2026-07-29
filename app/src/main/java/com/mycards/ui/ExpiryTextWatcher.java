package com.mycards.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

/**
 * Turns typing into {@code MM/YY} as you go, and mirrors the value into a partner field
 * until that partner is edited by hand.
 *
 * <p>A gift card's own expiry and the expiry printed on the payment card behind it are
 * almost always the same date. Making the user enter it twice is pointless friction, but
 * they genuinely can differ — so the mirror stops the moment the user touches the other
 * field themselves.
 */
public final class ExpiryTextWatcher implements TextWatcher {

    /** Set while this watcher is writing, so mirrored edits are not mistaken for typing. */
    private boolean selfUpdating;

    private final EditText field;
    private ExpiryTextWatcher partner;

    /** True once the user has typed into this field directly. */
    private boolean editedByUser;

    public ExpiryTextWatcher(EditText field) {
        this.field = field;
    }

    /** Links two expiry fields so each mirrors into the other until manually edited. */
    public static void link(ExpiryTextWatcher a, ExpiryTextWatcher b) {
        a.partner = b;
        b.partner = a;
    }

    /** Marks the field as user-owned, e.g. when populating a saved card. */
    public void markEditedByUser() {
        editedByUser = true;
    }

    public boolean isEditedByUser() {
        return editedByUser;
    }

    /** Writes a value without counting it as a manual edit. */
    public void setSilently(String text) {
        selfUpdating = true;
        field.setText(text);
        selfUpdating = false;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (selfUpdating) {
            return;
        }

        String digits = s.toString().replaceAll("[^0-9]", "");
        if (digits.length() > 4) {
            digits = digits.substring(0, 4);
        }

        // A leading digit above 1 can only be a single-digit month, so "3" becomes "03/"
        // and the user never has to type the zero or the slash.
        if (digits.length() == 1 && digits.charAt(0) > '1') {
            digits = "0" + digits;
        }

        String formatted = digits.length() > 2
                ? digits.substring(0, 2) + "/" + digits.substring(2)
                : digits;

        if (!formatted.equals(s.toString())) {
            selfUpdating = true;
            field.setText(formatted);
            field.setSelection(formatted.length());
            selfUpdating = false;
        }

        editedByUser = true;

        // Keep the partner in step, but never overwrite something typed there on purpose.
        if (partner != null && !partner.editedByUser) {
            partner.setSilently(formatted);
        }
    }
}
