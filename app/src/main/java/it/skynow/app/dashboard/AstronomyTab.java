package it.skynow.app.dashboard;

import it.skynow.app.net.Background;
import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.widget.ScrollView;
import it.skynow.app.R;
import it.skynow.app.astronomy.IssPasses;
import it.skynow.app.astronomy.LunarPhases;
import it.skynow.app.astronomy.HorizonsDistance;
import it.skynow.app.astronomy.MoonPhaseView;
import it.skynow.app.astronomy.OrbitalApsides;
import it.skynow.app.astronomy.PlanetIcon;
import it.skynow.app.astronomy.Planets;
import it.skynow.app.astronomy.SkyPath;
import it.skynow.app.astronomy.SkyPosition;
import it.skynow.app.astronomy.SkyTrack;
import it.skynow.app.astronomy.SkyTrajectory;
import it.skynow.app.maps.RadarActivity;
import it.skynow.app.ui.Formats;
import it.skynow.app.ui.SignalColors;
import it.skynow.app.ui.SkyViews;
import it.skynow.app.weather.ForecastSeries;
import it.skynow.app.weather.OpenMeteo;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** The Astronomy dashboard tab: Sun, Moon, seasons, planets, ISS passes, aurora, eclipses and transits. */
public final class AstronomyTab {
    /** State the tab reads from the activity. */
    public interface Host {
        Formats formats();
        void openMap(RadarActivity.Layer layer);
    }

    private final Activity activity;
    private final SkyViews views;
    private final Host host;
    private final AstronomyWidgets widgets;
    private final AuroraSection auroraSection;
    private final EventsSection events;
    private final SkyTrackDialog skyTrack;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;

    // The forecast the tab was last attached with; data is null when only events are shown.
    private LinearLayout page;
    private ScrollView scroll;
    private JSONObject data;
    private double latitude;
    private double longitude;
    private String place;

    private TextView sunCoordinates;
    private TextView moonCoordinates;
    private final LiveDistance sunDistance = new LiveDistance(HorizonsDistance.Body.SUN);
    private final LiveDistance moonDistance = new LiveDistance(HorizonsDistance.Body.MOON);
    private LinearLayout sunTrackHeading, moonTrackHeading;
    private SkyPath sunPath, moonPath;
    private String astronomyTailKey;
    private CompletableFuture<JSONObject> astronomyTail;
    private final List<PlanetRow> planetRows = new ArrayList<>();
    private boolean observablePlanetsOnly;
    private TextView noObservablePlanets;
    private LocalDate planetWindowDate;
    private final List<VisibilityBadge> visibilityBadges = new ArrayList<>();
    private final Runnable updateAstronomyVisibility = this::refreshAstronomyVisibility;
    private static final int[] MOON_PHASE_NAMES = {R.string.moon_new, R.string.moon_waxing_crescent, R.string.moon_first_quarter,
            R.string.moon_waxing_gibbous, R.string.moon_full, R.string.moon_waning_gibbous,
            R.string.moon_last_quarter, R.string.moon_waning_crescent};
    static final int ASTRONOMY_PAGE_SIZE = 4;
    private static final int SKY_PATH_HEIGHT_DP = 108;


    public AstronomyTab(Activity activity, SkyViews views, Host host) {
        this.activity = activity;
        this.views = views;
        this.host = host;
        widgets = new AstronomyWidgets(activity, views, this::formats);
        auroraSection = new AuroraSection(activity, views, host, widgets);
        skyTrack = new SkyTrackDialog(activity, views, host, widgets, this::refreshAstronomyVisibility);
        events = new EventsSection(activity, views, host, widgets, visibilityBadges, this::refreshAstronomyVisibility);
    }

    private Formats formats() {
        return host.formats();
    }

    private static int length(JSONObject series) { return ForecastSeries.length(series); }
    private static String at(JSONObject series, String key, int index) { return ForecastSeries.text(series, key, index); }
    private static double value(JSONObject series, String key, int index) { return ForecastSeries.value(series, key, index); }

    /** Builds the whole tab at the end of {@code page} for the given forecast. */
    public void attach(LinearLayout page, ScrollView scroll, JSONObject data, double latitude, double longitude,
                       String place) {
        show(page, scroll, data, latitude, longitude, place);
        JSONObject daily = data.optJSONObject("daily");
        sun(daily, 0);
        moon(daily, 0);
        astronomicalSeasons();
        planets();
        issPasses();
        auroraSection.attach(page, data, latitude, longitude);
        events.attach(page, scroll, data, latitude, longitude, place);
    }

    /** Shows only eclipses and transits, for a notification opened before the forecast has loaded. */
    public void attachEvents(LinearLayout page, ScrollView scroll, double latitude, double longitude, String place) {
        show(page, scroll, null, latitude, longitude, place);
        events.attach(page, scroll, data, latitude, longitude, place);
    }

    private void show(LinearLayout page, ScrollView scroll, JSONObject data, double latitude, double longitude,
                      String place) {
        this.page = page;
        this.scroll = scroll;
        this.data = data;
        this.latitude = latitude;
        this.longitude = longitude;
        this.place = place;
    }

    /** Forgets the views of a page that is being replaced. */
    public void detach() {
        visibilityBadges.clear();
        refreshAstronomyVisibility();
        auroraSection.detach();
        sunCoordinates = null;
        moonCoordinates = null;
        sunDistance.detach();
        moonDistance.detach();
        sunTrackHeading = null;
        moonTrackHeading = null;
        sunPath = null;
        moonPath = null;
        planetRows.clear();
        noObservablePlanets = null;
        planetWindowDate = null;
    }

    public void onResume() {
        resumed = true;
        refreshAstronomyVisibility();
        skyTrack.onResume();
    }

    public void onPause() {
        resumed = false;
        main.removeCallbacks(updateAstronomyVisibility);
        skyTrack.onPause();
    }

    /** Minute update of live positions and the aurora outlook. */
    public void tick(Instant now) {
        if (sunCoordinates != null) setSkyCoordinates(sunCoordinates, now, false);
        if (moonCoordinates != null) setSkyCoordinates(moonCoordinates, now, true);
        sunDistance.refresh();
        moonDistance.refresh();
        if (!planetRows.isEmpty()) updatePlanets(now);
        auroraSection.tick(now);
    }

    /** Lets a manual refresh reload the aurora and Kp forecasts immediately. */
    public void resetRefreshThrottles() {
        auroraSection.resetRefreshThrottles();
    }

    /** The eclipse or transit section to scroll to, from a notification; null once the user touches the screen. */
    public void setPendingEventSection(String section) {
        events.setPendingEventSection(section);
    }

    public boolean hasPendingEventSection() {
        return events.hasPendingEventSection();
    }

    /** Degrees between magnetic and true north at the phone, for the sky track compass. */
    public void setMagneticDeclination(double degrees) {
        skyTrack.setMagneticDeclination(degrees);
    }

    /** Time zone for event times while no forecast is loaded. */
    public void setNotificationZone(ZoneId zone) {
        events.setNotificationZone(zone);
    }

    public ZoneId notificationZone() {
        return events.notificationZone();
    }

    /** Local solar noon for day {@code index} of an Open-Meteo daily series, formatted for display. */
    public static String solarNoon(JSONObject daily, int index, double longitude, ZoneId zone, Formats formats) {
        LocalDate date = LocalDate.parse(ForecastSeries.text(daily, "time", index));
        return Formats.formatTime(SkyPath.solarNoon(date, longitude, zone), formats.clockFormat());
    }

    private String solarNoon(JSONObject daily, int index) {
        return solarNoon(daily, index, longitude, ZoneId.of(data.optString("timezone", "UTC")), formats());
    }

    private LinearLayout addRiseSetTime(LinearLayout times, String name, String time, boolean setting) {
        LinearLayout cell = views.column();
        int alignment = setting ? Gravity.END : Gravity.START;
        cell.setGravity(alignment);
        TextView title = views.label(name, 13, views.muted);
        title.setGravity(alignment);
        cell.addView(title);
        TextView reading = views.label(time, 19, views.ink);
        reading.setGravity(alignment);
        cell.addView(reading);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        if (setting) size.setMarginStart(views.dp(8));
        times.addView(cell, size);
        return cell;
    }

