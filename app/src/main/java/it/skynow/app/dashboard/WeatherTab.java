package it.skynow.app.dashboard;

import it.skynow.app.net.Background;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import it.skynow.app.R;
import it.skynow.app.dashboard.AstronomyTab;
import it.skynow.app.maps.RadarActivity;
import it.skynow.app.ui.Formats;
import it.skynow.app.ui.SignalColors;
import it.skynow.app.ui.SkyViews;
import it.skynow.app.weather.AirQualityBand;
import it.skynow.app.weather.DailyWeather;
import it.skynow.app.weather.ForecastSeries;
import it.skynow.app.weather.MeteoAlarm;
import it.skynow.app.weather.OpenMeteo;
import it.skynow.app.weather.WeatherCodes;
import it.skynow.app.weather.views.HourlyLineChart;
import it.skynow.app.weather.views.HumidityGauge;
import it.skynow.app.weather.views.PressureGauge;
import it.skynow.app.weather.views.UvGauge;
import it.skynow.app.weather.views.WindIndicator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** The Weather dashboard tab: current conditions, warnings, hourly and 10-day forecasts, and a day's details. */
public final class WeatherTab {
    /** Days shown in the forecast list and the day picker. */
    public static final int MAX_DAYS = 10;

    /** State the tab reads from the activity. */
    public interface Host {
        Formats formats();
        /** Whether the forecast location is in Italy. */
        boolean inItaly();
        /** Administrative region of the forecast location, or empty while unknown. */
        String selectedRegion();
        /** A note about how the location was chosen, or null. */
        String locationNotice();
        /** When the shown forecast was cached, or null for a fresh one. */
        Instant cachedForecastAt();
        void openMap(RadarActivity.Layer layer);
        /** Opens the details of forecast day {@code index}. */
        void showDay(int index);
    }

    private final Activity activity;
    private final SkyViews views;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    // The forecast the tab was last attached with.
    private LinearLayout page;
    private JSONObject data;
    private int day = -1;
    private double latitude;
    private double longitude;

    private LinearLayout regionalWarnings;
    private List<MeteoAlarm.Warning> warnings = List.of();
    private long warningsAttempt;
    private boolean warningsLoading;
    private Instant nextWarningChange;
    private View forecastMaps;
    private Button airQualityMapButton;
    private static final int DETAIL_CHART_HEIGHT_DP = 180;

