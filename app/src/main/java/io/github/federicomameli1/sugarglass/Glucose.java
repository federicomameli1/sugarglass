package io.github.federicomameli1.sugarglass;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/** Readings, and what the widget and the notification show about them. Plain Java, so unit tests can run it. */
final class Glucose {

    static final long WINDOW_MS = 3 * 60 * 60_000L; // fetched from Nightscout and drawn
    static final long STALE_MS = 15 * 60_000L;
    static final long GAP_MS = 10 * 60_000L;        // the graph line breaks across longer gaps
    static final int MIN_SPAN = 80;                 // mg/dl, floor on the graph's vertical range
    static final double MGDL_PER_MMOL = 18.0182;

    /** Where a value falls; the colours for each are resources, so they follow light and dark mode. */
    static final int IN_RANGE = 0, OUT_OF_RANGE = 1, URGENT = 2;

    static final class Reading {
        final long date;
        final int sgv; // always mg/dl, as Nightscout stores it
        final String direction;

        Reading(long date, int sgv, String direction) {
            this.date = date;
            this.sgv = sgv;
            this.direction = direction;
        }
    }

    /** What the user chose in the settings. Thresholds are kept in mg/dl whatever the display unit. */
    static final class Config {
        static final Config DEFAULT = new Config(false, 55, 70, 180, 250);

        final boolean mmol;
        final int urgentLow, low, high, urgentHigh;

        Config(boolean mmol, int urgentLow, int low, int high, int urgentHigh) {
            this.mmol = mmol;
            this.urgentLow = urgentLow;
            this.low = low;
            this.high = high;
            this.urgentHigh = urgentHigh;
        }

        boolean valid() {
            return 0 < urgentLow && urgentLow < low && low < high && high < urgentHigh;
        }
    }

    private Glucose() {
    }

    static String arrow(String direction) {
        switch (direction) {
            case "DoubleUp": return "↑↑"; // two single arrows: the ⇈ glyph is thin
            case "SingleUp": return "↑";
            case "FortyFiveUp": return "↗";
            case "Flat": return "→";
            case "FortyFiveDown": return "↘";
            case "SingleDown": return "↓";
            case "DoubleDown": return "↓↓";
            default: return "";
        }
    }

    static String value(int mgdl, Config c) {
        return c.mmol ? String.format(Locale.getDefault(), "%.1f", mgdl / MGDL_PER_MMOL) : String.valueOf(mgdl);
    }

    /** Change per 5 minutes in the display unit, against the reading closest to 5 minutes before the latest. */
    static String delta(List<Reading> newestFirst, Config c) {
        if (newestFirst.isEmpty()) return "";
        Reading now = newestFirst.get(0), past = null;
        for (Reading r : newestFirst) {
            long ago = now.date - r.date;
            if (ago < 60_000) continue; // the latest itself, or a duplicate upload
            if (ago > GAP_MS) break;    // too old to say anything about the trend
            past = r;
            if (ago >= 5 * 60_000) break;
        }
        if (past == null) return "";
        double mgdl = (now.sgv - past.sgv) * 5 * 60_000.0 / (now.date - past.date);
        if (!c.mmol) {
            long d = Math.round(mgdl);
            return (d > 0 ? "+" : "") + d;
        }
        long tenths = Math.round(mgdl / MGDL_PER_MMOL * 10); // rounded first, so no "-0.0" or "+0.0"
        return (tenths > 0 ? "+" : "") + String.format(Locale.getDefault(), "%.1f", tenths / 10.0);
    }

    static long minutesAgo(Reading r, long now) {
        return Math.max(0, (now - r.date) / 60_000);
    }

    static final int FIXED_MIN = 40, FIXED_MAX = 300; // mg/dl, default fixed graph scale

    /**
     * Vertical range of the graph, {bottom, top} in mg/dl: the fixed one if given, else fitted to the data
     * but always including the target range, so the range lines are there to read the curve against.
     */
    static int[] scale(List<Reading> r, Config c, int[] fixed) {
        if (fixed != null) return new int[]{fixed[0], fixed[1]};
        int min = c.low, max = c.high;
        for (Reading x : r) {
            min = Math.min(min, x.sgv);
            max = Math.max(max, x.sgv);
        }
        int pad = Math.max(0, MIN_SPAN - (max - min)) / 2; // quiet data stays flat instead of turning into cliffs
        return new int[]{min - pad, max + pad};
    }

    /**
     * Whether the app will send credentials to this Nightscout address: https anywhere, plain http only
     * on the home network, where nothing crosses the internet unencrypted.
     */
    static boolean safeAddress(String url) {
        URI u;
        try {
            u = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        if (u.getScheme() == null || u.getHost() == null) return false;
        String scheme = u.getScheme().toLowerCase(Locale.ROOT), host = u.getHost().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) return true;
        if (!scheme.equals("http")) return false;
        return host.equals("localhost") || host.endsWith(".local") || host.endsWith(".lan")
                || host.endsWith(".home.arpa") || privateIPv4(host)
                // IPv6: unique local (fc00::/7), link-local and loopback
                || host.startsWith("[fc") || host.startsWith("[fd") || host.startsWith("[fe80") || host.equals("[::1]");
    }

    private static boolean privateIPv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("\\d{1,3}")) return false;
            o[i] = Integer.parseInt(parts[i]);
            if (o[i] > 255) return false;
        }
        return o[0] == 10 || o[0] == 127 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254);
    }

    static boolean stale(Reading r, long now) {
        return now - r.date > STALE_MS;
    }

    static int band(int sgv, Config c) {
        if (sgv < c.urgentLow || sgv > c.urgentHigh) return URGENT;
        if (sgv < c.low || sgv > c.high) return OUT_OF_RANGE;
        return IN_RANGE;
    }
}
