package io.github.federicomameli1.sugarglass;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.MaskFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.List;

/**
 * Everything drawn rather than laid out, shared by the widget and the app. Drawn because a widget can
 * only show the system's arrow glyphs: here the trend gets a proper arrow, and the graph knows exactly
 * where the value and arrow sit.
 */
final class Art {

    private Art() {
    }

    /** What the widget shows about the latest reading, and how. */
    static final class Reading {
        final String value, direction;
        final int color, hue;

        /**
         * @param direction Nightscout direction, empty for no arrow (unknown, or a stale reading)
         * @param color     colour of the value
         * @param hue       range colour, for the arrow and the glow
         */
        Reading(String value, String direction, int color, int hue) {
            this.value = value;
            this.direction = direction;
            this.color = color;
            this.hue = hue;
        }
    }

    /**
     * The whole widget picture but the info line: glow, graph, value and arrow. The 1x1 widget has no
     * graph and centres the value; the others put it on the left and blur the graph behind it.
     */
    static Bitmap widget(List<Glucose.Reading> r, Reading shown, int w, int h, float density, long now,
                         int glow, int ink, Glucose.Config c, int[] fixed, boolean lines, boolean small) {
        w = Math.max(w, 1);
        h = Math.max(h, 1);
        float pad = (small ? 8 : 14) * density, info = (small ? 20 : 26) * density; // info line reserved below
        float boxTop = 6 * density, boxBottom = h - info;

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setLetterSpacing(-0.03f);
        text.setColor(shown.color);
        float cap = (boxBottom - boxTop) * (small ? 0.6f : 0.62f);
        float arrowSize = 0, gap = 0, valueWidth = 0, total = 0;
        for (int attempt = 0; attempt < 2; attempt++) { // second pass only if the first did not fit
            text.setTextSize(cap / capRatio(text));
            valueWidth = text.measureText(shown.value);
            arrowSize = cap * 0.8f;
            gap = shown.direction.isEmpty() ? 0 : cap * 0.25f;
            total = valueWidth + gap + arrowWidth(shown.direction, arrowSize);
            float room = (w - 2 * pad) * (small ? 1f : 0.8f);
            if (total <= room) break;
            cap *= room / total;
        }
        float left = small ? (w - total) / 2 : pad, middle = (boxTop + boxBottom) / 2;

        Bitmap out = draw(r, w, h, density, now, shown.hue, glow, ink, !small, c, fixed, lines,
                small ? 0 : left + total);
        Canvas canvas = new Canvas(out);
        canvas.drawText(shown.value, left, middle + cap / 2, text);
        arrow(canvas, shown.direction, left + valueWidth + gap + arrowWidth(shown.direction, arrowSize) / 2,
                middle, arrowSize, shown.hue);
        return out;
    }