    public WeatherTab(Activity activity, SkyViews views, Host host) {
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

    private String description(int code) {
        return WeatherCodes.description(activity, code);
    }

    private TextView title(String text) {
        TextView title = views.sectionTitle(text);
        page.addView(title);
        return title;
    }

    private void openItalianMap(RadarActivity.Layer layer) {
        if (host.inItaly()) host.openMap(layer);
    }

    /**
     * Builds the dashboard ({@code day} < 0) or the details of forecast day {@code day}
     * at the end of {@code page}.
     */
    public void attach(LinearLayout page, JSONObject data, int day, double latitude, double longitude) {
        this.page = page;
        this.data = data;
        this.day = day;
        this.latitude = latitude;
        this.longitude = longitude;
        JSONObject current = data.optJSONObject("current");
        JSONObject hourly = data.optJSONObject("hourly");
        JSONObject daily = data.optJSONObject("daily");
        int todayHour = currentHour(current, hourly);
        String today = at(daily, "time", 0);
        double rainRemaining = DailyWeather.remainingMaximum(hourly, "precipitation_probability", todayHour, today);
        int forecastDays = Math.min(MAX_DAYS, length(daily));
        DailyWeather[] summaries = new DailyWeather[forecastDays];
        for (int index = 0; index < forecastDays; index++) {
            String date = at(daily, "time", index);
            double dailyCode = value(daily, "weather_code", index);
            int fallback = index == 0 ? current.optInt("weather_code") : (int) dailyCode;
            summaries[index] = DailyWeather.summarize(hourly, index == 0 ? todayHour : 0, date, fallback);
        }
        if (day < 0) {
            String locationNotice = host.locationNotice();
            if (locationNotice != null) page.addView(views.label(locationNotice, 13, views.muted));
            Instant cachedForecastAt = host.cachedForecastAt();
            if (cachedForecastAt != null) {
                ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
                ZonedDateTime updated = cachedForecastAt.atZone(zone);
                String timestamp = updated.format(formats().shortDate(true)) + " · " + updated.format(formats().clockFormat());
                views.addSpaced(page, views.label(activity.getString(R.string.cached_forecast, timestamp), 13, views.muted), 4);
            }
            title(activity.getString(R.string.now));
            LinearLayout hero = views.row();
            TextView temperature = views.label(formats().tempDecimal(current.optDouble("temperature_2m")), 76, views.ink);
            temperature.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            temperature.setLetterSpacing(-0.045f);
            hero.addView(temperature);
            TextView icon = views.label(WeatherCodes.symbol(current.optInt("weather_code"), current.optInt("is_day") == 0), 37, views.ink);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setPadding(views.dp(6), 0, 0, 0);
            hero.addView(icon);
            LinearLayout summary = views.column();
            summary.setGravity(Gravity.END);
            summary.setPadding(views.dp(12), 0, 0, 0);
            TextView condition = views.label(description(current.optInt("weather_code")), 17, views.ink);
            condition.setTypeface(condition.getTypeface(), Typeface.BOLD);
            condition.setGravity(Gravity.END);
            summary.addView(condition);
            TextView feelsLike = views.label(activity.getString(R.string.feels_like_value, formats().temp(current.optDouble("apparent_temperature"))), 14, views.muted);
            feelsLike.setPadding(0, views.dp(6), 0, 0);
            feelsLike.setGravity(Gravity.END);
            summary.addView(feelsLike);
            if (views.largeText()) {
                summary.setGravity(Gravity.START);
                summary.setPadding(0, views.dp(4), 0, views.dp(6));
                condition.setGravity(Gravity.START);
                feelsLike.setGravity(Gravity.START);
                page.addView(hero);
                page.addView(summary);
            } else {
                hero.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
                page.addView(hero);
            }
            TextView extremes = views.label(activity.getString(R.string.high_low, formats().temp(value(daily, "temperature_2m_max", 0)),
                    formats().temp(value(daily, "temperature_2m_min", 0))), 15, views.muted);
            views.addSpaced(page, extremes, 10);
            regionalWarnings = views.column();
            page.addView(regionalWarnings);
            renderWarnings(Instant.now());
            refreshWarnings();
            forecast(hourly, todayHour, todayHour + 24, true);
            title(activity.getString(R.string.forecast_10_days));
            for (int index = 0; index < forecastDays; index++) {
                final int selected = index;
                DailyWeather dayWeather = summaries[index];
                int weather = dayWeather.code;
                boolean roomy = views.largeText();
                LinearLayout tile = roomy ? views.column() : views.row();
                tile.setBackground(views.cardBackground(12));
                tile.setPadding(views.dp(14), views.dp(11), views.dp(14), views.dp(11));
                TextView dateLabel = views.label(date(daily, index), 16, views.ink);
                LinearLayout values = tile;
                if (roomy) {
                    tile.addView(dateLabel);
                    values = views.row();
                    values.setPadding(0, views.dp(5), 0, 0);
                    tile.addView(values);
                } else {
                    tile.addView(dateLabel, new LinearLayout.LayoutParams(0, -2, 1));
                }
                double probability = index == 0 ? rainRemaining : value(daily, "precipitation_probability_max", index);
                double displayedProbability = Math.floor(Math.min(probability, 100) / 10) * 10;
                TextView chance = views.label(displayedProbability > 0 ? formats().percent(displayedProbability) : "", 14, views.accent);
                chance.setGravity(roomy ? Gravity.START : Gravity.END);
                chance.setSingleLine(true);
                int chanceWidth = Math.max(views.dp(40), Math.max(views.textWidth(chance, formats().percent(100)),
                        views.textWidth(chance, chance.getText().toString())) + views.dp(4));
                values.addView(chance, new LinearLayout.LayoutParams(chanceWidth, -2, roomy ? 1 : 0));
                TextView dayIcon = views.label(WeatherCodes.symbol(weather, dayWeather.night), 23, views.ink);
                dayIcon.setContentDescription(description(weather));
                dayIcon.setGravity(Gravity.CENTER);
                int iconWidth = Math.max(views.dp(38), dayIcon.getLineHeight() + views.dp(6));
                values.addView(dayIcon, new LinearLayout.LayoutParams(iconWidth, -2));
                TextView temperatures = views.label(range(daily, index), 16, views.ink);
                temperatures.setGravity(Gravity.END);
                int temperatureWidth = Math.max(views.dp(formats().temperatureUnit == 0 ? 70 : 83), views.textWidth(temperatures, range(daily, index)) + views.dp(6));
                values.addView(temperatures, new LinearLayout.LayoutParams(temperatureWidth, -2));
                tile.setOnClickListener(v -> host.showDay(selected));
                views.addSpaced(page, tile, 5);
            }
            title(activity.getString(R.string.current_conditions));
            metrics(formats().windSpeed(current.optDouble("wind_speed_10m"), true), current.optDouble("wind_direction_10m"),
                    current.optDouble("relative_humidity_2m"), value(hourly, "dew_point_2m", todayHour),
                    value(hourly, "uv_index", todayHour),
                    current.optDouble("surface_pressure"));
            airQuality();
            updateItalianMaps();
            details(hourly, daily, 0, todayHour, todayHour + 24, true);
        } else {
            LinearLayout dates = views.carousel(page);
            View selectedChip = null;
            for (int index = 0; index < forecastDays; index++) {
                final int selected = index;
                String key = index == 0 ? activity.getString(R.string.today) : LocalDate.parse(at(daily, "time", index)).format(DateTimeFormatter.ofPattern("EEE", formats().appLocale()));
                DailyWeather summary = summaries[index];
                int chipWeather = summary.code;
                String chipRange = range(daily, index);
                TextView chip = views.button(key + "\n" + WeatherCodes.symbol(chipWeather, summary.night) + "\n" + chipRange,
                        () -> host.showDay(selected));
                chip.setContentDescription(key + ", " + description(chipWeather) + ", " + chipRange);
                chip.setBackground(views.background(index == day ? activity.getColor(R.color.sky_selected) : activity.getColor(R.color.sky_card), 10));
                chip.setPadding(views.dp(8), views.dp(11), views.dp(8), views.dp(11));
                chip.setLineSpacing(views.dp(4), 1f);
                chip.setMinHeight(views.dp(96));
                int chipWidth = Math.max(views.dp(74), views.textWidth(chip, chipRange) + views.dp(22));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(chipWidth, -2);
                if (index > 0) size.leftMargin = views.dp(8);
                dates.addView(chip, size);
                if (index == day) selectedChip = chip;
            }
            View active = selectedChip;
            HorizontalScrollView strip = (HorizontalScrollView) dates.getParent();
            if (active != null) strip.post(() -> strip.scrollTo(active.getLeft() - (strip.getWidth() - active.getWidth()) / 2, 0));
            DailyWeather summary = summaries[day];
            int weather = summary.code;
            title(date(daily, day));
            String temperatureRange = range(daily, day);
            TextView dailyTemperature = views.label(temperatureRange + "  " + WeatherCodes.symbol(weather, summary.night), 49, views.ink);
            dailyTemperature.setContentDescription(temperatureRange);
            page.addView(dailyTemperature);
            page.addView(views.label(description(weather), 17, views.muted));
            if (summary.eventTime != null) page.addView(views.label(weatherNote(summary), 14, views.muted));
            String date = at(daily, "time", day);
            int start = 0;
            while (start < length(hourly) && !at(hourly, "time", start).startsWith(date)) start++;
            int end = start;
            while (end < length(hourly) && at(hourly, "time", end).startsWith(date)) end++;
            if (day == 0) start = todayHour;
            forecast(hourly, start, end, day == 0);
            title(activity.getString(R.string.daily_conditions));
            addMetricGrid(windTile(activity.getString(R.string.max_wind), formats().windSpeed(value(daily, "wind_speed_10m_max", day), true),
                            value(daily, "wind_direction_10m_dominant", day)),
                    humidityTile(activity.getString(R.string.average_humidity), value(daily, "relative_humidity_2m_mean", day), Double.NaN),
                    uvTile(activity.getString(R.string.max_uv), value(daily, "uv_index_max", day)), dailySunTimes(daily, day));
            details(hourly, daily, day, start, end, day == 0);
        }
    }

    /** Forgets the views of a page that is being replaced. */
    public void detach() {
        regionalWarnings = null;
        forecastMaps = null;
        airQualityMapButton = null;
    }

    /** Shows the Italian radar, satellite and air quality maps only for Italian locations. */
    public void updateItalianMaps() {
        int visibility = host.inItaly() ? View.VISIBLE : View.GONE;
        if (forecastMaps != null) forecastMaps.setVisibility(visibility);
        if (airQualityMapButton != null) airQualityMapButton.setVisibility(visibility);
    }

    /** Minute update: expire or start warnings and reload the feed when due. */
    public void tick(Instant now) {
        if (nextWarningChange != null && !now.isBefore(nextWarningChange)) renderWarnings(now);
        refreshWarnings();
    }

    /** Lets a manual refresh reload the warnings immediately. */
    public void resetRefreshThrottles() {
        warningsAttempt = 0;
    }

    private void forecast(JSONObject hourly, int start, int end, boolean now) {
        if (day < 0) {
            LinearLayout heading = views.row();
            heading.setPadding(0, views.dp(12), 0, views.dp(7));
            TextView caption = views.label(activity.getString(R.string.hourly_forecast), 19, views.ink);
            caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            heading.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout maps = views.row();
            maps.addView(views.mapButton(R.string.radar, () -> openItalianMap(RadarActivity.Layer.RADAR)));
            Button satellite = views.mapButton(R.string.satellite, () -> openItalianMap(RadarActivity.Layer.SATELLITE));
            LinearLayout.LayoutParams satelliteSize = new LinearLayout.LayoutParams(-2, -2);
            satelliteSize.setMarginStart(views.dp(5));
            maps.addView(satellite, satelliteSize);
            heading.addView(maps);
            forecastMaps = maps;
            page.addView(heading);
        } else {
            title(activity.getString(R.string.hourly_forecast));
        }
        LinearLayout box = views.card();
        LinearLayout hours = views.carousel(box);
        for (int i = start; i < Math.min(end, length(hourly)); i++) {
            LinearLayout cell = views.column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, views.dp(9), 0, views.dp(7));
            String time = now && i == start ? activity.getString(R.string.now) : formats().clock(at(hourly, "time", i));
            TextView timeLabel = views.label(time, 13, views.muted);
            int hourWidth = Math.max(views.dp(67), views.textWidth(timeLabel, formats().clock24 ? "23:00" : "12:00 PM") + views.dp(12));
            hours.addView(cell, new LinearLayout.LayoutParams(hourWidth, -2));
            cell.addView(views.label(formats().temp(value(hourly, "temperature_2m", i)), 21, views.ink), new LinearLayout.LayoutParams(-2, -2));
            TextView chance = views.label(rainChance(hourly, "precipitation_probability", i), 13, views.accent);
            chance.setGravity(Gravity.CENTER);
            chance.setMinHeight(views.dp(24));
            cell.addView(chance, new LinearLayout.LayoutParams(-1, -2));
            int code = (int) value(hourly, "weather_code", i);
            TextView weather = views.label(WeatherCodes.symbol(code, value(hourly, "is_day", i) == 0), 26, views.ink);
            weather.setContentDescription(description(code));
            weather.setGravity(Gravity.CENTER);
            weather.setMinHeight(views.dp(38));
            cell.addView(weather, new LinearLayout.LayoutParams(-1, -2));
            cell.addView(timeLabel, new LinearLayout.LayoutParams(-2, -2));
        }
        page.addView(box);
    }


