package io.github.federicomameli1.sugarglass;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** Home: the current value and the last 3 hours, with the settings folded away underneath. */
public class MainActivity extends Activity {

    private static final int GRAPH_DP = 220;
    private static final int NUMBER = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    // "" follows the system; the names are written in their own language, as every language picker does
    private static final String[] LANGUAGES = {"", "en", "it", "fr", "de", "es", "pt", "nl", "pl"};
    private static final String[] LANGUAGE_NAMES =
            {null, "English", "Italiano", "Français", "Deutsch", "Español", "Português", "Nederlands", "Polski"};

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
    private TextView value, arrow, info, status;
    private ImageView graph;
    private LinearLayout settings;
    private EditText url, secret, urgentLow, low, high, urgentHigh;
    private RadioButton mmol;
    private Spinner language;

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
        arrow = text(48, primary);
        arrow.setPadding(dp(8), 0, 0, dp(14));
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

        Button toggle = new Button(this, null, android.R.attr.borderlessButtonStyle);
        toggle.setText(R.string.settings);
        toggle.setTextColor(secondary);
        toggle.setOnClickListener(v -> settings.setVisibility(
                settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        LinearLayout.LayoutParams toggleAt = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        toggleAt.topMargin = dp(32);
        toggleAt.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(toggle, toggleAt);

        root.addView(settings());

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
    }

    private View settings() {
        settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        url = field(R.string.nightscout_url, GlucoseService.prefs(this).getString("url", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        secret = field(R.string.nightscout_secret, GlucoseService.prefs(this).getString("secret", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        Glucose.Config c = GlucoseService.config(this);
        settings.addView(label(R.string.units));
        RadioGroup units = new RadioGroup(this);
        units.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton mgdl = new RadioButton(this);
        mgdl.setText("mg/dL");
        mmol = new RadioButton(this);
        mmol.setText("mmol/L");
        units.addView(mgdl);
        units.addView(mmol);
        (c.mmol ? mmol : mgdl).setChecked(true);
        settings.addView(units);

        settings.addView(label(R.string.ranges));
        urgentLow = field(R.string.urgent_low, format(c.urgentLow, c.mmol), NUMBER);
        low = field(R.string.low, format(c.low, c.mmol), NUMBER);
        high = field(R.string.high, format(c.high, c.mmol), NUMBER);
        urgentHigh = field(R.string.urgent_high, format(c.urgentHigh, c.mmol), NUMBER);
        // Switching unit converts what is typed, so the thresholds keep meaning the same glucose
        units.setOnCheckedChangeListener((group, id) -> {
            boolean toMmol = id == mmol.getId();
            for (EditText e : new EditText[]{urgentLow, low, high, urgentHigh}) {
                int mgdlValue = toMgdl(e, !toMmol);
                if (mgdlValue > 0) e.setText(format(mgdlValue, toMmol));
            }
        });

        settings.addView(label(R.string.glow));
        SeekBar glow = new SeekBar(this);
        glow.setMax(100);
        glow.setProgress(GlucoseService.prefs(this).getInt("glow", 50));
        glow.setContentDescription(getString(R.string.glow));
        glow.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                GlucoseService.prefs(MainActivity.this).edit().putInt("glow", progress).apply();
                show(); // live preview on the big graph
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                GlucoseWidget.updateAll(MainActivity.this);
            }
        });
        settings.addView(glow);

        settings.addView(label(R.string.language));
        String[] names = LANGUAGE_NAMES.clone();
        names[0] = getString(R.string.system_language);
        language = new Spinner(this);
        language.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        language.setSelection(Math.max(0, java.util.Arrays.asList(LANGUAGES)
                .indexOf(GlucoseService.prefs(this).getString("language", ""))));
        settings.addView(language);

        Button save = new Button(this);
        save.setText(R.string.save);
        save.setOnClickListener(v -> save());
        settings.addView(save);
        status = text(13, secondary);
        settings.addView(status);
        // Open straight onto the settings only when there is nothing to show without them
        settings.setVisibility(url.getText().length() == 0 ? View.VISIBLE : View.GONE);
        return settings;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also revives the service if the system killed it: an app in the foreground is always allowed to
        if (url.getText().length() > 0) GlucoseService.start(this);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void show() {
        String s = GlucoseService.status;
        status.setText(s.isEmpty() ? getString(R.string.status_waiting) : s);
        status.setTextColor(secondary);
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
        arrow.setText(stale ? "" : Glucose.arrow(last.direction));
        arrow.setTextColor(hue);
        String unit = c.mmol ? " mmol/L" : " mg/dL";
        info.setText((delta.isEmpty() ? "" : delta + unit + "  ·  ") + GlucoseService.ago(this, last, now));
        info.setTextColor(stale ? getColor(R.color.range_warn) : secondary);

        int w = getResources().getDisplayMetrics().widthPixels - 2 * dp(24);
        graph.setImageBitmap(Art.draw(r, w, dp(GRAPH_DP), density, now, hue, GlucoseService.glowAlpha(this),
                getColor(R.color.line), true));
    }

    private void save() {
        boolean toMmol = mmol.isChecked();
        Glucose.Config c = new Glucose.Config(toMmol, toMgdl(urgentLow, toMmol), toMgdl(low, toMmol),
                toMgdl(high, toMmol), toMgdl(urgentHigh, toMmol));
        if (!c.valid()) {
            status.setText(R.string.ranges_invalid);
            status.setTextColor(getColor(R.color.range_urgent));
            return;
        }
        String address = url.getText().toString().trim();
        if (!address.isEmpty() && !address.contains("://")) address = "https://" + address;
        url.setText(address);
        String chosen = LANGUAGES[language.getSelectedItemPosition()];
        boolean newLanguage = !chosen.equals(GlucoseService.prefs(this).getString("language", ""));
        GlucoseService.prefs(this).edit()
                .putString("language", chosen)
                .putString("url", address)
                .putString("secret", secret.getText().toString().trim())
                .putBoolean("mmol", c.mmol)
                .putInt("urgent_low", c.urgentLow)
                .putInt("low", c.low)
                .putInt("high", c.high)
                .putInt("urgent_high", c.urgentHigh)
                .apply();

        // Without the exemption Android may stop the service and refuse to restart it in the background
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
        if (newLanguage) {
            // The service, its notification and the widgets were built in the old language
            stopService(new Intent(this, GlucoseService.class));
            GlucoseService.start(this);
            GlucoseWidget.updateAll(this);
            recreate();
            return;
        }
        GlucoseService.start(this);
        status.setText(R.string.status_updating);
        status.setTextColor(secondary);
        show();
    }

    private static String format(int mgdl, boolean mmol) {
        return mmol ? String.format(Locale.getDefault(), "%.1f", mgdl / Glucose.MGDL_PER_MMOL) : String.valueOf(mgdl);
    }

    /** What the field holds, in mg/dl; 0 when it is not a number, which the range check then rejects. */
    private static int toMgdl(EditText field, boolean mmol) {
        try {
            double v = Double.parseDouble(field.getText().toString().trim().replace(',', '.'));
            return (int) Math.round(mmol ? v * Glucose.MGDL_PER_MMOL : v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private EditText field(int label, String text, int inputType) {
        TextView name = label(label);
        EditText edit = new EditText(this);
        edit.setId(View.generateViewId());
        edit.setInputType(inputType);
        edit.setText(text);
        edit.setTextColor(primary);
        name.setLabelFor(edit.getId());
        settings.addView(name);
        settings.addView(edit);
        return edit;
    }

    private TextView label(int text) {
        TextView t = text(13, secondary);
        t.setText(text);
        t.setPadding(0, dp(12), 0, 0);
        return t;
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
