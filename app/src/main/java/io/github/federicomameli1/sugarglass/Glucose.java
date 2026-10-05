package io.github.federicomameli1.sugarglass;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/** Readings, and what the widget and the notification show about them. Plain Java, so unit tests can run it. */
final class Glucose {

    static final long WINDOW_MS = 3 * 60 * 60_000L; // fetched from Nightscout and drawn
    static final long STALE_MS = 15 * 60_000L;
    static final long HIDE_MS = 5 * 60_000L;        // the widget stops showing a value this long without a new one
    static final int LOW = 39, HIGH = 401;          // Nightscout's codes for below and above the sensor's range
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

        /**
         * In order, and each in a plausible band: a dropped or extra digit, or mg/dl values typed with
         * mmol/L selected, would otherwise silently turn the urgent colours off.
         */
        boolean valid() {
            return urgentLow < low && low < high && high < urgentHigh
                    && between(urgentLow, 40, 90) && between(low, 55, 120)
                    && between(high, 120, 300) && between(urgentHigh, 150, 400);
        }

        private static boolean between(int v, int min, int max) {
            return v >= min && v <= max;
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
        if (mgdl <= LOW) return "LOW";   // below what the sensor reads: not a measured 39
        if (mgdl >= HIGH) return "HIGH";
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
        if (past == null || clamped(now) || clamped(past)) return ""; // LOW/HIGH have no real slope
        double mgdl = (now.sgv - past.sgv) * 5 * 60_000.0 / (now.date - past.date);
        if (!c.mmol) {
            long d = Math.round(mgdl);
            return (d > 0 ? "+" : "") + d;
        }
        long tenths = Math.round(mgdl / MGDL_PER_MMOL * 10); // rounded first, so no "-0.0" or "+0.0"
        return (tenths > 0 ? "+" : "") + String.format(Locale.getDefault(), "%.1f", tenths / 10.0);
    }

    private static boolean clamped(Reading r) {
        return r.sgv <= LOW || r.sgv >= HIGH;
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
     * on the home network, where nothing crosses the internet unencrypted. Not the phone itself: any app
     * on it could listen on a local port and serve made-up readings.
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
        return host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || privateIPv4(host)
                // IPv6: unique local (fc00::/7) and link-local
                || host.startsWith("[fc") || host.startsWith("[fd") || host.startsWith("[fe80");
    }

    /** Nightscout access tokens look like "name-0123456789abcdef"; anything else is the API secret. */
    static boolean isToken(String secret) {
        return secret.matches("[\\w-]+-[0-9a-f]{16}");
    }

    /**
     * Whether this secret may go to this address. Over plain http only a token (give it the read-only
     * role) or nothing: the API secret is admin access, and even a home network can be someone else's
     * when the phone joins a hotel or café Wi-Fi with the same addresses.
     */
    static boolean secretAllowed(String url, String secret) {
        return secret.isEmpty() || isToken(secret) || url.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    /** An address as typed, split into a clean site address and any credential that was inside it. */
    static final class Address {
        final String url, secret;

        Address(String url, String secret) {
            this.url = url;
            this.secret = secret;
        }
    }

    /**
     * Cleans a typed or pasted address: https:// when no scheme is given, the credential moved out of
     * "https://SECRET@site" or "?token=", query and fragment dropped, a trailing /api/v1 removed.
     * Returns null when it is not an address at all.
     */
    static Address cleanAddress(String typed) {
        String s = typed.trim();
        if (s.isEmpty()) return new Address("", "");
        if (!s.contains("://")) s = "https://" + s;
        URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException e) {
            return null;
        }
        if (u.getScheme() == null || u.getHost() == null) return null;
        String secret = u.getUserInfo() == null ? "" : u.getUserInfo();
        if (u.getQuery() != null) {
            for (String part : u.getQuery().split("&")) {
                if (part.startsWith("token=")) secret = part.substring(6);
            }
        }
        String path = u.getPath() == null ? "" : u.getPath().replaceAll("/+$", "");
        if (path.toLowerCase(Locale.ROOT).endsWith("/api/v1")) path = path.substring(0, path.length() - 7);
        String url = u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost()
                + (u.getPort() == -1 ? "" : ":" + u.getPort()) + path;
        return new Address(url, secret);
    }

    /** Scheme, host and port: the stored secret only stays with the site it was given to. */
    static String origin(String url) {
        try {
            URI u = new URI(url);
            return (u.getScheme() + "://" + u.getHost() + ":" + u.getPort()).toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return "";
        }
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
        return o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254);
    }

    static boolean stale(Reading r, long now) {
        return now - r.date > STALE_MS;
    }

    /** The widget hides the value once no new reading has come for a while, until one does. */
    static boolean hidden(Reading r, long now) {
        return now - r.date > HIDE_MS;
    }

    static int band(int sgv, Config c) {
        if (sgv < c.urgentLow || sgv > c.urgentHigh) return URGENT;
        if (sgv < c.low || sgv > c.high) return OUT_OF_RANGE;
        return IN_RANGE;
    }
}
