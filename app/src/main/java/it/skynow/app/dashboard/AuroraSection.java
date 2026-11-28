package it.skynow.app.dashboard;

import it.skynow.app.net.Background;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import it.skynow.app.R;
import it.skynow.app.astronomy.AuroraForecast;
import it.skynow.app.astronomy.SkyPosition;
import it.skynow.app.maps.RadarActivity;
import it.skynow.app.ui.Formats;
import it.skynow.app.ui.SignalColors;
import it.skynow.app.ui.SkyViews;
import it.skynow.app.weather.ForecastSeries;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** The aurora card of the Astronomy tab: NOAA's visibility outlook, current Kp and the Kp forecast. */
final class AuroraSection {
    private final Activity activity;
    private final SkyViews views;
    private final AstronomyTab.Host host;
    private final AstronomyWidgets widgets;
    private final Handler main = new Handler(Looper.getMainLooper());

    // The forecast the section was last attached with.
    private LinearLayout page;
    private JSONObject data;
    private double latitude;
    private double longitude;

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

    AuroraSection(Activity activity, SkyViews views, AstronomyTab.Host host, AstronomyWidgets widgets) {
        this.activity = activity;
        this.views = views;
        this.host = host;
        this.widgets = widgets;
    }

    private Formats formats() {
        return host.formats();
    }

    private static int length(JSONObject series) { return ForecastSeries.length(series); }
    private static String at(JSONObject series, String key, int index) { return ForecastSeries.text(series, key, index); }
    private static double value(JSONObject series, String key, int index) { return ForecastSeries.value(series, key, index); }

    /** Adds the aurora card to the end of {@code page} and starts loading NOAA data. */
    void attach(LinearLayout page, JSONObject data, double latitude, double longitude) {
        this.page = page;
        this.data = data;
        this.latitude = latitude;
        this.longitude = longitude;
        aurora();
    }

    void detach() {
        auroraStatus = null;
        auroraNow = null;
        auroraTimes = null;
        currentKpView = null;
        currentKpBar = null;
        kpTable = null;
    }

    /** Minute update; reloads NOAA data when due. Does nothing while detached. */
    void tick(Instant now) {
        refreshAurora();
        if (kpTable != null && kpTable.isShown()) refreshKp();
        updateAurora(now);
    }

    void resetRefreshThrottles() {
        auroraAttempt = 0;
        kpAttempt = 0;
    }

    private void aurora() {
        LinearLayout tile = views.card();
        LinearLayout heading = widgets.astronomyTitle(widgets.astronomyTitleIcon(R.drawable.ic_aurora_astronomy), activity.getString(R.string.aurora));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinimumHeight(views.dp(32));
        auroraNow = widgets.nowActionBadge(() -> host.openMap(RadarActivity.Layer.AURORA));
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
        }, Background.IO).thenAccept(result -> main.post(() -> {
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
            // The interval in progress is shown above as the current Kp.
            if (!period.start.isAfter(now)) continue;
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
                return AuroraForecast.loadCurrentKp(Instant.now());
            } catch (Exception error) {
                android.util.Log.w("AuroraForecast", "NOAA current Kp unavailable", error);
                return null;
            }
        }, Background.IO).thenAccept(result -> main.post(() -> {
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
        }, Background.IO).thenAccept(result -> main.post(() -> {
            if (activity.isDestroyed()) return;
            auroraLoading = false;
            if (result != null) auroraForecast = result;
            updateAurora(Instant.now());
        }));
    }

    private void updateCurrentKp(Instant now) {
        if (currentKpView == null) return;
        // The reading belongs to one interval; the minute refresh replaces it soon after the next begins.
        if (currentKp == null || now.isAfter(currentKp.end().plusSeconds(600))) {
            currentKpBar.setVisibility(View.GONE);
            currentKpView.setText(R.string.aurora_kp_current_unavailable);
            return;
        }
        currentKpBar.setVisibility(View.VISIBLE);
        currentKpBar.getBackground().setLevel((int) Math.round(currentKp.kp * 10000 / 9));
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        String interval = Formats.formatTime(currentKp.time.atZone(zone).toLocalTime(), formats().clockFormat())
                + "–" + Formats.formatTime(currentKp.end().atZone(zone).toLocalTime(), formats().clockFormat());
        currentKpView.setText(activity.getString(R.string.aurora_kp_current, formats().number(currentKp.kp, "%.2f"), interval));
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
            auroraStatus.setText(activity.getString(R.string.aurora_direction, widgets.compassDirection(direction)));
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
}
