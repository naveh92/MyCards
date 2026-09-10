package com.mycards.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.mycards.R;
import com.mycards.cards.CardFace;
import com.mycards.cards.CardStatus;
import com.mycards.cards.FaceGradient;

/**
 * Turns a {@link CardFace} — or a colour the user picked — into the drawable that paints it.
 *
 * <p>Separate from the enum so {@code CardFace} stays free of Android types and can be
 * unit-tested without a device. This half is the part that cannot be, and it is deliberately
 * nothing but a lookup and a two-stop gradient.
 *
 * <p>A card with no chosen colour still falls to the hash in {@code CardFace}: the eight
 * faces are what makes an untouched wallet legible, and asking someone to pick a colour for
 * every card before it looks like anything would be a worse default than a good guess.
 */
public final class CardFaces {

    private CardFaces() {
    }

    /**
     * The face a row should wear.
     *
     * <p>A retired card loses its colour — its own as much as its type's. That is the point
     * of retiring it: the wallet's colours exist to help pick a card you are about to spend,
     * and a card you cannot spend should stop competing for that attention.
     */
    public static Drawable faceFor(Context context, String cardTypeId,
                                   @Nullable Integer chosenColor, CardStatus status) {
        if (status != null && status.isRetired()) {
            return ContextCompat.getDrawable(context, R.drawable.card_face_retired);
        }
        return faceFor(context, cardTypeId, chosenColor);
    }

    /** The card's own face, whatever state it is in. */
    public static Drawable faceFor(Context context, String cardTypeId,
                                   @Nullable Integer chosenColor) {
        if (chosenColor == null) {
            return ContextCompat.getDrawable(context, backgroundFor(cardTypeId));
        }
        return gradient(context, chosenColor);
    }

    /**
     * Paints a view's face, without inflating a drawable when nothing has changed.
     *
     * <p>Called on every bind of every visible row, so it keeps the last face it applied as
     * a tag and skips the work when the row is being rebound to the same one. Without that,
     * a scroll allocates a {@code GradientDrawable} per row per frame for every hand-coloured
     * card on screen.
     */
    public static void paint(View view, String cardTypeId,
                             @Nullable Integer chosenColor, CardStatus status) {
        Object key = faceKey(cardTypeId, chosenColor, status);
        if (key.equals(view.getTag(R.id.tag_card_face))) {
            return;
        }
        view.setBackground(faceFor(view.getContext(), cardTypeId, chosenColor, status));
        view.setTag(R.id.tag_card_face, key);
    }

    private static Object faceKey(String cardTypeId, @Nullable Integer chosenColor,
                                  CardStatus status) {
        if (status != null && status.isRetired()) {
            return "retired";
        }
        return chosenColor != null ? "custom:" + chosenColor : "type:" + cardTypeId;
    }

    /**
     * The single colour standing for a card, for places too small to carry a gradient.
     *
     * <p>The start stop of its face, so a marker beside a purchase is recognisably the same
     * colour as the card it was bought with.
     */
    @ColorInt
    public static int accentFor(Context context, String cardTypeId,
                                @Nullable Integer chosenColor) {
        if (chosenColor != null) {
            return FaceGradient.legible(chosenColor);
        }
        return context.getColor(accentFor(cardTypeId));
    }

    /**
     * A two-stop face built at runtime from one chosen colour.
     *
     * <p>Both stops come from {@link FaceGradient}, which derives them with the rule the eight
     * shipped faces were drawn with — so this is the same object the XML produces, assembled
     * a different way, and a hand-picked colour sits beside a card type's own without looking
     * like it came from somewhere else.
     */
    private static GradientDrawable gradient(Context context, @ColorInt int chosenColor) {
        int start = FaceGradient.legible(chosenColor);
        GradientDrawable face = new GradientDrawable(
                // 315° in the drawable XML, which is what the eight faces use: the light stop
                // at the top-left corner and the dark one anchoring the bottom-right.
                GradientDrawable.Orientation.TL_BR,
                new int[]{start, FaceGradient.endStop(start)});
        face.setShape(GradientDrawable.RECTANGLE);
        face.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        face.setCornerRadius(
                context.getResources().getDimension(R.dimen.card_face_corner));
        return face;
    }

    /**
     * The single colour standing for a card type, for places too small to carry a gradient.
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
    public static int backgroundFor(String cardTypeId, CardStatus status) {
        if (status != null && status.isRetired()) {
            return R.drawable.card_face_retired;
        }
        return backgroundFor(cardTypeId);
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
