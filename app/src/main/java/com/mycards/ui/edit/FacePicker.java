package com.mycards.ui.edit;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.mycards.R;
import com.mycards.cards.FaceGradient;
import com.mycards.ui.CardFaces;
import com.mycards.ui.Formats;

/**
 * The row of colours on the add/edit screen, and the picker behind its last swatch.
 *
 * <p>Two kinds of choice sit in one row on purpose. "Automatic" is not a colour — it is the
 * absence of one, and it has to be visible as a thing you can go back to, or picking a shade
 * once would be a decision with no undo. The eight presets after it are the wallet's own
 * faces, so the common case is one tap onto a colour that is already known to work. The last
 * swatch opens the sliders, for the colour that is not among the eight.
 *
 * <p>Whatever comes out is a plain ARGB int. Making it legible and turning it into a gradient
 * is {@link FaceGradient}'s job, and it happens at paint time rather than here — see
 * {@code CardEntity.faceColor} for why the raw choice is what gets stored.
 */
class FacePicker {

    /** What a card wears when nothing has been chosen for it: its card type's own face. */
    static final Integer AUTOMATIC = null;

    /**
     * The eight faces, as offered by hand.
     *
     * <p>Read from resources rather than repeated as literals, because a "teal" chosen here
     * and the teal a card type is given by the hash are meant to be one colour rather than
     * two that nearly match.
     */
    private static final int[] PRESET_COLORS = {
            R.color.face_indigo_start,
            R.color.face_teal_start,
            R.color.face_plum_start,
            R.color.face_rose_start,
            R.color.face_amber_start,
            R.color.face_forest_start,
            R.color.face_slate_start,
            R.color.face_magenta_start,
    };

    /** The sample balance on the picker's preview card. */
    private static final double PREVIEW_AMOUNT = 250d;

    private final LinearLayout row;
    private final Context context;

    /** Null means automatic; see {@link #AUTOMATIC}. */
    @Nullable
    private Integer chosen;

    /** The card type's own colour, which is what "Automatic" shows. */
    @ColorInt
    private int automaticColor;

    FacePicker(LinearLayout row) {
        this.row = row;
        this.context = row.getContext();
        this.automaticColor = context.getColor(R.color.face_slate_start);
    }

    @Nullable
    Integer chosen() {
        return chosen;
    }

    void setChosen(@Nullable Integer color) {
        this.chosen = color;
        redraw();
    }

    /**
     * Tells the row which card type is selected, so "Automatic" shows what it would give.
     *
     * <p>Without this the automatic swatch would be a grey circle standing for "something
     * else", which is the one thing a colour picker should never make someone guess at.
     */
    void setCardType(String cardTypeId) {
        automaticColor = context.getColor(CardFaces.accentFor(cardTypeId));
        redraw();
    }

    /** Builds the swatches. Call once; {@link #redraw} keeps them current after that. */
    void attach() {
        redraw();
    }

    private void redraw() {
        row.removeAllViews();

        row.addView(swatch(automaticColor,
                chosen == AUTOMATIC,
                context.getString(R.string.face_color_automatic),
                0,
                v -> choose(AUTOMATIC)));

        for (int i = 0; i < PRESET_COLORS.length; i++) {
            int color = context.getColor(PRESET_COLORS[i]);
            row.addView(swatch(color,
                    chosen != null && chosen == color,
                    context.getString(R.string.face_color_swatch, i + 1),
                    0,
                    v -> choose(color)));
        }

        // Lit in whatever is currently chosen when that is not one of the presets, so a
        // hand-mixed colour has somewhere on the row that shows it rather than disappearing
        // the moment the dialog closes.
        boolean custom = chosen != null && !isPreset(chosen);
        row.addView(swatch(custom ? chosen : automaticColor,
                custom,
                context.getString(R.string.face_color_custom),
                R.drawable.ic_palette,
                v -> openDialog()));
    }

    private boolean isPreset(int color) {
        for (int preset : PRESET_COLORS) {
            if (context.getColor(preset) == color) {
                return true;
            }
        }
        return false;
    }

    private void choose(@Nullable Integer color) {
        chosen = color;
        redraw();
    }

