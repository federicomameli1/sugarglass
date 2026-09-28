package io.github.federicomameli1.sugarglass;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Home: the current value and the last 3 hours. Settings live in {@link SettingsActivity}. */
public class MainActivity extends Activity {

    private static final int GRAPH_DP = 220;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            show();
            handler.postDelayed(this, 10_000); // the service fetches every minute; this just follows it
        }
    };

    private float density;
    private int primary, secondary;
    private TextView value, info;
    private ImageView arrow, graph;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(GlucoseService.localized(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        primary = getColor(R.color.text_primary);
        secondary = getColor(R.color.text_secondary);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));

        LinearLayout reading = new LinearLayout(this);
        reading.setGravity(Gravity.BOTTOM);
        value = text(96, primary);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setLetterSpacing(-0.03f);
        value.setText("---");
        arrow = new ImageView(this); // drawn, like the widget's: round-ended, not a font glyph
        arrow.setPadding(dp(10), 0, 0, dp(26));
        reading.addView(value);
        reading.addView(arrow);
        root.addView(reading);

        info = text(17, secondary);
        root.addView(info);

        graph = new ImageView(this);
        graph.setBackgroundResource(R.drawable.glass);
        graph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams graphSize =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(GRAPH_DP));
        graphSize.topMargin = dp(28);
        root.addView(graph, graphSize);

        TextView window = text(13, secondary);
        window.setText(R.string.last_3h);
        window.setPadding(0, dp(8), 0, 0);
        root.addView(window);

        Button settings = new Button(this, null, android.R.attr.borderlessButtonStyle);
        settings.setText(R.string.settings);
        settings.setTextColor(secondary);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams settingsAt = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        settingsAt.topMargin = dp(32);
        settingsAt.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(settings, settingsAt);

        TextView disclaimer = text(12, secondary);
        disclaimer.setText(R.string.disclaimer);
        disclaimer.setGravity(Gravity.CENTER);
        disclaimer.setPadding(0, dp(24), 0, 0);
        root.addView(disclaimer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        // First run: nothing to show until Nightscout is set, so go straight there
        if (state == null && GlucoseService.prefs(this).getString("url", "").isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class)
                    .putExtra(SettingsActivity.PAGE, SettingsActivity.NIGHTSCOUT));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also revives the service if the system killed it: an app in the foreground is always allowed to
        if (!GlucoseService.prefs(this).getString("url", "").isEmpty()) GlucoseService.start(this);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void show() {
        List<Glucose.Reading> r = GlucoseService.readings;
        if (r.isEmpty()) return;
        long now = System.currentTimeMillis();
        Glucose.Config c = GlucoseService.config(this);
        Glucose.Reading last = r.get(0);
        boolean stale = Glucose.stale(last, now);
        int hue = GlucoseService.color(this, last, now, c);
        String delta = Glucose.delta(r, c);

        value.setText(Glucose.value(last.sgv, c));
        value.setTextColor(stale ? (primary & 0xFFFFFF) | 0x80000000 : primary);
        arrow.setImageBitmap(Art.arrow(stale ? "" : last.direction, dp(44), hue));
        arrow.setContentDescription(Glucose.arrow(stale ? "" : last.direction));
        String unit = c.mmol ? " mmol/L" : " mg/dL";
        info.setText((delta.isEmpty() ? "" : delta + unit + "  ·  ") + GlucoseService.ago(this, last, now));
        info.setTextColor(stale ? getColor(R.color.range_warn) : secondary);

        int w = getResources().getDisplayMetrics().widthPixels - 2 * dp(24);
        graph.setImageBitmap(Art.draw(r, w, dp(GRAPH_DP), density, now, hue, GlucoseService.glowAlpha(this),
                getColor(R.color.line), true, c, GlucoseService.fixedScale(this), GlucoseService.rangeLines(this),
                0)); // nothing over it: all crisp
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float dp) {
        return Math.round(dp * density);
    }
}
