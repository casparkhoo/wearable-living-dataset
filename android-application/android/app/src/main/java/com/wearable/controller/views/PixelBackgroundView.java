package com.wearable.controller.views;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * A field of tiny square pixels that fills the whole background, very faint, in dark
 * greens with a few lime ones. It hardly moves: a small share of the pixels pick a new
 * brightness each frame and ease toward it, so the field shimmers slowly rather than
 * drifting. Stepped at a low frame rate, it reads as 8-bit.
 */
public class PixelBackgroundView extends View {
    private static final long FRAME_MS = 125;       // eight frames a second, for a gentle float
    private static final float CHANGE_SHARE = 0.12f;  // pixels re-picking their brightness every beat: always some twinkling
    private static final float EASE = 0.35f;

    private final Paint paint = new Paint();
    private final Random random = new Random(11);
    private final boolean night;
    private final float cell;
    private int columns;
    private int rows;
    private float[] level = new float[0];
    private float[] target = new float[0];
    private boolean[] lime = new boolean[0];
    private boolean running;
    private long frames;

    public PixelBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
        night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        cell = 12f * context.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // One extra cell all round, so the floating field never shows a bare edge.
        columns = Math.max(1, (int) (w / cell) + 3);
        rows = Math.max(1, (int) (h / cell) + 3);
        int count = columns * rows;
        level = new float[count];
        target = new float[count];
        lime = new boolean[count];
        for (int i = 0; i < count; i++) {
            level[i] = target[i] = random.nextFloat();
            lime[i] = random.nextInt(14) == 0;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            frames++;
            if (level.length > 0) {
                int changes = Math.max(1, (int) (level.length * CHANGE_SHARE));
                for (int i = 0; i < changes; i++) {
                    target[random.nextInt(level.length)] = random.nextFloat();
                }
                for (int i = 0; i < level.length; i++) {
                    float d = target[i] - level[i];
                    if (d > 0.01f || d < -0.01f) level[i] += d * EASE;
                }
            }
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        postDelayed(tick, FRAME_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float size = cell - 1.5f;
        // The whole field sways a few pixels, slowly, so it floats rather than sits.
        double t = frames * FRAME_MS / 1000.0;
        float driftX = (float) (Math.sin(t * 0.21) * cell * 0.45);
        float driftY = (float) (Math.cos(t * 0.15) * cell * 0.45);
        // Brightness ranges, as alpha out of 255.
        final int greenLow = night ? 2 : 3;
        final int greenHigh = night ? 11 : 9;
        final int limeHigh = night ? 26 : 20;
        final int green = night ? 0x2F8F55 : 0x6FAE4F;
        final int limeColor = 0xB6FF3B;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                int i = r * columns + c;
                float v = level[i];
                int alpha;
                int color;
                if (lime[i]) {
                    color = limeColor;
                    alpha = (int) (greenLow + v * (limeHigh - greenLow));
                } else {
                    color = green;
                    alpha = (int) (greenLow + v * (greenHigh - greenLow));
                }
                paint.setColor((alpha << 24) | color);
                float x = (c - 1) * cell + driftX + 0.5f;
                float y = (r - 1) * cell + driftY + 0.5f;
                canvas.drawRect(x, y, x + size, y + size, paint);
            }
        }
    }
}