    private TextView skyCoordinates(boolean moon) {
        TextView reading = views.label("", 12, views.muted);
        reading.setPadding(0, views.dp(8), 0, 0);
        setSkyCoordinates(reading, Instant.now(), moon);
        return reading;
    }

    private void setSkyCoordinates(TextView reading, Instant now, boolean moon) {
        SkyPosition position = SkyPosition.at(now, latitude, longitude, moon);
        boolean above = moon ? moonVisible(now, position) : sunVisible(now, position);
        reading.setVisibility(above ? View.VISIBLE : View.GONE);
        if (above) {
            reading.setText(widgets.skyCoordinatesText(position.azimuth, 90 - position.zenith));
        }
        LinearLayout heading = moon ? moonTrackHeading : sunTrackHeading;
        heading.getChildAt(heading.getChildCount() - 1).setVisibility(above ? View.VISIBLE : View.GONE);
        SkyPath path = moon ? moonPath : sunPath;
        path.updateNow(now, 90 - position.zenith);
    }

    private boolean sunVisible(Instant now, SkyPosition position) {
        if (data != null) {
            JSONObject daily = data.optJSONObject("daily");
            ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
            String date = now.atZone(zone).toLocalDate().toString();
            for (int i = 0; i < length(daily); i++) {
                if (!date.equals(at(daily, "time", i))) continue;
                String rise = at(daily, "sunrise", i);
                String set = at(daily, "sunset", i);
                if (rise.length() >= 16 && set.length() >= 16) {
                    Instant start = LocalDateTime.parse(rise).atZone(zone).toInstant();
                    Instant end = LocalDateTime.parse(set).atZone(zone).toInstant();
                    long duration = Duration.between(start, end).getSeconds();
                    if (duration == 0) return false;
                    if (duration >= 86400) return true;
                    if (duration > 0) return !now.isBefore(start) && now.isBefore(end);
                }
                break;
            }
        }
        return position.zenith <= 90.833;
    }

    private boolean moonVisible(Instant now, SkyPosition position) {
        if (data == null) return position.zenith < 90;
        JSONObject daily = data.optJSONObject("daily");
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        String today = now.atZone(zone).toLocalDate().toString();
        int days = length(daily);
        if (days == 0 || today.compareTo(at(daily, "time", 0)) < 0
                || today.compareTo(at(daily, "time", days - 1)) > 0) return position.zenith < 90;
        Instant previous = null;
        Instant next = null;
        boolean previousWasRise = false;
        boolean nextIsRise = false;
        for (int i = 0; i < days; i++) {
            for (String field : new String[]{"moonrise", "moonset"}) {
                String timestamp = at(daily, field, i);
                if (timestamp.length() < 16) continue;
                Instant event = LocalDateTime.parse(timestamp).atZone(zone).toInstant();
                boolean rise = field.equals("moonrise");
                if (event.isAfter(now)) {
                    if (next == null || event.isBefore(next)) {
                        next = event;
                        nextIsRise = rise;
                    }
                } else if (previous == null || event.isAfter(previous)) {
                    previous = event;
                    previousWasRise = rise;
                }
            }
        }
        if (previous != null) return previousWasRise;
        if (next != null) return !nextIsRise;
        return position.zenith < 90;
    }

    private void sun(JSONObject daily, int index) {
        LinearLayout tile = views.card();
        sunTrackHeading = addSkyTrackHeading(tile, widgets.astronomyTitleIcon(R.drawable.ic_sun_astronomy), activity.getString(R.string.sun),
                () -> showDailySkyTrack(activity.getString(R.string.sun), false, null, null));
        long seconds = daylightSeconds(daily, index);
        addSunPathDetails(tile, daily, index, seconds);
        sunCoordinates = skyCoordinates(false);
        tile.addView(sunCoordinates);
        addDistance(tile, sunDistance);
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        addOrbitalApsides(tile, zone, false);
        addUpcomingTimes(tile, daily, index, false, Double.NaN);
        views.addSpaced(page, tile, 9);
    }

