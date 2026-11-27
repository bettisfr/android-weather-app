package it.skynow.app.dashboard;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.widget.ScrollView;
import it.skynow.app.R;
import it.skynow.app.SkyTheme;
import it.skynow.app.astronomy.AuroraForecast;
import it.skynow.app.astronomy.EclipseCatalog;
import it.skynow.app.astronomy.EclipseIcon;
import it.skynow.app.astronomy.IssPasses;
import it.skynow.app.astronomy.LunarPhases;
import it.skynow.app.astronomy.MoonPhaseView;
import it.skynow.app.astronomy.OrbitalApsides;
import it.skynow.app.astronomy.PlanetIcon;
import it.skynow.app.astronomy.Planets;
import it.skynow.app.astronomy.SkyPath;
import it.skynow.app.astronomy.SkyPosition;
import it.skynow.app.astronomy.SkyTrack;
import it.skynow.app.astronomy.SkyTrajectory;
import it.skynow.app.astronomy.TransitCatalog;
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
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;

    // The forecast the tab was last attached with; data is null when only events are shown.
    private LinearLayout page;
    private ScrollView scroll;
    private JSONObject data;
    private double latitude;
    private double longitude;
    private String place;

    private AuroraForecast auroraForecast;
    private long auroraAttempt;
    private boolean auroraLoading;
    private TextView auroraStatus;
    private TextView auroraNow;
    private TextView auroraTimes;
    private TextView currentKpView;
    private View currentKpBar;
    private AuroraForecast.KpReading currentKp;
    private LinearLayout kpTable;
    private List<AuroraForecast.KpPeriod> kpPeriods = List.of();
    private long kpAttempt;
    private boolean kpLoading;
    private String pendingAstronomyEventSection;
    private ZoneId astronomyNotificationZone;
    private boolean solarEclipsesExpanded;
    private boolean lunarEclipsesExpanded;
    private boolean transitsExpanded;
    private TextView sunCoordinates;
    private TextView moonCoordinates;
    private LinearLayout sunTrackHeading, moonTrackHeading;
    private SkyPath sunPath, moonPath;
    private String astronomyTailKey;
    private CompletableFuture<JSONObject> astronomyTail;
    private final List<PlanetRow> planetRows = new ArrayList<>();
    private boolean observablePlanetsOnly;
    private TextView noObservablePlanets;
    private LocalDate planetWindowDate;
    private double magneticDeclination = Double.NaN;
    private TextView skyTrackDirection;
    private TextView skyTrackCoordinates;
    private java.util.function.LongFunction<SkyTrajectory.Point> trackedCelestialPosition;
    private SkyTrack aimedSkyTrack;
    private final List<VisibilityBadge> visibilityBadges = new ArrayList<>();
    private LiveIssBadge trackedIss;
    private final Runnable updateAstronomyVisibility = this::refreshAstronomyVisibility;
    private boolean phoneOrientationActive;
    private long lastPhoneReadingNanos;
    private final float[] phoneRotation = new float[9];
    private static final int[] MOON_PHASE_NAMES = {R.string.moon_new, R.string.moon_waxing_crescent, R.string.moon_first_quarter,
            R.string.moon_waxing_gibbous, R.string.moon_full, R.string.moon_waning_gibbous,
            R.string.moon_last_quarter, R.string.moon_waning_crescent};
    private static final int ASTRONOMY_PAGE_SIZE = 4;
    private static final int SKY_PATH_HEIGHT_DP = 108;

    private final SensorEventListener phoneOrientation = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (skyTrackDirection == null) return;
            SensorManager.getRotationMatrixFromVector(phoneRotation, event.values);
            // The rear camera points along the negative device Z axis.
            double east = -phoneRotation[2];
            double north = -phoneRotation[5];
            double horizontal = Math.hypot(east, north);
            double altitudeAngle = Math.toDegrees(Math.atan2(-phoneRotation[8], horizontal));
            double heading = Math.toDegrees(Math.atan2(east, north));
            boolean trueNorth = Double.isFinite(magneticDeclination);
            if (trueNorth) heading += magneticDeclination;
            boolean unreliable = event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE;
            if (aimedSkyTrack != null) {
                boolean aligned = trueNorth && !unreliable;
                aimedSkyTrack.setAim(aligned ? heading : Double.NaN, aligned ? altitudeAngle : Double.NaN);
            }
            if (lastPhoneReadingNanos != 0 && event.timestamp - lastPhoneReadingNanos < 100_000_000L) return;
            lastPhoneReadingNanos = event.timestamp;
            long elevation = Math.round(altitudeAngle);
            long azimuth = Math.floorMod(Math.round(heading), 360);
            boolean lowAccuracy = unreliable || event.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW;
            String direction = unreliable || Math.abs(elevation) == 90 ? "—" : azimuth + "° " + compassDirection(azimuth);
            String reading = activity.getString(trueNorth ? R.string.phone_direction_reading : R.string.phone_direction_magnetic,
                    direction, elevation);
            if (lowAccuracy) reading += "\n" + activity.getString(R.string.phone_compass_low_accuracy);
            setPhoneReading(skyTrackDirection, reading);
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    public AstronomyTab(Activity activity, SkyViews views, Host host) {
        this.activity = activity;
        this.views = views;
        this.host = host;
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
        aurora();
        eclipsesAndTransits();
    }

    /** Shows only eclipses and transits, for a notification opened before the forecast has loaded. */
    public void attachEvents(LinearLayout page, ScrollView scroll, double latitude, double longitude, String place) {
        show(page, scroll, null, latitude, longitude, place);
        eclipsesAndTransits();
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
        auroraStatus = null;
        auroraNow = null;
        auroraTimes = null;
        currentKpView = null;
        currentKpBar = null;
        kpTable = null;
        sunCoordinates = null;
        moonCoordinates = null;
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
        startPhoneOrientation();
    }

    public void onPause() {
        resumed = false;
        main.removeCallbacks(updateAstronomyVisibility);
        stopPhoneOrientation();
    }

    /** Minute update of live positions and the aurora outlook. */
    public void tick(Instant now) {
        if (sunCoordinates != null) setSkyCoordinates(sunCoordinates, now, false);
        if (moonCoordinates != null) setSkyCoordinates(moonCoordinates, now, true);
        if (!planetRows.isEmpty()) updatePlanets(now);
        refreshAurora();
        if (kpTable != null && kpTable.isShown()) refreshKp();
        updateAurora(now);
    }

    /** Lets a manual refresh reload the aurora and Kp forecasts immediately. */
    public void resetRefreshThrottles() {
        auroraAttempt = 0;
        kpAttempt = 0;
    }

    /** The eclipse or transit section to scroll to, from a notification; null once the user touches the screen. */
    public void setPendingEventSection(String section) {
        pendingAstronomyEventSection = section;
    }

    public boolean hasPendingEventSection() {
        return pendingAstronomyEventSection != null;
    }

    /** Degrees between magnetic and true north at the phone, for the sky track compass. */
    public void setMagneticDeclination(double degrees) {
        magneticDeclination = degrees;
    }

    /** Time zone for event times while no forecast is loaded. */
    public void setNotificationZone(ZoneId zone) {
        astronomyNotificationZone = zone;
    }

    public ZoneId notificationZone() {
        return astronomyNotificationZone;
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
            reading.setText(skyCoordinatesText(position.azimuth, 90 - position.zenith));
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

    private String skyCoordinatesText(double azimuth, double elevation) {
        return activity.getString(R.string.sky_coordinates, Math.floorMod(Math.round(azimuth), 360),
                compassDirection(azimuth), Math.round(elevation));
    }

    private void sun(JSONObject daily, int index) {
        LinearLayout tile = views.card();
        sunTrackHeading = addSkyTrackHeading(tile, astronomyTitleIcon(R.drawable.ic_sun_astronomy), activity.getString(R.string.sun),
                () -> showDailySkyTrack(activity.getString(R.string.sun), false, null, null));
        long seconds = daylightSeconds(daily, index);
        addSunPathDetails(tile, daily, index, seconds);
        sunCoordinates = skyCoordinates(false);
        tile.addView(sunCoordinates);
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
        moonTrackHeading = addSkyTrackHeading(tile, astronomyTitleIcon(R.drawable.ic_moon_astronomy), activity.getString(R.string.moon),
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
            addCelestialTitleIcon(planetTitle, new PlanetIcon(activity, body));
            TextView name = views.label(activity.getString(names[body.ordinal()]), 16, views.ink);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            planetTitle.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView magnitude = views.badge("");
            LinearLayout.LayoutParams magnitudeSize = new LinearLayout.LayoutParams(-2, -2);
            magnitudeSize.setMarginStart(views.dp(6));
            planetTitle.addView(magnitude, magnitudeSize);
            TextView now = nowActionBadge(entry::performClick);
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

    private void startPhoneOrientation() {
        if (!resumed || skyTrackDirection == null || phoneOrientationActive) return;
        SensorManager manager = activity.getSystemService(SensorManager.class);
        Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (sensor == null) sensor = manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        phoneOrientationActive = sensor != null
                && manager.registerListener(phoneOrientation, sensor, SensorManager.SENSOR_DELAY_GAME, 0);
        if (!phoneOrientationActive) skyTrackDirection.setText(R.string.phone_direction_unavailable);
    }

    private void stopPhoneOrientation() {
        if (phoneOrientationActive) activity.getSystemService(SensorManager.class).unregisterListener(phoneOrientation);
        phoneOrientationActive = false;
        lastPhoneReadingNanos = 0;
    }

    private void setPhoneReading(TextView direction, String reading) {
        if (direction != null && !reading.contentEquals(direction.getText())) direction.setText(reading);
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
                setMagnitudeBadge(row.magnitude, reading.magnitude, magnitudeColor(reading.magnitude));
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
                        compassDirection(reading.azimuth), Math.round(reading.elevation)));
            }
        }
        noObservablePlanets.setVisibility(observablePlanetsOnly && !anyObservable ? View.VISIBLE : View.GONE);
        planetWindowDate = today;
    }

    private String compassDirection(double azimuth) {
        String[] directions = formats().italian()
                ? new String[]{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO"}
                : new String[]{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return directions[(int) Math.round(azimuth / 22.5) % directions.length];
    }

    private void aurora() {
        LinearLayout tile = views.card();
        LinearLayout heading = astronomyTitle(astronomyTitleIcon(R.drawable.ic_aurora_astronomy), activity.getString(R.string.aurora));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinimumHeight(views.dp(32));
        auroraNow = nowActionBadge(() -> host.openMap(RadarActivity.Layer.AURORA));
        auroraNow.setVisibility(View.GONE);
        LinearLayout.LayoutParams badgeSize = new LinearLayout.LayoutParams(-2, -2);
        badgeSize.setMarginStart(views.dp(6));
        heading.addView(auroraNow, badgeSize);
        tile.addView(heading);
        auroraStatus = views.label("", 15, views.ink);
        auroraStatus.setPadding(0, views.dp(6), 0, 0);
        tile.addView(auroraStatus);
        auroraTimes = views.label("", 12, views.muted);
        auroraTimes.setPadding(0, views.dp(4), 0, 0);
        auroraTimes.setSingleLine(true);
        auroraTimes.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(auroraTimes);
        LinearLayout mapRow = views.row();
        mapRow.setGravity(Gravity.CENTER_VERTICAL);
        mapRow.setPadding(0, views.dp(5), 0, 0);
        LinearLayout kpSummary = views.column();
        mapRow.addView(kpSummary, new LinearLayout.LayoutParams(0, -2, 1));
        currentKpView = views.label("", 14, views.accent);
        kpSummary.addView(currentKpView);
        currentKpBar = kpBar();
        LinearLayout.LayoutParams currentBarSize = new LinearLayout.LayoutParams(-1, views.dp(6));
        currentBarSize.topMargin = views.dp(5);
        kpSummary.addView(currentKpBar, currentBarSize);
        Button map = views.mapButton(R.string.aurora_map_short, () -> host.openMap(RadarActivity.Layer.AURORA));
        map.setContentDescription(activity.getString(R.string.aurora_map));
        LinearLayout.LayoutParams mapSize = new LinearLayout.LayoutParams(-2, -2);
        mapSize.setMarginStart(views.dp(8));
        mapRow.addView(map, mapSize);
        tile.addView(mapRow);
        LinearLayout table = views.column();
        kpTable = table;
        table.setVisibility(View.GONE);
        Button toggle = views.accordionButton(activity.getString(R.string.aurora_kp_forecast));
        toggle.setOnClickListener(v -> {
            boolean open = table.getVisibility() != View.VISIBLE;
            table.setVisibility(open ? View.VISIBLE : View.GONE);
            views.updateAccordion(toggle, open);
            if (open) {
                refreshKp();
                renderKp();
            }
        });
        LinearLayout.LayoutParams toggleSize = new LinearLayout.LayoutParams(-1, -2);
        toggleSize.topMargin = views.dp(12);
        toggleSize.bottomMargin = views.dp(8);
        tile.addView(toggle, toggleSize);
        tile.addView(table);
        views.addSpaced(page, tile, 9);
        refreshAurora();
        updateAurora(Instant.now());
    }

    private void refreshKp() {
        long elapsed = SystemClock.elapsedRealtime();
        if (kpLoading || (kpAttempt != 0 && elapsed - kpAttempt < 15 * 60 * 1000L)) return;
        kpAttempt = elapsed;
        kpLoading = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return AuroraForecast.loadKp();
            } catch (Exception error) {
                android.util.Log.w("AuroraForecast", "NOAA Kp forecast unavailable", error);
                return null;
            }
        }).thenAccept(result -> main.post(() -> {
            if (activity.isDestroyed()) return;
            kpLoading = false;
            if (result != null) kpPeriods = result;
            renderKp();
        }));
    }

    private void renderKp() {
        if (kpTable == null || kpTable.getVisibility() != View.VISIBLE) return;
        kpTable.removeAllViews();
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        DateTimeFormatter hours = DateTimeFormatter.ofPattern(formats().clock24 ? "HH:mm" : "h:mm a", formats().appLocale()).withZone(zone);
        DateTimeFormatter dates = formats().shortDate(false).withZone(zone);
        Instant now = Instant.now();
        LocalDate currentDate = null;
        for (AuroraForecast.KpPeriod period : kpPeriods) {
            if (!period.end().isAfter(now)) continue;
            LocalDate date = period.start.atZone(zone).toLocalDate();
            if (!date.equals(currentDate)) {
                TextView dayLabel = views.label(dates.format(period.start), 13, views.muted);
                dayLabel.setPadding(0, views.dp(currentDate == null ? 0 : 12), 0, 0);
                kpTable.addView(dayLabel);
                currentDate = date;
            }
            LinearLayout line = views.row();
            line.setPadding(0, views.dp(4), 0, views.dp(4));
            line.addView(views.label(hours.format(period.start) + "–" + hours.format(period.end()), 14, views.ink));
            View bar = kpBar();
            bar.getBackground().setLevel((int) Math.round(period.kp * 10000 / 9));
            LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(0, views.dp(6), 1);
            barSize.setMarginStart(views.dp(10));
            barSize.setMarginEnd(views.dp(10));
            line.addView(bar, barSize);
            TextView kpReading = views.label("Kp " + formats().number(period.kp, "%.2f") + "/9", 14, views.accent);
            line.addView(kpReading);
            kpTable.addView(line);
        }
        int text = currentDate != null ? R.string.aurora_kp_hint
                : kpLoading ? R.string.aurora_loading : R.string.aurora_unavailable;
        views.addSpaced(kpTable, views.label(activity.getString(text), 12, views.muted), 8);
    }

    private View kpBar() {
        View bar = new View(activity);
        android.graphics.drawable.ClipDrawable fill = new android.graphics.drawable.ClipDrawable(
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{SignalColors.GREEN, SignalColors.YELLOW, SignalColors.RED}),
                Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        bar.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                views.background(activity.getColor(R.color.sky_selected), 3), fill}));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return bar;
    }

    private void refreshAurora() {
        long elapsed = SystemClock.elapsedRealtime();
        if (auroraStatus == null || auroraLoading
                || (auroraAttempt != 0 && elapsed - auroraAttempt < 5 * 60 * 1000L)) return;
        auroraAttempt = elapsed;
        auroraLoading = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return AuroraForecast.loadCurrentKp();
            } catch (Exception error) {
                android.util.Log.w("AuroraForecast", "NOAA current Kp unavailable", error);
                return null;
            }
        }).thenAccept(result -> main.post(() -> {
            if (activity.isDestroyed()) return;
            if (result != null) currentKp = result;
            updateCurrentKp(Instant.now());
        }));
        CompletableFuture.supplyAsync(() -> {
            try {
                return AuroraForecast.load();
            } catch (Exception error) {
                android.util.Log.w("AuroraForecast", "NOAA forecast unavailable", error);
                return null;
            }
        }).thenAccept(result -> main.post(() -> {
            if (activity.isDestroyed()) return;
            auroraLoading = false;
            if (result != null) auroraForecast = result;
            updateAurora(Instant.now());
        }));
    }

    private void updateCurrentKp(Instant now) {
        if (currentKpView == null) return;
        if (currentKp == null || now.isAfter(currentKp.time.plusSeconds(1800))) {
            currentKpBar.setVisibility(View.GONE);
            currentKpView.setText(R.string.aurora_kp_current_unavailable);
            return;
        }
        currentKpBar.setVisibility(View.VISIBLE);
        currentKpBar.getBackground().setLevel((int) Math.round(currentKp.kp * 10000 / 9));
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        currentKpView.setText(activity.getString(R.string.aurora_kp_current, formats().number(currentKp.kp, "%.2f"),
                Formats.formatTime(currentKp.time.atZone(zone).toLocalTime(), formats().clockFormat())));
    }

    private void updateAurora(Instant now) {
        if (auroraStatus == null) return;
        updateCurrentKp(now);
        boolean night = SkyPosition.at(now, latitude, longitude, false).zenith > 90;
        auroraStatus.setVisibility(night ? View.VISIBLE : View.GONE);
        auroraNow.setVisibility(View.GONE);
        if (auroraForecast == null || !auroraForecast.isCurrentAt(now)) {
            auroraStatus.setText(auroraLoading ? R.string.aurora_loading : R.string.aurora_unavailable);
            auroraTimes.setVisibility(View.GONE);
            return;
        }
        Instant forecastTime = auroraForecast.forecastTime;
        Instant viewingTime = forecastTime.isBefore(now) ? now : forecastTime;
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        LocalDate today = now.atZone(zone).toLocalDate();
        auroraTimes.setVisibility(View.VISIBLE);
        auroraTimes.setText(activity.getString(R.string.aurora_times,
                auroraTimestamp(auroraForecast.observationTime.atZone(zone), today),
                auroraTimestamp(forecastTime.atZone(zone), today)));
        if (!night) return;
        boolean covered = auroraForecast.coversOnMap(latitude, longitude);
        auroraNow.setVisibility(covered ? View.VISIBLE : View.GONE);
        double direction = auroraForecast.estimatedDirection(latitude, longitude);
        if (!covered && !Double.isFinite(direction)) {
            auroraStatus.setText(R.string.aurora_unlikely);
        } else if (SkyPosition.at(viewingTime, latitude, longitude, false).zenith < 96) {
            auroraStatus.setText(R.string.aurora_too_light);
        } else if (auroraCloudCover(viewingTime, zone) >= 80) {
            auroraStatus.setText(R.string.aurora_cloudy);
        } else if (covered || direction < 0) {
            auroraStatus.setText(R.string.aurora_possible);
        } else {
            auroraStatus.setText(activity.getString(R.string.aurora_direction, compassDirection(direction)));
        }
    }

    private String auroraTimestamp(ZonedDateTime timestamp, LocalDate today) {
        String time = timestamp.format(formats().clockFormat());
        return timestamp.toLocalDate().equals(today) ? time
                : timestamp.format(DateTimeFormatter.ofPattern(formats().italian() ? "d/M" : "M/d", formats().appLocale())) + " " + time;
    }

    private double auroraCloudCover(Instant time, ZoneId zone) {
        JSONObject hourly = data.optJSONObject("hourly");
        String hour = time.atZone(zone).format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH", Locale.ROOT));
        for (int i = 0; i < length(hourly); i++) {
            if (at(hourly, "time", i).startsWith(hour)) return value(hourly, "cloud_cover", i);
        }
        return Double.NaN;
    }

    private void issPasses() {
        LinearLayout tile = views.card();
        tile.addView(astronomyTitle(astronomyTitleIcon(R.drawable.ic_iss_astronomy), activity.getString(R.string.iss_passes)));
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
        }).thenAccept(passes -> main.post(() -> {
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
            setMagnitudeBadge(magnitude, pass.magnitude, magnitudeColor(pass.magnitude));
            LinearLayout.LayoutParams magnitudeSize = new LinearLayout.LayoutParams(-2, -2);
            magnitudeSize.setMarginStart(views.dp(6));
            heading.addView(magnitude, magnitudeSize);
            TextView live = nowActionBadge(entry::performClick);
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
            String appears = issBearing(pass.rise.azimuth);
            String culminates = issBearing(pass.peak.azimuth);
            String disappears = issBearing(pass.set.azimuth);
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

    private static class VisibilityBadge {
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

    private static final class LiveIssBadge extends VisibilityBadge {
        final IssPasses.Pass pass;

        LiveIssBadge(IssPasses.Pass pass, TextView badge) {
            super(pass.rise.time, pass.set.time, badge, null);
            this.pass = pass;
        }
    }

    private TextView issVisibilityBadge() {
        TextView badge = views.observingBadge(activity.getString(R.string.astronomy_visible_now));
        badge.setVisibility(View.GONE);
        return badge;
    }

    private void refreshAstronomyVisibility() {
        main.removeCallbacks(updateAstronomyVisibility);
        if (!resumed) return;
        long millis = System.currentTimeMillis();
        long second = Math.floorDiv(millis, 1000L);
        long delay = Long.MAX_VALUE;
        for (VisibilityBadge live : visibilityBadges) delay = Math.min(delay, live.update(millis, second));
        if (trackedIss != null && aimedSkyTrack != null) {
            delay = Math.min(delay, trackedIss.update(millis, second));
            SkyTrajectory.Point position = trackedIss.pass.positionAt(second);
            updateSkyTrackPosition(position);
            if (position != null) delay = Math.min(delay, 1000L - Math.floorMod(millis, 1000L));
        } else if (trackedCelestialPosition != null && aimedSkyTrack != null) {
            updateSkyTrackPosition(trackedCelestialPosition.apply(second));
            delay = Math.min(delay, 60_000L - Math.floorMod(millis, 60_000L));
        }
        if (delay != Long.MAX_VALUE) main.postDelayed(updateAstronomyVisibility, delay);
    }

    private void updateSkyTrackPosition(SkyTrajectory.Point position) {
        boolean above = position != null && position.elevation >= 0;
        aimedSkyTrack.setCurrentPosition(above ? position : null);
        skyTrackCoordinates.setVisibility(above ? View.VISIBLE : View.GONE);
        if (above) {
            skyTrackCoordinates.setText(activity.getString(R.string.sky_track_now) + " · "
                    + skyCoordinatesText(position.azimuth, position.elevation));
        }
    }

    private void addCelestialTitleIcon(LinearLayout heading, View icon) {
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(views.dp(23), views.dp(23));
        iconSize.setMarginEnd(views.dp(7));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(icon, iconSize);
    }

    private View astronomyTitleIcon(int drawable) {
        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageResource(drawable);
        return icon;
    }

    private LinearLayout astronomyTitle(View icon, String name) {
        LinearLayout heading = views.row();
        addCelestialTitleIcon(heading, icon);
        heading.addView(views.label(name, 16, views.ink), new LinearLayout.LayoutParams(0, -2, 1));
        return heading;
    }

    private LinearLayout addSkyTrackHeading(LinearLayout panel, View icon, String name, Runnable open) {
        LinearLayout heading = astronomyTitle(icon, name);
        heading.setMinimumHeight(views.dp(32));
        TextView now = nowActionBadge(open);
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

    private TextView nowActionBadge(Runnable open) {
        TextView badge = views.observingBadge(activity.getString(R.string.sky_track_now) + "  ›");
        badge.setContentDescription(activity.getString(R.string.astronomy_open_now_details));
        views.cardAction(badge, open);
        return badge;
    }

    private void setMagnitudeBadge(TextView badge, double magnitude, int fill) {
        String value = formats().number(magnitude, "%+.1f");
        badge.setText(activity.getString(R.string.astronomy_magnitude, value));
        badge.setContentDescription(activity.getString(R.string.astronomy_magnitude_description, value));
        badge.setBackground(views.background(SignalColors.tint(fill), 8));
    }

    public void showIssPass(IssPasses.Pass pass, ZoneId zone, String period, String url) {
        LocalDate date = Instant.ofEpochSecond(pass.rise.time).atZone(zone).toLocalDate();
        showSkyTrack(activity.getString(R.string.iss_track), period, pass.magnitude, magnitudeColor(pass.magnitude), panel -> {
            TextView live = issVisibilityBadge();
            LinearLayout.LayoutParams liveSize = new LinearLayout.LayoutParams(-2, -2);
            liveSize.topMargin = views.dp(6);
            panel.addView(live, liveSize);
            trackedIss = new LiveIssBadge(pass, live);
            addSkyTrack(panel, pass.track, pass.rise, pass.peak, pass.set, null, pass.brightest);
            addSkyTrackPoint(panel, R.string.iss_track_start, pass.rise, SkyTrack.START_COLOR, zone, date);
            addSkyTrackPoint(panel, R.string.iss_track_peak, pass.peak, SkyTrack.MAX_COLOR, zone, date);
            addSkyTrackPoint(panel, R.string.iss_track_brightest, pass.brightest, SkyTrack.BRIGHTEST_COLOR, zone, date);
            addSkyTrackPoint(panel, R.string.iss_track_end, pass.set, SkyTrack.END_COLOR, zone, date);

            if (url != null) {
                Button link = views.iconActionButton(R.string.iss_open_heavens_above, R.drawable.ic_open_external,
                        () -> openAstronomyPage(url));
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
        if (track.continuous && track.rise != null && track.set != null && track.rise.time <= track.set.time) {
            period += " · " + skyTrackTime(track.rise, zone, date) + " – " + skyTrackTime(track.set, zone, date);
        }
        int fill = planet == null ? SignalColors.PURPLE : magnitudeColor(magnitude);
        showSkyTrack(activity.getString(R.string.daily_sky_track, name), period, magnitude, fill, panel -> {
            trackedCelestialPosition = second -> {
                if (second > trackEnd) return null;
                if (planet != null) return SkyTrajectory.positionAt(second, lat, lon, false, planet);
                Instant instant = Instant.ofEpochSecond(second);
                SkyPosition position = SkyPosition.at(instant, lat, lon, moon);
                return (moon ? moonVisible(instant, position) : sunVisible(instant, position))
                        ? new SkyTrajectory.Point(second, position.azimuth, Math.max(0, 90 - position.zenith))
                        : null;
            };
            if (observingWindows != null) {
                views.addSpaced(panel, views.label(observingWindows.toString(), 13, views.accent), 6);
            }
            addSkyTrack(panel, track.points, track.rise, track.peak, track.set, track.now, null);
            if (track.peak == null) {
                int message = nightOnly ? R.string.sky_track_no_night_path : R.string.sky_track_no_path;
                panel.addView(views.label(activity.getString(message), 14, views.muted));
            }
            addSkyTrackPoint(panel, nightOnly ? R.string.iss_track_start : R.string.sky_track_rise,
                    track.rise, SkyTrack.START_COLOR, zone, date);
            addSkyTrackPoint(panel, R.string.iss_track_peak, track.peak, SkyTrack.MAX_COLOR, zone, date);
            addSkyTrackPoint(panel, nightOnly ? R.string.iss_track_end : R.string.sky_track_set,
                    track.set, SkyTrack.END_COLOR, zone, date);
        });
        refreshAstronomyVisibility();
    }

    private void showSkyTrack(String title, String period, double magnitude, int magnitudeFill,
                              java.util.function.Consumer<LinearLayout> content) {
        Dialog dialog = new Dialog(activity, R.style.SkyHereTheme);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout screen = views.column();
        screen.setBackgroundColor(views.surfaceColor);
        screen.addView(views.detailHeader(title, R.string.sky_track_back, dialog::dismiss));

        ScrollView viewport = new ScrollView(activity);
        viewport.setFillViewport(true);
        LinearLayout body = views.column();
        body.setPadding(views.dp(12), views.dp(4), views.dp(12), views.dp(12));
        LinearLayout panel = views.column();
        panel.setBackground(views.background(activity.getColor(R.color.sky_card), 16));
        panel.setPadding(views.dp(14), views.dp(12), views.dp(14), views.dp(14));
        LinearLayout summary = views.row();
        summary.setGravity(Gravity.TOP);
        summary.addView(views.label(period, 14, views.muted), new LinearLayout.LayoutParams(0, -2, 1));
        if (Double.isFinite(magnitude)) {
            TextView badge = views.badge("");
            setMagnitudeBadge(badge, magnitude, magnitudeFill);
            LinearLayout.LayoutParams badgeSize = new LinearLayout.LayoutParams(-2, -2);
            badgeSize.setMarginStart(views.dp(8));
            summary.addView(badge, badgeSize);
        }
        panel.addView(summary);
        content.accept(panel);
        body.addView(panel);
        viewport.addView(body);
        screen.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(screen);
        dialog.setOnDismissListener(ignored -> {
            aimedSkyTrack = null;
            skyTrackCoordinates = null;
            trackedCelestialPosition = null;
            trackedIss = null;
            refreshAstronomyVisibility();
            skyTrackDirection = null;
            stopPhoneOrientation();
        });

        android.view.Window window = dialog.getWindow();
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        dialog.show();
        window.setLayout(-1, -1);
        SkyTheme.applySystemBars(window, activity, views.surfaceColor);
        startPhoneOrientation();
    }

    private void addSkyTrack(LinearLayout panel, List<? extends SkyTrajectory.Point> points, SkyTrajectory.Point start,
                             SkyTrajectory.Point peak, SkyTrajectory.Point end, SkyTrajectory.Point now,
                             SkyTrajectory.Point brightest) {
        String[] cardinal = {compassDirection(0), compassDirection(90), compassDirection(180), compassDirection(270)};
        skyTrackCoordinates = views.label("", 15, activity.getColor(R.color.sky_observing));
        skyTrackCoordinates.setPadding(0, views.dp(7), 0, views.dp(3));
        skyTrackCoordinates.setVisibility(View.GONE);
        panel.addView(skyTrackCoordinates);
        aimedSkyTrack = new SkyTrack(activity, points, start, peak, end, now, brightest, cardinal);
        int chartSize = Math.min(380, activity.getResources().getConfiguration().screenWidthDp - 52);
        panel.addView(aimedSkyTrack, new LinearLayout.LayoutParams(-1, views.dp(chartSize)));
        LinearLayout direction = views.column();
        direction.setBackground(views.background(activity.getColor(R.color.sky_subtle), 12));
        direction.setPadding(views.dp(11), views.dp(9), views.dp(11), views.dp(9));
        direction.addView(views.label(activity.getString(R.string.phone_direction), 14, views.ink));
        skyTrackDirection = views.label(activity.getString(R.string.phone_direction_waiting), 17, views.ink);
        views.addSpaced(direction, skyTrackDirection, 5);
        views.addSpaced(direction, views.label(activity.getString(R.string.phone_direction_hint), 12, views.muted), 5);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = views.dp(9);
        panel.addView(direction, size);
        addSkyTrackLegendItem(panel, activity.getString(R.string.sky_track_now), SkyTrack.NOW_COLOR, 10);
    }

    private void addSkyTrackPoint(LinearLayout panel, int title, SkyTrajectory.Point point, int color,
                                  ZoneId zone, LocalDate date) {
        if (point == null) return;
        String time = skyTrackTime(point, zone, date);
        String position = point.elevation >= 0 ? issBearing(point.azimuth) + " · " + Math.round(point.elevation) + "°"
                : activity.getString(R.string.planet_below_horizon);
        String details = activity.getString(title) + " · " + time + " · " + position;
        addSkyTrackLegendItem(panel, details, color, 8);
    }

    private void addSkyTrackLegendItem(LinearLayout panel, String details, int color, int diameter) {
        LinearLayout item = views.row();
        View marker = new View(activity);
        marker.setBackground(views.background(color, diameter / 2));
        LinearLayout.LayoutParams markerSize = new LinearLayout.LayoutParams(views.dp(diameter), views.dp(diameter));
        markerSize.setMarginEnd(views.dp(8));
        item.addView(marker, markerSize);
        item.addView(views.label(details, 14, views.ink), new LinearLayout.LayoutParams(0, -2, 1));
        views.addSpaced(panel, item, 6);
    }

    private String skyTrackTime(SkyTrajectory.Point point, ZoneId zone, LocalDate date) {
        ZonedDateTime localTime = Instant.ofEpochSecond(point.time).atZone(zone);
        String time = localTime.format(formats().clockFormat());
        return localTime.toLocalDate().equals(date) ? time : localTime.format(formats().shortDate(false)) + " " + time;
    }

    private String issBearing(double azimuth) {
        return compassDirection(azimuth) + " " + (Math.round(azimuth) % 360) + "°";
    }

    private static int magnitudeColor(double magnitude) {
        if (magnitude <= -4) return SignalColors.RED;
        if (magnitude <= -3) return SignalColors.ORANGE;
        if (magnitude <= -2) return SignalColors.YELLOW;
        if (magnitude <= -1) return SignalColors.BLUE;
        return SignalColors.GRAY;
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

    private ZoneId astronomyEventZone() {
        if (data != null) return ZoneId.of(data.optString("timezone", "UTC"));
        return astronomyNotificationZone == null ? ZoneId.systemDefault() : astronomyNotificationZone;
    }

    private void eclipsesAndTransits() {
        double lat = latitude;
        double lon = longitude;
        ZoneId zone = astronomyEventZone();
        boolean openFromNotification = pendingAstronomyEventSection != null;
        boolean openTransits = "TRANSIT".equals(pendingAstronomyEventSection);
        boolean openLunar = "LUNAR".equals(pendingAstronomyEventSection);
        solarEclipsesExpanded |= "SOLAR".equals(pendingAstronomyEventSection);
        lunarEclipsesExpanded |= openLunar;
        transitsExpanded |= openTransits;
        LinearLayout solarEclipses = astronomyAccordion(R.string.solar_eclipses, solarEclipsesExpanded,
                expanded -> solarEclipsesExpanded = expanded,
                content -> {
                    content.setPadding(0, views.dp(10), 0, 0);
                    new EclipseList(content, false, lat, lon, zone).showNext();
                });
        LinearLayout lunarEclipses = astronomyAccordion(R.string.lunar_eclipses, lunarEclipsesExpanded,
                expanded -> lunarEclipsesExpanded = expanded,
                content -> {
                    content.setPadding(0, views.dp(10), 0, 0);
                    new EclipseList(content, true, lat, lon, zone).showNext();
                });
        views.addSpaced(page, solarEclipses, 12);
        views.addSpaced(page, lunarEclipses, 9);
        LinearLayout transitsCard = astronomyAccordion(R.string.planetary_transits, transitsExpanded,
                expanded -> transitsExpanded = expanded, content -> {
            content.setPadding(0, views.dp(10), 0, 0);
            List<TransitCatalog.Event> transits = TransitCatalog.upcoming(lat, lon, Instant.now());
            if (transits.isEmpty()) content.addView(views.label(activity.getString(R.string.no_transits), 14, views.muted));
            else appendTransits(content, transits, zone, 0);
        });
        views.addSpaced(page, transitsCard, 9);
        if (openFromNotification) {
            View section = openTransits ? transitsCard : openLunar ? lunarEclipses : solarEclipses;
            ScrollView target = scroll;
            LinearLayout eventPage = page;
            Runnable reveal = () -> {
                if (pendingAstronomyEventSection == null || !section.isAttachedToWindow()) return;
                android.graphics.Rect bounds = new android.graphics.Rect();
                section.getDrawingRect(bounds);
                eventPage.offsetDescendantRectToMyCoords(section, bounds);
                target.scrollTo(0, bounds.top);
            };
            section.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    target.post(reveal));
            target.post(reveal);
        }
    }

    private LinearLayout astronomyAccordion(int title, boolean expanded,
            java.util.function.Consumer<Boolean> saveExpanded, java.util.function.Consumer<LinearLayout> populate) {
        LinearLayout card = views.card();
        LinearLayout content = views.column();
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        Button toggle = views.accordionButton(activity.getString(title));
        views.updateAccordion(toggle, expanded);
        toggle.setOnClickListener(v -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            saveExpanded.accept(open);
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            views.updateAccordion(toggle, open);
            if (open && content.getChildCount() == 0) populate.accept(content);
        });
        card.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        card.addView(content);
        if (expanded) populate.accept(content);
        return card;
    }

    private void astronomicalSeasons() {
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        LinearLayout seasons = views.card();
        seasons.addView(astronomyTitle(astronomyTitleIcon(R.drawable.ic_seasons_astronomy),
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

    private void appendTransits(LinearLayout list, List<TransitCatalog.Event> items, ZoneId zone, int start) {
        int end = Math.min(items.size(), start + ASTRONOMY_PAGE_SIZE);
        for (int i = start; i < end; i++) addTransit(list, items.get(i), zone);
        refreshAstronomyVisibility();
        if (end >= items.size()) return;
        Button more = views.moreButton();
        more.setContentDescription(activity.getString(R.string.more_transits));
        more.setOnClickListener(v -> {
            list.removeView(more);
            appendTransits(list, items, zone, end);
        });
        list.addView(more);
    }

    private final class EclipseList {
        private final LinearLayout events;
        private final LinearLayout footer;
        private final List<EclipseCatalog.Event> items = new ArrayList<>();
        private final boolean lunar;
        private final double latitude;
        private final double longitude;
        private final ZoneId zone;
        private int year;
        private int shown;
        private int goal;

        EclipseList(LinearLayout parent, boolean lunar, double latitude, double longitude, ZoneId zone) {
            this.lunar = lunar;
            this.latitude = latitude;
            this.longitude = longitude;
            this.zone = zone;
            year = Instant.now().atZone(zone).getYear();
            events = views.column();
            footer = views.column();
            parent.addView(events);
            parent.addView(footer);
        }

        void showNext() {
            goal = shown + ASTRONOMY_PAGE_SIZE;
            fill(2);
        }

        private void fill(int requestsLeft) {
            footer.removeAllViews();
            int available = Math.min(goal, items.size());
            while (shown < available) addEclipse(events, items.get(shown++), zone);
            refreshAstronomyVisibility();
            int catalogLastYear = lunar ? EclipseCatalog.LAST_OFFLINE_LUNAR_YEAR : EclipseCatalog.LAST_OFFLINE_SOLAR_YEAR;
            boolean hasMoreYears = year <= catalogLastYear;
            if (shown < goal && requestsLeft > 0 && hasMoreYears) {
                fetch(requestsLeft);
                return;
            }
            if (shown == 0) {
                int lastYear = Math.min(year - 1, catalogLastYear);
                footer.addView(views.label(activity.getString(R.string.no_eclipses_until, lastYear), 14, views.muted));
            }
            if (!hasMoreYears && shown >= items.size()) return;
            Button more = views.moreButton();
            more.setOnClickListener(v -> showNext());
            more.setContentDescription(lunar ? activity.getString(R.string.more_lunar_eclipses) : activity.getString(R.string.more_solar_eclipses));
            footer.addView(more);
        }

        private void fetch(int requestsLeft) {
            footer.addView(views.loadingStatus(activity.getString(R.string.loading_eclipses)));
            int startYear = year;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return EclipseCatalog.upcoming(activity.getApplicationContext(), lunar, startYear, latitude, longitude, Instant.now());
                } catch (Exception error) {
                    android.util.Log.w("SkyHere", "Eclipse catalog unavailable: " + error, error);
                    return null;
                }
            }).thenAccept(results -> activity.runOnUiThread(() -> {
                if (activity.isDestroyed() || !footer.isAttachedToWindow()) return;
                if (results == null) {
                    footer.removeAllViews();
                    footer.addView(views.label(activity.getString(R.string.eclipse_data_unavailable), 14, views.muted));
                    footer.addView(views.button(activity.getString(R.string.retry), () -> fill(requestsLeft)));
                    return;
                }
                year += EclipseCatalog.yearsPerRequest(lunar);
                items.addAll(results);
                fill(requestsLeft - 1);
            }));
        }
    }

    private void addEclipse(LinearLayout parent, EclipseCatalog.Event event, ZoneId zone) {
        LinearLayout entry = views.column();
        entry.setPadding(views.dp(11), views.dp(10), views.dp(11), views.dp(10));
        entry.setBackground(views.background(activity.getColor(R.color.sky_subtle), 12));
        String eventUrl = event.detailsUrl(latitude, longitude);
        ArrayList<String> localDetails = new ArrayList<>();
        LinearLayout heading = views.row();
        EclipseIcon icon = new EclipseIcon(activity, event);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(views.dp(36), views.dp(36));
        iconSize.rightMargin = views.dp(9);
        heading.addView(icon, iconSize);
        LinearLayout title = views.column();
        title.addView(views.label(EclipseCatalog.typeLabel(activity, event.type), 15, views.ink));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = nowActionBadge(entry::performClick);
        live.setVisibility(View.GONE);
        LinearLayout.LayoutParams liveSize = new LinearLayout.LayoutParams(-2, -2);
        liveSize.setMarginStart(views.dp(6));
        heading.addView(live, liveSize);
        View arrow = views.chevron();
        heading.addView(arrow);
        visibilityBadges.add(new VisibilityBadge(event.start.getEpochSecond(), event.end.getEpochSecond(), live, arrow));
        entry.addView(heading);
        ZonedDateTime start = event.start.atZone(zone);
        ZonedDateTime end = event.end.atZone(zone);
        DateTimeFormatter date = formats().shortDate(true);
        TextView when = views.label(start.format(date), 13, views.muted);
        localDetails.add(EclipseCatalog.typeLabel(activity, event.type) + " · " + when.getText());
        when.setPadding(0, views.dp(3), 0, 0);
        title.addView(when);
        String range = start.format(formats().clockFormat()) + " – " + end.format(formats().clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += activity.getString(R.string.next_day_suffix);
        TextView visible = views.label(activity.getString(R.string.visible_range, range), 14, views.ink);
        localDetails.add(visible.getText().toString());
        visible.setPadding(0, views.dp(7), 0, 0);
        entry.addView(visible);
        Duration duration = Duration.between(event.start, event.end);
        long minutes = Math.max(1, Math.round(duration.getSeconds() / 60.0));
        String durationText = formats().durationText(minutes);
        String durationLabel = activity.getString(event.horizonLimited ? R.string.estimated_duration : R.string.duration_value, durationText);
        durationLabel += " · " + activity.getString(event.estimatedPeak ? R.string.best_visible : R.string.peak_time,
                event.maximum.atZone(zone).format(formats().clockFormat()));
        TextView details = views.label(durationLabel, 13, views.muted);
        localDetails.add(durationLabel);
        details.setPadding(0, views.dp(3), 0, 0);
        entry.addView(details);
        String centralDuration = EclipseCatalog.centralPhaseDuration(activity, event);
        if (centralDuration != null) {
            TextView centralLabel = views.label(centralDuration, 13, views.accent);
            localDetails.add(centralDuration);
            centralLabel.setPadding(0, views.dp(3), 0, 0);
            entry.addView(centralLabel);
        }
        if (Double.isFinite(event.obscuration)) {
            int disk = event.lunar ? (event.penumbral ? R.string.moon_penumbra : R.string.moon_shadow) : R.string.sun_covered;
            String coverageText = activity.getString(disk, event.formattedObscuration(formats().appLocale()));
            if (event.estimatedPeak) coverageText = activity.getString(R.string.approximate_value, coverageText);
            TextView coverage = views.label(coverageText, 13, views.accent);
            localDetails.add(coverageText);
            coverage.setPadding(0, views.dp(3), 0, 0);
            entry.addView(coverage);
        }
        views.cardAction(entry, () -> openEclipseMap(event, eventUrl, localDetails));
        views.addSpaced(parent, entry, 7);
    }

    private void openEclipseMap(EclipseCatalog.Event event, String url, ArrayList<String> localDetails) {
        try {
            activity.startActivity(visibilityMapIntent(event.catalogDate(), url, localDetails)
                    .putExtra("visibilityKind", event.lunar ? "lunar" : "solar")
                    .putExtra("visibilitySamples", event.lunarVisibilitySamples().toString()));
        } catch (org.json.JSONException error) {
            openAstronomyPage(url);
        }
    }

    private android.content.Intent visibilityMapIntent(LocalDate date, String url, ArrayList<String> localDetails) {
        return new android.content.Intent(activity, RadarActivity.class)
                .putExtra("layer", RadarActivity.Layer.ECLIPSE.name())
                .putExtra("place", place).putExtra("lat", latitude).putExtra("lon", longitude)
                .putExtra("timeZone", astronomyEventZone().getId())
                .putExtra("clock24", formats().clock24)
                .putExtra("eventDate", date.toString()).putExtra("detailsUrl", url)
                .putStringArrayListExtra("localDetails", localDetails);
    }

    private void addTransit(LinearLayout parent, TransitCatalog.Event event, ZoneId zone) {
        LinearLayout entry = views.column();
        entry.setPadding(views.dp(11), views.dp(10), views.dp(11), views.dp(10));
        entry.setBackground(views.background(activity.getColor(R.color.sky_subtle), 12));
        String eventUrl = event.detailsUrl(latitude, longitude);
        String transitName = activity.getString(R.string.transit_of,
                activity.getString(event.transit.planet.equals("Mercury") ? R.string.mercury : R.string.venus));
        TransitCatalog.DiskPosition position = event.transit.diskPosition(event.visibleStart, event.visibleEnd);

        LinearLayout header = views.row();
        View icon = new View(activity) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float x = getWidth() / 2f;
                float y = getHeight() / 2f;
                float radius = Math.min(x, y) * .73f;
                paint.setColor(0xffffeaca);
                canvas.drawCircle(x, y, radius * 1.25f, paint);
                paint.setColor(0xffe9a946);
                canvas.drawCircle(x, y, radius, paint);
                paint.setColor(0xff293a58);
                canvas.drawCircle(x + radius * (float) position.x, y + radius * (float) position.y,
                        radius * (event.transit.planet.equals("Mercury") ? .10f : .18f), paint);
            }
        };
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(views.dp(36), views.dp(36));
        iconSize.rightMargin = views.dp(9);
        header.addView(icon, iconSize);
        LinearLayout title = views.column();
        title.addView(views.label(transitName, 15, views.ink));
        ZonedDateTime date = event.visibleStart.atZone(zone);
        TextView when = views.label(date.format(formats().shortDate(true)), 13, views.muted);
        when.setPadding(0, views.dp(3), 0, 0);
        title.addView(when);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = nowActionBadge(entry::performClick);
        live.setVisibility(View.GONE);
        LinearLayout.LayoutParams liveSize = new LinearLayout.LayoutParams(-2, -2);
        liveSize.setMarginStart(views.dp(6));
        header.addView(live, liveSize);
        View arrow = views.chevron();
        header.addView(arrow);
        visibilityBadges.add(new VisibilityBadge(event.visibleStart.getEpochSecond(),
                event.visibleEnd.getEpochSecond(), live, arrow));
        entry.addView(header);

        ZonedDateTime start = event.visibleStart.atZone(zone);
        ZonedDateTime end = event.visibleEnd.atZone(zone);
        String range = start.format(formats().clockFormat()) + " – " + end.format(formats().clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += activity.getString(R.string.next_day_suffix);
        TextView visible = views.label(activity.getString(R.string.estimated_visible_range, range), 14, views.ink);
        visible.setPadding(0, views.dp(7), 0, 0);
        entry.addView(visible);
        long minutes = Math.max(1, Math.round(Duration.between(event.visibleStart, event.visibleEnd).getSeconds() / 60.0));
        String duration = formats().durationText(minutes);
        TextView details = views.label(activity.getString(R.string.estimated_duration, duration), 13, views.muted);
        details.setPadding(0, views.dp(3), 0, 0);
        entry.addView(details);
        ArrayList<String> localDetails = new ArrayList<>();
        localDetails.add(transitName + " · " + when.getText());
        localDetails.add(visible.getText().toString());
        localDetails.add(details.getText().toString());
        views.cardAction(entry, () -> {
            try {
                activity.startActivity(visibilityMapIntent(event.catalogDate(), eventUrl, localDetails)
                        .putExtra("visibilityKind", "transit").putExtra("transitName", transitName)
                        .putExtra("catalogUrl", event.catalogUrl())
                        .putExtra("visibilitySamples", event.visibilitySamples().toString()));
            } catch (org.json.JSONException error) {
                openAstronomyPage(eventUrl);
            }
        });
        views.addSpaced(parent, entry, 7);
    }

    private void openAstronomyPage(String url) {
        try {
            activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(activity, activity.getString(R.string.no_browser), Toast.LENGTH_SHORT).show();
        }
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
            });
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
