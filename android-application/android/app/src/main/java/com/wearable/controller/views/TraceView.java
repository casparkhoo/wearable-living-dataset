package com.wearable.controller.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * A line plot that rescales to its own data. It draws the shape of a signal,
 * not units: the pulse waveform, the movement trace and the heart-rate trend
 * from the backend are each plotted this way.
 */
public class TraceView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] values = new float[0];
    private String emptyText = "no data";

    public TraceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * density);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(0xFF2563EB);
        label.setTextSize(11f * density);
        label.setColor(0xFF61708A);
        label.setTextAlign(Paint.Align.CENTER);
    }

    public void setLineColor(int color) {
        line.setColor(color);
        invalidate();
    }

    public void setEmptyText(String text) {
        emptyText = text;
    }

    public void setValues(float[] newValues) {
        values = newValues == null ? new float[0] : newValues;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (values.length < 2) {
            canvas.drawText(emptyText, w / 2f, h / 2f + label.getTextSize() / 3f, label);
            return;
        }
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        for (float v : values) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        float span = hi - lo == 0f ? 1f : hi - lo;
        float pad = 4f * getResources().getDisplayMetrics().density;
        path.reset();
        for (int i = 0; i < values.length; i++) {
            float x = w * i / (values.length - 1f);
            float y = h - pad - (values[i] - lo) / span * (h - 2f * pad);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, line);
    }
}