    private void addSunPathDetails(LinearLayout tile, JSONObject daily, int index, long seconds) {
        LocalTime sunrise = Formats.localTime(at(daily, "sunrise", index));
        LocalTime sunset = Formats.localTime(at(daily, "sunset", index));
        boolean polar = isPolarDaylight(seconds);
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        int twilight = SkyPath.civilTwilightMinutes(date, latitude);
        if (polar) {
            views.addSpaced(tile, views.label(activity.getString(polarDaylightLabel(seconds)), 14, views.ink), 7);
        } else {
            LinearLayout times = views.row();
            times.setGravity(Gravity.TOP);
            addRiseSetTime(times, activity.getString(R.string.sunrise), Formats.formatTime(sunrise, formats().clockFormat()), false);
            addRiseSetTime(times, activity.getString(R.string.sunset), Formats.formatTime(sunset, formats().clockFormat()), true);
            views.addSpaced(tile, times, 7);
        }
        FrameLayout chart = new FrameLayout(activity);
        sunPath = new SkyPath(activity, date, zone, Instant.now(), latitude, longitude, false, formats().clock24);
        chart.addView(sunPath, new FrameLayout.LayoutParams(-1, -1));
        TextView maximum = views.label(activity.getString(R.string.sun_max_today, solarMaximumElevation(date, zone, seconds)), 12, views.muted);
        maximum.setPaddingRelative(views.dp(4), 0, views.dp(4), 0);
        maximum.setBackground(views.background(activity.getColor(R.color.sky_card), 4));
        FrameLayout.LayoutParams maximumSize = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        maximumSize.setMarginEnd(views.dp(4));
        chart.addView(maximum, maximumSize);
        LinearLayout.LayoutParams chartSize = new LinearLayout.LayoutParams(-1, views.dp(SKY_PATH_HEIGHT_DP));
        chartSize.topMargin = views.dp(6);
        tile.addView(chart, chartSize);

        views.addSpaced(tile, views.label(daylightSummary(seconds), 11, views.muted), 4);

        LinearLayout legend = views.row();
        legend.setGravity(Gravity.TOP);
        legend.setPadding(views.dp(5), 0, views.dp(5), 0);
        String dawn = twilight > 0 && sunrise != null ? Formats.formatTime(sunrise.minusMinutes(twilight), formats().clockFormat()) : "—";
        String dusk = twilight > 0 && sunset != null ? Formats.formatTime(sunset.plusMinutes(twilight), formats().clockFormat()) : "—";
        if (!polar) legend.addView(views.label(activity.getString(R.string.dawn) + "\n" + dawn, 12, views.muted), new LinearLayout.LayoutParams(0, -2, 1));
        TextView noonLabel = views.label(activity.getString(R.string.solar_noon) + "\n" + solarNoon(daily, index), 12, views.muted);
        noonLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        legend.addView(noonLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView duskLabel = views.label(activity.getString(R.string.dusk) + "\n" + dusk, 12, views.muted);
        duskLabel.setGravity(Gravity.END);
        if (!polar) legend.addView(duskLabel, new LinearLayout.LayoutParams(0, -2, 1));
        views.addSpaced(tile, legend, 7);
    }

    private String solarMaximumElevation(LocalDate date, ZoneId zone, long seconds) {
        double maximum = SkyPosition.maximumSolarElevation(date, zone, latitude, longitude);
        if (seconds > 0) maximum = Math.max(0, maximum);
        return maximum < 0 && Math.round(maximum) == 0 ? "<0°" : formats().number(maximum, "%.0f") + "°";
    }

    private void moon(JSONObject daily, int index) {
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        double phase = moonPhaseForToday(daily, index, date, zone);
        LinearLayout tile = views.card();
        moonTrackHeading = addSkyTrackHeading(tile, widgets.astronomyTitleIcon(R.drawable.ic_moon_astronomy), activity.getString(R.string.moon),
                () -> showDailySkyTrack(activity.getString(R.string.moon), true, null, null));
        LinearLayout readings = views.row();
        readings.setGravity(Gravity.TOP);
        LocalDateTime moonrise = nextLunarEvent(daily, "moonrise", index, null);
        for (String field : new String[]{"moonrise", "moonset"}) {
            boolean rise = field.equals("moonrise");
            String event = activity.getString(rise ? R.string.moonrise : R.string.moonset);
            LocalDateTime eventTime = rise ? moonrise : nextLunarEvent(daily, field, index, moonrise);
            LinearLayout time = addRiseSetTime(readings, event,
                    eventTime == null ? activity.getString(R.string.unavailable) : eventTime.format(formats().clockFormat()), !rise);
            if (eventTime != null) {
                LocalDate eventDate = eventTime.toLocalDate();
                String dayLabel = eventDate.equals(date) ? activity.getString(R.string.today)
                        : eventDate.equals(date.plusDays(1)) ? activity.getString(R.string.tomorrow)
                        : eventDate.format(formats().shortDate(false));
                TextView when = views.label(dayLabel, 11, views.muted);
                when.setPadding(0, views.dp(3), 0, 0);
                when.setGravity(rise ? Gravity.START : Gravity.END);
                time.addView(when);
            }
        }
        views.addSpaced(tile, readings, 7);
        moonPath = new SkyPath(activity, date, zone, Instant.now(), latitude, longitude, true, formats().clock24);
        LinearLayout.LayoutParams chartSize = new LinearLayout.LayoutParams(-1, views.dp(SKY_PATH_HEIGHT_DP));
        chartSize.topMargin = views.dp(6);
        tile.addView(moonPath, chartSize);
        LinearLayout phaseDetails = views.row();
        phaseDetails.setPadding(views.dp(5), views.dp(3), views.dp(5), 0);
        LinearLayout phaseLabels = views.column();
        if (Double.isFinite(phase)) {
            int position = moonPhaseIndex(phase, date, zone);
            MoonPhaseView icon = new MoonPhaseView(activity, phase, latitude < 0);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            phaseDetails.addView(icon, new LinearLayout.LayoutParams(views.dp(30), views.dp(30)));
            phaseLabels.setPadding(views.dp(8), 0, 0, 0);
            TextView illuminated = views.label(formats().percent(100 * MoonPhaseView.illumination(phase)), 15, views.ink);
            illuminated.setContentDescription(activity.getString(R.string.moon_illuminated, illuminated.getText()));
            phaseLabels.addView(illuminated);
            TextView phaseName = views.label(activity.getString(MOON_PHASE_NAMES[position]), 13, views.muted);
            phaseName.setPadding(0, views.dp(6), 0, 0);
            phaseLabels.addView(phaseName);
        } else {
            phaseLabels.addView(views.label(activity.getString(R.string.phase_unavailable), 13, views.muted));
        }
        phaseDetails.addView(phaseLabels, new LinearLayout.LayoutParams(0, -2, 1));
        views.addSpaced(tile, phaseDetails, 8);
        moonCoordinates = skyCoordinates(true);
        tile.addView(moonCoordinates);
        addDistance(tile, moonDistance);
        DateTimeFormatter format = formats().shortDate(false);
        LinearLayout phases = views.row();
        phases.setGravity(Gravity.TOP);
        phases.setPadding(0, views.dp(13), 0, 0);
        for (boolean full : new boolean[]{true, false}) {
            LinearLayout event = views.column();
            TextView phaseTitle = views.label(full ? activity.getString(R.string.next_full_moon) : activity.getString(R.string.next_new_moon), 13, views.muted);
            if (views.stackedCards()) phaseTitle.setMinLines(2);
            event.addView(phaseTitle);
            TextView when = views.label(LunarPhases.nextDate(date, zone, full).format(format), 16, views.ink);
            when.setPadding(0, views.dp(4), 0, 0);
            event.addView(when);
            phases.addView(event, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tile.addView(phases);
        addOrbitalApsides(tile, zone, true);
        addUpcomingTimes(tile, daily, index, true, phase);
        views.addSpaced(page, tile, 9);
    }

    /** Adds the distance reading below the sky coordinates, in the same place on the Sun and Moon cards. */
    private void addDistance(LinearLayout tile, LiveDistance distance) {
        LinearLayout reading = distance.create();
        reading.setPadding(0, views.dp(12), 0, 0);
        tile.addView(reading);
        distance.refresh();
    }

    /** A "distance from Earth" reading for the Sun or Moon card, kept current from NASA/JPL Horizons. */
    private final class LiveDistance {
        private final HorizonsDistance.Body body;
        private TextView value;
        private View column;
        private long attempt;
        private boolean loading;

        LiveDistance(HorizonsDistance.Body body) {
            this.body = body;
        }

        /** The reading's views, hidden until a distance is known. */
        LinearLayout create() {
            LinearLayout reading = views.column();
            reading.setVisibility(View.GONE);
            value = views.label("", 15, views.ink);
            reading.addView(value);
            TextView title = views.label(activity.getString(R.string.distance_from_earth), 13, views.muted);
            title.setPadding(0, views.dp(6), 0, 0);
            reading.addView(title);
            column = reading;
            return reading;
        }

        void detach() {
            value = null;
            column = null;
        }

        /** Shows the distance from the cached table, downloading a new one when it no longer covers now. */
        void refresh() {
            if (value == null) return;
            Instant now = Instant.now();
            HorizonsDistance table = HorizonsDistance.cached(body);
            if (table != null && table.covers(now)) {
                show(now);
                return;
            }
            long elapsed = SystemClock.elapsedRealtime();
            if (loading || (attempt != 0 && elapsed - attempt < 15 * 60 * 1000L)) return;
            attempt = elapsed;
            loading = true;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return HorizonsDistance.load(body, now);
                } catch (Exception error) {
                    android.util.Log.w("SkyHere", "JPL Horizons distance unavailable: " + body, error);
                    return null;
                }
            }, Background.IO).thenAccept(result -> main.post(() -> {
                loading = false;
                if (!activity.isDestroyed()) show(Instant.now());
            }));
        }

        private void show(Instant now) {
            if (value == null) return;
            HorizonsDistance table = HorizonsDistance.cached(body);
            double km = table == null ? Double.NaN : table.kmAt(now);
            column.setVisibility(Double.isFinite(km) ? View.VISIBLE : View.GONE);
            if (Double.isFinite(km)) value.setText(String.format(formats().appLocale(), "%,d km", Math.round(km)));
        }
    }

    private LocalDateTime nextLunarEvent(JSONObject daily, String field, int index, LocalDateTime after) {
        for (int day = index; day < length(daily); day++) {
            String timestamp = at(daily, field, day);
            if (timestamp.length() < 16) continue;
            LocalDateTime event = LocalDateTime.parse(timestamp);
            if (after == null || event.isAfter(after)) return event;
        }
        return null;
    }

