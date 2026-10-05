package io.github.federicomameli1.sugarglass;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

public class GlucoseTest {

    private static final Glucose.Config MGDL = Glucose.Config.DEFAULT;
    private static final Glucose.Config MMOL = new Glucose.Config(true, 55, 70, 180, 250);

    @Before
    public void englishNumbers() {
        Locale.setDefault(Locale.UK);
    }

    private static Glucose.Reading at(int minutesAgo, int sgv) {
        return new Glucose.Reading(1_000_000_000L - minutesAgo * 60_000L, sgv, "Flat");
    }

    @Test
    public void deltaIsPerFiveMinutesWhateverTheReadingInterval() {
        // one reading every 2 minutes, rising 1 mg/dl per minute
        assertEquals("+5", Glucose.delta(Arrays.asList(at(0, 106), at(2, 104), at(4, 102), at(6, 100)), MGDL));
    }

    @Test
    public void deltaSkipsDuplicatesAndGivesUpAcrossGaps() {
        assertEquals("-10", Glucose.delta(Arrays.asList(at(0, 90), at(0, 90), at(5, 100)), MGDL));
        assertEquals("", Glucose.delta(Arrays.asList(at(0, 90), at(12, 100)), MGDL));
        assertEquals("", Glucose.delta(Arrays.asList(at(0, 90)), MGDL));
    }

    @Test
    public void mmolShowsOneDecimalAndNeverASignedZero() {
        assertEquals("7.0", Glucose.value(126, MMOL)); // 6.993
        assertEquals("+0.3", Glucose.delta(Arrays.asList(at(0, 105), at(5, 100)), MMOL));
        assertEquals("-0.6", Glucose.delta(Arrays.asList(at(0, 90), at(5, 100)), MMOL));
        // -1 mg/dl over 6 minutes is -0.046 mmol/L per 5 minutes: must read 0.0, not -0.0
        assertEquals("0.0", Glucose.delta(Arrays.asList(at(0, 100), at(6, 101)), MMOL));
    }

    @Test
    public void bandsEdgesIncluded() {
        assertEquals(Glucose.URGENT, Glucose.band(54, MGDL));
        assertEquals(Glucose.OUT_OF_RANGE, Glucose.band(55, MGDL));
        assertEquals(Glucose.OUT_OF_RANGE, Glucose.band(69, MGDL));
        assertEquals(Glucose.IN_RANGE, Glucose.band(70, MGDL));
        assertEquals(Glucose.IN_RANGE, Glucose.band(180, MGDL));
        assertEquals(Glucose.OUT_OF_RANGE, Glucose.band(250, MGDL));
        assertEquals(Glucose.URGENT, Glucose.band(251, MGDL));
    }

    @Test
    public void autoScaleAlwaysShowsTheTargetRange() {
        // all readings inside 100..120: the scale still reaches down to low and up to high
        assertArrayEquals(new int[]{70, 180}, Glucose.scale(Arrays.asList(at(0, 120), at(5, 100)), MGDL, null));
        // a spike above high stretches the top, the bottom stays at low
        assertArrayEquals(new int[]{70, 260}, Glucose.scale(Arrays.asList(at(0, 260), at(5, 100)), MGDL, null));
        assertArrayEquals(new int[]{60, 250}, Glucose.scale(Arrays.asList(at(0, 400)), MGDL, new int[]{60, 250}));
    }

    @Test
    public void plainHttpOnlyOnTheHomeNetwork() {
        assertTrue(Glucose.safeAddress("https://my.nightscout.site"));
        assertTrue(Glucose.safeAddress("HTTPS://My.Site"));
        assertTrue(Glucose.safeAddress("http://192.168.1.20:1337"));
        assertTrue(Glucose.safeAddress("http://10.0.0.5"));
        assertTrue(Glucose.safeAddress("http://172.20.1.1"));
        assertTrue(Glucose.safeAddress("http://nightscout.local"));
        assertTrue(Glucose.safeAddress("http://[fd12::1]:1337"));
        assertFalse(Glucose.safeAddress("http://my.nightscout.site"));
        assertFalse(Glucose.safeAddress("http://172.32.1.1"));   // just outside 172.16/12
        assertFalse(Glucose.safeAddress("http://192.168.1.300")); // not an address
        assertFalse(Glucose.safeAddress("http://8.8.8.8"));
        assertFalse(Glucose.safeAddress("http://192.168.1.1.evil.com"));
        assertFalse(Glucose.safeAddress("ftp://192.168.1.1"));
        assertFalse(Glucose.safeAddress("not a url"));
        // the phone itself: any app on it could serve made-up readings on a local port
        assertFalse(Glucose.safeAddress("http://localhost:17580"));
        assertFalse(Glucose.safeAddress("http://127.0.0.1:17580"));
        assertFalse(Glucose.safeAddress("http://[::1]:17580"));
    }

    @Test
    public void apiSecretNeverOverPlainHttp() {
        String token = "reader-0123456789abcdef";
        assertTrue(Glucose.isToken(token));
        assertFalse(Glucose.isToken("my-api-secret-123"));
        assertTrue(Glucose.secretAllowed("http://192.168.1.20", token));
        assertTrue(Glucose.secretAllowed("http://192.168.1.20", ""));
        assertFalse(Glucose.secretAllowed("http://192.168.1.20", "my-api-secret-123"));
        assertTrue(Glucose.secretAllowed("https://my.site", "my-api-secret-123"));
    }

    @Test
    public void addressIsCleanedAndCredentialsMovedOut() {
        Glucose.Address a = Glucose.cleanAddress("MySecret123@my.site/api/v1/");
        assertEquals("https://my.site", a.url);
        assertEquals("MySecret123", a.secret);
        a = Glucose.cleanAddress("https://my.site:8443/ns/?token=reader-0123456789abcdef#x");
        assertEquals("https://my.site:8443/ns", a.url);
        assertEquals("reader-0123456789abcdef", a.secret);
        a = Glucose.cleanAddress("  http://192.168.1.20:1337  ");
        assertEquals("http://192.168.1.20:1337", a.url);
        assertEquals("", a.secret);
        assertEquals(Glucose.origin("https://my.site"), Glucose.origin("https://MY.site/other"));
        assertFalse(Glucose.origin("https://my.site").equals(Glucose.origin("https://evil.site")));
    }

    @Test
    public void lowAndHighAreNotNumbersAndHaveNoTrend() {
        assertEquals("LOW", Glucose.value(39, MGDL));
        assertEquals("HIGH", Glucose.value(401, MMOL));
        assertEquals("", Glucose.delta(Arrays.asList(at(0, 39), at(5, 60)), MGDL));
        assertEquals(Glucose.URGENT, Glucose.band(39, MGDL));
        assertEquals(Glucose.URGENT, Glucose.band(401, MGDL));
    }

    @Test
    public void thresholdsMustGoUp() {
        assertFalse(new Glucose.Config(false, 70, 70, 180, 250).valid());
        assertFalse(new Glucose.Config(false, 55, 190, 180, 250).valid());
        assertTrue(Glucose.Config.DEFAULT.valid());
    }

    @Test
    public void thresholdsMustBePlausible() {
        assertFalse(new Glucose.Config(false, 5, 70, 180, 250).valid());      // a dropped digit
        assertFalse(new Glucose.Config(false, 55, 70, 180, 2500).valid());    // an extra one
        assertFalse(new Glucose.Config(true, 991, 1261, 3243, 4505).valid()); // mg/dl typed as mmol/L
    }
}