    /**
     * Glow, target range and 3-hour line.
     *
     * @param hue       range colour of the latest reading, for the glow
     * @param ink       colour of the line and the dot, so it reads on light and dark glass
     * @param glow      alpha of the glow at the bottom edge, 0 for none
     * @param line      false for the 1x1 widget, which only has room for the glow
     * @param fixed     fixed {bottom, top} in mg/dl, or null for a scale fitted to the data
     * @param lines     whether to draw the target range as dashed lines
     * @param textRight right edge of what is drawn over the line; left of it the line blurs. 0 for none
     */
    static Bitmap draw(List<Glucose.Reading> r, int w, int h, float density, long now,
                       int hue, int glow, int ink, boolean line, Glucose.Config c, int[] fixed, boolean lines,
                       float textRight) {
        w = Math.max(w, 1);
        h = Math.max(h, 1);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float radius = 24 * density; // same corners as the glass drawable
        Path clip = new Path();
        clip.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        if (r.isEmpty()) return out;

        Paint tint = new Paint();
        tint.setShader(new LinearGradient(0, h, 0, h * 0.6f, // lower 40%: a tint, not a fill
                (hue & 0xFFFFFF) | (glow << 24), hue & 0xFFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, tint);
        if (!line) return out;

        int[] scale = Glucose.scale(r, c, fixed);
        int min = scale[0], max = scale[1];
        float top = h * 0.2f, bottom = h * 0.8f, right = w - 14 * density; // room for the dot at "now"
        long from = now - Glucose.WINDOW_MS;

        // The target range as two faint dashed lines, so a curve can be read without an axis
        Paint range = new Paint(Paint.ANTI_ALIAS_FLAG);
        range.setStyle(Paint.Style.STROKE);
        range.setStrokeWidth(1 * density);
        range.setColor((ink & 0xFFFFFF) | 0x4D000000);
        range.setPathEffect(new DashPathEffect(new float[]{4 * density, 4 * density}, 0));
        for (int limit : lines ? new int[]{c.low, c.high} : new int[0]) {
            float ly = bottom - (bottom - top) * (limit - min) / (max - min);
            canvas.drawLine(0, ly, w, ly, range);
        }

        Path path = new Path();
        Glucose.Reading prev = null;
        float x = 0, y = 0;
        for (int i = r.size() - 1; i >= 0; i--) { // oldest first
            Glucose.Reading reading = r.get(i);
            int sgv = Math.max(min, Math.min(max, reading.sgv)); // off a fixed scale: pinned to its edge
            x = right * (reading.date - from) / Glucose.WINDOW_MS;
            y = bottom - (bottom - top) * (sgv - min) / (max - min);
            if (prev == null || reading.date - prev.date > Glucose.GAP_MS) path.moveTo(x, y);
            else path.lineTo(x, y);
            prev = reading;
        }

        Bitmap sharp = stroke(path, w, h, density, ink, null);
        if (textRight > 0) {
            // Blurred and dim under the value and arrow, crisp from just past them
            float edge = textRight + 8 * density;
            Bitmap soft = stroke(path, w, h, density, ink, new BlurMaskFilter(5 * density, BlurMaskFilter.Blur.NORMAL));
            mask(soft, edge * 0.7f, 150, edge, 0);
            mask(sharp, edge * 0.65f, 0, edge, 255);
            canvas.drawBitmap(soft, 0, 0, null);
            soft.recycle();
        }
        canvas.drawBitmap(sharp, 0, 0, null);
        sharp.recycle();

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(ink | 0xFF000000);
        canvas.drawCircle(x, y, 3.5f * density, dot); // the latest reading
        return out;
    }

    /** Width the arrow for this direction takes at this size: a double arrow is two, side by side. */
    static float arrowWidth(String direction, float size) {
        if (direction.isEmpty() || angle(direction) == null) return 0;
        return direction.startsWith("Double") ? size * 1.25f : size;
    }

    /** The arrow alone, for the app's home screen. */
    static Bitmap arrow(String direction, int size, int color) {
        Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(arrowWidth(direction, size))), size,
                Bitmap.Config.ARGB_8888);
        arrow(new Canvas(b), direction, b.getWidth() / 2f, size / 2f, size, color);
        return b;
    }

    /**
     * A thick arrow with round ends, like the one in Apple's Find My, centred on (cx, cy) and pointing
     * where the glucose is going. Nothing for an unknown direction.
     */
    static void arrow(Canvas canvas, String direction, float cx, float cy, float size, int color) {
        Float angle = angle(direction);
        if (angle == null) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.17f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        // Pointing right, fitting the size once the round caps are added
        float half = size / 2 - p.getStrokeWidth() / 2, head = half * 0.8f;
        Path arrow = new Path();
        arrow.moveTo(-half, 0);
        arrow.lineTo(half, 0);
        arrow.moveTo(half - head, -head);
        arrow.lineTo(half, 0);
        arrow.lineTo(half - head, head);

        boolean twice = direction.startsWith("Double");
        float[] offsets = twice ? new float[]{-size * 0.28f, size * 0.28f} : new float[]{0};
        for (float offset : offsets) {
            canvas.save();
            canvas.translate(cx + offset, cy);
            canvas.rotate(angle);
            if (twice) canvas.scale(0.9f, 0.9f);
            canvas.drawPath(arrow, p);
            canvas.restore();
        }
    }

    /** Degrees clockwise from pointing right, as the canvas rotates; null for no arrow. */
    private static Float angle(String direction) {
        switch (direction) {
            case "DoubleUp":
            case "SingleUp": return -90f;
            case "FortyFiveUp": return -45f;
            case "Flat": return 0f;
            case "FortyFiveDown": return 45f;
            case "SingleDown":
            case "DoubleDown": return 90f;
            default: return null;
        }
    }

    /** Height of a digit as a share of the text size, so the value can be sized by what shows. */
    private static float capRatio(Paint p) {
        float size = p.getTextSize();
        p.setTextSize(100);
        Rect bounds = new Rect();
        p.getTextBounds("0", 0, 1, bounds);
        p.setTextSize(size);
        return bounds.height() / 100f;
    }

    private static Bitmap stroke(Path path, int w, int h, float density, int ink, MaskFilter blur) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f * density);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(ink);
        p.setMaskFilter(blur);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawPath(path, p);
        return b;
    }

    /** Multiplies the bitmap's alpha by a horizontal ramp from a0 at x0 to a1 at x1. */
    private static void mask(Bitmap b, float x0, int a0, float x1, int a1) {
        Paint p = new Paint();
        p.setShader(new LinearGradient(x0, 0, x1, 0, a0 << 24, a1 << 24, Shader.TileMode.CLAMP));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        new Canvas(b).drawRect(0, 0, b.getWidth(), b.getHeight(), p);
    }
}
