package io.github.federicomameli1.sugarglass;

import java.util.ArrayList;
import java.util.List;

/** Made-up readings for the debug build, to see any value, trend or stale state without waiting for one. */
final class Fake {

    static final String[] DIRECTIONS =
            {"DoubleUp", "SingleUp", "FortyFiveUp", "Flat", "FortyFiveDown", "SingleDown", "DoubleDown"};

    private Fake() {
    }

    /**
     * Three hours of readings every 5 minutes, ending at {@code value}: the last half hour follows the
     * chosen trend, the rest is a gentle wave so the graph has something to show.
     */
    static List<Glucose.Reading> series(int value, String direction, boolean stale, long now) {
        double perMinute = perMinute(direction);
        long end = stale ? now - 20 * 60_000L : now;
        List<Glucose.Reading> out = new ArrayList<>();
        for (int minutes = 0; minutes <= 180; minutes += 5) {
            double v = value - perMinute * Math.min(minutes, 30)
                    + (minutes > 30 ? 25 * Math.sin((minutes - 30) / 25.0) : 0);
            int sgv = (int) Math.max(40, Math.min(400, Math.round(v)));
            out.add(new Glucose.Reading(end - minutes * 60_000L, sgv, direction));
        }
        return out;
    }

    /** Roughly the rate each Nightscout direction stands for, in mg/dl per minute. */
    private static double perMinute(String direction) {
        switch (direction) {
            case "DoubleUp": return 3.5;
            case "SingleUp": return 2.5;
            case "FortyFiveUp": return 1.5;
            case "FortyFiveDown": return -1.5;
            case "SingleDown": return -2.5;
            case "DoubleDown": return -3.5;
            default: return 0;
        }
    }
}