    private void addOrbitalApsides(LinearLayout tile, ZoneId zone, boolean moon) {
        OrbitalApsides.Event[] events;
        try {
            events = OrbitalApsides.upcoming(activity, Instant.now(), moon);
        } catch (java.io.IOException error) {
            android.util.Log.w("SkyHere", "Orbital apsides unavailable", error);
            return;
        }
        if (events[0] == null && events[1] == null) return;
        int[] titles = moon ? new int[]{R.string.next_perigee, R.string.next_apogee}
                : new int[]{R.string.next_perihelion, R.string.next_aphelion};
        LinearLayout upcoming = views.row();
        upcoming.setPadding(0, views.dp(12), 0, 0);
        DateTimeFormatter date = formats().shortDate(true);
        for (int i = 0; i < titles.length; i++) {
            LinearLayout column = views.column();
            column.setPaddingRelative(0, 0, views.dp(6), 0);
            TextView title = views.label(activity.getString(titles[i]), 13, views.muted);
            if (views.stackedCards()) title.setMinLines(2);
            column.addView(title);
            OrbitalApsides.Event event = events[i];
            if (event != null) {
                ZonedDateTime local = event.time.plusSeconds(30).atZone(zone);
                column.addView(views.label(date.format(local), 14, views.ink));
                column.addView(views.label(local.format(formats().clockFormat()) + " · "
                        + String.format(formats().appLocale(), "%,d km", event.distanceKm), 12, views.muted));
            } else {
                column.addView(views.label(activity.getString(R.string.unavailable), 13, views.muted));
            }
            upcoming.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tile.addView(upcoming);
    }

    private static final class PlanetRow {
        final Planets.Body body;
        final View entry;
        final TextView now, magnitude, position, windows;
        List<Planets.Window> observingWindows = List.of();

        PlanetRow(Planets.Body body, View entry, TextView now, TextView magnitude,
                  TextView position, TextView windows) {
            this.body = body;
            this.entry = entry;
            this.now = now;
            this.magnitude = magnitude;
            this.position = position;
            this.windows = windows;
        }

        boolean observesAt(Instant now) {
            return observingWindows.stream().anyMatch(window -> !now.isBefore(window.start) && now.isBefore(window.end));
        }
    }

    private void planets() {
        LinearLayout tile = views.card();
        LinearLayout title = views.row();
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, views.dp(8));
        TextView heading = views.label(activity.getString(R.string.planets), 16, views.ink);
        title.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        CheckBox visibleOnly = new CheckBox(activity);
        visibleOnly.setText(R.string.planets_visible_only);
        visibleOnly.setTextSize(13);
        visibleOnly.setTextColor(views.ink);
        visibleOnly.setChecked(observablePlanetsOnly);
        visibleOnly.setOnCheckedChangeListener((button, checked) -> {
            observablePlanetsOnly = checked;
            updatePlanets(Instant.now());
        });
        title.addView(visibleOnly);
        tile.addView(title);
        int[] names = {R.string.mercury, R.string.venus, R.string.mars, R.string.jupiter, R.string.saturn, R.string.uranus};
        for (Planets.Body body : Planets.Body.values()) {
            LinearLayout entry = views.column();
            entry.setBackground(views.background(activity.getColor(R.color.sky_subtle), 12));
            entry.setPadding(views.dp(11), views.dp(9), views.dp(11), views.dp(9));
            LinearLayout planetTitle = views.row();
            widgets.addCelestialTitleIcon(planetTitle, new PlanetIcon(activity, body));
            TextView name = views.label(activity.getString(names[body.ordinal()]), 16, views.ink);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            planetTitle.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView magnitude = views.badge("");
            LinearLayout.LayoutParams magnitudeSize = new LinearLayout.LayoutParams(-2, -2);
            magnitudeSize.setMarginStart(views.dp(6));
            planetTitle.addView(magnitude, magnitudeSize);
            TextView now = widgets.nowActionBadge(entry::performClick);
            LinearLayout.LayoutParams nowSize = new LinearLayout.LayoutParams(-2, -2);
            nowSize.setMarginStart(views.dp(6));
            planetTitle.addView(now, nowSize);
            entry.addView(planetTitle);
            TextView position = views.label("", 13, views.ink);
            position.setPadding(0, views.dp(4), 0, 0);
            entry.addView(position);
            TextView windows = views.label("", 12, views.muted);
            windows.setPadding(0, views.dp(5), 0, 0);
            entry.addView(windows);
            if (body == Planets.Body.URANUS) {
                TextView optics = views.label(activity.getString(R.string.uranus_binoculars), 12, views.muted);
                optics.setPadding(0, views.dp(4), 0, 0);
                entry.addView(optics);
            }
            PlanetRow planet = new PlanetRow(body, entry, now, magnitude, position, windows);
            planetRows.add(planet);
            views.cardAction(entry, () -> {
                if (planet.observesAt(Instant.now())) {
                    showDailySkyTrack(name.getText().toString(), false, body, planet.windows.getText());
                }
            });
            views.addSpaced(tile, entry, 7);
        }
        noObservablePlanets = views.label(activity.getString(R.string.planets_none_visible_now), 13, views.muted);
        tile.addView(noObservablePlanets);
        TextView note = views.label(activity.getString(R.string.planets_clear_sky), 11, views.muted);
        note.setPadding(0, views.dp(8), 0, 0);
        tile.addView(note);
        updatePlanets(Instant.now());
        views.addSpaced(page, tile, 9);
    }

    private void updatePlanets(Instant now) {
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant();
        boolean sunBelowHorizon = SkyPosition.at(now, latitude, longitude, false).zenith > 90;
        boolean anyObservable = false;
        for (PlanetRow row : planetRows) {
            Planets.Reading reading = Planets.at(row.body, now, latitude, longitude);
            boolean showMagnitude = reading.elevation > 0 && sunBelowHorizon;
            row.magnitude.setVisibility(showMagnitude ? View.VISIBLE : View.GONE);
            if (showMagnitude) {
                widgets.setMagnitudeBadge(row.magnitude, reading.magnitude, widgets.magnitudeColor(reading.magnitude));
            }
            if (!today.equals(planetWindowDate)) {
                List<String> hours = new ArrayList<>();
                row.observingWindows = Planets.windows(row.body, today, zone, latitude, longitude);
                for (Planets.Window window : row.observingWindows) {
                    String start = window.start.atZone(zone).format(formats().clockFormat());
                    String end = window.end.equals(tomorrow) && formats().clock24 ? "24:00" : window.end.atZone(zone).format(formats().clockFormat());
                    hours.add(start + "–" + end);
                }
                row.windows.setText(hours.isEmpty() ? activity.getString(R.string.planet_no_window)
                        : activity.getString(R.string.planet_today_windows, android.text.TextUtils.join(" · ", hours)));
            }
            boolean observingNow = row.observesAt(now);
            anyObservable |= observingNow;
            row.entry.setVisibility(!observablePlanetsOnly || observingNow ? View.VISIBLE : View.GONE);
            setSkyTrackAvailable(row.entry, row.now, observingNow);
            row.position.setVisibility(observingNow ? View.VISIBLE : View.GONE);
            if (observingNow) {
                row.position.setText(activity.getString(R.string.planet_position, Math.round(reading.azimuth) % 360,
                        widgets.compassDirection(reading.azimuth), Math.round(reading.elevation)));
            }
        }
        noObservablePlanets.setVisibility(observablePlanetsOnly && !anyObservable ? View.VISIBLE : View.GONE);
        planetWindowDate = today;
    }

    private void issPasses() {
        LinearLayout tile = views.card();
        tile.addView(widgets.astronomyTitle(widgets.astronomyTitleIcon(R.drawable.ic_iss_astronomy), activity.getString(R.string.iss_passes)));
        TextView note = views.label(activity.getString(R.string.iss_scope), 13, views.muted);
        views.addSpaced(tile, note, 8);
        TextView status = views.label(activity.getString(R.string.iss_loading), 13, views.muted);
        tile.addView(status);
        views.addSpaced(page, tile, 9);
        double lat = latitude;
        double lon = longitude;
        double height = data.optDouble("elevation");
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        CompletableFuture.supplyAsync(() -> {
            try {
                return IssPasses.upcoming(activity.getApplicationContext(), lat, lon, height, Instant.now());
            } catch (Exception error) {
                android.util.Log.w("SkyHere", "ISS predictions unavailable", error);
                return null;
            }
        }, Background.IO).thenAccept(passes -> main.post(() -> {
            if (activity.isDestroyed() || !tile.isAttachedToWindow()) return;
            if (passes == null || passes.isEmpty()) {
                status.setText(passes == null ? R.string.iss_unavailable : R.string.iss_empty);
                return;
            }
            tile.removeView(status);
            LinearLayout rows = views.column();
            tile.addView(rows);
            Button more = views.moreButton();
            tile.addView(more);
            more.setOnClickListener(v -> appendIssPasses(rows, more, passes, zone));
            appendIssPasses(rows, more, passes, zone);
        }));
    }

