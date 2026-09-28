package io.github.federicomameli1.sugarglass;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Locale;

/**
 * Settings, one page per topic: a menu, then Nightscout, units and ranges, widget and graph, language,
 * and in debug builds the fake readings. The page is chosen by the {@link #PAGE} extra; none is the menu.
 */
public class SettingsActivity extends Activity {

    static final String PAGE = "page";
    static final String NIGHTSCOUT = "nightscout", RANGES = "ranges", DISPLAY = "display",
            LANGUAGE = "language", DEBUG = "debug";

    private static final int NUMBER = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    // "" follows the system; the names are written in their own language, as every language picker does
    private static final String[] LANGUAGES = {"", "en", "it", "fr", "de", "es", "pt", "nl", "pl"};
    private static final String[] LANGUAGE_NAMES =
            {null, "English", "Italiano", "Français", "Deutsch", "Español", "Português", "Nederlands", "Polski"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private float density;
    private int primary, secondary;
    private LinearLayout page;
    private TextView status;
    private FrameLayout preview;
    private ImageView previewPicture;
    private TextView previewInfo;

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
        MainActivity.paintBars(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(16), dp(24), dp(24));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setContentDescription(getString(R.string.back));
        back.setPadding(dp(4), dp(10), dp(10), dp(10));
        back.setOnClickListener(v -> finish());
        page.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        String which = getIntent().getStringExtra(PAGE);
        if (NIGHTSCOUT.equals(which)) nightscout();
        else if (RANGES.equals(which)) ranges();
        else if (DISPLAY.equals(which)) display();
        else if (LANGUAGE.equals(which)) language();
        else if (DEBUG.equals(which) && BuildConfig.DEBUG) debug();
        else menu();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    // Pages

    private void menu() {
        title(getString(R.string.settings));
        row("Nightscout", NIGHTSCOUT);
        row(getString(R.string.section_ranges), RANGES);
        row(getString(R.string.section_display), DISPLAY);
        row(getString(R.string.language), LANGUAGE);
        if (BuildConfig.DEBUG) row("Debug", DEBUG);
    }

    private void nightscout() {
        title("Nightscout");
        SharedPreferences p = GlucoseService.prefs(this);
        EditText url = field(R.string.nightscout_url, p.getString("url", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText secret = field(R.string.nightscout_secret, p.getString("secret", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(R.string.save, () -> {
            String address = url.getText().toString().trim();
            if (!address.isEmpty() && !address.contains("://")) address = "https://" + address;
            url.setText(address);
            p.edit().putString("url", address).putString("secret", secret.getText().toString().trim()).apply();
            // Without the exemption Android may stop the service and refuse to restart it in the background
            if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
            GlucoseService.start(this);
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            say(getString(R.string.status_updating), secondary);
        });
        status();
        // Follow the service's fetch outcome, so a wrong address or token shows up here within seconds
        handler.post(new Runnable() {
            @Override
            public void run() {
                String s = GlucoseService.status;
                if (!s.isEmpty()) say(s, secondary);
                handler.postDelayed(this, 2000);
            }
        });
    }

    private void ranges() {
        title(getString(R.string.section_ranges));
        Glucose.Config c = GlucoseService.config(this);
        page.addView(label(R.string.units));
        RadioGroup units = new RadioGroup(this);
        units.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton mgdl = radio("mg/dL"), mmol = radio("mmol/L");
        units.addView(mgdl);
        units.addView(mmol);
        (c.mmol ? mmol : mgdl).setChecked(true);
        page.addView(units);

        EditText urgentLow = field(R.string.urgent_low, format(c.urgentLow, c.mmol), NUMBER);
        EditText low = field(R.string.low, format(c.low, c.mmol), NUMBER);
        EditText high = field(R.string.high, format(c.high, c.mmol), NUMBER);
        EditText urgentHigh = field(R.string.urgent_high, format(c.urgentHigh, c.mmol), NUMBER);
        EditText[] all = {urgentLow, low, high, urgentHigh};
        // Switching unit converts what is typed, so the thresholds keep meaning the same glucose
        units.setOnCheckedChangeListener((group, id) -> convert(all, id == mmol.getId()));

        button(R.string.save_short, () -> {
            boolean inMmol = mmol.isChecked();
            Glucose.Config chosen = new Glucose.Config(inMmol, toMgdl(urgentLow, inMmol), toMgdl(low, inMmol),
                    toMgdl(high, inMmol), toMgdl(urgentHigh, inMmol));
            if (!chosen.valid()) {
                say(getString(R.string.ranges_invalid), getColor(R.color.range_urgent));
                return;
            }
            GlucoseService.prefs(this).edit()
                    .putBoolean("mmol", chosen.mmol)
                    .putInt("urgent_low", chosen.urgentLow)
                    .putInt("low", chosen.low)
                    .putInt("high", chosen.high)
                    .putInt("urgent_high", chosen.urgentHigh)
                    .apply();
            applied();
            saved();
        });
        status();
    }

    private void display() {
        title(getString(R.string.section_display));
        SharedPreferences p = GlucoseService.prefs(this);
        previewWidget();

        // Look, glow and the switches apply at once, as a preview; nothing typed, so nothing to validate
        page.addView(label(R.string.widget_look));
        RadioGroup looks = new RadioGroup(this);
        looks.setOrientation(RadioGroup.HORIZONTAL);
        int[] names = {R.string.look_auto, R.string.look_dark, R.string.look_light};
        for (int i = 0; i < names.length; i++) {
            RadioButton b = radio(getString(names[i]));
            b.setTag(i);
            looks.addView(b);
            if (i == GlucoseService.look(this)) b.setChecked(true);
        }
        looks.setOnCheckedChangeListener((group, id) -> {
            p.edit().putInt("look", (int) group.findViewById(id).getTag()).apply();
            GlucoseWidget.updateAll(this);
            drawPreview();
        });
        page.addView(looks);

        page.addView(label(R.string.glow));
        SeekBar glow = new SeekBar(this);
        glow.setMax(100);
        glow.setProgress(p.getInt("glow", 50));
        glow.setContentDescription(getString(R.string.glow));
        glow.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                p.edit().putInt("glow", progress).apply();
                drawPreview();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                GlucoseWidget.updateAll(SettingsActivity.this);
            }
        });
        page.addView(glow);

        CheckBox lines = new CheckBox(this);
        lines.setText(R.string.range_lines);
        lines.setChecked(GlucoseService.rangeLines(this));
        lines.setOnCheckedChangeListener((box, on) -> {
            p.edit().putBoolean("range_lines", on).apply();
            applied();
            drawPreview();
        });
        LinearLayout.LayoutParams linesAt = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        linesAt.topMargin = dp(16);
        page.addView(lines, linesAt);

        boolean mmol = GlucoseService.config(this).mmol;
        CheckBox fixed = new CheckBox(this);
        fixed.setText(R.string.fixed_scale);
        fixed.setChecked(p.getBoolean("fixed_scale", false));
        LinearLayout.LayoutParams fixedAt = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fixedAt.topMargin = dp(4);
        page.addView(fixed, fixedAt);

        LinearLayout scale = new LinearLayout(this);
        scale.setOrientation(LinearLayout.VERTICAL);
        LinearLayout outer = page;
        page = scale; // the fields go in their own group, shown only with a fixed scale
        String unit = mmol ? " (mmol/L)" : " (mg/dL)";
        EditText bottom = field(getString(R.string.scale_bottom) + unit,
                format(p.getInt("scale_min", Glucose.FIXED_MIN), mmol), NUMBER);
        EditText top = field(getString(R.string.scale_top) + unit,
                format(p.getInt("scale_max", Glucose.FIXED_MAX), mmol), NUMBER);
        page = outer;
        page.addView(scale);
        scale.setVisibility(fixed.isChecked() ? View.VISIBLE : View.GONE);
        fixed.setOnCheckedChangeListener((box, on) -> {
            p.edit().putBoolean("fixed_scale", on).apply();
            scale.setVisibility(on ? View.VISIBLE : View.GONE);
            applied();
            drawPreview();
        });
        // Look, glow and switches already show while you change them; Save stores the typed scale and closes
        button(R.string.save_short, () -> {
            if (fixed.isChecked()) {
                int from = toMgdl(bottom, mmol), to = toMgdl(top, mmol);
                if (from < 20 || to > 600 || from >= to) {
                    say(getString(R.string.scale_invalid), getColor(R.color.range_urgent));
                    return;
                }
                p.edit().putInt("scale_min", from).putInt("scale_max", to).apply();
            }
            applied();
            saved();
        });
        status();
    }

    private void language() {
        title(getString(R.string.language));
        String current = GlucoseService.prefs(this).getString("language", "");
        RadioGroup group = new RadioGroup(this);
        for (int i = 0; i < LANGUAGES.length; i++) {
            RadioButton b = radio(i == 0 ? getString(R.string.system_language) : LANGUAGE_NAMES[i]);
            b.setTag(LANGUAGES[i]);
            group.addView(b);
            if (LANGUAGES[i].equals(current)) b.setChecked(true);
        }
        page.addView(group);
        button(R.string.save_short, () -> {
            String chosen = (String) group.findViewById(group.getCheckedRadioButtonId()).getTag();
            if (chosen.equals(current)) {
                saved();
                return;
            }
            GlucoseService.prefs(this).edit().putString("language", chosen).commit();
            // The service, its notification, the widgets and these screens were built in the old language
            stopService(new Intent(this, GlucoseService.class));
            GlucoseService.start(this);
            GlucoseWidget.updateAll(this);
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        });
    }

    /** Debug builds only: pick a value, trend and age and see widget, notification and status bar with it. */
    private void debug() {
        title("Debug");
        SharedPreferences p = GlucoseService.prefs(this);
        TextView note = text(13, secondary);
        note.setText("Fake readings, for testing the look. Not translated: it is a developer tool.");
        page.addView(note);

        CheckBox fake = new CheckBox(this);
        fake.setText("Use fake readings instead of Nightscout");
        fake.setChecked(p.getBoolean("fake", false));
        fake.setOnCheckedChangeListener((box, on) -> fakeChanged("fake", on));
        page.addView(fake);

        TextView valueLabel = text(13, secondary);
        valueLabel.setText("Value: " + p.getInt("fake_value", 120) + " mg/dL");
        page.addView(valueLabel);
        SeekBar value = new SeekBar(this);
        value.setMax(400 - 40);
        value.setProgress(p.getInt("fake_value", 120) - 40);
        value.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                valueLabel.setText("Value: " + (progress + 40) + " mg/dL");
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                fakeChanged("fake_value", bar.getProgress() + 40);
            }
        });
        page.addView(value);

