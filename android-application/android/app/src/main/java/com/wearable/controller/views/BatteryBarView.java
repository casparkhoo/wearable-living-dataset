package com.wearable.controller.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** A thin rounded bar for a battery level, coloured by how much is left. */
public class BatteryBarView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int percent = -1;

    public BatteryBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        track.setColor(0xFFDCE3EE);
    }

    public BatteryBarView(Context context) {
        this(context, null);
    }

    public void setPercent(int value) {
        percent = Math.max(0, Math.min(100, value));
        fill.setColor(percent <= 15 ? 0xFFD14C3D : percent <= 30 ? 0xFFD98A2B : 0xFF1F9D72);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight();
        c.drawRoundRect(0, 0, getWidth(), h, h / 2f, h / 2f, track);
        if (percent >= 0) {
            float width = Math.max(h, getWidth() * percent / 100f);
            c.drawRoundRect(0, 0, width, h, h / 2f, h / 2f, fill);
        }
    }
}
