package io.github.federicomameli1.sugarglass;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.MaskFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;

import java.util.List;

/**
 * The picture behind the numbers, shared by the widget and the app: a glow rising from the bottom, tinted by
 * the current range, and the 3-hour line, blurred and dimmed where the value sits so it stays out of its way.
 */
final class Art {

    private Art() {
    }

    /**
     * @param hue   range colour of the latest reading, for the glow
     * @param ink   colour of the line and the dot, so it reads on light and dark glass
     * @param glow  alpha of the glow at the bottom edge, 0 for none
     * @param line  false for the 1x1 widget, which only has room for the glow
     */
    static Bitmap draw(List<Glucose.Reading> r, int w, int h, float density, long now,
                       int hue, int glow, int ink, boolean line) {
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

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Glucose.Reading x : r) {
            min = Math.min(min, x.sgv);
            max = Math.max(max, x.sgv);
        }
        int pad = Math.max(0, Glucose.MIN_SPAN - (max - min)) / 2; // quiet data stays flat instead of turning into cliffs
        min -= pad;
        max += pad;
        float top = h * 0.2f, bottom = h * 0.8f, right = w - 14 * density; // room for the dot at "now"
        long from = now - Glucose.WINDOW_MS;

        Path path = new Path();
        Glucose.Reading prev = null;
        float x = 0, y = 0;
        for (int i = r.size() - 1; i >= 0; i--) { // oldest first
            Glucose.Reading reading = r.get(i);
            x = right * (reading.date - from) / Glucose.WINDOW_MS;
            y = bottom - (bottom - top) * (reading.sgv - min) / (max - min);
            if (prev == null || reading.date - prev.date > Glucose.GAP_MS) path.moveTo(x, y);
            else path.lineTo(x, y);
            prev = reading;
        }

        // Where the value is drawn: a fixed width, so a wide widget keeps most of its line crisp
        float text = Math.min(w * 0.65f, 170 * density);
        Bitmap soft = stroke(path, w, h, density, ink, new BlurMaskFilter(5 * density, BlurMaskFilter.Blur.NORMAL));
        Bitmap sharp = stroke(path, w, h, density, ink, null);
        mask(soft, text * 0.6f, 150, text, 0);  // under the value: blurred and dim
        mask(sharp, text * 0.55f, 0, text, 255); // past it: crisp
        canvas.drawBitmap(soft, 0, 0, null);
        canvas.drawBitmap(sharp, 0, 0, null);
        soft.recycle();
        sharp.recycle();

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(ink | 0xFF000000);
        canvas.drawCircle(x, y, 3.5f * density, dot); // the latest reading
        return out;
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
