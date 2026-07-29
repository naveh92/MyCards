package com.mycards.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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
        applySystemBarIconColour(activity);

        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) {
            return;
        }
        apply(((ViewGroup) content).getChildAt(0));
    }

    /**
     * Darkens the clock and status icons when what sits behind them is light.
     *
     * <p>Which colour is correct depends on the platform version, because the two draw the
     * status bar from different things:
     *
     * <ul>
     *   <li>Up to Android 14, {@code android:statusBarColor} in the theme is honoured, so the
     *       bar is filled with the toolbar blue and the icons must stay light.
     *   <li>From Android 15 that attribute is ignored for an app targeting SDK 35 — the bar
     *       is transparent and shows the <em>window background</em> through it, not the
     *       toolbar. In dark mode that is nearly black and the light icons still read, which
     *       is why this went unnoticed; in light mode they land on near-white and vanish.
     * </ul>
     *
     * <p>So the icons flip only where the background actually flips. The flag is set per
     * activity rather than in the theme because it belongs to the window's insets
     * controller, and each activity has its own window.
     */
    private static void applySystemBarIconColour(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Bars are painted with the theme colours; the defaults are already right.
            return;
        }
        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView());
        // "Light bars" describes the background, so the icons on it are drawn dark.
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);
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
