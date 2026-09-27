package io.github.federicomameli1.sugarglass;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
        update(context, manager, ids);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        manager.updateAppWidget(id, render(context, options));
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        update(context, manager, manager.getAppWidgetIds(new ComponentName(context, GlucoseWidget.class)));
    }

    private static void update(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, render(context, manager.getAppWidgetOptions(id)));
    }

    private static RemoteViews render(Context base, Bundle options) {
        // From the application context: it follows light/dark switches, while the service's own
        // localized context is a snapshot taken when it started
        Context context = GlucoseService.localized(base.getApplicationContext());
        // Portrait cell size: MIN_WIDTH is the portrait width, MAX_HEIGHT the portrait height
        int widthDp = Math.max(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH), 40);
        int heightDp = Math.max(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT), 40);
        boolean small = widthDp < SMALL_BELOW_DP;

        RemoteViews v = new RemoteViews(context.getPackageName(), small ? R.layout.widget_small : R.layout.widget);
        v.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        List<Glucose.Reading> r = GlucoseService.readings;
        if (r.isEmpty()) return v; // the layout shows "---"

        long now = System.currentTimeMillis();
        Glucose.Config c = GlucoseService.config(context);
        Glucose.Reading last = r.get(0);
        boolean stale = Glucose.stale(last, now);
        int hue = GlucoseService.color(context, last, now, c);
        int primary = context.getColor(R.color.text_primary);
        long minutes = Glucose.minutesAgo(last, now);
        String delta = Glucose.delta(r, c), age = minutes == 0 ? context.getString(R.string.now) : minutes + "m";

        v.setTextViewText(R.id.value, Glucose.value(last.sgv, c));
        v.setTextColor(R.id.value, stale ? (primary & 0xFFFFFF) | 0x80000000 : primary);
        v.setTextViewText(R.id.arrow, stale ? "" : Glucose.arrow(last.direction));
        v.setTextColor(R.id.arrow, hue);
        v.setTextViewText(R.id.info, small || delta.isEmpty() ? age : delta + " · " + age);
        v.setTextColor(R.id.info, stale ? context.getColor(R.color.range_warn) : context.getColor(R.color.text_secondary));

        float density = context.getResources().getDisplayMetrics().density;
        v.setImageViewBitmap(R.id.graph, Art.draw(r, Math.round(widthDp * density), Math.round(heightDp * density),
                density, now, hue, GlucoseService.glowAlpha(context), context.getColor(R.color.line), !small));
        return v;
    }
}
