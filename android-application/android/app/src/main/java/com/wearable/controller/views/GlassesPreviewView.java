package com.wearable.controller.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import com.wearable.controller.impl.GlassesImage;

/**
 * What the lenses show, drawn on the phone: the same 576 x 136 picture that is sent to the
 * glasses, scaled up without smoothing so it reads like their display, in bright green on
 * black with a faint pixel grid.
 */
public class GlassesPreviewView extends View {
    private final Paint picture = new Paint();
    private final Paint grid = new Paint();
    private final Rect source = new Rect(0, 0, GlassesImage.WIDTH, GlassesImage.HEIGHT);
    private final Rect target = new Rect();
    private Bitmap bitmap;

    public GlassesPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        picture.setFilterBitmap(false);
        // White pixels become bright green; black stays black.
        picture.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                0, 0, 0, 0, 0,
                1.0f, 0, 0, 0, 0,
                0.18f, 0, 0, 0, 0,
                0, 0, 0, 1, 0})));
        grid.setColor(0x33000000);
        grid.setStrokeWidth(1f);
    }

    public void setDashboard(GlassesImage.Dashboard dashboard) {
        Bitmap next = GlassesImage.renderBitmap(dashboard);
        Bitmap old = bitmap;
        bitmap = next;
        if (old != null) old.recycle();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.round(width * GlassesImage.HEIGHT / (float) GlassesImage.WIDTH));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF020604);
        if (bitmap == null) return;
        target.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bitmap, source, target, picture);
        // Pixel grid: one dark line per source pixel, only when the pixels are big enough to show.
        float cell = getWidth() / (float) GlassesImage.WIDTH;
        if (cell >= 1.6f) {
            for (int x = 0; x <= GlassesImage.WIDTH; x++) canvas.drawLine(x * cell, 0, x * cell, getHeight(), grid);
            for (int y = 0; y <= GlassesImage.HEIGHT; y++) canvas.drawLine(0, y * cell, getWidth(), y * cell, grid);
        }
    }
}
