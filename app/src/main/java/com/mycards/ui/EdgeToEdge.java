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

    /**
     * The same, for a screen whose app bar collapses as the list underneath it scrolls.
     *
     * <p>Padding the CoordinatorLayout itself breaks that collapse, and the way it breaks is
     * worth writing down because nothing about it is visible. {@code AppBarLayout.Behavior}
     * declines to take part in a nested scroll unless
     *
     * <pre>parent.getHeight() - scrollingChild.getHeight() &lt;= appBar.getHeight()</pre>
     *
     * <p>A CoordinatorLayout's own height includes its padding while its children are
     * measured inside it, so every pixel of inset padding is added to the left-hand side and
     * to nothing on the right. On a phone with a 136px status bar and a 63px gesture bar
     * that is 199px of handicap, and the app bar has to be that much taller than its own
     * collapsed height before the list can move it at all. This wallet's header cleared it by
     * 132px and then lost 45dp in a redesign, at which point the total simply stopped
     * collapsing — with no error, and still draggable by hand, which is what makes it read as
     * a mystery rather than a bug.
     *
     * <p>So the insets go on the two views that actually meet the bars instead. The top one
     * lands on the app bar, which is what sits under the status bar and now paints its own
     * surface behind it; the bottom one lands on the content and on the floating button. The
     * comparison above then reduces to {@code statusInset + collapsedHeight <= statusInset +
     * collapsedHeight + headerHeight}, which is true for any header of any height on any
     * device — the property the old arrangement only ever had by accident.
     *
     * @param activity the screen, whose system-bar icons are coloured as in {@link #apply}
     * @param root    the CoordinatorLayout, which keeps only the horizontal insets
     * @param header  the AppBarLayout, which takes the top one
     * @param content the scrolling container, which takes the bottom one
     * @param floating a button anchored to the bottom edge, kept clear of the bar and the
     *                 keyboard by its margin; may be null
     */
    public static void applyAroundAppBar(Activity activity, View root, View header,
                                         View content, View floating) {
        applySystemBarIconColour(activity);
        int floatingMargin = floating == null ? 0
                : ((ViewGroup.MarginLayoutParams) floating.getLayoutParams()).bottomMargin;

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);

            view.setPadding(bars.left, 0, bars.right, 0);
            header.setPadding(header.getPaddingLeft(), bars.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), bottom);

            if (floating != null) {
                // A floating button is not inside the content, so it gets the inset as
                // margin. Folded into the margin the layout already asked for rather than
                // replacing it, or the button would sit against the navigation bar.
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) floating.getLayoutParams();
                if (lp.bottomMargin != floatingMargin + bottom) {
                    lp.bottomMargin = floatingMargin + bottom;
                    floating.setLayoutParams(lp);
                }
            }
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
