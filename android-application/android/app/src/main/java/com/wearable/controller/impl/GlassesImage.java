package com.wearable.controller.impl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

/**
 * The glasses dashboard as a picture: what the lenses show while the wearer looks
 * up. The Even G1 draws a 576 x 136 one-bit image, which allows a real waveform
 * line, dividers and large numbers where its text screen allows only five lines
 * of characters.
 */
public final class GlassesImage {
    public static final int WIDTH = 576;
    public static final int HEIGHT = 136;

    /** What to draw; any text may be empty and the waveform may be null. */
    public static final class Dashboard {
        public String clock = "";
        public String recording = "";
        public boolean recordingActive;
        public String heartRate = "--";
        public String pulseQuality = "";
        public String movement = "";
        public String ring = "";
        public String rate = "";
        public String cloud = "";
        public boolean cloudOnline;
        public String alert = "";
        public String alerts = "";
        public float[] wave;
        /** One row per recording wearable when there are several (ring and earbuds). */
        public java.util.List<DeviceRow> devices = new java.util.ArrayList<>();
    }

    /** A wearable's line on the dashboard: short name, heart rate, signal. */
    public static final class DeviceRow {
        public final String name;
        public final String heartRate;
        public final String signal;

        public DeviceRow(String name, String heartRate, String signal) {
            this.name = name;
            this.heartRate = heartRate;
            this.signal = signal;
        }
    }

    /**
     * Rows left empty above and below the content. The lenses do not show the whole
     * 136 rows: the top was cut off and the glasses overlay their own prompts at the
     * bottom. Adjustable from the debug broadcast while the right values are found.
     */
    public static volatile int topMargin = 22;
    public static volatile int bottomMargin = 30;

    private GlassesImage() {
    }

