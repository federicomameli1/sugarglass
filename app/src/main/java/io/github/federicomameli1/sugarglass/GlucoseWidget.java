package io.github.federicomameli1.sugarglass;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.List;

/**
 * One widget, any size: 1x1 shows the value over the range glow, 2x1 and wider add the 3-hour line
 * (see {@link Art}).
 */
public class GlucoseWidget extends AppWidgetProvider {

    private static final int SMALL_BELOW_DP = 100; // narrower than two cells

    @Override
    public void onReceive(Context context, Intent intent) {
        // Also the app's boot and update receiver: the service has to come back on its own
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            GlucoseService.start(context);
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        // The ids in the broadcast are ignored: the receiver is exported (it has to be), so any app could
        // send thousands of them and stall the app; only our own widgets get drawn
        updateAll(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        update(context, manager, new int[]{id});
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        update(context, manager, manager.getAppWidgetIds(new ComponentName(context, GlucoseWidget.class)));
    }

    private static void update(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            try {
                manager.updateAppWidget(id, render(context, manager.getAppWidgetOptions(id)));
            } catch (Throwable e) { // one bad widget, or an odd size from a launcher, must not crash the app
                try {
                    manager.updateAppWidget(id, new RemoteViews(context.getPackageName(), R.layout.widget));
                } catch (Throwable ignored) {
                    // nothing more to try
                }
            }
        }
    }

    private static RemoteViews render(Context base, Bundle options) {
        // From the application context: it follows light/dark switches, while the service's own
        // localized context is a snapshot taken when it started
        Context context = GlucoseService.themed(GlucoseService.localized(base.getApplicationContext()));
        // Portrait cell size: MIN_WIDTH is the portrait width, MAX_HEIGHT the portrait height
        // ...clamped to the screen, so a launcher reporting a silly size cannot make us run out of memory
        android.util.DisplayMetrics screen = context.getResources().getDisplayMetrics();
        int maxWidthDp = Math.round(screen.widthPixels / screen.density), maxHeightDp = Math.round(screen.heightPixels / screen.density);
        int widthDp = Math.min(Math.max(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), 40), maxWidthDp);
        int heightDp = Math.min(Math.max(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT), 40), maxHeightDp);
        boolean small = widthDp < SMALL_BELOW_DP;

        RemoteViews v = new RemoteViews(context.getPackageName(), small ? R.layout.widget_small : R.layout.widget);
        v.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        int look = GlucoseService.look(context);
        // The launcher picks the automatic glass by its own mode; a forced look names its glass outright.
        // Set every time, automatic included: the launcher reuses the previous view, so whatever a render
        // does not set keeps the old value
        v.setInt(android.R.id.background, "setBackgroundResource", look == GlucoseService.LOOK_DARK
                ? R.drawable.glass_dark : look == GlucoseService.LOOK_LIGHT ? R.drawable.glass_light : R.drawable.glass);
        List<Glucose.Reading> r = GlucoseService.readings;
        if (r.isEmpty()) { // no data: clear everything a previous render drew
            v.setTextViewText(R.id.info, "---");
            v.setTextColor(R.id.info, context.getColor(R.color.text_secondary));
            v.setImageViewResource(R.id.graph, 0);
            v.setContentDescription(R.id.graph, "---");
            return v;
        }

        float density = context.getResources().getDisplayMetrics().density;
        Face face = face(context, r, Math.round(widthDp * density), Math.round(heightDp * density), small,
                GlucoseService.fixedScale(context));
        v.setTextViewText(R.id.info, face.info);
        v.setTextColor(R.id.info, face.infoColor);
        v.setImageViewBitmap(R.id.graph, face.picture);
        v.setContentDescription(R.id.graph, face.description);
        return v;
    }

    /** What a widget of this size shows, also used for the preview in the settings. */
    static final class Face {
        final Bitmap picture;
        final String info, description;
        final int infoColor;

        private Face(Bitmap picture, String info, int infoColor, String description) {
            this.picture = picture;
            this.info = info;
            this.infoColor = infoColor;
            this.description = description;
        }
    }

    /**
     * @param context already in the widget's language and look, so every colour and word matches
     * @param r       readings, newest first, not empty
     * @param fixed   the graph's fixed scale, or null to fit the data (the preview passes the one being typed)
     */
    static Face face(Context context, List<Glucose.Reading> r, int w, int h, boolean small, int[] fixed) {
        long now = System.currentTimeMillis();
        Glucose.Config c = GlucoseService.config(context);
        Glucose.Reading last = r.get(0);
        boolean stale = Glucose.stale(last, now);
        // After 5 minutes with nothing new the value goes, until a reading comes: a home screen widget can
        // stay on screen long after the app stopped updating it, and then an old number looks current
        boolean hidden = Glucose.hidden(last, now);
        int hue = GlucoseService.color(context, last, now, c);
        int primary = context.getColor(R.color.text_primary);
        long minutes = Glucose.minutesAgo(last, now);
        String delta = hidden ? "" : Glucose.delta(r, c), age = minutes == 0 ? context.getString(R.string.now) : minutes + "m";
        String value = hidden ? "---" : Glucose.value(last.sgv, c);
        String info = small || delta.isEmpty() ? age : delta + " · " + age;
        if (GlucoseService.demo(context)) info = "DEMO · " + info; // made-up readings must never pass for real ones

        Art.Reading shown = new Art.Reading(value, hidden ? "" : last.direction,
                hidden ? context.getColor(R.color.text_secondary) : primary,
                hidden ? context.getColor(R.color.stale) : hue);
        Bitmap picture = Art.widget(r, shown, w, h, context.getResources().getDisplayMetrics().density, now,
                GlucoseService.glowAlpha(context), context.getColor(R.color.line), c,
                fixed, GlucoseService.rangeLines(context), small);
        int infoColor = hidden || stale ? context.getColor(R.color.range_warn) : context.getColor(R.color.text_secondary);
        // The value and arrow are pixels, so screen readers get them in words
        return new Face(picture, info, infoColor, value + " " + Glucose.arrow(hidden ? "" : last.direction) + ", " + info);
    }
}