    private void appendIssPasses(LinearLayout rows, Button more, List<IssPasses.Pass> passes, ZoneId zone) {
        int start = rows.getChildCount();
        int end = Math.min(start + ASTRONOMY_PAGE_SIZE, passes.size());
        DateTimeFormatter dayFormat = formats().shortDate(true);
        DateTimeFormatter timeFormat = formats().clockFormat();
        for (int i = start; i < end; i++) {
            IssPasses.Pass pass = passes.get(i);
            ZonedDateTime rise = Instant.ofEpochSecond(pass.rise.time).atZone(zone);
            ZonedDateTime set = Instant.ofEpochSecond(pass.set.time).atZone(zone);
            String range = timeFormat.format(rise) + " – " + timeFormat.format(set);
            if (!rise.toLocalDate().equals(set.toLocalDate())) range += activity.getString(R.string.next_day_suffix);
            long duration = pass.set.time - pass.rise.time;
            LinearLayout entry = views.column();
            entry.setPadding(views.dp(12), views.dp(8), views.dp(12), views.dp(8));
            entry.setBackground(views.background(activity.getColor(R.color.sky_subtle), 12));
            LinearLayout heading = views.row();
            heading.setGravity(Gravity.TOP);
            String headingText = dayFormat.format(rise) + " · " + range;
            TextView period = views.label(headingText.replace(" – ", "\u00a0–\u00a0"), 15, views.ink);
            heading.addView(period, new LinearLayout.LayoutParams(0, -2, 1));
            TextView magnitude = views.badge("");
            widgets.setMagnitudeBadge(magnitude, pass.magnitude, widgets.magnitudeColor(pass.magnitude));
            LinearLayout.LayoutParams magnitudeSize = new LinearLayout.LayoutParams(-2, -2);
            magnitudeSize.setMarginStart(views.dp(6));
            heading.addView(magnitude, magnitudeSize);
            TextView live = widgets.nowActionBadge(entry::performClick);
            live.setVisibility(View.GONE);
            LinearLayout.LayoutParams liveSize = new LinearLayout.LayoutParams(-2, -2);
            liveSize.setMarginStart(views.dp(6));
            heading.addView(live, liveSize);
            View arrow = views.chevron();
            visibilityBadges.add(new VisibilityBadge(pass.rise.time, pass.set.time, live, arrow));
            LinearLayout.LayoutParams arrowSize = new LinearLayout.LayoutParams(-2, -2);
            arrowSize.setMarginStart(views.dp(6));
            heading.addView(arrow, arrowSize);
            views.addSpaced(entry, heading, 4);
            TextView details = views.label(activity.getString(R.string.iss_details, duration / 60, duration % 60,
                    Math.round(pass.peak.elevation)), 13, views.muted);
            views.addSpaced(entry, details, 3);
            String appears = widgets.issBearing(pass.rise.azimuth);
            String culminates = widgets.issBearing(pass.peak.azimuth);
            String disappears = widgets.issBearing(pass.set.azimuth);
            TextView directions = views.label(activity.getString(R.string.iss_directions_short, appears, culminates, disappears), 13, views.muted);
            directions.setContentDescription(activity.getString(R.string.iss_directions, appears, culminates, disappears));
            entry.addView(directions);
            String url = issPassUrl(pass, zone, latitude, longitude, data.optDouble("elevation", 0), place);
            views.cardAction(entry, () -> showIssPass(pass, zone, headingText, url));
            views.addSpaced(rows, entry, 8);
        }
        more.setVisibility(end < passes.size() ? View.VISIBLE : View.GONE);
        refreshAstronomyVisibility();
    }

    static class VisibilityBadge {
        final long start;
        final long end;
        final TextView badge;
        final View inactiveArrow;

        VisibilityBadge(long start, long end, TextView badge, View inactiveArrow) {
            this.start = start;
            this.end = end;
            this.badge = badge;
            this.inactiveArrow = inactiveArrow;
        }

        long update(long nowMillis, long second) {
            boolean visible = second >= start && second <= end;
            badge.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (inactiveArrow != null) inactiveArrow.setVisibility(visible ? View.GONE : View.VISIBLE);
            if (second < start) return start * 1000L - nowMillis;
            if (second <= end) return (end + 1) * 1000L - nowMillis;
            return Long.MAX_VALUE;
        }
    }

    private void refreshAstronomyVisibility() {
        main.removeCallbacks(updateAstronomyVisibility);
        if (!resumed) return;
        long millis = System.currentTimeMillis();
        long second = Math.floorDiv(millis, 1000L);
        long delay = Long.MAX_VALUE;
        for (VisibilityBadge live : visibilityBadges) delay = Math.min(delay, live.update(millis, second));
        delay = Math.min(delay, skyTrack.updateLive(millis, second));
        if (delay != Long.MAX_VALUE) main.postDelayed(updateAstronomyVisibility, delay);
    }

    private LinearLayout addSkyTrackHeading(LinearLayout panel, View icon, String name, Runnable open) {
        LinearLayout heading = widgets.astronomyTitle(icon, name);
        heading.setMinimumHeight(views.dp(32));
        TextView now = widgets.nowActionBadge(open);
        LinearLayout.LayoutParams badgeSize = new LinearLayout.LayoutParams(-2, -2);
        badgeSize.setMarginStart(views.dp(6));
        heading.addView(now, badgeSize);
        panel.addView(heading);
        return heading;
    }

    private void setSkyTrackAvailable(View target, View indicator, boolean available) {
        target.setClickable(available);
        target.setFocusable(available);
        indicator.setVisibility(available ? View.VISIBLE : View.GONE);
    }

    public void showIssPass(IssPasses.Pass pass, ZoneId zone, String period, String url) {
        LocalDate date = Instant.ofEpochSecond(pass.rise.time).atZone(zone).toLocalDate();
        skyTrack.showSkyTrack(activity.getString(R.string.iss_track), period, pass.magnitude, widgets.magnitudeColor(pass.magnitude), panel -> {
            TextView live = skyTrack.issVisibilityBadge();
            LinearLayout.LayoutParams liveSize = new LinearLayout.LayoutParams(-2, -2);
            liveSize.topMargin = views.dp(6);
            panel.addView(live, liveSize);
            skyTrack.trackIss(pass, live);
            skyTrack.addSkyTrack(panel, pass.track, pass.rise, pass.peak, pass.set, null, pass.brightest);
            skyTrack.addSkyTrackPoint(panel, R.string.iss_track_start, pass.rise, SkyTrack.START_COLOR, zone, date);
            skyTrack.addSkyTrackPoint(panel, R.string.iss_track_peak, pass.peak, SkyTrack.MAX_COLOR, zone, date);
            skyTrack.addSkyTrackPoint(panel, R.string.iss_track_brightest, pass.brightest, SkyTrack.BRIGHTEST_COLOR, zone, date);
            skyTrack.addSkyTrackPoint(panel, R.string.iss_track_end, pass.set, SkyTrack.END_COLOR, zone, date);

            if (url != null) {
                Button link = views.iconActionButton(R.string.iss_open_heavens_above, R.drawable.ic_open_external,
                        () -> widgets.openAstronomyPage(url));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
                size.topMargin = views.dp(13);
                size.gravity = Gravity.CENTER_HORIZONTAL;
                panel.addView(link, size);
            }
        });
        refreshAstronomyVisibility();
    }

