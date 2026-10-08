package io.github.federicomameli1.sugarglass;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

import java.util.List;

/**
 * Experimental, off unless turned on in Accessibility and in the settings: a small pill with the value and arrow at the bottom of the lock
 * screen. Apps cannot draw there; an accessibility overlay can. It reads nothing on screen
 * (no window content in its config), it only draws, and it never takes touches.
 */
public class LockPill extends AccessibilityService {

    // ponytail: polls the lock state while shown, since HyperOS tells apps about a fingerprint unlock ~0.7 s
    // late. Only while the pill is up (screen on, locked), so it costs nothing the rest of the time
    private static final long TICK_MS = 100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ImageView view;
    private String drawn = "";
    private boolean pin; // the PIN pad is up: the pill would sit over it
    private boolean covered; // another app is shown over the lock screen

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            check();
            if (view != null) handler.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            pin = false;
            covered = false;
            check();
        }
    };

    @Override
    protected void onServiceConnected() {
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, f);
        GlucoseService.pill = true;
        check();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence from = event.getPackageName();
        if (from == null || getPackageName().contentEquals(from)) return; // its own window showing up
        boolean system = "com.android.systemui".contentEquals(from);
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Something else over the lock screen (camera, a side panel app): out of its way.
            // Only the package name is read, never what the window says
            covered = !system;
            pin = false;
        } else if (system) {
            // On HyperOS the PIN pad shows as focus moving to its text field
            pin = "android.widget.EditText".contentEquals(event.getClassName());
        }
        check();
    }

    /**
     * On the lock screen and not yet unlocked. A fingerprint or PIN makes the device unlocked before the
     * lock screen finishes going away, so with a secure lock that is the earlier, better signal.
     */
    private boolean locked() {
        KeyguardManager k = getSystemService(KeyguardManager.class);
        return k.isKeyguardLocked() && (!k.isDeviceSecure() || k.isDeviceLocked())
                && getSystemService(PowerManager.class).isInteractive();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screen);
        GlucoseService.pill = false;
        hide();
        super.onDestroy();
    }

    /** Shown only while the screen is on and locked, and there is something to show. */
    private void check() {
        boolean locked = locked();
        List<Glucose.Reading> r = GlucoseService.readings;
        if (!locked || pin || covered || !GlucoseService.pillOn(this) || r.isEmpty()) {
            hide();
            return;
        }
        long now = System.currentTimeMillis();
        Glucose.Reading last = r.get(0);
        boolean hidden = Glucose.hidden(last, now); // same rule as the widget: an old number never looks current
        Glucose.Config c = GlucoseService.config(this);
        String value = hidden ? "---" : Glucose.value(last.sgv, c);
        if (GlucoseService.demo(this)) value = "DEMO " + value; // made-up readings must never pass for real ones
        String direction = hidden || Glucose.stale(last, now) ? "" : last.direction;
        int hue = GlucoseService.color(this, last, now, c);
        String key = value + direction + hue;
        if (view == null) {
            view = new ImageView(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(34 * getResources().getDisplayMetrics().density); // level with torch and camera
            getSystemService(WindowManager.class).addView(view, lp);
            drawn = "";
            handler.postDelayed(tick, TICK_MS);
        }
        if (!key.equals(drawn)) {
            view.setImageBitmap(pill(value, direction, hidden ? getColor(R.color.stale) : hue));
            view.setContentDescription(value + " " + Glucose.arrow(direction));
            drawn = key;
        }
    }

    private void hide() {
        handler.removeCallbacks(tick);
        if (view == null) return;
        getSystemService(WindowManager.class).removeView(view);
        view = null;
    }

    /** Dark glass pill, white value, arrow in the range colour. */
    private Bitmap pill(String value, String direction, int hue) {
        float d = getResources().getDisplayMetrics().density, h = 40 * d, pad = 16 * d, gap = 6 * d;
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(22 * d);
        text.setColor(0xFFFFFFFF);
        float arrow = 20 * d, arrowW = Art.arrowWidth(direction, arrow);
        float valueW = text.measureText(value), w = pad * 2 + valueW + (arrowW > 0 ? gap + arrowW : 0);
        Bitmap b = Bitmap.createBitmap(Math.round(w), Math.round(h), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(0x73000000);
        canvas.drawRoundRect(0, 0, w, h, h / 2, h / 2, fill);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1 * d);
        edge.setColor(0x47FFFFFF);
        canvas.drawRoundRect(d / 2, d / 2, w - d / 2, h - d / 2, h / 2, h / 2, edge);
        Paint.FontMetrics m = text.getFontMetrics();
        canvas.drawText(value, pad, h / 2 - (m.ascent + m.descent) / 2, text);
        Art.arrow(canvas, direction, pad + valueW + gap + arrowW / 2, h / 2, arrow, hue);
        return b;
    }
}
