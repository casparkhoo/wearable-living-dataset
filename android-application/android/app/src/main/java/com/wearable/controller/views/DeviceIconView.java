package com.wearable.controller.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * A line icon for each kind of wearable on a rounded tile: a ring with a stone, a pair of
 * glasses, an earbud or a plain dot. The tile colour shows its state.
 */
public class DeviceIconView extends View {
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path path = new Path();
    private String type = "";

    public DeviceIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        tile.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(0xFFFFFFFF);
    }

    public DeviceIconView(Context context) {
        this(context, null);
    }

    /** {@code iconKey} is DeviceProfile.getIconKey(): ring, glasses, earbud, or anything else for a dot. */
    public void set(String iconKey, int tintColor) {
        this.type = iconKey == null ? "" : iconKey;
        tile.setColor(tintColor);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        c.drawRoundRect(0, 0, w, h, w * 0.16f, w * 0.16f, tile);
        stroke.setStrokeWidth(w * 0.062f);
        c.save();
        // Icons are drawn on a 24 x 24 grid, centred with a margin.
        float scale = w * 0.62f / 24f;
        c.translate(w * 0.19f, h * 0.19f);
        c.scale(scale, scale);
        stroke.setStrokeWidth(1.9f);
        if ("ring".equals(type)) {
            box.set(5f, 8.5f, 19f, 20.5f);
            c.drawOval(box, stroke);
            box.set(7.8f, 11.1f, 16.2f, 17.9f);
            c.drawOval(box, stroke);
            path.reset();
            path.moveTo(9.2f, 6.2f);
            path.lineTo(12f, 3.4f);
            path.lineTo(14.8f, 6.2f);
            path.lineTo(12f, 9f);
            path.close();
            c.drawPath(path, stroke);
        } else if ("glasses".equals(type)) {
            box.set(2.2f, 8.6f, 10.6f, 15.2f);
            c.drawRoundRect(box, 2.6f, 2.6f, stroke);
            box.set(13.4f, 8.6f, 21.8f, 15.2f);
            c.drawRoundRect(box, 2.6f, 2.6f, stroke);
            path.reset();
            path.moveTo(10.6f, 11.4f);
            path.quadTo(12f, 10.2f, 13.4f, 11.4f);
            c.drawPath(path, stroke);
            c.drawLine(2.2f, 10.4f, 0.9f, 8.2f, stroke);
            c.drawLine(21.8f, 10.4f, 23.1f, 8.2f, stroke);
        } else if ("earbud".equals(type)) {
            c.drawCircle(10.5f, 9f, 4.6f, stroke);
            path.reset();
            path.moveTo(15f, 11.5f);
            path.lineTo(15f, 19.5f);
            path.quadTo(15f, 21f, 13.4f, 21f);
            path.quadTo(12f, 21f, 12f, 19.5f);
            path.lineTo(12f, 13.6f);
            c.drawPath(path, stroke);
        } else {
            c.drawCircle(12f, 12f, 3f, stroke);
            c.drawCircle(12f, 12f, 8f, stroke);
        }
        c.restore();
    }
}
