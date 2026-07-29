package com.mycards.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeps content clear of the status and navigation bars.
 *
 * <p>From Android 15, an app targeting SDK 35 is laid out edge to edge whether it asks to be
 * or not, so a toolbar at the top of the layout ends up drawn underneath the clock. Padding
 * the content root by the system-bar insets puts it back where it belongs, while still
 * letting the background colour extend behind the bars.
 */
public final class EdgeToEdge {

    private EdgeToEdge() {
    }

    public static void apply(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) {
            return;
        }
        apply(((ViewGroup) content).getChildAt(0));
    }

    public static void apply(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            // The IME inset has to be folded in here. Consuming the insets stops
            // adjustResize from lifting anything, so without this the keyboard covers the
            // bottom of the screen — on the search screen, that is the add-card button.
            int bottom = Math.max(bars.bottom, ime.bottom);

            view.setPadding(bars.left, bars.top, bars.right, bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
