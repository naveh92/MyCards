package com.mycards.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

/**
 * How much of a card is still yours to spend, drawn as ten segments rather than a bar.
 *
 * <p>This replaced a plain progress bar, and the reason is worth writing down because both
 * draw the same number. A continuous track that shrinks as you spend is a fuel gauge: it is
 * read as a thing being used up, and the wallet then reports on every card how far gone it
 * is. The question actually being asked at a counter is the opposite one — <em>have I got
 * enough on this card?</em> — and that is a quantity, not a level.
 *
 * <p>Segments say quantity. Six lit blocks out of ten is countable in the way "60% along a
 * bar" is not, and it reads as six things you still have rather than four you have lost. The
 * fill is still the remaining share, exactly as before; what changed is what the shape says
 * about it.
 *
 * <p>The last lit segment is filled proportionally rather than rounded, so the drawing never
 * claims more or less than the balance does. Rounding to whole segments would have made a
 * card with ₪4 of ₪100 left show as empty, which is the one reading that must never be wrong.
 */
public class BalanceMeter extends View {

    /** Ten reads as a share without needing to be counted; more becomes a texture. */
    private static final int SEGMENTS = 10;

    /** The gap between segments, in dp. */
    private static final float GAP_DP = 3f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF segment = new RectF();

    private float fraction;

    @ColorInt
    private int fillColor = 0xFFFFFFFF;

    @ColorInt
    private int trackColor = 0x47FFFFFF;

    public BalanceMeter(Context context) {
        this(context, null);
    }

    public BalanceMeter(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * The share of the card still available, 0 to 1.
     *
     * <p>Clamped rather than trusted: a card can read as more than it started with when the
     * initial amount is corrected downwards after purchases are logged, and a meter drawn
     * past full looks broken rather than lucky.
     */
    public void setFraction(float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        if (clamped == this.fraction) {
            return;
        }
        this.fraction = clamped;
        invalidate();
    }

    /** The two colours: what is left, and the empty part of the track behind it. */
    public void setColors(@ColorInt int fill, @ColorInt int track) {
        if (fill == fillColor && track == trackColor) {
            return;
        }
        this.fillColor = fill;
        this.trackColor = track;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth() - getPaddingStart() - getPaddingEnd();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0f || height <= 0f) {
            return;
        }

        float gap = GAP_DP * getResources().getDisplayMetrics().density;
        float segmentWidth = (width - gap * (SEGMENTS - 1)) / SEGMENTS;
        if (segmentWidth <= 0f) {
            return;
        }
        float radius = height / 2f;
        float top = getPaddingTop();
        float bottom = top + height;

        // Drawn twice over the same geometry: once in the track colour for the whole strip,
        // then again in the fill colour under a clip that stops where the balance does. That
        // is what lets a segment be part-lit without any per-segment arithmetic, and it keeps
        // the two passes incapable of disagreeing about where the segments are.
        drawSegments(canvas, trackColor, segmentWidth, gap, top, bottom, radius);

        if (fraction <= 0f) {
            return;
        }
        canvas.save();
        float filled = width * fraction;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (rtl) {
            // Fills from the trailing edge in Hebrew, the same way the numbers beside it run.
            float right = getPaddingStart() + width;
            canvas.clipRect(right - filled, top, right, bottom);
        } else {
            canvas.clipRect(getPaddingStart(), top, getPaddingStart() + filled, bottom);
        }
        drawSegments(canvas, fillColor, segmentWidth, gap, top, bottom, radius);
        canvas.restore();
    }

    private void drawSegments(Canvas canvas, @ColorInt int color, float segmentWidth,
                              float gap, float top, float bottom, float radius) {
        paint.setColor(color);
        float x = getPaddingStart();
        for (int i = 0; i < SEGMENTS; i++) {
            segment.set(x, top, x + segmentWidth, bottom);
            canvas.drawRoundRect(segment, radius, radius, paint);
            x += segmentWidth + gap;
        }
    }
}
