package com.mycards.ui;

import android.app.Activity;
import android.content.res.Configuration;
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
     * <p>What the bar shows is now the same on every version we support: the theme sets
     * {@code statusBarColor} to transparent, so the <em>window background</em> shows
     * through — near-white in light mode, near-black in dark. The icons therefore follow
     * night mode and nothing else.
     *
     * <p>This used to return early below Android 15, and that was correct only while the
     * theme filled the bar with a solid blue: light icons on blue read at any version, and
     * the flip was needed only where the attribute stopped being honoured. Moving the
     * toolbar onto the ordinary surface removed the blue, and with it the reason for the
     * early return — leaving it in place would have put light icons on a near-white bar for
     * every device on Android 14 and below, which is the whole of minSdk 26 to 34.
     *
     * <p>The flag is set per activity rather than in the theme because it belongs to the
     * window's insets controller, and each activity has its own window.
     */
    private static void applySystemBarIconColour(Activity activity) {
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
