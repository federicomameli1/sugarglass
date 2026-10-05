package io.github.federicomameli1.sugarglass;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.DateFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Polls Nightscout while the phone is awake and keeps the notification and the widgets current.
 * Running in the foreground is what keeps it alive, and its notification is the persistent one.
 */
public class GlucoseService extends Service {

    // A new id, because a channel's importance can't be lowered by the app once the user or the system
    // (HyperOS raised it to high) has changed it; the old "glucose" channel is deleted on start
    private static final String CHANNEL = "glucose_quiet", OLD_CHANNEL = "glucose";
    private static final int NOTIFICATION_ID = 1;
    private static final long POLL_MS = 60_000;
    private static final long WATCHDOG_MS = 15 * 60_000;
    private static final long FETCH_DEADLINE_MS = 30_000; // a whole request, however slowly the server answers

    /** Newest first. Shared with the widget, which runs in the same process. */
    static volatile List<Glucose.Reading> readings = Collections.emptyList();
    /**
     * Where {@link #readings} came from: "fake", or the Nightscout address. Changing either drops them,
     * so one site's values, or made-up ones, never show as another's.
     */
    static volatile String source = "";
    /** Last fetch outcome for the settings screen; empty until the first attempt. */
    static volatile String status = "";
    /** Whether the last fetch failed, so the notification can say so without quoting the error. */
    static volatile boolean failing;
    /** What the notification last showed that matters, so a dark screen isn't woken for an unchanged value. */
    private volatile String shown = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    // ponytail: the loop pauses in deep sleep, when nobody is looking; screen-on refreshes at once
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            // Redraw from memory first, so the age and the stale state move even while a fetch hangs
            redraw(getSystemService(PowerManager.class).isInteractive());
            refresh();
            handler.postDelayed(this, POLL_MS);
        }
    };
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            redraw(true); // right away from memory, with the age as of now...
            refresh();    // ...then the fresh reading, a second or two later
        }
    };

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings", MODE_PRIVATE);
    }

    /** The context in the language chosen in the settings, or unchanged when following the system. */
    static Context localized(Context base) {
        String language = prefs(base).getString("language", "");
        if (language.isEmpty()) return base;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(new Locale(language));
        return base.createConfigurationContext(c);
    }

    static final int LOOK_AUTO = 0, LOOK_DARK = 1, LOOK_LIGHT = 2;

    /** Widget look chosen in the settings: automatic follows the system's light/dark mode. */
    static int look(Context context) {
        return prefs(context).getInt("look", LOOK_AUTO);
    }

    /** The context in the widget's look, so colours resolved from it match the glass behind them. */
    static Context themed(Context base) {
        int look = look(base);
        if (look == LOOK_AUTO) return base;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (look == LOOK_DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(c);
    }

    /** The graph's fixed {bottom, top} in mg/dl, or null when it fits itself to the data. */
    static int[] fixedScale(Context context) {
        SharedPreferences p = prefs(context);
        return p.getBoolean("fixed_scale", false)
                ? new int[]{p.getInt("scale_min", Glucose.FIXED_MIN), p.getInt("scale_max", Glucose.FIXED_MAX)}
                : null;
    }

    static boolean rangeLines(Context context) {
        return prefs(context).getBoolean("range_lines", true);
    }

    /** Alpha of the range glow, from the slider: 0 to 100, where 50 is the default look. */
    static int glowAlpha(Context context) {
        return Math.round(prefs(context).getInt("glow", 50) * 1.16f);
    }

    static Glucose.Config config(Context context) {
        SharedPreferences p = prefs(context);
        Glucose.Config d = Glucose.Config.DEFAULT;
        return new Glucose.Config(p.getBoolean("mmol", d.mmol), p.getInt("urgent_low", d.urgentLow),
                p.getInt("low", d.low), p.getInt("high", d.high), p.getInt("urgent_high", d.urgentHigh));
    }

    /** The range colour of the latest reading, grey once it is stale. */
    static int color(Context context, Glucose.Reading last, long now, Glucose.Config c) {
        if (Glucose.stale(last, now)) return context.getColor(R.color.stale);
        int band = Glucose.band(last.sgv, c);
        return context.getColor(band == Glucose.URGENT ? R.color.range_urgent
                : band == Glucose.OUT_OF_RANGE ? R.color.range_warn : R.color.range_ok);
    }

    static String ago(Context context, Glucose.Reading last, long now) {
        long minutes = Glucose.minutesAgo(last, now);
        return minutes == 0 ? context.getString(R.string.now) : context.getString(R.string.minutes_ago, minutes);
    }

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, GlucoseService.class));
        } catch (RuntimeException e) { // background start refused; the next boot, app open or widget tap retries
            status = context.getString(R.string.status_not_started, e.getMessage());
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(localized(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Default importance keeps it on the lock screen; no sound, vibration or light, so updating it
        // never counts as an alert (HyperOS lit up the screen edges for each update on a high channel)
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel(OLD_CHANNEL);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        try {
            startForeground(NOTIFICATION_ID, notification());
        } catch (RuntimeException e) { // same refusal as in start(), seen on a sticky restart
            status = getString(R.string.status_not_started, e.getMessage());
            stopSelf();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration changed) {
        super.onConfigurationChanged(changed);
        GlucoseWidget.updateAll(this); // light/dark switched: the colours set from code have to follow now
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(poll);
        handler.post(poll); // fetch now, e.g. right after the settings changed
        // Watchdog: if the system kills the process overnight, this brings the service back. Each start
        // re-arms it, so it only fires when nothing else has restarted the service for 15 minutes.
        // ponytail: a "force stop" (what some OEM cleaners do) cancels alarms too; nothing an app can do about that
        getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_MS, PendingIntent.getForegroundService(this, 0,
                        new Intent(this, GlucoseService.class), PendingIntent.FLAG_IMMUTABLE));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        unregisterReceiver(screenOn);
        network.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void refresh() {
        network.execute(() -> {
            SharedPreferences p = prefs(this);
            // Debug builds only: made-up readings, chosen in the settings, instead of Nightscout
            boolean fake = BuildConfig.DEBUG && p.getBoolean("fake", false);
            String from = fake ? "fake" : p.getString("url", "");
            if (!from.equals(source)) { // a different source: what we have is not its data
                readings = Collections.emptyList();
                source = from;
                handler.post(() -> redraw(true));
            }
            try {
                readings = fake ? Fake.series(p.getInt("fake_value", 120), p.getString("fake_direction", "Flat"),
                        p.getBoolean("fake_stale", false), System.currentTimeMillis()) : fetch();
                failing = false;
                status = getString(R.string.status_updated,
                        DateFormat.getTimeFormat(this).format(new Date()), readings.size());
            } catch (Throwable e) { // keep the old readings: their age shows they are getting stale
                // Throwable, not Exception: a hostile or broken answer must not crash-loop the service
                String error = e.toString(), secret = p.getString("secret", "");
                if (!secret.isEmpty()) error = error.replace(secret, "…"); // some errors quote the address, token included
                failing = true;
                status = getString(R.string.status_error, error);
            }
            try {
                redraw(getSystemService(PowerManager.class).isInteractive());
            } catch (Throwable e) {
                status = getString(R.string.status_error, e.toString());
            }
        });
    }

    /**
     * Updates the notification and the widgets. With the screen off, the notification is only reposted
     * when something that matters changed (value, arrow, stale, demo, or the error with no readings):
     * every update counts as an interruption, and the screen-on redraw brings the age up to date anyway.
     */
    private void redraw(boolean screenOn) {
        List<Glucose.Reading> r = readings;
        long now = System.currentTimeMillis();
        String key;
        if (r.isEmpty()) {
            key = "none:" + failing;
        } else {
            Glucose.Reading last = r.get(0);
            boolean stale = Glucose.stale(last, now);
            key = last.sgv + ":" + (stale ? "" : last.direction) + ":" + stale + ":" + demo(this);
        }
        if (screenOn || !key.equals(shown)) {
            shown = key;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
        GlucoseWidget.updateAll(this);
    }

    private List<Glucose.Reading> fetch() throws Exception {
        SharedPreferences p = prefs(this);
        String base = p.getString("url", "").replaceAll("/+$", "");
        String secret = p.getString("secret", "");
        if (base.isEmpty()) throw new IOException(getString(R.string.error_no_url));
        // Also checked here, not only when saving: 1.1.0 accepted plain http to any address
        if (!Glucose.safeAddress(base)) throw new IOException(getString(R.string.error_insecure));
        if (!Glucose.secretAllowed(base, secret)) throw new IOException(getString(R.string.error_http_secret));
        boolean token = Glucose.isToken(secret);
        String url = base + "/api/v1/entries/sgv.json?count=1000&find%5Bdate%5D%5B%24gte%5D="
                + (System.currentTimeMillis() - Glucose.WINDOW_MS) + (token ? "&token=" + secret : "");

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        // A redirect would carry the api-secret header to wherever it points: stop and say where instead
        c.setInstanceFollowRedirects(false);
        if (!secret.isEmpty() && !token) c.setRequestProperty("api-secret", sha1(secret));
        // The timeouts only bound each read; this bounds the whole request, so a server trickling its
        // answer cannot hold the fetch (and the display behind it) forever
        Runnable abort = c::disconnect;
        handler.postDelayed(abort, FETCH_DEADLINE_MS);
        try {
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) throw new IOException(getString(R.string.error_redirect, site(c.getHeaderField("Location"))));
            if (code != 200) throw new IOException(getString(R.string.error_http, code));
            JSONArray entries = new JSONArray(new Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A").next());
            List<Glucose.Reading> out = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                int sgv = e.optInt("sgv");
                // below 39 are sensor error codes, not glucose
                if (sgv >= 39) out.add(new Glucose.Reading(e.getLong("date"), sgv, e.optString("direction")));
            }
            out.sort((a, b) -> Long.compare(b.date, a.date));
            return out;
        } finally {
            handler.removeCallbacks(abort);
            c.disconnect();
        }
    }

    /** Just scheme and host of a redirect target: the full address could echo the token back. */
    private static String site(String location) {
        try {
            java.net.URI u = new java.net.URI(location);
            return u.getScheme() + "://" + u.getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Debug builds only: what is shown is made up, and everything showing it must say so. Taken from
     * where the readings came from, not from the setting, which can change before they are replaced.
     */
    static boolean demo(Context context) {
        return BuildConfig.DEBUG && "fake".equals(source);
    }

    private static String sha1(String s) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private Notification notification() {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_drop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        List<Glucose.Reading> r = readings;
        // Never the error itself here: it can name the Nightscout site, and this shows on the lock screen
        if (r.isEmpty()) return b.setContentTitle(getString(failing ? R.string.notify_error : R.string.status_waiting)).build();

        long now = System.currentTimeMillis();
        Glucose.Config c = config(this);
        Glucose.Reading last = r.get(0);
        boolean stale = Glucose.stale(last, now);
        String value = Glucose.value(last.sgv, c);
        String arrow = stale ? "" : " " + Glucose.arrow(last.direction);
        String delta = stale ? "" : Glucose.delta(r, c); // an old trend is not a trend
        // The number in the status bar only while it is current and real; otherwise the plain drop
        if (!stale && !demo(this)) b.setSmallIcon(Icon.createWithBitmap(statusBarIcon(value)));
        return b.setContentTitle((demo(this) ? "DEMO  " : "") + value + arrow + (delta.isEmpty() ? "" : "   " + delta))
                .setContentText(ago(this, last, now))
                .build();
    }

    /** The value itself as the status bar icon, so it shows without pulling the shade down. */
    private Bitmap statusBarIcon(String value) {
        int size = Math.round(24 * getResources().getDisplayMetrics().density);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF); // the system tints it; only the alpha matters
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        // The system shrinks the whole square, so the digits have to fill it to read as big as the clock:
        // digits 90% of the height, squeezed up to 40% sideways, and only then made smaller to fit
        Rect digits = new Rect();
        p.setTextSize(size);
        p.getTextBounds(value, 0, value.length(), digits);
        p.setTextSize(size * 0.9f * size / digits.height());
        float width = p.measureText(value);
        if (width > size) p.setTextScaleX(Math.max(0.6f, size / width));
        width = p.measureText(value); // already includes the squeeze
        if (width > size) p.setTextSize(p.getTextSize() * size / width);
        p.getTextBounds(value, 0, value.length(), digits);
        Bitmap icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(icon).drawText(value, size / 2f, size / 2f + digits.height() / 2f, p);
        return icon;
    }
}