    /** The dashboard as a bitmap, white on black, for the on-screen preview. */
    public static Bitmap renderBitmap(Dashboard d) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        // The phone shows the whole height; the margins are for the lenses, which
        // cut off the top and are overlaid at the bottom.
        draw(canvas, d, 6, 6);
        return bitmap;
    }

    /** The dashboard as the bytes of a 1-bit BMP file, the format the glasses take. */
    public static byte[] render(Dashboard d) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        draw(canvas, d, topMargin, bottomMargin);
        byte[] bmp = toMonochromeBmp(bitmap);
        bitmap.recycle();
        return bmp;
    }

    private static Paint paint(float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(size);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    private static void draw(Canvas c, Dashboard d, int topRows, int bottomRows) {
        final float top = topRows;
        final float bottom = HEIGHT - bottomRows;
        final float height = bottom - top;
        // Smaller than before: the bars take less of the lens, and the middle holds
        // a row per wearable when several are recording.
        final float barH = height * 0.21f;          // clock and recording
        final float footH = height * 0.20f;         // status cells
        final float midTop = top + barH + 3;
        final float midBottom = bottom - footH - 3;

        Paint line = paint(10, false);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f);
        Paint fill = paint(10, false);

        // Top bar: clock at the left, recording state at the right.
        Paint clock = paint(barH * 0.95f, true);
        c.drawText(d.clock, 12, top + barH * 0.82f, clock);
        Paint rec = paint(barH * 0.82f, true);
        float recWidth = rec.measureText(d.recording);
        c.drawText(d.recording, WIDTH - 12 - recWidth, top + barH * 0.80f, rec);
        float dotX = WIDTH - 12 - recWidth - 12;
        float dotY = top + barH * 0.52f;
        if (d.recordingActive) {
            c.drawCircle(dotX, dotY, barH * 0.2f, fill);
        } else {
            Paint ring = paint(10, false);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2f);
            c.drawCircle(dotX, dotY, barH * 0.18f, ring);
        }
        c.drawLine(0, top + barH, WIDTH, top + barH, line);

        float midH = midBottom - midTop;
        float split;
        if (d.devices.size() > 1) {
            // Several wearables: a compact row each, "RING  62 bpm  signal ok".
            split = 300;
            int rows = Math.min(d.devices.size(), 3);
            float rowH = midH / rows;
            Paint name = paint(rowH * 0.50f, true);
            Paint rate = paint(rowH * 0.70f, true);
            Paint small = paint(rowH * 0.42f, false);
            for (int i = 0; i < rows; i++) {
                DeviceRow row = d.devices.get(i);
                float base = midTop + rowH * (i + 0.78f);
                c.drawText(row.name, 12, base, name);
                heart(c, 92, base - rowH * 0.26f, rowH * 0.36f, fill);
                c.drawText(row.heartRate, 106, base, rate);
                float unitX = 106 + rate.measureText(row.heartRate) + 4;
                c.drawText("bpm", unitX, base, small);
                c.drawText(row.signal, unitX + small.measureText("bpm") + 10, base, small);
            }
        } else {
            // One wearable: its heart rate large, signal under it (smaller than before).
            split = 190;
            heart(c, 20, midTop + midH * 0.32f, midH * 0.36f, fill);
            Paint big = paint(midH * 0.62f, true);
            c.drawText(d.heartRate, 44, midTop + midH * 0.62f, big);
            float unitX = 44 + big.measureText(d.heartRate) + 6;
            c.drawText("bpm", unitX, midTop + midH * 0.62f, paint(midH * 0.28f, false));
            c.drawText(d.pulseQuality, 12, midBottom - 1, paint(midH * 0.26f, false));
        }
        c.drawLine(split, midTop + 2, split, midBottom - 2, line);
        // Waveform: the same 10 s trace the app and console plot.
        wave(c, d.wave, split + 12, midTop + 3, WIDTH - 12, midBottom - 3);

        // Footer: status cells, or the latest alert in white.
        c.drawLine(0, bottom - footH, WIDTH, bottom - footH, line);
        float footTop = bottom - footH + 3;
        if (!d.alert.isEmpty()) {
            c.drawRect(0, footTop, WIDTH, bottom, fill);
            Paint inverse = paint(footH * 0.70f, true);
            inverse.setColor(Color.BLACK);
            c.drawText(d.alert, 12, bottom - footH * 0.18f, inverse);
        } else {
            Paint small = paint(footH * 0.66f, false);
            String[] items = {d.movement, d.ring, d.rate, d.cloud, d.alerts};
            float cell = WIDTH / (float) items.length;
            for (int i = 0; i < items.length; i++) {
                if (i > 0) c.drawLine(i * cell, footTop + 2, i * cell, bottom - 2, line);
                float w = small.measureText(items[i]);
                c.drawText(items[i], i * cell + (cell - w) / 2f, bottom - footH * 0.2f, small);
            }
            if (d.cloudOnline) {
                float w = small.measureText(items[3]);
                c.drawCircle(3 * cell + (cell - w) / 2f - 8, bottom - footH * 0.42f, footH * 0.14f, fill);
            }
        }
    }

    private static void heart(Canvas c, float cx, float cy, float size, Paint fill) {
        Path p = new Path();
        float r = size / 4f;
        p.moveTo(cx, cy + size * 0.45f);
        p.cubicTo(cx - size * 0.9f, cy - size * 0.05f, cx - size * 0.45f, cy - size * 0.65f, cx, cy - size * 0.2f);
        p.cubicTo(cx + size * 0.45f, cy - size * 0.65f, cx + size * 0.9f, cy - size * 0.05f, cx, cy + size * 0.45f);
        c.drawPath(p, fill);
    }

    /** A line through the samples, scaled to fill the box. */
    private static void wave(Canvas c, float[] values, float left, float top, float right, float bottom) {
        Paint axis = paint(10, false);
        axis.setStyle(Paint.Style.STROKE);
        axis.setStrokeWidth(1f);
        if (values == null || values.length < 4) {
            float mid = (top + bottom) / 2f;
            c.drawLine(left, mid, right, mid, axis);
            return;
        }
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        for (float v : values) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        float span = hi - lo < 1e-6f ? 1f : hi - lo;
        Paint stroke = paint(10, false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        float w = right - left;
        float h = bottom - top;
        for (int i = 0; i < values.length; i++) {
            float x = left + w * i / (values.length - 1f);
            float y = bottom - (values[i] - lo) / span * h;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, stroke);
    }

    /**
     * 1-bit BMP, bottom-up rows, palette black then white; a lit pixel is a set bit.
     * Rows are 72 bytes, already a multiple of four.
     */
    private static byte[] toMonochromeBmp(Bitmap bitmap) {
        final int rowBytes = WIDTH / 8;
        final int dataSize = rowBytes * HEIGHT;
        final int headerSize = 14 + 40 + 8;
        byte[] out = new byte[headerSize + dataSize];
        // File header.
        out[0] = 'B';
        out[1] = 'M';
        putInt(out, 2, headerSize + dataSize);
        putInt(out, 10, headerSize);
        // Info header.
        putInt(out, 14, 40);
        putInt(out, 18, WIDTH);
        putInt(out, 22, HEIGHT);
        out[26] = 1;
        out[28] = 1;
        putInt(out, 34, dataSize);
        putInt(out, 38, 2835);
        putInt(out, 42, 2835);
        putInt(out, 46, 2);
        // Palette: index 0 black, index 1 white.
        out[54] = 0; out[55] = 0; out[56] = 0; out[57] = 0;
        out[58] = (byte) 255; out[59] = (byte) 255; out[60] = (byte) 255; out[61] = 0;
        int[] pixels = new int[WIDTH * HEIGHT];
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            int row = headerSize + (HEIGHT - 1 - y) * rowBytes;
            for (int x = 0; x < WIDTH; x++) {
                if (Color.red(pixels[y * WIDTH + x]) > 127) {
                    out[row + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                }
            }
        }
        return out;
    }

    private static void putInt(byte[] b, int at, int value) {
        b[at] = (byte) value;
        b[at + 1] = (byte) (value >> 8);
        b[at + 2] = (byte) (value >> 16);
        b[at + 3] = (byte) (value >> 24);
    }
}