    private void showDailySkyTrack(String name, boolean moon, Planets.Body planet, CharSequence observingWindows) {
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        Instant now = Instant.now();
        SkyPosition sun = SkyPosition.at(now, latitude, longitude, false);
        SkyPosition body = moon ? SkyPosition.at(now, latitude, longitude, true) : sun;
        if (planet == null && !(moon ? moonVisible(now, body) : sunVisible(now, sun))) {
            setSkyCoordinates(moon ? moonCoordinates : sunCoordinates, now, moon);
            return;
        }
        double magnitude;
        if (planet != null) {
            Planets.Reading reading = Planets.at(planet, now, latitude, longitude);
            magnitude = reading.elevation > 0 && sun.zenith > 90 ? reading.magnitude : Double.NaN;
        } else {
            magnitude = moon ? LunarPhases.apparentMagnitude(sun, body) : -26.74 + 5 * Math.log10(sun.distanceAu);
        }
        SkyTrajectory track;
        if (moon) {
            JSONObject daily = data.optJSONObject("daily");
            LocalDateTime local = now.atZone(zone).toLocalDateTime();
            Instant rise = null;
            for (int i = 0; i < length(daily); i++) {
                String timestamp = at(daily, "moonrise", i);
                if (timestamp.length() < 16) continue;
                Instant event = LocalDateTime.parse(timestamp).atZone(zone).toInstant();
                if (!event.isAfter(now) && (rise == null || event.isAfter(rise))) rise = event;
            }
            LocalDateTime set = nextLunarEvent(daily, "moonset", 0, local);
            track = SkyTrajectory.lunarPass(now, zone, latitude, longitude, rise,
                    set == null ? null : set.atZone(zone).toInstant());
        } else {
            track = SkyTrajectory.today(now, zone, latitude, longitude, planet);
        }
        boolean nightOnly = planet != null;
        ZonedDateTime localNow = now.atZone(zone);
        LocalDate date = moon && track.rise != null ? Instant.ofEpochSecond(track.rise.time).atZone(zone).toLocalDate()
                : localNow.toLocalDate();
        long trackEnd = moon ? track.points.get(track.points.size() - 1).time
                : localNow.toLocalDate().plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;
        double lat = latitude;
        double lon = longitude;
        String period = date.format(formats().shortDate(true));
        // A Moon that stays up for days is drawn over one lunar day; show where that window starts and ends.
        SkyTrajectory.Point first = moon && track.rise == null ? track.points.get(0) : track.rise;
        SkyTrajectory.Point last = moon && track.set == null ? track.points.get(track.points.size() - 1) : track.set;
        if (track.continuous && first != null && last != null && first.time <= last.time) {
            period += " · " + skyTrack.skyTrackTime(first, zone, date) + " – " + skyTrack.skyTrackTime(last, zone, date);
        }
        int fill = planet == null ? SignalColors.PURPLE : widgets.magnitudeColor(magnitude);
        skyTrack.showSkyTrack(activity.getString(R.string.daily_sky_track, name), period, magnitude, fill, panel -> {
            skyTrack.trackCelestial(second -> {
                if (second > trackEnd) return null;
                if (planet != null) return SkyTrajectory.positionAt(second, lat, lon, false, planet);
                Instant instant = Instant.ofEpochSecond(second);
                SkyPosition position = SkyPosition.at(instant, lat, lon, moon);
                return (moon ? moonVisible(instant, position) : sunVisible(instant, position))
                        ? new SkyTrajectory.Point(second, position.azimuth, Math.max(0, 90 - position.zenith))
                        : null;
            });
            if (observingWindows != null) {
                views.addSpaced(panel, views.label(observingWindows.toString(), 13, views.accent), 6);
            }
            skyTrack.addSkyTrack(panel, track.points, track.rise, track.peak, track.set, track.now, null);
            if (track.peak == null) {
                int message = nightOnly ? R.string.sky_track_no_night_path : R.string.sky_track_no_path;
                panel.addView(views.label(activity.getString(message), 14, views.muted));
            }
            skyTrack.addSkyTrackPoint(panel, nightOnly ? R.string.iss_track_start : R.string.sky_track_rise,
                    track.rise, SkyTrack.START_COLOR, zone, date);
            skyTrack.addSkyTrackPoint(panel, R.string.iss_track_peak, track.peak, SkyTrack.MAX_COLOR, zone, date);
            skyTrack.addSkyTrackPoint(panel, nightOnly ? R.string.iss_track_end : R.string.sky_track_set,
                    track.set, SkyTrack.END_COLOR, zone, date);
        });
        refreshAstronomyVisibility();
    }

    public String issPassUrl(IssPasses.Pass pass, ZoneId zone, double latitude, double longitude,
                              double altitude, String place) {
        double modifiedJulianDate = 40587 + pass.peak.time / 86400.0;
        return android.net.Uri.parse("https://heavens-above.com/passdetails.aspx").buildUpon()
                .appendQueryParameter("satid", "25544")
                .appendQueryParameter("mjd", String.format(Locale.ROOT, "%.8f", modifiedJulianDate))
                .appendQueryParameter("type", "V")
                .appendQueryParameter("lat", Double.toString(latitude))
                .appendQueryParameter("lng", Double.toString(longitude))
                .appendQueryParameter("alt", Long.toString(Math.round(altitude)))
                .appendQueryParameter("loc", place)
                .appendQueryParameter("tz", "Europe/Rome".equals(zone.getId()) ? "CET" : "UCT")
                .appendQueryParameter("cul", formats().italian() ? "it" : "en").build().toString();
    }

    private void astronomicalSeasons() {
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        LinearLayout seasons = views.card();
        seasons.addView(widgets.astronomyTitle(widgets.astronomyTitleIcon(R.drawable.ic_seasons_astronomy),
                activity.getString(R.string.astronomical_seasons)));
        LinearLayout content = views.column();
        content.setPadding(0, views.dp(6), 0, 0);
        seasons.addView(content);
        LocalDate today = LocalDate.now(zone);
        DateTimeFormatter date = DateTimeFormatter.ofPattern("EEE d MMM uuuu", formats().appLocale());
        DateTimeFormatter time = formats().clockFormat();
        // UTC timestamps from the US Naval Observatory Earth Seasons API.
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                activity.getAssets().open("astronomical-seasons-utc.txt"), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while (content.getChildCount() < 4 && (line = reader.readLine()) != null) {
                Instant event = Instant.parse(line);
                ZonedDateTime local = event.atZone(zone);
                LocalDate eventDate = local.toLocalDate();
                if (eventDate.isBefore(today)) continue;
                int title;
                switch (event.atZone(java.time.ZoneOffset.UTC).getMonth()) {
                    case MARCH: title = R.string.march_equinox; break;
                    case JUNE: title = R.string.june_solstice; break;
                    case SEPTEMBER: title = R.string.september_equinox; break;
                    case DECEMBER: title = R.string.december_solstice; break;
                    default: continue;
                }
                LinearLayout item = views.column();
                item.setPadding(0, views.dp(5), 0, views.dp(5));
                LinearLayout heading = views.row();
                heading.setGravity(Gravity.CENTER_VERTICAL);
                heading.addView(views.label(activity.getString(title), 15, views.ink), new LinearLayout.LayoutParams(0, -2, 1));
                TextView badge = views.nowBadge();
                badge.setVisibility(eventDate.equals(today) ? View.VISIBLE : View.GONE);
                heading.addView(badge);
                visibilityBadges.add(new VisibilityBadge(eventDate.atStartOfDay(zone).toEpochSecond(),
                        eventDate.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1, badge, null));
                item.addView(heading);
                item.addView(views.label(date.format(local) + " · " + time.format(local), 13, views.muted));
                long daylight = SkyPosition.daylightMinutes(eventDate, zone, latitude);
                item.addView(views.label(activity.getString(R.string.season_day_night,
                        formats().durationText(daylight), formats().durationText(1440 - daylight)), 13, views.muted));
                content.addView(item);
            }
        } catch (java.io.IOException error) {
            android.util.Log.w("SkyHere", "Season calendar unavailable", error);
        }
        if (content.getChildCount() == 0) content.addView(views.label(activity.getString(R.string.unavailable), 14, views.muted));
        View divider = new View(activity);
        divider.setBackgroundColor(activity.getColor(R.color.sky_card_outline));
        LinearLayout.LayoutParams dividerSize = new LinearLayout.LayoutParams(-1, views.dp(1));
        dividerSize.setMargins(0, views.dp(7), 0, views.dp(8));
        seasons.addView(divider, dividerSize);