    public void refreshWarnings() {
        long elapsed = SystemClock.elapsedRealtime();
        if (!host.inItaly() || host.selectedRegion().isEmpty() || warningsLoading
                || (warningsAttempt != 0 && elapsed - warningsAttempt < 15 * 60 * 1000L)) return;
        warningsAttempt = elapsed;
        warningsLoading = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return MeteoAlarm.warnings();
            } catch (Exception error) {
                android.util.Log.w("MeteoAlarm", "Warning feed unavailable", error);
                return null;
            }
        }, Background.IO).thenAccept(result -> main.post(() -> {
            if (activity.isDestroyed()) return;
            warningsLoading = false;
            warnings = result == null ? List.of() : result;
            renderWarnings(Instant.now());
        }));
    }


    public void renderWarnings(Instant now) {
        nextWarningChange = null;
        if (regionalWarnings == null) return;
        regionalWarnings.removeAllViews();
        if (!host.inItaly() || host.selectedRegion().isEmpty()) return;
        String region = MeteoAlarm.regionKey(host.selectedRegion());
        DateTimeFormatter validity = DateTimeFormatter.ofPattern("EEE d MMM, " + (formats().clock24 ? "HH:mm" : "h:mm a"), formats().appLocale())
                .withZone(ZoneId.of("Europe/Rome"));
        for (MeteoAlarm.Warning warning : warnings) {
            if (!MeteoAlarm.regionKey(warning.area).equals(region) || !now.isBefore(warning.expires)) continue;
            boolean active = !now.isBefore(warning.start);
            Instant change = active ? warning.expires : warning.start;
            if (nextWarningChange == null || change.isBefore(nextWarningChange)) {
                nextWarningChange = change;
            }
            LinearLayout banner = views.card();
            String event = warning.event.toLowerCase(Locale.ROOT);
            int color = event.startsWith("red ") ? SignalColors.RED
                    : event.startsWith("orange ") ? SignalColors.ORANGE : SignalColors.YELLOW;
            banner.setBackground(views.background(SignalColors.tint(color), 12));
            TextView heading = views.label(warningTitle(warning.event), 16, views.ink);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            LinearLayout titleRow = views.row();
            titleRow.setGravity(Gravity.TOP);
            titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            if (active) {
                LinearLayout.LayoutParams badgeSize = new LinearLayout.LayoutParams(-2, -2);
                badgeSize.setMarginStart(views.dp(8));
                titleRow.addView(views.nowBadge(), badgeSize);
            }
            banner.addView(titleRow);
            TextView details = views.label(activity.getString(R.string.regional_warning_validity, warning.area,
                    validity.format(warning.start), validity.format(warning.expires)), 13, views.ink);
            details.setPadding(0, views.dp(4), 0, 0);
            banner.addView(details);
            banner.setOnClickListener(v -> activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(warning.url()))));
            views.addSpaced(regionalWarnings, banner, 8);
        }
    }


    private String warningTitle(String event) {
        if (!formats().italian()) return event;
        String[] parts = event.toLowerCase(Locale.ROOT).split(" ", 2);
        if (parts.length != 2) return event;
        String level;
        switch (parts[0]) {
            case "yellow": level = "gialla"; break;
            case "orange": level = "arancione"; break;
            case "red": level = "rossa"; break;
            default: return event;
        }
        String hazard;
        switch (parts[1]) {
            case "wind warning": hazard = "vento"; break;
            case "rain warning": hazard = "pioggia"; break;
            case "thunderstorm warning": hazard = "temporali"; break;
            case "snow-ice warning": case "snow/ice warning": hazard = "neve e ghiaccio"; break;
            case "fog warning": hazard = "nebbia"; break;
            case "high temperature warning": hazard = "temperature elevate"; break;
            case "low temperature warning": hazard = "temperature basse"; break;
            case "coastal event warning": hazard = "fenomeni costieri"; break;
            case "flooding warning": hazard = "alluvioni"; break;
            default: return event;
        }
        return "Allerta " + level + " per " + hazard;
    }


    private void details(JSONObject hourly, JSONObject daily, int index, int start, int end, boolean now) {
        title(activity.getString(R.string.hourly_details));
        LinearLayout box = views.card();
        LinearLayout tabs = views.row();
        box.addView(tabs);
        LinearLayout lowerTabs = views.largeText() ? views.row() : tabs;
        if (lowerTabs != tabs) {
            lowerTabs.setPadding(0, views.dp(4), 0, 0);
            box.addView(lowerTabs);
        }
        LinearLayout body = views.column();
        box.addView(body);
        String[] titles = {activity.getString(R.string.precipitation), activity.getString(R.string.wind), activity.getString(R.string.humidity), activity.getString(R.string.temperature)};
        TextView[] buttons = new TextView[titles.length];
        java.util.function.IntConsumer select = tab -> {
            for (int i = 0; i < buttons.length; i++) buttons[i].setBackground(views.background(i == tab ? activity.getColor(R.color.sky_selected) : activity.getColor(R.color.sky_subtle), 8));
            body.removeAllViews();
            LinearLayout summary = views.row();
            summary.setPadding(0, views.dp(14), 0, 0);
            body.addView(summary);
            String date = at(daily, "time", index);
            if (tab == 0) {
                detailSummary(summary, activity.getString(R.string.daily_total), formats().number(value(daily, "precipitation_sum", index), "%.1f") + " mm");
            } else if (tab == 1) {
                detailSummary(summary, activity.getString(R.string.daily_high), formats().windSpeed(value(daily, "wind_speed_10m_max", index), true));
                detailSummary(summary, activity.getString(R.string.daily_average), formats().windSpeed(DailyWeather.hourlySummary(hourly, "wind_speed_10m", date, false), true));
            } else if (tab == 2) {
                detailSummary(summary, activity.getString(R.string.daily_high), formats().percent(DailyWeather.hourlySummary(hourly, "relative_humidity_2m", date, true)));
                detailSummary(summary, activity.getString(R.string.daily_average), formats().percent(value(daily, "relative_humidity_2m_mean", index)));
            } else {
                detailSummary(summary, activity.getString(R.string.daily_high), formats().temp(value(daily, "temperature_2m_max", index)));
                detailSummary(summary, activity.getString(R.string.daily_low), formats().temp(value(daily, "temperature_2m_min", index)));
            }
            hourlyPlot(body, hourly, start, Math.min(end, length(hourly)), now, tab);
        };
        for (int i = 0; i < titles.length; i++) {
            final int tab = i;
            buttons[i] = views.button(titles[i], () -> select.accept(tab));
            buttons[i].setTextSize(12);
            buttons[i].setGravity(Gravity.CENTER);
            buttons[i].setMinHeight(views.dp(36));
            buttons[i].setPadding(views.dp(4), views.dp(5), views.dp(4), views.dp(5));
            LinearLayout destination = i >= 2 ? lowerTabs : tabs;
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2,
                    lowerTabs != tabs ? 1f : i == 0 || i == 3 ? 1.25f : 1f);
            if (destination.getChildCount() > 0) size.leftMargin = views.dp(4);
            destination.addView(buttons[i], size);
        }
        select.accept(0);
        page.addView(box);
    }


    private void detailSummary(LinearLayout parent, String heading, String value) {
        LinearLayout summary = views.column();
        summary.addView(views.label(heading, 13, views.muted));
        summary.addView(views.label(value, 25, views.ink));
        parent.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
    }


    private void hourlyPlot(LinearLayout body, JSONObject hourly, int start, int end, boolean now, int tab) {
        boolean precipitation = tab == 0;
        boolean wind = tab == 1;
        boolean humidity = tab == 2;
        LinearLayout chart = views.column();
        int topGap = views.dp(8);
        int legendHeight = Math.max(views.dp(19), views.label("", 11, views.muted).getLineHeight() * (views.largeText() ? 2 : 1));
        int legendGap = views.dp(5);
        TextView axisLabel = views.label("", 11, views.muted);
        int timeHeight = Math.max(views.dp(20), axisLabel.getLineHeight() + views.dp(5));
        int directionHeight = views.dp(25);
        chart.setPadding(0, topGap, 0, 0);
        int chartHeight = views.dp(DETAIL_CHART_HEIGHT_DP) + (views.largeText() ? views.dp(32) : 0);
        body.addView(chart, new LinearLayout.LayoutParams(-1, chartHeight));
        String firstName = new String[]{activity.getString(R.string.amount), activity.getString(R.string.wind_speed), activity.getString(R.string.humidity), activity.getString(R.string.temperature)}[tab];
        String secondName = new String[]{activity.getString(R.string.rain_chance), activity.getString(R.string.gusts), "", activity.getString(R.string.feels_like)}[tab];
        LinearLayout legend = views.row();
        TextView firstLegend = views.label((precipitation ? "■ " : "● ") + firstName, 11, activity.getColor(R.color.sky_accent));
        if (views.largeText()) legend.addView(firstLegend, new LinearLayout.LayoutParams(0, -2, 1));
        else legend.addView(firstLegend);
        if (!humidity) {
            TextView secondKey = views.label("┄ " + secondName, 11, activity.getColor(R.color.sky_chart_secondary));
            secondKey.setPadding(views.dp(12), 0, 0, 0);
            if (views.largeText()) legend.addView(secondKey, new LinearLayout.LayoutParams(0, -2, 1));
            else legend.addView(secondKey);
        }
        String unit = new String[]{"mm", formats().windUnitLabel(), "%", Formats.TEMPERATURE_UNITS[formats().temperatureUnit]}[tab];
        TextView units = views.label(precipitation ? "mm / %" : unit, 11, views.muted);
        units.setGravity(Gravity.END);
        legend.addView(units, views.largeText() ? new LinearLayout.LayoutParams(-2, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams legendSize = new LinearLayout.LayoutParams(-1, legendHeight);
        legendSize.bottomMargin = legendGap;
        chart.addView(legend, legendSize);

        String firstKey = new String[]{"precipitation", "wind_speed_10m", "relative_humidity_2m", "temperature_2m"}[tab];
        String secondField = new String[]{"precipitation_probability", "wind_gusts_10m", "", "apparent_temperature"}[tab];
        int count = Math.max(0, end - start);
        double[] first = new double[count];
        double[] second = new double[humidity ? 0 : count];
        for (int i = 0; i < count; i++) {
            double a = value(hourly, firstKey, start + i);
            first[i] = wind ? formats().displayWind(a) : tab == 3 ? formats().displayTemperature(a) : a;
            if (!humidity) {
                double b = value(hourly, secondField, start + i);
                second[i] = wind ? formats().displayWind(b) : tab == 3 ? formats().displayTemperature(b) : b;
            }
        }

        int scaleWidth = Math.max(views.dp(24), views.textWidth(axisLabel, "100.0") + views.dp(5));
        int screenWidth = views.dp(activity.getResources().getConfiguration().screenWidthDp);
        int minimumHourWidth = views.textWidth(axisLabel, formats().clock24 ? "23:00" : "12 PM") + views.dp(7);
        if (now) minimumHourWidth = Math.max(minimumHourWidth, views.textWidth(axisLabel, activity.getString(R.string.now)) + views.dp(4));
        int hourWidth = Math.max(Math.max(views.dp(32), minimumHourWidth),
                (screenWidth - 2 * (views.dp(16) + views.dp(13)) - scaleWidth * (precipitation ? 2 : 1)) / 8);
        int plotHeight = chartHeight - topGap - legendHeight - legendGap - timeHeight - directionHeight;
        HourlyLineChart plot = new HourlyLineChart(activity, first, second, hourWidth, precipitation, humidity, wind,
                precipitation || (wind && formats().windUnit == 2) ? 1 : 0);
        LinearLayout plotRow = views.row();
        plotRow.setGravity(Gravity.TOP);
        chart.addView(plotRow, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams leftScale = new LinearLayout.LayoutParams(scaleWidth, plotHeight);
        leftScale.topMargin = directionHeight;
        plotRow.addView(plot.scale(false), leftScale);
        HorizontalScrollView viewport = new HorizontalScrollView(activity);
        viewport.setHorizontalScrollBarEnabled(false);
        plotRow.addView(viewport, new LinearLayout.LayoutParams(0, -1, 1));
        if (precipitation) {
            LinearLayout.LayoutParams rightScale = new LinearLayout.LayoutParams(scaleWidth, plotHeight);
            rightScale.topMargin = directionHeight;
            plotRow.addView(plot.scale(true), rightScale);
        }
        LinearLayout series = views.column();
        viewport.addView(series);
        if (wind) {
            LinearLayout directions = views.row();
            for (int i = 0; i < count; i++) {
                FrameLayout cell = new FrameLayout(activity);
                cell.addView(new WindIndicator(activity, value(hourly, "wind_direction_10m", start + i)),
                        new FrameLayout.LayoutParams(views.dp(22), directionHeight, Gravity.CENTER));
                directions.addView(cell, new LinearLayout.LayoutParams(hourWidth, directionHeight));
            }
            series.addView(directions);
        } else {
            series.setPadding(0, directionHeight, 0, 0);
        }
        series.addView(plot, new LinearLayout.LayoutParams(hourWidth * count, plotHeight));
        LinearLayout hours = views.row();
        series.addView(hours);
        for (int i = 0; i < count; i++) {
            int index = start + i;
            String hour = index == start && now ? activity.getString(R.string.now) : Formats.formatTime(Formats.localTime(at(hourly, "time", index)), formats().hourClockFormat());
            LinearLayout cell = views.column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView time = views.label(hour, 11, views.muted);
            time.setGravity(Gravity.CENTER);
            String description = hour + ", " + lineReading(firstName, first[i], unit);
            if (!humidity) description += ", " + lineReading(secondName, second[i], precipitation ? "%" : unit);
            time.setContentDescription(description);
            cell.addView(time, new LinearLayout.LayoutParams(-1, timeHeight));
            hours.addView(cell, new LinearLayout.LayoutParams(hourWidth, -2));
        }
    }


    private String lineReading(String name, double value, String unit) {
        return Double.isFinite(value) ? activity.getString(R.string.chart_reading, name, formats().number(value, "%.1f"), unit)
                : activity.getString(R.string.chart_reading_missing, name);
    }


    private void airQuality() {
        LinearLayout tile = views.card();
        LinearLayout heading = views.row();
        heading.setGravity(Gravity.TOP);
        LinearLayout summary = views.column();
        summary.addView(views.label(activity.getString(R.string.air_quality), 14, views.muted));
        TextView index = views.label(activity.getString(R.string.air_quality_loading), 19, views.ink);
        LinearLayout.LayoutParams indexSize = new LinearLayout.LayoutParams(-2, -2);
        indexSize.topMargin = views.dp(5);
        summary.addView(index, indexSize);
        heading.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        airQualityMapButton = views.mapButton(R.string.aurora_map_short, () -> openItalianMap(RadarActivity.Layer.AIR_QUALITY));
        airQualityMapButton.setContentDescription(activity.getString(R.string.air_quality_map));
        LinearLayout.LayoutParams mapSize = new LinearLayout.LayoutParams(-2, -2);
        mapSize.setMarginStart(views.dp(8));
        heading.addView(airQualityMapButton, mapSize);
        tile.addView(heading);
        LinearLayout pollutants = views.column();
        pollutants.setVisibility(View.GONE);
        LinearLayout.LayoutParams pollutantSize = new LinearLayout.LayoutParams(-1, -2);
        pollutantSize.topMargin = views.dp(10);
        tile.addView(pollutants, pollutantSize);
        views.addSpaced(page, tile, 9);
        double lat = latitude;
        double lon = longitude;
        CompletableFuture.supplyAsync(() -> {
            try {
                return OpenMeteo.airQuality(lat, lon);
            } catch (Exception unavailable) {
                android.util.Log.w("SkyHere", "Air quality unavailable", unavailable);
                return null;
            }
        }, Background.IO).thenAccept(result -> main.post(() -> {
            if (activity.isDestroyed() || !tile.isAttachedToWindow()) return;
            double aqi = result == null ? Double.NaN : result.optDouble("european_aqi");
            if (!Double.isFinite(aqi)) {
                index.setText(R.string.air_quality_unavailable);
                return;
            }
            AirQualityBand band = AirQualityBand.forIndex(Math.round(aqi));
            index.setText(activity.getString(R.string.air_quality_index, Math.round(aqi), activity.getString(band.label)));
            GradientDrawable marker = views.background(band.color, 6);
            marker.setBounds(0, 0, views.dp(10), views.dp(10));
            index.setCompoundDrawablesRelative(marker, null, null, null);
            index.setCompoundDrawablePadding(views.dp(7));
            index.setBackground(views.background(SignalColors.tint(band.color), 9));
            index.setPaddingRelative(views.dp(9), views.dp(4), views.dp(9), views.dp(4));
            addAirQualityPollutants(pollutants, result);
            pollutants.setVisibility(View.VISIBLE);
        }));
    }


    private void addAirQualityPollutants(LinearLayout grid, JSONObject result) {
        String[] fields = {"pm2_5", "pm10", "ozone", "nitrogen_dioxide", "sulphur_dioxide"};
        String[] symbols = {activity.getString(R.string.air_quality_pm25), "PM10", "O₃", "NO₂", "SO₂"};
        int[] names = {R.string.air_quality_pm25, R.string.air_quality_pm10, R.string.air_quality_ozone,
                R.string.air_quality_nitrogen_dioxide, R.string.air_quality_sulphur_dioxide};
        String unit = activity.getString(R.string.air_quality_unit);
        int columns = views.largeText() ? 2 : 3;
        for (int start = 0; start < fields.length; start += columns) {
            LinearLayout line = views.row();
            line.setGravity(Gravity.TOP);
            int end = Math.min(start + columns, fields.length);
            for (int i = start; i < end; i++) {
                LinearLayout cell = views.column();
                cell.setGravity(Gravity.START);
                cell.addView(views.label(symbols[i], 12, views.muted));
                double concentration = result.optDouble(fields[i]);
                String value = formats().number(concentration, "%.1f");
                android.text.SpannableString valueWithUnit = new android.text.SpannableString(
                        Double.isFinite(concentration) ? value + "\u00a0" + unit : value);
                valueWithUnit.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, value.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (valueWithUnit.length() > value.length()) {
                    valueWithUnit.setSpan(new android.text.style.RelativeSizeSpan(0.73f),
                            value.length(), valueWithUnit.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                TextView reading = views.label("", 16, views.ink);
                reading.setText(valueWithUnit);
                AirQualityBand band = AirQualityBand.forConcentration(fields[i], concentration);
                String description = lineReading(activity.getString(names[i]), concentration, unit);
                if (band != null) {
                    reading.setBackground(views.background(SignalColors.tint(band.color), 7));
                    reading.setPaddingRelative(views.dp(6), views.dp(2), views.dp(6), views.dp(2));
                    description += " · " + activity.getString(band.label);
                }
                LinearLayout.LayoutParams readingSize = new LinearLayout.LayoutParams(-2, -2);
                readingSize.topMargin = views.dp(3);
                cell.addView(reading, readingSize);
                cell.setContentDescription(description);
                cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                for (int child = 0; child < cell.getChildCount(); child++) {
                    cell.getChildAt(child).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                }
                LinearLayout.LayoutParams cellSize = new LinearLayout.LayoutParams(0, -2, 1);
                if (i - start < columns - 1) cellSize.setMarginEnd(views.dp(4));
                line.addView(cell, cellSize);
            }
            for (int i = end - start; i < columns; i++) {
                line.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1));
            }
            views.addSpaced(grid, line, start == 0 ? 0 : 8);
        }
    }


    private void metrics(String wind, double direction, double humidity, double dewPoint, double uv, double pressure) {
        String pressureValue = formats().pressureReading(pressure);
        android.text.SpannableString pressureWithUnit = new android.text.SpannableString(
                pressureValue + "\u00a0" + formats().pressureUnitLabel());
        pressureWithUnit.setSpan(new android.text.style.RelativeSizeSpan(13f / 25),
                pressureValue.length(), pressureWithUnit.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        double elevation = data.optDouble("elevation");
        String altitude = Double.isFinite(elevation) ? activity.getString(R.string.location_elevation, formats().number(elevation, "%,.0f")) : null;
        LinearLayout pressureTile = gaugeTile(activity.getString(R.string.pressure), pressureWithUnit,
                altitude, new PressureGauge(activity, pressure, formats()::pressureReading), 60);
        addMetricGrid(windTile(activity.getString(R.string.wind), wind, direction),
                humidityTile(activity.getString(R.string.humidity), humidity, dewPoint),
                uvTile(activity.getString(R.string.uv_index), uv), pressureTile);
    }


    private LinearLayout windTile(String title, String speed, double direction) {
        LinearLayout wind = views.card();
        wind.addView(views.label(title, 14, views.muted));
        LinearLayout readings = views.row();
        wind.addView(readings);
        LinearLayout text = views.column();
        readings.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        text.addView(views.label(speed, 22, views.ink));
        if (Double.isFinite(direction)) {
            String[] compass = formats().italian() ? new String[]{"N", "NE", "E", "SE", "S", "SO", "O", "NO"}
                    : new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            int index = Math.floorMod((int) Math.round(direction / 45), compass.length);
            int bearing = Math.floorMod((int) Math.round(direction), 360);
            text.addView(views.label(activity.getString(R.string.from_bearing, compass[index], bearing), 13, views.muted));
        }
        readings.addView(new WindIndicator(activity, direction), new LinearLayout.LayoutParams(views.dp(46), views.dp(46)));
        return wind;
    }


    private LinearLayout humidityTile(String title, double humidity, double dewPoint) {
        String detail = Double.isFinite(dewPoint) ? activity.getString(R.string.dew_point, formats().temp(dewPoint)) : null;
        return gaugeTile(title, formats().percent(humidity), detail, new HumidityGauge(activity, humidity), 38);
    }


    private LinearLayout uvTile(String title, double index) {
        String level = null;
        if (Double.isFinite(index)) {
            int rounded = (int) Math.round(index);
            if (rounded >= 11) level = activity.getString(R.string.uv_extreme);
            else if (rounded >= 8) level = activity.getString(R.string.uv_very_high);
            else if (rounded >= 6) level = activity.getString(R.string.uv_high);
            else if (rounded >= 3) level = activity.getString(R.string.uv_moderate);
            else level = activity.getString(R.string.uv_low);
        }
        return gaugeTile(title, formats().number(index, "%.1f"), level, new UvGauge(activity, index), 38);
    }


    private LinearLayout gaugeTile(String title, CharSequence value, String detail, View gauge, int gaugeWidth) {
        LinearLayout tile = views.card();
        tile.addView(views.label(title, 14, views.muted));
        LinearLayout content = views.row();
        tile.addView(content, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout text = views.column();
        content.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView reading = views.label("", 25, views.ink);
        reading.setText(value);
        text.addView(reading);
        if (detail != null) {
            TextView subtitle = views.label(detail, 13, views.muted);
            subtitle.setPadding(0, views.dp(3), 0, 0);
            text.addView(subtitle);
        }
        content.addView(gauge, new LinearLayout.LayoutParams(views.dp(gaugeWidth), views.dp(58)));
        return tile;
    }


    private LinearLayout metric(String title, String value) {
        LinearLayout tile = views.card();
        tile.addView(views.label(title, 14, views.muted));
        TextView reading = views.label(value, 22, views.ink);
        reading.setPadding(0, views.dp(8), 0, 0);
        tile.addView(reading);
        return tile;
    }


    private void addMetricGrid(View first, View second, View third, View fourth) {
        View[] cards = {first, second, third, fourth};
        boolean stacked = views.stackedCards();
        int width = views.dp(activity.getResources().getConfiguration().screenWidthDp) - page.getPaddingLeft() - page.getPaddingRight();
        int tileWidth = Math.max(1, stacked ? width : (width - views.dp(10)) / 2);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(tileWidth, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int height = views.dp(100);
        for (View card : cards) {
            card.measure(widthSpec, heightSpec);
            height = Math.max(height, card.getMeasuredHeight());
        }
        for (View card : cards) card.setMinimumHeight(height);
        addMetricRow(first, second);
        addMetricRow(third, fourth);
    }


    private void addMetricRow(View first, View second) {
        if (views.stackedCards()) {
            views.addSpaced(page, first, 9);
            views.addSpaced(page, second, 9);
            return;
        }
        LinearLayout tiles = views.row();
        tiles.setGravity(Gravity.TOP);
        tiles.addView(first, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.leftMargin = views.dp(10);
        tiles.addView(second, params);
        views.addSpaced(page, tiles, 9);
    }

    private LinearLayout dailySunTimes(JSONObject daily, int index) {
        LinearLayout tile = views.card();
        tile.addView(views.label(activity.getString(R.string.sunrise_sunset), 14, views.muted));
        long seconds = AstronomyTab.daylightSeconds(daily, index);
        if (AstronomyTab.isPolarDaylight(seconds)) {
            TextView status = views.label(activity.getString(AstronomyTab.polarDaylightLabel(seconds)), 13, views.ink);
            status.setPadding(0, views.dp(5), 0, 0);
            tile.addView(status);
        }
        String[] labels = {activity.getString(R.string.sunrise), activity.getString(R.string.solar_noon), activity.getString(R.string.sunset)};
        String[] values = {AstronomyTab.isPolarDaylight(seconds) ? "—" : formats().clock(at(daily, "sunrise", index)), AstronomyTab.solarNoon(daily, index, longitude, ZoneId.of(data.optString("timezone", "UTC")), formats()),
                AstronomyTab.isPolarDaylight(seconds) ? "—" : formats().clock(at(daily, "sunset", index))};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout line = views.row();
            line.setPadding(0, views.dp(5), 0, 0);
            line.addView(views.label(labels[i], 12, views.muted), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(views.label(values[i], 13, views.ink));
            tile.addView(line);
        }
        return tile;
    }


    private String weatherNote(DailyWeather summary) {
        return activity.getString(R.string.forecast_weather_event, description(summary.eventCode), formats().clock(summary.eventTime));
    }


    private int currentHour(JSONObject current, JSONObject hourly) {
        LocalDateTime localHour = LocalDateTime.now(ZoneId.of(data.optString("timezone", "UTC")))
                .truncatedTo(ChronoUnit.HOURS);
        String time = current.optString("time");
        String today = at(data.optJSONObject("daily"), "time", 0);
        if (localHour.toLocalDate().toString().equals(today)) {
            String hour = localHour.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH", Locale.US));
            for (int i = 0; i < length(hourly); i++) {
                if (at(hourly, "time", i).startsWith(hour)) return i;
            }
        }
        if (time.length() < 13) return 0;
        for (int i = 0; i < length(hourly); i++) if (at(hourly, "time", i).startsWith(time.substring(0, 13))) return i;
        return 0;
    }


    private String date(JSONObject daily, int index) {
        if (index == 0) return activity.getString(R.string.today);
        return LocalDate.parse(at(daily, "time", index)).format(DateTimeFormatter.ofPattern(formats().italian() ? "EEEE d MMM" : "EEEE, MMM d", formats().appLocale()));
    }


    private String range(JSONObject daily, int index) {
        return formats().temp(value(daily, "temperature_2m_max", index)) + "/" + formats().temp(value(daily, "temperature_2m_min", index));
    }


    private String rainChance(JSONObject data, String key, int index) {
        double chance = value(data, key, index);
        return chance > 0 ? formats().number(chance, "%.0f") + "%" : "";
    }
}