    /**
     * One circle in the row.
     *
     * <p>Drawn as the face's start stop rather than as its gradient: at 44dp a two-stop
     * gradient is a flat colour with a slightly muddy corner, and the job of the row is
     * telling nine choices apart at a glance.
     */
    private View swatch(@ColorInt int color, boolean selected, String description,
                        int icon, View.OnClickListener click) {
        int size = dp(44);

        FrameLayout holder = new FrameLayout(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMarginEnd(dp(6));
        holder.setLayoutParams(params);
        holder.setClickable(true);
        holder.setFocusable(true);
        holder.setContentDescription(description);
        holder.setOnClickListener(click);
        holder.setBackground(circle(FaceGradient.legible(color), selected));
        // Said out loud, because a ring drawn around one circle in a row of ten is not
        // something a screen reader can see.
        holder.setSelected(selected);

        if (selected || icon != 0) {
            ImageView mark = new ImageView(context);
            mark.setImageResource(selected ? R.drawable.ic_check : icon);
            FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            markParams.gravity = Gravity.CENTER;
            mark.setLayoutParams(markParams);
            holder.addView(mark);
        }
        return holder;
    }

    /**
     * The swatch's shape: a filled circle, with a ring around the chosen one.
     *
     * <p>The ring is a separate layer outside the fill rather than a stroke on it, so
     * selecting a swatch does not shrink the colour it is showing — which on a row of ten
     * reads as the chosen one being the odd size rather than the chosen one.
     */
    private LayerDrawable circle(@ColorInt int color, boolean selected) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        if (selected) {
            ring.setStroke(dp(2), MaterialColors.getColor(
                    row, androidx.appcompat.R.attr.colorPrimary));
        }

        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(color);

        LayerDrawable layers = new LayerDrawable(new GradientDrawable[]{ring, fill});
        int inset = selected ? dp(5) : 0;
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    /**
     * The three sliders, over a live preview of the card they build.
     *
     * <p>Opens on whatever is currently in force — the chosen colour, or the card type's own
     * when nothing has been chosen — so it starts from the card as it looks now rather than
     * from an arbitrary shade.
     */
    private void openDialog() {
        int start = chosen != null ? chosen : automaticColor;

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_face_color, null);
        View preview = view.findViewById(R.id.preview);
        TextView previewAmount = view.findViewById(R.id.previewAmount);
        Slider red = view.findViewById(R.id.red);
        Slider green = view.findViewById(R.id.green);
        Slider blue = view.findViewById(R.id.blue);
        TextView redLabel = view.findViewById(R.id.redLabel);
        TextView greenLabel = view.findViewById(R.id.greenLabel);
        TextView blueLabel = view.findViewById(R.id.blueLabel);

        previewAmount.setText(Formats.money(PREVIEW_AMOUNT, null));

        red.setValue(FaceGradient.red(start));
        green.setValue(FaceGradient.green(start));
        blue.setValue(FaceGradient.blue(start));

        // A one-element array because the listeners below are lambdas, and this is what the
        // dialog's Save button reads when it is finally pressed.
        final int[] picked = {start};

        Runnable render = () -> {
            int color = FaceGradient.argb(0xFF,
                    (int) red.getValue(), (int) green.getValue(), (int) blue.getValue());
            picked[0] = color;
            // Built by the same code the wallet uses, so a colour too pale to carry white
            // text shows here already darkened. The preview is the explanation — a card is
            // what is being chosen, and it is on screen.
            preview.setBackground(CardFaces.faceFor(context, null, color));
            redLabel.setText(channel(R.string.face_color_red, red));
            greenLabel.setText(channel(R.string.face_color_green, green));
            blueLabel.setText(channel(R.string.face_color_blue, blue));
        };

        Slider.OnChangeListener onChange = (slider, value, fromUser) -> render.run();
        red.addOnChangeListener(onChange);
        green.addOnChangeListener(onChange);
        blue.addOnChangeListener(onChange);
        render.run();

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.face_color_custom)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> choose(picked[0]))
                .show();
    }

    private String channel(int nameRes, Slider slider) {
        return context.getString(R.string.face_color_channel,
                context.getString(nameRes), (int) slider.getValue());
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