        seasons.addView(views.label(place, 14, views.ink));
        seasons.addView(views.label(activity.getString(R.string.season_coordinates,
                formats().number(latitude, "%.4f"), formats().number(longitude, "%.4f")), 13, views.muted));
        TextView rotation = views.label(activity.getString(R.string.earth_rotation_speed,
                formats().number(SkyPosition.rotationSpeedKmh(latitude), "%,.0f")), 14, views.ink);
        rotation.setPadding(0, views.dp(4), 0, 0);
        seasons.addView(rotation);
        seasons.addView(views.label(activity.getString(R.string.solar_declination,
                formats().number(SkyPosition.solarDeclinationDegrees(Instant.now()), "%.2f")), 13, views.muted));
        views.addSpaced(page, seasons, 9);
        refreshAstronomyVisibility();
    }

    private static double moonPhaseForToday(JSONObject daily, int index, LocalDate date, ZoneId zone) {
        double phase = value(daily, "moon_phase", index);
        double following = value(daily, "moon_phase", index + 1);
        ZonedDateTime now = ZonedDateTime.now(zone);
        if (!now.toLocalDate().equals(date) || !Double.isFinite(phase) || !Double.isFinite(following)) return phase;
        // Open-Meteo samples each daily phase at local noon.
        double timeFromNoon = now.toLocalTime().toSecondOfDay() / 86400.0 - .5;
        double interpolated = phase + timeFromNoon * Math.IEEEremainder(following - phase, 1);
        return interpolated - Math.floor(interpolated);
    }

    private static int moonPhaseIndex(double phase, LocalDate date, ZoneId zone) {
        for (int quarter = 0; quarter < 4; quarter++) {
            if (LunarPhases.nextDate(date, zone, quarter).equals(date)) return quarter * 2;
        }
        return 1 + 2 * Math.min(3, (int) (phase * 4));
    }

    public static long daylightSeconds(JSONObject daily, int index) {
        String rise = at(daily, "sunrise", index);
        String set = at(daily, "sunset", index);
        if (rise.length() < 16 || set.length() < 16) return -1;
        return Duration.between(LocalDateTime.parse(rise), LocalDateTime.parse(set)).getSeconds();
    }

    public static boolean isPolarDaylight(long seconds) {
        return seconds == 0 || seconds == 86400;
    }

    public static int polarDaylightLabel(long seconds) {
        return seconds == 0 ? R.string.polar_night : R.string.midnight_sun;
    }

    private String daylightSummary(long seconds) {
        if (seconds < 0) return activity.getString(R.string.daylight_unavailable);
        long minutes = seconds / 60;
        String duration = minutes >= 60 ? activity.getString(R.string.daylight_hours_minutes, minutes / 60, minutes % 60)
                : formats().durationText(minutes);
        return activity.getString(R.string.daylight_duration, duration);
    }

    private void addUpcomingTimes(LinearLayout tile, JSONObject daily, int index, boolean moon, double currentMoonPhase) {
        int count = OpenMeteo.UPCOMING_ASTRONOMY_DAYS;
        int end = Math.min(length(daily), index + count);
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        int upcomingDays = count - 1;
        LocalDate last = date.plusDays(upcomingDays);
        LocalDate firstMissing = date.plusDays(end - index);
        LocalDate tailEnd = last;
        if (moon) {
            for (int i = end - 1; i >= index; i--) {
                String rise = at(daily, "moonrise", i);
                if (rise.length() < 16) continue;
                if (nextLunarEvent(daily, "moonset", i, LocalDateTime.parse(rise)) == null) {
                    tailEnd = last.plusDays(1);
                }
                break;
            }
            if (!firstMissing.isAfter(last)) tailEnd = last.plusDays(1);
        }
        LocalDate fetchThrough = tailEnd;
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        String title = activity.getString(R.string.next_days, upcomingDays);
        LinearLayout collapsible = views.column();
        LinearLayout table = views.column();
        collapsible.addView(table);
        TextView status = views.label("", 12, views.muted);
        views.addSpaced(collapsible, status, 6);
        status.setVisibility(View.GONE);
        boolean[] loading = {false};
        boolean[] complete = {firstMissing.isAfter(fetchThrough)};
        collapsible.setVisibility(View.GONE);
        Button toggle = views.accordionButton(title);
        toggle.setOnClickListener(v -> {
            boolean open = collapsible.getVisibility() != View.VISIBLE;
            collapsible.setVisibility(open ? View.VISIBLE : View.GONE);
            views.updateAccordion(toggle, open);
            if (!open || complete[0] || loading[0]) return;
            loading[0] = true;
            status.setText(R.string.upcoming_astronomy_loading);
            status.setVisibility(View.VISIBLE);
            loadAstronomyTail(firstMissing, fetchThrough).thenAccept(extra -> main.post(() -> {
                if (activity.isDestroyed() || !table.isAttachedToWindow()) return;
                loading[0] = false;
                int remaining = index + count - end;
                if (extra == null || length(extra) < remaining) {
                    if (remaining > 0) status.setText(R.string.upcoming_astronomy_unavailable);
                    else status.setVisibility(View.GONE);
                    return;
                }
                if (moon) {
                    table.removeViews(1, table.getChildCount() - 1);
                    appendUpcomingTimeRows(table, daily, index, end, true, zone, extra, currentMoonPhase);
                }
                appendUpcomingTimeRows(table, extra, 0, remaining, moon, zone, moon ? daily : null, Double.NaN);
                if (!moon) sizeSolarColumns(table, tile);
                complete[0] = true;
                status.setVisibility(View.GONE);
            }));
        });
        LinearLayout.LayoutParams toggleSize = new LinearLayout.LayoutParams(-1, -2);
        toggleSize.topMargin = views.dp(8);
        tile.addView(toggle, toggleSize);
        tile.addView(collapsible);
        if (moon) {
            addEventTableRow(table, activity.getString(R.string.date), views.label(activity.getString(R.string.phase), 12, views.muted), views.muted,
                    activity.getString(R.string.moonrise_column), activity.getString(R.string.moonset_column));
        } else {
            addEventTableRow(table, activity.getString(R.string.date), null, views.muted, activity.getString(R.string.sunrise),
                    activity.getString(R.string.sunset), activity.getString(R.string.daylight_column), activity.getString(R.string.max_elevation));
        }
        appendUpcomingTimeRows(table, daily, index, end, moon, zone, null, currentMoonPhase);
        if (!moon) sizeSolarColumns(table, tile);
    }

    private CompletableFuture<JSONObject> loadAstronomyTail(LocalDate start, LocalDate end) {
        double lat = latitude;
        double lon = longitude;
        String key = lat + "," + lon + ":" + start + ":" + end;
        if (!key.equals(astronomyTailKey) || astronomyTail == null
                || (astronomyTail.isDone() && astronomyTail.getNow(null) == null)) {
            astronomyTailKey = key;
            astronomyTail = CompletableFuture.supplyAsync(() -> {
                try {
                    return OpenMeteo.astronomyDays(lat, lon, start, end);
                } catch (Exception error) {
                    android.util.Log.w("SkyHere", "Additional Sun and Moon dates unavailable", error);
                    return null;
                }
            }, Background.IO);
        }
        return astronomyTail;
    }

    private void appendUpcomingTimeRows(LinearLayout table, JSONObject daily, int from, int end, boolean moon,
                                        ZoneId zone, JSONObject lunarNeighbor, double currentMoonPhase) {
        String rise = moon ? "moonrise" : "sunrise";
        String set = moon ? "moonset" : "sunset";
        DateTimeFormatter dateFormat = formats().shortDate(false);
        for (int i = from; i < end; i++) {
            LocalDate day = LocalDate.parse(at(daily, "time", i));
            String date = day.format(dateFormat);
            if (!moon) {
                long seconds = daylightSeconds(daily, i);
                String duration = seconds <= 0 ? "-" : String.format(formats().appLocale(), "%d:%02d:%02d",
                        seconds / 3600, seconds / 60 % 60, seconds % 60);
                boolean polar = isPolarDaylight(seconds);
                LinearLayout line = addEventTableRow(table, date, null, views.ink, polar ? "—" : formats().eventClock(at(daily, rise, i)),
                        polar ? "—" : formats().eventClock(at(daily, set, i)),
                        duration, solarMaximumElevation(day, zone, seconds));
                if (!polar) {
                    highlightSolarExtremum((TextView) line.getChildAt(1), position -> solarTableTime(daily, rise, position), i, end);
                    highlightSolarExtremum((TextView) line.getChildAt(2), position -> solarTableTime(daily, set, position), i, end);
                }
                if (seconds > 0) {
                    highlightSolarExtremum((TextView) line.getChildAt(3), position -> daylightSeconds(daily, position), i, end);
                }
                continue;
            }
            View phaseIcon;
            double phase = i == from && Double.isFinite(currentMoonPhase) ? currentMoonPhase : value(daily, "moon_phase", i);
            if (Double.isFinite(phase)) {
                LinearLayout phaseCell = views.largeText() ? views.column() : views.row();
                phaseCell.setGravity(Gravity.CENTER);
                MoonPhaseView glyph = new MoonPhaseView(activity, phase, latitude < 0);
                glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                phaseCell.addView(glyph, new LinearLayout.LayoutParams(views.dp(21), views.dp(21)));
                String amount = formats().percent(100 * MoonPhaseView.illumination(phase));
                TextView illumination = views.label(amount, 11, views.muted);
                if (!views.largeText()) illumination.setPadding(views.dp(4), 0, 0, 0);
                illumination.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                phaseCell.addView(illumination);
                String phaseName = activity.getString(MOON_PHASE_NAMES[moonPhaseIndex(phase, day, zone)]);
                phaseCell.setContentDescription(phaseName + ", " + activity.getString(R.string.moon_illuminated, amount));
                phaseIcon = phaseCell;
            } else {
                phaseIcon = views.label("—", 13, views.muted);
                ((TextView) phaseIcon).setGravity(Gravity.CENTER);
                phaseIcon.setContentDescription(activity.getString(R.string.phase_unavailable));
            }
            String riseTime = at(daily, rise, i);
            LocalDateTime moonrise = riseTime.length() >= 16 ? LocalDateTime.parse(riseTime) : null;
            LocalDateTime moonset = null;
            if (moonrise != null) {
                moonset = nextLunarEvent(daily, set, i, moonrise);
                if (moonset == null && lunarNeighbor != null) moonset = nextLunarEvent(lunarNeighbor, set, 0, moonrise);
            } else {
                String timestamp = at(daily, set, i);
                if (timestamp.length() >= 16) {
                    moonset = LocalDateTime.parse(timestamp);
                    moonrise = previousMoonrise(daily, moonset);
                    if (moonrise == null && lunarNeighbor != null) moonrise = previousMoonrise(lunarNeighbor, moonset);
                }
            }
            DateTimeFormatter timeFormat = formats().secondsClockFormat();
            String rising = moonrise == null ? "—" : moonrise.format(timeFormat);
            String setTime = moonset == null ? "—" : moonset.format(timeFormat);
            LinearLayout line = addEventTableRow(table, date, phaseIcon, views.ink, rising, setTime);
            addLunarDateOffset((TextView) line.getChildAt(2), rising, day, moonrise, dateFormat);
            addLunarDateOffset((TextView) line.getChildAt(3), setTime, day, moonset, dateFormat);
        }
    }

    private LocalDateTime previousMoonrise(JSONObject daily, LocalDateTime moonset) {
        for (int i = length(daily) - 1; i >= 0; i--) {
            String timestamp = at(daily, "moonrise", i);
            if (timestamp.length() < 16) continue;
            LocalDateTime moonrise = LocalDateTime.parse(timestamp);
            if (moonrise.isBefore(moonset)) return moonrise;
        }
        return null;
    }

    private void addLunarDateOffset(TextView cell, String time, LocalDate day, LocalDateTime event,
                                    DateTimeFormatter dateFormat) {
        if (event == null) return;
        long offset = ChronoUnit.DAYS.between(day, event.toLocalDate());
        if (offset == 0) return;
        String suffix = (offset < 0 ? "−" : "+") + Math.abs(offset);
        android.text.SpannableString reading = new android.text.SpannableString(time + suffix);
        int flags = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        reading.setSpan(new android.text.style.SuperscriptSpan(), time.length(), reading.length(), flags);
        reading.setSpan(new android.text.style.RelativeSizeSpan(0.7f), time.length(), reading.length(), flags);
        cell.setText(reading);
        cell.setContentDescription(time + " · " + event.toLocalDate().format(dateFormat));
    }

    private void highlightSolarExtremum(TextView cell, java.util.function.IntToLongFunction reading, int index, int end) {
        long value = reading.applyAsLong(index);
        if (value < 0 || index == 0) return;
        long previous = reading.applyAsLong(index - 1);
        if (previous < 0 || previous == value) return;
        int after = index + 1;
        while (after < end && reading.applyAsLong(after) == value) after++;
        if (after >= end) return;
        long next = reading.applyAsLong(after);
        if (next < 0) return;
        if (value < previous && value < next) {
            cell.setTextColor(activity.getColor(R.color.sky_solar_minimum));
            cell.setTypeface(cell.getTypeface(), Typeface.BOLD);
        } else if (value > previous && value > next) {
            cell.setTextColor(activity.getColor(R.color.sky_solar_maximum));
            cell.setTypeface(cell.getTypeface(), Typeface.BOLD);
        }
    }

    private long solarTableTime(JSONObject daily, String event, int index) {
        String timestamp = at(daily, event, index);
        if (timestamp.length() < 16 || isPolarDaylight(daylightSeconds(daily, index))) return -1;
        return LocalDateTime.parse(timestamp).toLocalTime().toSecondOfDay();
    }

    private void sizeSolarColumns(LinearLayout table, LinearLayout tile) {
        LinearLayout heading = (LinearLayout) table.getChildAt(0);
        int[] widths = new int[heading.getChildCount()];
        int available = views.dp(activity.getResources().getConfiguration().screenWidthDp)
                - page.getPaddingLeft() - page.getPaddingRight() - tile.getPaddingLeft() - tile.getPaddingRight();
        int minimumDateWidth = 0;
        for (int row = 1; row < table.getChildCount(); row++) {
            LinearLayout line = (LinearLayout) table.getChildAt(row);
            for (int col = 0; col < widths.length; col++) {
                TextView cell = (TextView) line.getChildAt(col);
                String value = cell.getText().toString();
                widths[col] = Math.max(widths[col], views.textWidth(cell, value) + cell.getPaddingLeft() + views.dp(1));
                if (col == 0) {
                    for (String word : value.split(" ")) {
                        minimumDateWidth = Math.max(minimumDateWidth, views.textWidth(cell, word) + views.dp(1));
                    }
                }
            }
        }
        for (int col = 0; col < widths.length; col++) {
            TextView cell = (TextView) heading.getChildAt(col);
            for (String word : cell.getText().toString().split(" ")) {
                widths[col] = Math.max(widths[col], views.textWidth(cell, word) + cell.getPaddingLeft() + views.dp(1));
            }
        }
        int total = 0;
        for (int width : widths) total += width;
        widths[0] -= Math.max(0, total - available);
        if (widths[0] < minimumDateWidth) {
            table.removeViewAt(0);
            for (int row = 0; row < table.getChildCount(); row++) {
                LinearLayout line = (LinearLayout) table.getChildAt(row);
                line.setOrientation(LinearLayout.VERTICAL);
                line.setGravity(Gravity.START);
                TextView date = (TextView) line.getChildAt(0);
                date.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                date.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                for (int col = 1; col < widths.length; col++) {
                    TextView value = (TextView) line.getChildAt(col);
                    TextView key = (TextView) heading.getChildAt(col);
                    value.setText(key.getText() + "  " + value.getText());
                    value.setGravity(Gravity.START);
                    value.setPadding(0, views.dp(3), 0, 0);
                    value.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                }
            }
            return;
        }
        for (int row = 0; row < table.getChildCount(); row++) {
            LinearLayout line = (LinearLayout) table.getChildAt(row);
            for (int col = 0; col < widths.length; col++) {
                line.getChildAt(col).setLayoutParams(new LinearLayout.LayoutParams(widths[col], -2, 1));
            }
        }
    }

    private LinearLayout addEventTableRow(LinearLayout table, String date, View phase, int color, String... readings) {
        LinearLayout line = views.row();
        line.setPadding(0, views.dp(5), 0, views.dp(5));
        TextView dateLabel = views.label(date, 13, color);
        line.addView(dateLabel, new LinearLayout.LayoutParams(0, -2, 1.3f));
        if (phase != null) {
            if (phase instanceof TextView) ((TextView) phase).setGravity(Gravity.CENTER);
            line.addView(phase, new LinearLayout.LayoutParams(views.dp(72), -2));
        }
        for (String time : readings) {
            TextView value = views.label(time, 13, color);
            value.setGravity(Gravity.END);
            if (phase == null) value.setPadding(views.dp(6), 0, 0, 0);
            line.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        }
        table.addView(line);
        return line;
    }
}
