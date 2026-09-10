package com.mycards.ui;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;

import com.mycards.R;
import com.mycards.cards.CardFace;
import com.mycards.cards.CardStatus;

/**
 * Turns a {@link CardFace} into the drawable that paints it.
 *
 * <p>Separate from the enum so {@code CardFace} stays free of Android types and can be
 * unit-tested without a device. This half is the part that cannot be, and it is deliberately
 * nothing but a lookup.
 */
public final class CardFaces {

    private CardFaces() {
    }

    /**
     * The face a row should wear.
     *
     * <p>A retired card loses its colour. That is the point of retiring it: the wallet's
     * colours exist to help pick a card you are about to spend, and a card you cannot spend
     * should stop competing for that attention.
     */
    @DrawableRes
    public static int backgroundFor(String cardTypeId, CardStatus status) {
        if (status != null && status.isRetired()) {
            return R.drawable.card_face_retired;
        }
        return backgroundFor(cardTypeId);
    }

    /**
     * The single colour standing for a card type, for places too small to carry a gradient.
     *
     * <p>The start stop of the face, so a marker beside a purchase is recognisably the same
     * colour as the card it was bought with.
     */
    @ColorRes
    public static int accentFor(String cardTypeId) {
        switch (CardFace.of(cardTypeId)) {
            case INDIGO:
                return R.color.face_indigo_start;
            case TEAL:
                return R.color.face_teal_start;
            case PLUM:
                return R.color.face_plum_start;
            case ROSE:
                return R.color.face_rose_start;
            case AMBER:
                return R.color.face_amber_start;
            case FOREST:
                return R.color.face_forest_start;
            case MAGENTA:
                return R.color.face_magenta_start;
            case SLATE:
            default:
                return R.color.face_slate_start;
        }
    }

    /** The card type's own face, whatever state the card is in. */
    @DrawableRes
    public static int backgroundFor(String cardTypeId) {
        switch (CardFace.of(cardTypeId)) {
            case INDIGO:
                return R.drawable.card_face_indigo;
            case TEAL:
                return R.drawable.card_face_teal;
            case PLUM:
                return R.drawable.card_face_plum;
            case ROSE:
                return R.drawable.card_face_rose;
            case AMBER:
                return R.drawable.card_face_amber;
            case FOREST:
                return R.drawable.card_face_forest;
            case MAGENTA:
                return R.drawable.card_face_magenta;
            case SLATE:
            default:
                return R.drawable.card_face_slate;
        }
    }
}
