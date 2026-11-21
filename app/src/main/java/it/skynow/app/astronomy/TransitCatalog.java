package it.skynow.app.astronomy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TransitCatalog {
    private static final double SOLAR_HORIZON = -.833;
    // Transit Predictions by Fred Espenak, NASA/GSFC.
    // Catalog date, geocentric contacts I and IV, greatest transit, solar RA, Dec and GST at 0h UT.
    private static final Transit[] CATALOG = {
            new Transit("Mercury", "2032-11-13", "06:41", "08:54", "11:07", 15.274, -18.14, 3.535),
            new Transit("Mercury", "2039-11-07", "07:17", "08:46", "10:15", 14.822, -16.27, 3.095),
            new Transit("Mercury", "2049-05-07", "11:03", "14:24", "17:44", 3.000, 17.04, 15.058),
            new Transit("Mercury", "2052-11-09", "23:53", "02:29", "05:06", 14.996, -17.02, 3.265),
            new Transit("Mercury", "2062-05-10", "18:16", "21:36", "00:57", 3.206, 17.88, 15.265),
            new Transit("Mercury", "2065-11-11", "17:24", "20:06", "22:48", 15.170, -17.73, 3.435),
            new Transit("Mercury", "2078-11-14", "11:42", "13:41", "15:39", 15.345, -18.41, 3.605),
            new Transit("Mercury", "2085-11-07", "11:42", "13:34", "15:26", 14.893, -16.58, 3.165),
            new Transit("Mercury", "2095-05-08", "17:20", "21:05", "00:50", 3.075, 17.35, 15.133),
            new Transit("Mercury", "2098-11-10", "04:35", "07:16", "09:57", 15.066, -17.31, 3.335),
            new Transit("Mercury", "2108-05-12", "01:40", "04:16", "06:52", 3.281, 18.16, 15.340),
            new Transit("Mercury", "2111-11-14", "22:15", "00:53", "03:30", 15.241, -18.01, 3.505),
            new Transit("Mercury", "2124-11-15", "16:49", "18:28", "20:07", 15.418, -18.67, 3.674),
            new Transit("Mercury", "2131-11-09", "16:14", "18:22", "20:31", 14.962, -16.87, 3.234),
            new Transit("Mercury", "2141-05-10", "23:46", "03:43", "07:39", 3.151, 17.65, 15.207),
            new Transit("Mercury", "2144-11-11", "09:18", "12:02", "14:46", 15.137, -17.59, 3.404),
            new Transit("Mercury", "2154-05-13", "10:03", "10:58", "11:53", 3.357, 18.45, 15.414),
            new Transit("Mercury", "2157-11-14", "03:08", "05:40", "08:11", 15.313, -18.28, 3.574),
            new Transit("Mercury", "2170-11-16", "22:05", "23:15", "00:26", 15.489, -18.92, 3.744),
            new Transit("Mercury", "2174-05-08", "02:24", "03:26", "04:27", 3.021, 17.12, 15.076),
            new Transit("Mercury", "2177-11-09", "20:48", "23:09", "01:30", 15.033, -17.17, 3.304),
            new Transit("Mercury", "2187-05-11", "06:27", "10:24", "14:21", 3.226, 17.94, 15.282),
            new Transit("Mercury", "2190-11-12", "14:03", "16:48", "19:33", 15.207, -17.87, 3.474),
            new Transit("Mercury", "2203-11-16", "08:04", "10:27", "12:49", 15.384, -18.54, 3.644),
            new Transit("Mercury", "2210-11-09", "09:14", "10:13", "11:11", 14.930, -16.73, 3.203),
            new Transit("Mercury", "2220-05-09", "07:23", "09:56", "12:30", 3.095, 17.41, 15.150),
            new Transit("Mercury", "2223-11-12", "01:25", "03:55", "06:26", 15.103, -17.45, 3.373),
            new Transit("Mercury", "2233-05-12", "13:13", "16:59", "20:46", 3.301, 18.23, 15.357),
            new Transit("Mercury", "2236-11-13", "18:50", "21:35", "00:19", 15.279, -18.14, 3.543),
            new Transit("Mercury", "2249-11-16", "13:02", "15:12", "17:23", 15.456, -18.80, 3.713),
            new Transit("Mercury", "2256-11-09", "13:26", "14:59", "16:32", 15.000, -17.02, 3.273),
            new Transit("Mercury", "2266-05-10", "13:16", "16:34", "19:51", 3.170, 17.71, 15.225),
            new Transit("Mercury", "2269-11-12", "06:04", "08:42", "11:19", 15.175, -17.73, 3.443),
            new Transit("Mercury", "2279-05-13", "20:14", "23:38", "03:01", 3.376, 18.50, 15.431),
            new Transit("Mercury", "2282-11-15", "23:41", "02:22", "05:02", 15.350, -18.41, 3.613),
            new Transit("Mercury", "2295-11-17", "18:03", "19:59", "21:54", 15.528, -19.04, 3.783),
            new Transit("Venus", "2117-12-11", "23:58", "02:48", "05:38", 17.201, -22.97, 5.320),
            new Transit("Venus", "2125-12-08", "13:15", "16:01", "18:48", 17.026, -22.74, 5.163),
            new Transit("Venus", "2247-06-11", "08:42", "11:33", "14:25", 5.289, 23.05, 17.287),
            new Transit("Venus", "2255-06-09", "01:08", "04:38", "08:08", 5.135, 22.87, 17.141),
            new Transit("Venus", "2360-12-13", "22:32", "01:44", "04:56", 17.348, -23.09, 5.458),
            new Transit("Venus", "2368-12-10", "12:29", "14:45", "17:01", 17.172, -22.90, 5.301),
            new Transit("Venus", "2490-06-12", "11:39", "14:17", "16:55", 5.454, 23.17, 17.436),
            new Transit("Venus", "2498-06-10", "03:48", "07:25", "11:02", 5.301, 23.02, 17.290),
            new Transit("Venus", "2603-12-16", "20:43", "00:13", "03:43", 17.494, -23.18, 5.596),
            new Transit("Venus", "2611-12-13", "12:04", "13:34", "15:04", 17.319, -23.03, 5.440),
            new Transit("Venus", "2733-06-15", "15:02", "17:18", "19:34", 5.623, 23.24, 17.587),
            new Transit("Venus", "2741-06-13", "06:33", "10:17", "14:00", 5.468, 23.14, 17.440),
            new Transit("Venus", "2846-12-16", "19:30", "23:11", "02:52", 17.643, -23.24, 5.735),
            new Transit("Venus", "2976-06-16", "17:45", "19:44", "21:44", 5.791, 23.28, 17.735),
            new Transit("Venus", "2984-06-14", "09:01", "12:49", "16:37", 5.634, 23.21, 17.589),
            new Transit("Venus", "3089-12-18", "17:39", "21:31", "01:23", 17.790, -23.27, 5.873),
            new Transit("Venus", "3219-06-19", "20:50", "22:19", "23:49", 5.957, 23.28, 17.885),
            new Transit("Venus", "3227-06-17", "11:21", "15:13", "19:05", 5.801, 23.25, 17.738),
            new Transit("Venus", "3332-12-20", "16:16", "20:14", "00:12", 17.939, -23.26, 6.012),
            new Transit("Venus", "3462-06-22", "23:29", "00:27", "01:26", 6.123, 23.24, 18.034),
            new Transit("Venus", "3470-06-19", "13:31", "17:26", "21:22", 5.967, 23.25, 17.887),
            new Transit("Venus", "3575-12-23", "14:29", "18:32", "22:34", 18.087, -23.23, 6.150),
            new Transit("Venus", "3713-06-21", "15:25", "19:22", "23:20", 6.133, 23.21, 18.036),
            new Transit("Venus", "3818-12-25", "12:57", "17:01", "21:05", 18.237, -23.16, 6.290),
            new Transit("Venus", "3956-06-23", "17:22", "21:21", "01:21", 6.300, 23.13, 18.184)
    };

    public static final class Event {
        public final Transit transit;
        public final Instant visibleStart;
        public final Instant visibleEnd;

        Event(Transit transit, Instant visibleStart, Instant visibleEnd) {
            this.transit = transit;
            this.visibleStart = visibleStart;
            this.visibleEnd = visibleEnd;
        }

        public String detailsUrl(double latitude, double longitude) {
            // The NASA catalog also covers dates outside Timeanddate's event pages.
            if (transit.date.getYear() > 2125) {
                return catalogUrl();
            }
            return EclipseCatalog.localDetailsUrl(transit.date, latitude, longitude);
        }

        public String catalogUrl() {
            return "https://eclipse.gsfc.nasa.gov/transit/catalog/" + transit.planet + "Catalog.html";
        }

        public LocalDate catalogDate() {
            return transit.date;
        }

        public org.json.JSONArray visibilitySamples() throws org.json.JSONException {
            org.json.JSONArray samples = new org.json.JSONArray();
            long end = transit.end.getEpochSecond();
            for (long time = transit.start.getEpochSecond(); ; time = Math.min(time + 300, end)) {
                samples.put(new org.json.JSONArray().put(transit.solarDec).put(transit.solarLongitude(time))
                        .put(SOLAR_HORIZON));
                if (time == end) return samples;
            }
        }
    }

    public static final class Transit {
        public final String planet;
        final LocalDate date;
        final Instant greatest;
        final Instant start;
        final Instant end;
        final double solarRa;
        final double solarDec;
        final double siderealTime;
        final long midnight;

        Transit(String planet, String date, String start, String greatest, String end,
                double solarRa, double solarDec, double siderealTime) {
            this.planet = planet;
            this.date = LocalDate.parse(date);
            this.greatest = this.date.atTime(LocalTime.parse(greatest)).toInstant(ZoneOffset.UTC);
            Instant first = this.date.atTime(LocalTime.parse(start)).toInstant(ZoneOffset.UTC);
            Instant last = this.date.atTime(LocalTime.parse(end)).toInstant(ZoneOffset.UTC);
            this.start = first.isAfter(this.greatest) ? first.minusSeconds(86400) : first;
            this.end = last.isBefore(this.greatest) ? last.plusSeconds(86400) : last;
            this.solarRa = solarRa;
            this.solarDec = solarDec;
            this.siderealTime = siderealTime;
            midnight = this.date.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        }

        boolean aboveHorizon(long time, double latitude, double longitude) {
            double ha = Math.toRadians(longitude - solarLongitude(time));
            double lat = Math.toRadians(latitude);
            double dec = Math.toRadians(solarDec);
            double sineAltitude = Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(ha);
            return sineAltitude >= Math.sin(Math.toRadians(SOLAR_HORIZON));
        }

        private double solarLongitude(long time) {
            double hours = (time - midnight) / 3600.0;
            return Math.IEEEremainder(-15 * (siderealTime + 1.0027379 * hours - solarRa), 360);
        }
    }

    private TransitCatalog() {}

    public static List<Event> upcoming(double latitude, double longitude, Instant now) {
        List<Event> events = new ArrayList<>();
        for (Transit transit : CATALOG) {
            if (transit.end.isBefore(now)) continue;
            Event event = visibleEvent(transit, latitude, longitude);
            if (event != null && !event.visibleEnd.isBefore(now)) events.add(event);
        }
        events.sort(Comparator.comparing(event -> event.transit.greatest));
        return events;
    }

    public static Event atDate(LocalDate date, double latitude, double longitude) {
        for (Transit transit : CATALOG) {
            if (transit.date.equals(date)) return visibleEvent(transit, latitude, longitude);
        }
        return null;
    }

    private static Event visibleEvent(Transit transit, double latitude, double longitude) {
        long start = transit.start.getEpochSecond();
        long end = transit.end.getEpochSecond();
        long visibleStart = -1;
        long visibleEnd = -1;
        boolean wasVisible = transit.aboveHorizon(start, latitude, longitude);
        if (wasVisible) visibleStart = start;
        for (long previous = start, time = Math.min(start + 60, end); time > previous;
             previous = time, time = Math.min(time + 60, end)) {
            boolean visible = transit.aboveHorizon(time, latitude, longitude);
            if (visible != wasVisible) {
                long crossing = crossing(transit, previous, time, latitude, longitude, visible);
                if (visible && visibleStart < 0) visibleStart = crossing;
                if (!visible) visibleEnd = crossing;
            }
            if (visible && time == end) visibleEnd = end;
            wasVisible = visible;
        }
        return visibleStart >= 0 && visibleEnd > visibleStart
                ? new Event(transit, Instant.ofEpochSecond(visibleStart), Instant.ofEpochSecond(visibleEnd)) : null;
    }

    private static long crossing(Transit transit, long low, long high, double latitude, double longitude, boolean rising) {
        while (high - low > 1) {
            long middle = (low + high) / 2;
            if (transit.aboveHorizon(middle, latitude, longitude) == rising) high = middle;
            else low = middle;
        }
        return high;
    }
}