        Spinner direction = new Spinner(this);
        String[] arrows = new String[Fake.DIRECTIONS.length];
        for (int i = 0; i < arrows.length; i++) arrows[i] = Glucose.arrow(Fake.DIRECTIONS[i]) + "  " + Fake.DIRECTIONS[i];
        direction.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, arrows));
        direction.setSelection(Math.max(0, Arrays.asList(Fake.DIRECTIONS).indexOf(p.getString("fake_direction", "Flat"))));
        direction.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!Fake.DIRECTIONS[position].equals(p.getString("fake_direction", "Flat"))) {
                    fakeChanged("fake_direction", Fake.DIRECTIONS[position]);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        page.addView(direction);

        CheckBox stale = new CheckBox(this);
        stale.setText("Last reading 20 minutes old");
        stale.setChecked(p.getBoolean("fake_stale", false));
        stale.setOnCheckedChangeListener((box, on) -> fakeChanged("fake_stale", on));
        page.addView(stale);
        button(R.string.save_short, this::saved);
    }

    /**
     * A 2x1 widget at the top of the widget page, drawn by the same code as the real one, so every change
     * here shows before leaving the page. Without readings yet, it shows made-up ones.
     */
    private void previewWidget() {
        int w = Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(320));
        preview = new FrameLayout(this);
        previewPicture = new ImageView(this);
        previewPicture.setScaleType(ImageView.ScaleType.FIT_XY);
        preview.addView(previewPicture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        previewInfo = text(12, secondary);
        previewInfo.setPadding(dp(14), 0, dp(14), dp(7));
        previewInfo.setSingleLine();
        preview.addView(previewInfo, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(w, Math.round(w * 0.46f));
        at.gravity = Gravity.CENTER_HORIZONTAL;
        at.bottomMargin = dp(8);
        page.addView(preview, at);
        preview.post(this::drawPreview); // sized once laid out
    }

    private void drawPreview() {
        if (preview == null || preview.getWidth() == 0) return;
        Context looked = GlucoseService.themed(this);
        int look = GlucoseService.look(this);
        preview.setBackgroundResource(look == GlucoseService.LOOK_DARK ? R.drawable.glass_dark
                : look == GlucoseService.LOOK_LIGHT ? R.drawable.glass_light : R.drawable.glass);
        java.util.List<Glucose.Reading> r = GlucoseService.readings;
        if (r.isEmpty()) r = Fake.series(120, "FortyFiveUp", false, System.currentTimeMillis());
        GlucoseWidget.Face face = GlucoseWidget.face(looked, r, preview.getWidth(), preview.getHeight(), false);
        previewPicture.setImageBitmap(face.picture);
        previewPicture.setContentDescription(face.description);
        previewInfo.setText(face.info);
        previewInfo.setTextColor(face.infoColor);
    }

    // Shared bits

    /** Confirms and goes back to where the page was opened from. */
    private void saved() {
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Widgets and notification redraw with what was just saved. */
    private void applied() {
        GlucoseWidget.updateAll(this);
        GlucoseService.start(this);
    }

    /** Stores a debug setting and has the service redraw everything with it right away. */
    private void fakeChanged(String key, Object value) {
        SharedPreferences.Editor e = GlucoseService.prefs(this).edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) e.putInt(key, (Integer) value);
        else e.putString(key, (String) value);
        e.commit(); // the service reads it straight away
        GlucoseService.start(this);
    }

    private void convert(EditText[] fields, boolean toMmol) {
        for (EditText e : fields) {
            int mgdl = toMgdl(e, !toMmol);
            if (mgdl > 0) e.setText(format(mgdl, toMmol));
        }
    }

    private static String format(int mgdl, boolean mmol) {
        return mmol ? String.format(Locale.getDefault(), "%.1f", mgdl / Glucose.MGDL_PER_MMOL) : String.valueOf(mgdl);
    }

    /** What the field holds, in mg/dl; 0 when it is not a number, which the checks then reject. */
    private static int toMgdl(EditText field, boolean mmol) {
        try {
            double v = Double.parseDouble(field.getText().toString().trim().replace(',', '.'));
            return (int) Math.round(mmol ? v * Glucose.MGDL_PER_MMOL : v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void title(String text) {
        TextView t = text(28, primary);
        t.setText(text);
        t.setPadding(0, 0, 0, dp(16));
        page.addView(t);
    }

    /** A menu row opening one page. */
    private void row(String text, String which) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text + "  ›");
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTextColor(primary);
        b.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class).putExtra(PAGE, which)));
        page.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
    }

    private void button(int text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        at.topMargin = dp(16);
        page.addView(b, at);
    }

    private void status() {
        status = text(13, secondary);
        status.setPadding(0, dp(8), 0, 0);
        page.addView(status);
    }

    private void say(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    private EditText field(int label, String text, int inputType) {
        return field(getString(label), text, inputType);
    }

    private EditText field(String label, String text, int inputType) {
        TextView name = text(13, secondary);
        name.setText(label);
        name.setPadding(0, dp(12), 0, 0);
        EditText edit = new EditText(this);
        edit.setId(View.generateViewId());
        edit.setInputType(inputType);
        edit.setText(text);
        edit.setTextColor(primary);
        name.setLabelFor(edit.getId());
        page.addView(name);
        page.addView(edit);
        return edit;
    }

    private TextView label(int text) {
        TextView t = text(13, secondary);
        t.setText(text);
        t.setPadding(0, dp(16), 0, 0);
        return t;
    }

    private RadioButton radio(String text) {
        RadioButton b = new RadioButton(this);
        b.setId(View.generateViewId());
        b.setText(text);
        return b;
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
