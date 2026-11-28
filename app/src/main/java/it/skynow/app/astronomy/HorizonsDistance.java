package it.skynow.app.astronomy;

import it.skynow.app.net.HttpText;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Geocentric distance of the Sun or Moon from NASA/JPL Horizons (DE440), the ephemeris behind the
 * apsides table. One request returns two days of hourly values, which are interpolated.
 */
public final class HorizonsDistance {
    public enum Body {
        SUN("10"), MOON("301");

        final String command;

        Body(String command) {
            this.command = command;
        }
    }

    public static final String SOURCE = "https://ssd.jpl.nasa.gov/horizons/";
    private static final String API = "https://ssd.jpl.nasa.gov/api/horizons.api";
    private static final double AU_KM = 149597870.7;
    private static final int HOURS = 48;
    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);
    // Horizons writes English month abbreviations; Android's ROOT locale does not use them.
    private static final DateTimeFormatter TABLE_TIME = DateTimeFormatter.ofPattern("uuuu-MMM-dd HH:mm", Locale.US);

    private static final HorizonsDistance[] CACHE = new HorizonsDistance[Body.values().length];

    private final long[] seconds;
    private final double[] km;

    private HorizonsDistance(long[] seconds, double[] km) {
        this.seconds = seconds;
        this.km = km;
    }

    /** The last table downloaded for {@code body} in this process, or null. */
    public static HorizonsDistance cached(Body body) {
        synchronized (CACHE) {
            return CACHE[body.ordinal()];
        }
    }

    /** Downloads hourly distances of {@code body} from an hour before {@code now} and caches them. Blocking. */
    public static HorizonsDistance load(Body body, Instant now) throws Exception {
        LocalDateTime start = LocalDateTime.ofInstant(now, ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).minusHours(1);
        String query = API + "?format=json&OBJ_DATA='NO'&MAKE_EPHEM='YES'&EPHEM_TYPE='OBSERVER'"
                + "&COMMAND='" + body.command + "'&CENTER='500@399'&QUANTITIES='20'&CSV_FORMAT='YES'&STEP_SIZE='1h'"
                + "&START_TIME=" + quoted(start.format(QUERY_TIME))
                + "&STOP_TIME=" + quoted(start.plusHours(HOURS).format(QUERY_TIME));
        HorizonsDistance table = parse(new JSONObject(HttpText.read(query, 15000)).getString("result"));
        synchronized (CACHE) {
            CACHE[body.ordinal()] = table;
        }
        return table;
    }

    private static String quoted(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode("'" + value + "'", "UTF-8");
    }

    /** Reads the CSV rows between $$SOE and $$EOE of a Horizons observer table with quantity 20 (range). */
    static HorizonsDistance parse(String result) {
        int start = result.indexOf("$$SOE");
        int end = result.indexOf("$$EOE");
        if (start < 0 || end < start) throw new IllegalArgumentException("No ephemeris in Horizons response");
        List<Long> times = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (String line : result.substring(start + 5, end).split("\n")) {
            String[] fields = line.split(",");
            if (fields.length < 4) continue;
            times.add(LocalDateTime.parse(fields[0].trim(), TABLE_TIME).toEpochSecond(ZoneOffset.UTC));
            distances.add(Double.parseDouble(fields[3].trim()) * AU_KM);
        }
        if (times.size() < 2) throw new IllegalArgumentException("Too few ephemeris rows");
        long[] seconds = new long[times.size()];
        double[] km = new double[times.size()];
        for (int i = 0; i < seconds.length; i++) {
            seconds[i] = times.get(i);
            km[i] = distances.get(i);
        }
        return new HorizonsDistance(seconds, km);
    }

    public boolean covers(Instant time) {
        long second = time.getEpochSecond();
        return second >= seconds[0] && second <= seconds[seconds.length - 1];
    }

    /** Distance in km at {@code time}, interpolated between hourly values; NaN outside the table. */
    public double kmAt(Instant time) {
        long second = time.getEpochSecond();
        if (!covers(time)) return Double.NaN;
        for (int i = 1; i < seconds.length; i++) {
            if (second <= seconds[i]) {
                double fraction = (double) (second - seconds[i - 1]) / (seconds[i] - seconds[i - 1]);
                return km[i - 1] + fraction * (km[i] - km[i - 1]);
            }
        }
        return km[km.length - 1];
    }
}
