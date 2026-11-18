package it.meteo.locale;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.app.Dialog;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter DETAIL_CLOCK = DateTimeFormatter.ofPattern("h a", Locale.US);
    private static final int[] MOON_PHASE_NAMES = {R.string.moon_new, R.string.moon_waxing_crescent, R.string.moon_first_quarter,
            R.string.moon_waxing_gibbous, R.string.moon_full, R.string.moon_waning_gibbous,
            R.string.moon_last_quarter, R.string.moon_waning_crescent};
    private static final int ASTRONOMY_PAGE_SIZE = 4;
    private static final int FORECAST_DAYS = 10;
    private static final int LOCATION_PERMISSION = 1;
    private static final long FORECAST_REFRESH_MS = 5 * 60 * 1000;
    private static final int DETAIL_CHART_HEIGHT_DP = 180;
    private static final int INK = 0xff242d40;
    private static final int MUTED = 0xff546074;
    private static final int ACCENT = 0xff435e94;
    private static final int SURFACE = 0xffedf0f5;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private LinearLayout page;
    private ScrollView scroll;
    private JSONObject data;
    private String place;
    private double latitude;
    private double longitude;
    private boolean fahrenheit;
    private boolean clock24;
    private int windUnit;
    private int pressureUnit;

    private static final String[] WIND_UNITS = {"km/h", "mph", "m/s", "kn"};
    private static final double[] WIND_FACTORS = {1, 0.621371, 1.0 / 3.6, 1.0 / 1.852};
    private static final String[] PRESSURE_UNITS = {"hPa", "mbar", "inHg", "mmHg"};
    private static final double[] PRESSURE_FACTORS = {1, 1, 0.029529983, 0.750061683};
    private int day = -1;
    private int homeScroll;
    private boolean choosingLocation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationListener locationListener;
    private Runnable locationTimeout;
    private Runnable pendingSearch;
    private String locationNotice;
    private int forecastRequest;
    private boolean forecastLoading;
    private long forecastAttemptAt;
    private ZonedDateTime forecastHour;
    private boolean usingCurrentLocation;
    private TextView placeHeader;
    private TextView currentLocationTitle;
    private TextView currentLocationWeather;
    private String devicePlace;
    private JSONObject deviceWeather;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        place = prefs.getString("place", "Bastia Umbra");
        latitude = Double.parseDouble(prefs.getString("latitude", "43.066"));
        longitude = Double.parseDouble(prefs.getString("longitude", "12.548"));
        fahrenheit = prefs.getBoolean("fahrenheit", false);
        clock24 = prefs.getBoolean("clock24", true);
        windUnit = prefs.getInt("windUnit", 0);
        pressureUnit = prefs.getInt("pressureUnit", 0);
        currentLocation(!prefs.getBoolean("askedForLocation", false));
    }

    private void currentLocation(boolean askPermission) {
        stopLocating();
        forecastRequest++;
        forecastLoading = false;
        if (!hasLocationPermission()) {
            if (askPermission) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("askedForLocation", true).apply();
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            } else {
                fallbackLocation(getString(R.string.location_permission_off));
            }
            return;
        }

        choosingLocation = false;
        day = -1;
        layout();
        page.addView(header(getString(R.string.current_location), this::locations));
        page.addView(loadingStatus(getString(R.string.finding_location)));
        LocationManager manager = getSystemService(LocationManager.class);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (locationListener != this) return;
                stopLocating();
                latitude = location.getLatitude();
                longitude = location.getLongitude();
                place = getString(R.string.current_location);
                devicePlace = null;
                deviceWeather = null;
                usingCurrentLocation = true;
                locationNotice = null;
                load();
                resolveLocationName(latitude, longitude);
            }

            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        boolean precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean listening = false;
        for (String provider : manager.getProviders(true)) {
            if (!LocationManager.NETWORK_PROVIDER.equals(provider) && !"fused".equals(provider)
                    && !(precise && LocationManager.GPS_PROVIDER.equals(provider))) continue;
            try {
                manager.requestLocationUpdates(provider, 0, 0, locationListener, Looper.getMainLooper());
                listening = true;
            } catch (IllegalArgumentException | SecurityException error) {
                android.util.Log.d("MeteoLocale", "Location provider unavailable: " + provider, error);
            }
        }
        if (listening) {
            locationTimeout = () -> fallbackLocation(getString(R.string.current_location_unavailable));
            main.postDelayed(locationTimeout, 12000);
        } else {
            fallbackLocation(getString(R.string.current_location_unavailable));
        }
    }

    private void resolveLocationName(double lat, double lon) {
        if (!Geocoder.isPresent()) return;
        Geocoder geocoder = new Geocoder(this, appLocale());
        java.util.function.Consumer<List<Address>> resolved = addresses -> {
            if (addresses == null || addresses.isEmpty()) return;
            Address address = addresses.get(0);
            String locality = address.getLocality();
            String name = locality == null ? address.getSubAdminArea() : locality;
            if (name == null || name.trim().isEmpty()) return;
            runOnUiThread(() -> {
                if (isDestroyed() || !usingCurrentLocation || lat != latitude || lon != longitude) return;
                place = name;
                devicePlace = name;
                if (placeHeader != null) updatePlaceHeader();
                updateCurrentLocationShortcut();
            });
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(lat, lon, 1, resolved::accept);
        } else {
            network.execute(() -> {
                try {
                    resolved.accept(geocoder.getFromLocation(lat, lon, 1));
                } catch (java.io.IOException error) {
                    android.util.Log.d("MeteoLocale", "Location name unavailable", error);
                }
            });
        }
    }

    private TextView placeTitle() {
        placeHeader = header(place, this::locations);
        updatePlaceHeader();
        return placeHeader;
    }

    private void updatePlaceHeader() {
        placeHeader.setText(place);
        if (usingCurrentLocation) {
            placeHeader.setContentDescription(getString(R.string.current_location_named, place));
            placeHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_current_location, 0, 0, 0);
            placeHeader.setCompoundDrawablePadding(dp(8));
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void stopLocating() {
        if (locationListener != null) {
            getSystemService(LocationManager.class).removeUpdates(locationListener);
            locationListener = null;
        }
        if (locationTimeout != null) {
            main.removeCallbacks(locationTimeout);
            locationTimeout = null;
        }
    }

    private void fallbackLocation(String reason) {
        stopLocating();
        usingCurrentLocation = false;
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        place = prefs.getString("place", "Bastia Umbra");
        latitude = Double.parseDouble(prefs.getString("latitude", "43.066"));
        longitude = Double.parseDouble(prefs.getString("longitude", "12.548"));
        locationNotice = reason;
        load();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_PERMISSION) currentLocation(false);
    }

    private void load() {
        data = null;
        day = -1;
        homeScroll = 0;
        choosingLocation = false;
        layout();
        page.addView(placeTitle());
        page.addView(loadingStatus(getString(R.string.loading_forecast)));
        requestForecast(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (data == null || choosingLocation || locationListener != null || forecastLoading) return;
        ZonedDateTime localHour = Instant.now().atZone(ZoneId.of(data.optString("timezone", "UTC")))
                .truncatedTo(ChronoUnit.HOURS);
        if (SystemClock.elapsedRealtime() - forecastAttemptAt >= FORECAST_REFRESH_MS
                || !localHour.equals(forecastHour)) requestForecast(true);
    }

    private void requestForecast(boolean refreshing) {
        forecastLoading = true;
        forecastAttemptAt = SystemClock.elapsedRealtime();
        if (data != null) {
            forecastHour = Instant.now().atZone(ZoneId.of(data.optString("timezone", "UTC"))).truncatedTo(ChronoUnit.HOURS);
        }
        final int request = ++forecastRequest;
        final double lat = latitude;
        final double lon = longitude;
        network.execute(() -> {
            try {
                JSONObject response = OpenMeteo.forecast(lat, lon);
                runOnUiThread(() -> {
                    if (request != forecastRequest || isDestroyed()) return;
                    forecastLoading = false;
                    forecastHour = Instant.now().atZone(ZoneId.of(response.optString("timezone", "UTC")))
                            .truncatedTo(ChronoUnit.HOURS);
                    int offset = refreshing && scroll != null ? scroll.getScrollY() : 0;
                    if (refreshing && day >= 0 && data != null) {
                        String selectedDate = at(data.optJSONObject("daily"), "time", day);
                        JSONObject refreshed = response.optJSONObject("daily");
                        day = 0;
                        for (int i = 0; i < Math.min(FORECAST_DAYS, length(refreshed)); i++) {
                            if (selectedDate.equals(at(refreshed, "time", i))) day = i;
                        }
                    }
                    data = response;
                    if (usingCurrentLocation) {
                        deviceWeather = response.optJSONObject("current");
                        updateCurrentLocationShortcut();
                    }
                    if (!choosingLocation) {
                        render();
                        ScrollView updated = scroll;
                        if (offset > 0) updated.post(() -> updated.scrollTo(0, offset));
                    }
                });
            } catch (Exception error) {
                android.util.Log.w("MeteoLocale", "Forecast request failed", error);
                runOnUiThread(() -> {
                    if (request != forecastRequest || isDestroyed()) return;
                    forecastLoading = false;
                    if (refreshing || choosingLocation) return;
                    layout();
                    page.addView(placeTitle());
                    page.addView(label(getString(R.string.forecast_unavailable), 22, INK));
                    page.addView(button(getString(R.string.retry), this::load));
                });
            }
        });
    }

    private void layout() {
        placeHeader = null;
        currentLocationTitle = null;
        currentLocationWeather = null;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(SURFACE);
        scroll.setFillViewport(true);
        page = column();
        page.setPadding(dp(16), dp(12), dp(16), dp(20));
        scroll.addView(page);
        if (day >= 0 && !choosingLocation) {
            LinearLayout screen = column();
            screen.setBackgroundColor(SURFACE);
            LinearLayout toolbar = column();
            toolbar.setPadding(dp(16), dp(8), dp(16), 0);
            toolbar.addView(header("‹  " + getString(R.string.forecast_10_days), this::home));
            screen.addView(toolbar);
            screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            setContentView(screen);
        } else {
            setContentView(scroll);
        }
    }

    private void render() {
        choosingLocation = false;
        layout();
        JSONObject current = data.optJSONObject("current");
        JSONObject hourly = data.optJSONObject("hourly");
        JSONObject daily = data.optJSONObject("daily");
        int todayHour = currentHour(current, hourly);
        String today = at(daily, "time", 0);
        double rainRemaining = remainingMaximum(hourly, "precipitation_probability", todayHour, today);
        double remainingWeather = remainingMaximum(hourly, "weather_code", todayHour, today);
        int todayWeather = Double.isFinite(remainingWeather) ? (int) remainingWeather : current.optInt("weather_code");
        if (day < 0) {
            LinearLayout top = row();
            top.addView(placeTitle(), new LinearLayout.LayoutParams(0, -2, 1));
            TextView settings = button("⋮", this::settings);
            settings.setTextSize(24);
            settings.setMinWidth(dp(46));
            settings.setContentDescription(getString(R.string.settings));
            top.addView(settings);
            page.addView(top);
            if (locationNotice != null) page.addView(label(locationNotice, 13, MUTED));
            title(getString(R.string.now));
            LinearLayout hero = row();
            TextView temperature = label(temp(current.optDouble("temperature_2m")), 76, INK);
            temperature.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            temperature.setLetterSpacing(-0.045f);
            hero.addView(temperature);
            TextView icon = label(symbol(current.optInt("weather_code"), current.optInt("is_day") == 0), 37, INK);
            icon.setPadding(dp(6), 0, 0, 0);
            hero.addView(icon);
            LinearLayout summary = column();
            summary.setGravity(Gravity.END);
            summary.setPadding(dp(12), 0, 0, 0);
            TextView condition = label(description(current.optInt("weather_code")), 17, INK);
            condition.setGravity(Gravity.END);
            summary.addView(condition);
            TextView feelsLike = label(getString(R.string.feels_like_value, temp(current.optDouble("apparent_temperature"))), 14, MUTED);
            feelsLike.setPadding(0, dp(6), 0, 0);
            feelsLike.setGravity(Gravity.END);
            summary.addView(feelsLike);
            if (largeText()) {
                summary.setGravity(Gravity.START);
                summary.setPadding(0, dp(4), 0, dp(6));
                condition.setGravity(Gravity.START);
                feelsLike.setGravity(Gravity.START);
                page.addView(hero);
                page.addView(summary);
            } else {
                hero.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
                page.addView(hero);
            }
            TextView extremes = label(getString(R.string.high_low, temp(value(daily, "temperature_2m_max", 0)),
                    temp(value(daily, "temperature_2m_min", 0))), 15, MUTED);
            addSpaced(page, extremes, 10);
            forecast(hourly, todayHour, todayHour + 24, true);
            title(getString(R.string.forecast_10_days));
            for (int index = 0; index < Math.min(FORECAST_DAYS, length(daily)); index++) {
                final int selected = index;
                int weather = index == 0 ? todayWeather : (int) value(daily, "weather_code", index);
                boolean roomy = largeText();
                LinearLayout tile = roomy ? column() : row();
                tile.setBackground(background(Color.WHITE, 12));
                tile.setPadding(dp(14), dp(11), dp(14), dp(11));
                TextView dateLabel = label(date(daily, index), 16, INK);
                LinearLayout values = tile;
                if (roomy) {
                    tile.addView(dateLabel);
                    values = row();
                    values.setPadding(0, dp(5), 0, 0);
                    tile.addView(values);
                } else {
                    tile.addView(dateLabel, new LinearLayout.LayoutParams(0, -2, 1));
                }
                double probability = index == 0 ? rainRemaining : value(daily, "precipitation_probability_max", index);
                TextView chance = label(probability > 0 ? percent(probability) : "", 14, ACCENT);
                chance.setGravity(roomy ? Gravity.START : Gravity.END);
                values.addView(chance, new LinearLayout.LayoutParams(roomy ? 0 : dp(40), -2, roomy ? 1 : 0));
                TextView dayIcon = label(symbol(weather, false), 23, INK);
                dayIcon.setGravity(Gravity.CENTER);
                int iconWidth = Math.max(dp(38), dayIcon.getLineHeight() + dp(6));
                values.addView(dayIcon, new LinearLayout.LayoutParams(iconWidth, -2));
                TextView temperatures = label(range(daily, index), 16, INK);
                temperatures.setGravity(Gravity.END);
                int temperatureWidth = Math.max(dp(fahrenheit ? 83 : 70), textWidth(temperatures, range(daily, index)) + dp(6));
                values.addView(temperatures, new LinearLayout.LayoutParams(temperatureWidth, -2));
                tile.setOnClickListener(v -> {
                    homeScroll = scroll.getScrollY();
                    day = selected;
                    render();
                });
                addSpaced(page, tile, 5);
            }
            title(getString(R.string.current_conditions));
            metrics(windSpeed(current.optDouble("wind_speed_10m"), true), current.optDouble("wind_direction_10m"),
                    current.optDouble("relative_humidity_2m"), value(hourly, "dew_point_2m", todayHour),
                    value(hourly, "uv_index", todayHour),
                    current.optDouble("surface_pressure"));
            details(hourly, daily, 0, todayHour, todayHour + 24, true);
            title(getString(R.string.astronomy));
            sun(daily, 0);
            moon(daily, 0);
            eclipsesAndTransits();
            page.addView(button(getString(R.string.weather_data), () -> startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://open-meteo.com/")))));
        } else {
            LinearLayout dates = carousel(page);
            View selectedChip = null;
            for (int index = 0; index < Math.min(FORECAST_DAYS, length(daily)); index++) {
                final int selected = index;
                String key = index == 0 ? getString(R.string.today) : LocalDate.parse(at(daily, "time", index)).format(DateTimeFormatter.ofPattern("EEE", appLocale()));
                TextView chip = button(key + "\n" + symbol(index == 0 ? todayWeather : (int) value(daily, "weather_code", index), false) + "\n" + range(daily, index), () -> {
                    day = selected;
                    render();
                });
                chip.setBackground(background(index == day ? 0xffd9e3f8 : Color.WHITE, 10));
                chip.setPadding(dp(8), dp(11), dp(8), dp(11));
                chip.setLineSpacing(dp(4), 1f);
                chip.setMinHeight(dp(96));
                int chipWidth = Math.max(dp(74), textWidth(chip, range(daily, index)) + dp(22));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(chipWidth, -2);
                if (index > 0) size.leftMargin = dp(8);
                dates.addView(chip, size);
                if (index == day) selectedChip = chip;
            }
            View active = selectedChip;
            HorizontalScrollView strip = (HorizontalScrollView) dates.getParent();
            if (active != null) strip.post(() -> strip.scrollTo(active.getLeft() - (strip.getWidth() - active.getWidth()) / 2, 0));
            int weather = day == 0 ? todayWeather : (int) value(daily, "weather_code", day);
            title(date(daily, day));
            page.addView(label(range(daily, day) + "  " + symbol(weather, false), 49, INK));
            page.addView(label(description(weather), 17, MUTED));
            String date = at(daily, "time", day);
            int start = 0;
            while (start < length(hourly) && !at(hourly, "time", start).startsWith(date)) start++;
            int end = start;
            while (end < length(hourly) && at(hourly, "time", end).startsWith(date)) end++;
            if (day == 0) start = todayHour;
            forecast(hourly, start, end, day == 0);
            title(getString(R.string.daily_conditions));
            windTiles(getString(R.string.max_wind), windSpeed(value(daily, "wind_speed_10m_max", day), true),
                    value(daily, "wind_direction_10m_dominant", day),
                    humidityTile(getString(R.string.average_humidity), value(daily, "relative_humidity_2m_mean", day), Double.NaN));
            addMetricRow(uvTile(getString(R.string.max_uv), value(daily, "uv_index_max", day)),
                    dailySunTimes(daily, day));
            details(hourly, daily, day, start, end, day == 0);
        }
    }

    private void forecast(JSONObject hourly, int start, int end, boolean now) {
        title(getString(R.string.hourly_forecast));
        LinearLayout box = card();
        LinearLayout hours = carousel(box);
        for (int i = start; i < Math.min(end, length(hourly)); i++) {
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(9), 0, dp(7));
            String time = now && i == start ? getString(R.string.now) : clock(at(hourly, "time", i));
            TextView timeLabel = label(time, 13, MUTED);
            int hourWidth = Math.max(dp(67), textWidth(timeLabel, clock24 ? "23:00" : "12:00 PM") + dp(12));
            hours.addView(cell, new LinearLayout.LayoutParams(hourWidth, -2));
            cell.addView(label(temp(value(hourly, "temperature_2m", i)), 21, INK), new LinearLayout.LayoutParams(-2, -2));
            TextView chance = label(rainChance(hourly, "precipitation_probability", i), 13, ACCENT);
            chance.setGravity(Gravity.CENTER);
            chance.setMinHeight(dp(24));
            cell.addView(chance, new LinearLayout.LayoutParams(-1, -2));
            TextView weather = label(symbol((int) value(hourly, "weather_code", i), value(hourly, "is_day", i) == 0), 26, INK);
            weather.setGravity(Gravity.CENTER);
            weather.setMinHeight(dp(38));
            cell.addView(weather, new LinearLayout.LayoutParams(-1, -2));
            cell.addView(timeLabel, new LinearLayout.LayoutParams(-2, -2));
        }
        page.addView(box);
    }

    private void details(JSONObject hourly, JSONObject daily, int index, int start, int end, boolean now) {
        title(getString(R.string.hourly_details));
        LinearLayout box = card();
        LinearLayout tabs = row();
        box.addView(tabs);
        LinearLayout lowerTabs = largeText() ? row() : tabs;
        if (lowerTabs != tabs) {
            lowerTabs.setPadding(0, dp(4), 0, 0);
            box.addView(lowerTabs);
        }
        LinearLayout body = column();
        box.addView(body);
        String[] titles = {getString(R.string.precipitation), getString(R.string.wind), getString(R.string.humidity), getString(R.string.temperature)};
        TextView[] buttons = new TextView[titles.length];
        java.util.function.IntConsumer select = tab -> {
            for (int i = 0; i < buttons.length; i++) buttons[i].setBackground(background(i == tab ? 0xffd9e3f8 : 0xfff1f3f8, 8));
            body.removeAllViews();
            LinearLayout summary = row();
            summary.setPadding(0, dp(14), 0, 0);
            body.addView(summary);
            String date = at(daily, "time", index);
            if (tab == 0) {
                detailSummary(summary, getString(R.string.daily_total), number(value(daily, "precipitation_sum", index), "%.1f") + " mm");
            } else if (tab == 1) {
                detailSummary(summary, getString(R.string.daily_high), windSpeed(value(daily, "wind_speed_10m_max", index), true));
                detailSummary(summary, getString(R.string.daily_average), windSpeed(hourlyDaySummary(hourly, "wind_speed_10m", date, false), true));
            } else if (tab == 2) {
                detailSummary(summary, getString(R.string.daily_high), percent(hourlyDaySummary(hourly, "relative_humidity_2m", date, true)));
                detailSummary(summary, getString(R.string.daily_average), percent(value(daily, "relative_humidity_2m_mean", index)));
            } else {
                detailSummary(summary, getString(R.string.daily_high), temp(value(daily, "temperature_2m_max", index)));
                detailSummary(summary, getString(R.string.daily_low), temp(value(daily, "temperature_2m_min", index)));
            }
            hourlyPlot(body, hourly, start, Math.min(end, length(hourly)), now, tab);
        };
        for (int i = 0; i < titles.length; i++) {
            final int tab = i;
            buttons[i] = button(titles[i], () -> select.accept(tab));
            buttons[i].setTextSize(12);
            buttons[i].setGravity(Gravity.CENTER);
            buttons[i].setMinHeight(dp(36));
            buttons[i].setPadding(dp(4), dp(5), dp(4), dp(5));
            LinearLayout destination = i >= 2 ? lowerTabs : tabs;
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2,
                    lowerTabs != tabs ? 1f : i == 0 || i == 3 ? 1.25f : 1f);
            if (destination.getChildCount() > 0) size.leftMargin = dp(4);
            destination.addView(buttons[i], size);
        }
        select.accept(0);
        page.addView(box);
    }

    private void detailSummary(LinearLayout parent, String heading, String value) {
        LinearLayout summary = column();
        summary.addView(label(heading, 13, MUTED));
        summary.addView(label(value, 25, INK));
        parent.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private static double hourlyDaySummary(JSONObject hourly, String key, String date, boolean high) {
        int count = 0;
        double total = 0;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < length(hourly); i++) {
            if (!at(hourly, "time", i).startsWith(date)) continue;
            double reading = value(hourly, key, i);
            if (!Double.isFinite(reading)) continue;
            count++;
            total += reading;
            maximum = Math.max(maximum, reading);
        }
        if (count == 0) return Double.NaN;
        return high ? maximum : total / count;
    }

    private void hourlyPlot(LinearLayout body, JSONObject hourly, int start, int end, boolean now, int tab) {
        boolean precipitation = tab == 0;
        boolean wind = tab == 1;
        boolean humidity = tab == 2;
        LinearLayout chart = column();
        int topGap = dp(8);
        int legendHeight = Math.max(dp(19), label("", 11, MUTED).getLineHeight() * (largeText() ? 2 : 1));
        int legendGap = dp(5);
        TextView axisLabel = label("", 10, MUTED);
        int timeHeight = Math.max(dp(20), axisLabel.getLineHeight() + dp(5));
        int directionHeight = wind ? dp(25) : 0;
        chart.setPadding(0, topGap, 0, 0);
        int chartHeight = dp(DETAIL_CHART_HEIGHT_DP) + (largeText() ? dp(32) : 0);
        body.addView(chart, new LinearLayout.LayoutParams(-1, chartHeight));
        String firstName = new String[]{getString(R.string.amount), getString(R.string.wind_speed), getString(R.string.humidity), getString(R.string.temperature)}[tab];
        String secondName = new String[]{getString(R.string.rain_chance), getString(R.string.gusts), "", getString(R.string.feels_like)}[tab];
        LinearLayout legend = row();
        TextView firstLegend = label((precipitation ? "■ " : "● ") + firstName, 11, HourlyLineChart.PRIMARY);
        if (largeText()) legend.addView(firstLegend, new LinearLayout.LayoutParams(0, -2, 1));
        else legend.addView(firstLegend);
        if (!humidity) {
            TextView secondKey = label("┄ " + secondName, 11, HourlyLineChart.SECONDARY);
            secondKey.setPadding(dp(12), 0, 0, 0);
            if (largeText()) legend.addView(secondKey, new LinearLayout.LayoutParams(0, -2, 1));
            else legend.addView(secondKey);
        }
        String unit = new String[]{"mm", WIND_UNITS[windUnit], "%", fahrenheit ? "°F" : "°C"}[tab];
        TextView units = label(precipitation ? "mm / %" : unit, 11, MUTED);
        units.setGravity(Gravity.END);
        legend.addView(units, largeText() ? new LinearLayout.LayoutParams(-2, -2)
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
            first[i] = wind ? a * WIND_FACTORS[windUnit] : tab == 3 ? displayTemperature(a) : a;
            if (!humidity) {
                double b = value(hourly, secondField, start + i);
                second[i] = wind ? b * WIND_FACTORS[windUnit] : tab == 3 ? displayTemperature(b) : b;
            }
        }

        int scaleWidth = Math.max(dp(24), textWidth(axisLabel, "100.0") + dp(5));
        int screenWidth = dp(getResources().getConfiguration().screenWidthDp);
        int minimumHourWidth = textWidth(axisLabel, clock24 ? "23:00" : "12 PM") + dp(7);
        int hourWidth = Math.max(Math.max(dp(32), minimumHourWidth),
                (screenWidth - 2 * (dp(16) + dp(13)) - scaleWidth * (precipitation ? 2 : 1)) / 8);
        int plotHeight = chartHeight - topGap - legendHeight - legendGap - timeHeight - directionHeight;
        HourlyLineChart plot = new HourlyLineChart(this, first, second, hourWidth, precipitation, humidity,
                precipitation || (wind && windUnit == 2) ? 1 : 0);
        LinearLayout plotRow = row();
        plotRow.setGravity(Gravity.TOP);
        chart.addView(plotRow, new LinearLayout.LayoutParams(-1, 0, 1));
        plotRow.addView(plot.scale(false), new LinearLayout.LayoutParams(scaleWidth, plotHeight));
        HorizontalScrollView viewport = new HorizontalScrollView(this);
        viewport.setHorizontalScrollBarEnabled(false);
        plotRow.addView(viewport, new LinearLayout.LayoutParams(0, -1, 1));
        if (precipitation) plotRow.addView(plot.scale(true), new LinearLayout.LayoutParams(scaleWidth, plotHeight));
        LinearLayout series = column();
        viewport.addView(series);
        series.addView(plot, new LinearLayout.LayoutParams(hourWidth * count, plotHeight));
        LinearLayout hours = row();
        series.addView(hours);
        for (int i = 0; i < count; i++) {
            int index = start + i;
            String hour = index == start && now ? getString(R.string.now) : formatTime(localTime(at(hourly, "time", index)), clock24 ? CLOCK_24 : DETAIL_CLOCK);
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            if (wind) {
                cell.addView(new WindIndicator(this, value(hourly, "wind_direction_10m", index)),
                        new LinearLayout.LayoutParams(dp(22), directionHeight));
            }
            TextView time = label(hour, 10, MUTED);
            time.setGravity(Gravity.CENTER);
            String description = hour + ", " + lineReading(firstName, first[i], unit);
            if (!humidity) description += ", " + lineReading(secondName, second[i], precipitation ? "%" : unit);
            time.setContentDescription(description);
            cell.addView(time, new LinearLayout.LayoutParams(-1, timeHeight));
            hours.addView(cell, new LinearLayout.LayoutParams(hourWidth, -2));
        }
    }

    private String lineReading(String name, double value, String unit) {
        return Double.isFinite(value) ? getString(R.string.chart_reading, name, number(value, "%.1f"), unit)
                : getString(R.string.chart_reading_missing, name);
    }

    private void metrics(String wind, double direction, double humidity, double dewPoint, double uv, double pressure) {
        windTiles(getString(R.string.wind), wind, direction, humidityTile(getString(R.string.humidity), humidity, dewPoint));
        addMetricRow(uvTile(getString(R.string.uv_index), uv), gaugeTile(getString(R.string.pressure), pressureReading(pressure), PRESSURE_UNITS[pressureUnit],
                new PressureGauge(this, pressure, pressureReading(PressureGauge.MIN_HPA),
                        pressureReading(PressureGauge.MAX_HPA)), 60));
    }

    private void windTiles(String title, String speed, double direction, View second) {
        LinearLayout wind = card();
        wind.addView(label(title, 14, MUTED));
        LinearLayout readings = row();
        wind.addView(readings);
        LinearLayout text = column();
        readings.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        text.addView(label(speed, 22, INK));
        if (Double.isFinite(direction)) {
            String[] compass = italian() ? new String[]{"N", "NE", "E", "SE", "S", "SO", "O", "NO"}
                    : new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            int index = Math.floorMod((int) Math.round(direction / 45), compass.length);
            int bearing = Math.floorMod((int) Math.round(direction), 360);
            text.addView(label(getString(R.string.from_bearing, compass[index], bearing), 13, MUTED));
        }
        readings.addView(new WindIndicator(this, direction), new LinearLayout.LayoutParams(dp(46), dp(46)));
        addMetricRow(wind, second);
    }

    private LinearLayout humidityTile(String title, double humidity, double dewPoint) {
        String detail = Double.isFinite(dewPoint) ? getString(R.string.dew_point, temp(dewPoint)) : null;
        return gaugeTile(title, percent(humidity), detail, new HumidityGauge(this, humidity), 38);
    }

    private LinearLayout uvTile(String title, double index) {
        String level = null;
        if (Double.isFinite(index)) {
            int rounded = (int) Math.round(index);
            if (rounded >= 11) level = getString(R.string.uv_extreme);
            else if (rounded >= 8) level = getString(R.string.uv_very_high);
            else if (rounded >= 6) level = getString(R.string.uv_high);
            else if (rounded >= 3) level = getString(R.string.uv_moderate);
            else level = getString(R.string.uv_low);
        }
        return gaugeTile(title, number(index, "%.1f"), level, new UvGauge(this, index), 38);
    }

    private LinearLayout gaugeTile(String title, String value, String detail, View gauge, int gaugeWidth) {
        LinearLayout tile = card();
        tile.addView(label(title, 14, MUTED));
        LinearLayout content = row();
        tile.addView(content, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout text = column();
        content.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        text.addView(label(value, 25, INK));
        if (detail != null) {
            TextView subtitle = label(detail, 13, MUTED);
            subtitle.setPadding(0, dp(3), 0, 0);
            text.addView(subtitle);
        }
        content.addView(gauge, new LinearLayout.LayoutParams(dp(gaugeWidth), dp(58)));
        return tile;
    }

    private String percent(double value) {
        return Double.isFinite(value) ? number(value, "%.0f") + "%" : "—";
    }

    private LinearLayout metric(String title, String value) {
        LinearLayout tile = card();
        tile.addView(label(title, 14, MUTED));
        TextView reading = label(value, 22, INK);
        reading.setPadding(0, dp(8), 0, 0);
        tile.addView(reading);
        return tile;
    }

    private void addMetricRow(View first, View second) {
        first.setMinimumHeight(dp(100));
        second.setMinimumHeight(dp(100));
        if (stackedCards()) {
            addSpaced(page, first, 9);
            addSpaced(page, second, 9);
            return;
        }
        LinearLayout tiles = row();
        tiles.setGravity(Gravity.TOP);
        tiles.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(10);
        tiles.addView(second, params);
        addSpaced(page, tiles, 9);
    }

    private String solarNoon(JSONObject daily, int index) {
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        return formatTime(SunPath.solarNoon(date, longitude, ZoneId.of(data.optString("timezone", "UTC"))), clockFormat());
    }

    private LinearLayout dailySunTimes(JSONObject daily, int index) {
        LinearLayout tile = card();
        tile.addView(label(getString(R.string.sunrise_sunset), 14, MUTED));
        String[] labels = {getString(R.string.sunrise), getString(R.string.solar_noon), getString(R.string.sunset)};
        String[] values = {clock(at(daily, "sunrise", index)), solarNoon(daily, index), clock(at(daily, "sunset", index))};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout line = row();
            line.setPadding(0, dp(5), 0, 0);
            line.addView(label(labels[i], 12, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(label(values[i], 13, INK));
            tile.addView(line);
        }
        return tile;
    }

    private void addSunTime(LinearLayout times, String name, String time) {
        TextView title = label(name, 13, MUTED);
        if (times.getChildCount() > 0) title.setPadding(0, dp(7), 0, 0);
        times.addView(title);
        times.addView(label(time, 19, INK));
    }

    private void sun(JSONObject daily, int index) {
        LinearLayout tile = card();
        tile.addView(label(getString(R.string.sunrise_sunset), 16, INK));
        LocalTime sunrise = localTime(at(daily, "sunrise", index));
        LocalTime sunset = localTime(at(daily, "sunset", index));
        boolean roomy = largeText();
        LinearLayout content = roomy ? column() : row();
        LinearLayout timeRow = roomy ? row() : column();
        content.addView(timeRow, new LinearLayout.LayoutParams(roomy ? -1 : dp(93), -2));
        LinearLayout evening = roomy ? column() : timeRow;
        if (roomy) {
            timeRow.setGravity(Gravity.TOP);
            timeRow.setPadding(0, dp(9), 0, 0);
            LinearLayout morning = column();
            timeRow.addView(morning, new LinearLayout.LayoutParams(0, -2, 1));
            timeRow.addView(evening, new LinearLayout.LayoutParams(0, -2, 1));
            addSunTime(morning, getString(R.string.sunrise), formatTime(sunrise, clockFormat()));
        } else {
            addSunTime(timeRow, getString(R.string.sunrise), formatTime(sunrise, clockFormat()));
        }
        addSunTime(evening, getString(R.string.sunset), formatTime(sunset, clockFormat()));
        TextView daylight = label(daylightDuration(daily, index), 11, MUTED);
        daylight.setPadding(0, dp(5), 0, 0);
        evening.addView(daylight);
        int twilight = SunPath.civilTwilightMinutes(LocalDate.parse(at(daily, "time", index)), latitude);
        LinearLayout chart = column();
        content.addView(chart, new LinearLayout.LayoutParams(roomy ? -1 : 0, -2, roomy ? 0 : 1));
        chart.addView(new SunPath(this, sunrise, sunset,
                localTime(data.optJSONObject("current").optString("time"))),
                new LinearLayout.LayoutParams(-1, dp(90)));
        LinearLayout legend = row();
        legend.setGravity(Gravity.TOP);
        legend.setPadding(dp(5), 0, dp(5), 0);
        String dawn = twilight > 0 && sunrise != null ? formatTime(sunrise.minusMinutes(twilight), clockFormat()) : "—";
        String dusk = twilight > 0 && sunset != null ? formatTime(sunset.plusMinutes(twilight), clockFormat()) : "—";
        legend.addView(label(getString(R.string.dawn) + "\n" + dawn, 12, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        TextView noonLabel = label(getString(R.string.solar_noon) + "\n" + solarNoon(daily, index), 12, MUTED);
        noonLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        legend.addView(noonLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView duskLabel = label(getString(R.string.dusk) + "\n" + dusk, 12, MUTED);
        duskLabel.setGravity(Gravity.END);
        legend.addView(duskLabel, new LinearLayout.LayoutParams(0, -2, 1));
        chart.addView(legend);
        tile.addView(content);
        addUpcomingTimes(tile, daily, index, false);
        addSpaced(page, tile, 9);
    }

    private void moon(JSONObject daily, int index) {
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        double phase = moonPhaseForToday(daily, index, date, zone);
        LinearLayout tile = card();
        tile.addView(label(getString(R.string.moon), 16, INK));
        LinearLayout readings = row();
        readings.setGravity(Gravity.TOP);
        readings.setPadding(0, dp(10), 0, 0);
        LinearLayout phaseDetails = column();
        if (stackedCards()) {
            phaseDetails.setPadding(0, dp(7), 0, 0);
            tile.addView(phaseDetails);
        } else {
            readings.addView(phaseDetails, new LinearLayout.LayoutParams(0, -2, 1.25f));
        }
        if (Double.isFinite(phase)) {
            int position = moonPhaseIndex(phase, date, zone);
            LinearLayout illumination = row();
            MoonPhaseView icon = new MoonPhaseView(this, phase, latitude < 0);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            illumination.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView illuminated = label(percent(100 * MoonPhaseView.illumination(phase)), 15, INK);
            illuminated.setPadding(dp(6), 0, 0, 0);
            illuminated.setContentDescription(getString(R.string.moon_illuminated, illuminated.getText()));
            illumination.addView(illuminated);
            phaseDetails.addView(illumination);
            phaseDetails.addView(label(getString(MOON_PHASE_NAMES[position]), 13, MUTED));
        } else {
            phaseDetails.addView(label(getString(R.string.phase_unavailable), 13, MUTED));
        }
        for (String field : new String[]{"moonrise", "moonset"}) {
            String event = getString(field.equals("moonrise") ? R.string.moonrise : R.string.moonset);
            int eventDay = index;
            String timestamp = at(daily, field, eventDay);
            while (timestamp.length() < 16 && eventDay + 1 < length(daily)) {
                timestamp = at(daily, field, ++eventDay);
            }
            LinearLayout time = column();
            time.addView(label(event, 13, MUTED));
            TextView reading = label(timestamp.length() >= 16 ? clock(timestamp) : getString(R.string.unavailable), 17, INK);
            reading.setPadding(0, dp(4), 0, 0);
            time.addView(reading);
            if (timestamp.length() >= 16) {
                LocalDate eventDate = LocalDate.parse(timestamp.substring(0, 10));
                String dayLabel = eventDate.equals(date) ? getString(R.string.today)
                        : eventDate.equals(date.plusDays(1)) ? getString(R.string.tomorrow)
                        : eventDate.format(shortDate(false));
                TextView when = label(dayLabel, 11, MUTED);
                when.setPadding(0, dp(3), 0, 0);
                time.addView(when);
            }
            readings.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tile.addView(readings);
        DateTimeFormatter format = shortDate(false);
        LinearLayout phases = row();
        phases.setGravity(Gravity.TOP);
        phases.setPadding(0, dp(13), 0, 0);
        for (boolean full : new boolean[]{true, false}) {
            LinearLayout event = column();
            TextView phaseTitle = label(full ? getString(R.string.next_full_moon) : getString(R.string.next_new_moon), 13, MUTED);
            if (stackedCards()) phaseTitle.setMinLines(2);
            event.addView(phaseTitle);
            TextView when = label(LunarPhases.nextDate(date, zone, full).format(format), 16, INK);
            when.setPadding(0, dp(4), 0, 0);
            event.addView(when);
            phases.addView(event, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tile.addView(phases);
        addUpcomingTimes(tile, daily, index, true);
        addSpaced(page, tile, 9);
    }

    private void eclipsesAndTransits() {
        LinearLayout card = card();
        LinearLayout content = column();
        content.setVisibility(View.GONE);
        TextView toggle = button(getString(R.string.eclipses_transits) + "  ▾", () -> {});
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setPadding(dp(1), 0, dp(1), 0);
        if (android.os.Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(getString(R.string.collapsed));
        double lat = latitude;
        double lon = longitude;
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        toggle.setOnClickListener(v -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(open ? getString(R.string.eclipses_transits) + "  ▴" : getString(R.string.eclipses_transits) + "  ▾");
            if (android.os.Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(open ? getString(R.string.expanded) : getString(R.string.collapsed));
            if (!open || content.getChildCount() != 0) return;
            for (boolean lunar : new boolean[]{false, true}) {
                TextView heading = label(lunar ? getString(R.string.lunar_eclipses) : getString(R.string.solar_eclipses), 16, INK);
                heading.setPadding(0, dp(10), 0, dp(4));
                content.addView(heading);
                LinearLayout events = column();
                content.addView(events);
                new EclipseList(events, lunar, lat, lon, zone).showNext();
            }
            TextView source = button(getString(R.string.eclipse_data), () -> startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://opale.imcce.fr/"))));
            source.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            source.setPadding(0, dp(8), 0, 0);
            content.addView(source);
            TextView heading = label(getString(R.string.planetary_transits), 16, INK);
            heading.setPadding(0, dp(14), 0, dp(6));
            content.addView(heading);
            List<TransitCatalog.Event> transits = TransitCatalog.upcoming(lat, lon, Instant.now());
            if (transits.isEmpty()) content.addView(label(getString(R.string.no_transits), 14, MUTED));
            else {
                LinearLayout list = column();
                content.addView(list);
                appendTransits(list, transits, zone, 0);
            }
            TextView transitSource = button("Transit Predictions by Fred Espenak, NASA/GSFC", () ->
                    openAstronomyPage("https://eclipse.gsfc.nasa.gov/transit/transit.html"));
            transitSource.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            transitSource.setPadding(0, dp(4), 0, 0);
            content.addView(transitSource);
        });
        card.addView(toggle);
        card.addView(content);
        page.addView(card);
    }

    private void appendTransits(LinearLayout list, List<TransitCatalog.Event> items, ZoneId zone, int start) {
        int end = Math.min(items.size(), start + ASTRONOMY_PAGE_SIZE);
        for (int i = start; i < end; i++) addTransit(list, items.get(i), zone);
        if (end >= items.size()) return;
        TextView more = button(getString(R.string.more), () -> {});
        more.setContentDescription(getString(R.string.more_transits));
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
            events = column();
            footer = column();
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
            if (shown < goal && requestsLeft > 0) {
                fetch(requestsLeft);
                return;
            }
            if (shown == 0) footer.addView(label(getString(R.string.no_eclipses_until, year - 1), 14, MUTED));
            TextView more = button(getString(R.string.more), this::showNext);
            more.setContentDescription(lunar ? getString(R.string.more_lunar_eclipses) : getString(R.string.more_solar_eclipses));
            footer.addView(more);
        }

        private void fetch(int requestsLeft) {
            footer.addView(loadingStatus(getString(R.string.loading_eclipses)));
            int startYear = year;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return EclipseCatalog.upcoming(lunar, startYear, latitude, longitude, Instant.now());
                } catch (Exception error) {
                    android.util.Log.w("SkyHere", "Eclipse catalog unavailable: " + error, error);
                    return null;
                }
            }).thenAccept(results -> runOnUiThread(() -> {
                if (isDestroyed() || !footer.isAttachedToWindow()) return;
                if (results == null) {
                    footer.removeAllViews();
                    footer.addView(label(getString(R.string.eclipse_data_unavailable), 14, MUTED));
                    footer.addView(button(getString(R.string.retry), () -> fill(requestsLeft)));
                    return;
                }
                year += EclipseCatalog.yearsPerRequest(lunar);
                items.addAll(results);
                fill(requestsLeft - 1);
            }));
        }
    }

    private void addEclipse(LinearLayout parent, EclipseCatalog.Event event, ZoneId zone) {
        LinearLayout entry = column();
        entry.setPadding(dp(11), dp(10), dp(11), dp(10));
        entry.setBackground(background(0xfff1f3f8, 12));
        linkAstronomyCard(entry, event.detailsUrl());
        LinearLayout heading = row();
        EclipseIcon icon = new EclipseIcon(this, event);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconSize.rightMargin = dp(9);
        heading.addView(icon, iconSize);
        LinearLayout title = column();
        title.addView(label(eclipseTypeLabel(event.type), 15, INK));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(astronomyLinkIcon());
        entry.addView(heading);
        ZonedDateTime start = event.start.atZone(zone);
        ZonedDateTime end = event.end.atZone(zone);
        DateTimeFormatter date = shortDate(true);
        TextView when = label(start.format(date), 13, MUTED);
        when.setPadding(0, dp(3), 0, 0);
        title.addView(when);
        String range = start.format(clockFormat()) + " – " + end.format(clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += getString(R.string.next_day_suffix);
        TextView visible = label(getString(R.string.visible_range, range), 14, INK);
        visible.setPadding(0, dp(7), 0, 0);
        entry.addView(visible);
        Duration duration = Duration.between(event.start, event.end);
        long minutes = Math.max(1, Math.round(duration.getSeconds() / 60.0));
        String durationText = durationText(minutes);
        String durationLabel = getString(event.horizonLimited ? R.string.estimated_duration : R.string.duration_value, durationText);
        durationLabel += " · " + getString(event.estimatedPeak ? R.string.best_visible : R.string.peak_time,
                event.maximum.atZone(zone).format(clockFormat()));
        TextView details = label(durationLabel, 13, MUTED);
        details.setPadding(0, dp(3), 0, 0);
        entry.addView(details);
        if (Double.isFinite(event.obscuration)) {
            int disk = event.lunar ? (event.penumbral ? R.string.moon_penumbra : R.string.moon_shadow) : R.string.sun_covered;
            String coverageText = getString(disk, percent(event.obscuration));
            if (event.estimatedPeak) coverageText = getString(R.string.approximate_value, coverageText);
            TextView coverage = label(coverageText, 13, ACCENT);
            coverage.setPadding(0, dp(3), 0, 0);
            entry.addView(coverage);
        }
        addSpaced(parent, entry, 7);
    }

    private void addTransit(LinearLayout parent, TransitCatalog.Event event, ZoneId zone) {
        LinearLayout entry = column();
        entry.setPadding(dp(11), dp(10), dp(11), dp(10));
        entry.setBackground(background(0xfff1f3f8, 12));
        linkAstronomyCard(entry, event.detailsUrl());

        LinearLayout header = row();
        View icon = new View(this) {
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
                canvas.drawCircle(x + radius * .12f, y + radius * .20f,
                        radius * (event.transit.planet.equals("Mercury") ? .10f : .18f), paint);
            }
        };
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconSize.rightMargin = dp(9);
        header.addView(icon, iconSize);
        LinearLayout title = column();
        title.addView(label(getString(R.string.transit_of,
                getString(event.transit.planet.equals("Mercury") ? R.string.mercury : R.string.venus)), 15, INK));
        ZonedDateTime date = event.visibleStart.atZone(zone);
        TextView when = label(date.format(shortDate(true)), 13, MUTED);
        when.setPadding(0, dp(3), 0, 0);
        title.addView(when);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(astronomyLinkIcon());
        entry.addView(header);

        ZonedDateTime start = event.visibleStart.atZone(zone);
        ZonedDateTime end = event.visibleEnd.atZone(zone);
        String range = start.format(clockFormat()) + " – " + end.format(clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += getString(R.string.next_day_suffix);
        TextView visible = label(getString(R.string.estimated_visible_range, range), 14, INK);
        visible.setPadding(0, dp(7), 0, 0);
        entry.addView(visible);
        long minutes = Math.max(1, Math.round(Duration.between(event.visibleStart, event.visibleEnd).getSeconds() / 60.0));
        String duration = durationText(minutes);
        TextView details = label(getString(R.string.estimated_duration, duration), 13, MUTED);
        details.setPadding(0, dp(3), 0, 0);
        entry.addView(details);
        addSpaced(parent, entry, 7);
    }

    private TextView astronomyLinkIcon() {
        TextView link = label("↗", 19, ACCENT);
        link.setContentDescription(getString(R.string.open_in_browser));
        link.setPadding(dp(8), 0, dp(3), 0);
        return link;
    }

    private void linkAstronomyCard(View card, String url) {
        card.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22435e94), null, background(Color.WHITE, 12)));
        card.setOnClickListener(v -> openAstronomyPage(url));
    }

    private void openAstronomyPage(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, getString(R.string.no_browser), Toast.LENGTH_SHORT).show();
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

    private String daylightDuration(JSONObject daily, int index) {
        String rise = at(daily, "sunrise", index);
        String set = at(daily, "sunset", index);
        if (rise.length() < 16 || set.length() < 16) return getString(R.string.daylight_unavailable);
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        long minutes = Duration.between(LocalDateTime.parse(rise).atZone(zone),
                LocalDateTime.parse(set).atZone(zone)).toMinutes();
        if (minutes < 0) return getString(R.string.daylight_unavailable);
        return getString(R.string.daylight_duration, durationText(minutes));
    }

    private void addUpcomingTimes(LinearLayout tile, JSONObject daily, int index, boolean moon) {
        int end = Math.min(length(daily), index + FORECAST_DAYS + 1);
        if (end <= index + 1) return;
        String title = getString(R.string.next_days, end - index - 1);
        LinearLayout table = column();
        table.setVisibility(View.GONE);
        TextView toggle = button(title + "  ▾", () -> {});
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setPadding(0, dp(6), dp(10), dp(6));
        toggle.setOnClickListener(v -> {
            boolean open = table.getVisibility() != View.VISIBLE;
            table.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(title + (open ? "  ▴" : "  ▾"));
            if (android.os.Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(open ? getString(R.string.expanded) : getString(R.string.collapsed));
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(getString(R.string.collapsed));
        tile.addView(toggle);
        tile.addView(table);
        String rise = moon ? "moonrise" : "sunrise";
        String set = moon ? "moonset" : "sunset";
        addEventTableRow(table, getString(R.string.date), moon ? label(getString(R.string.phase), 12, MUTED) : null,
                getString(moon ? R.string.moonrise : R.string.sunrise),
                getString(moon ? R.string.moonset : R.string.sunset), MUTED);
        DateTimeFormatter dateFormat = shortDate(false);
        for (int i = index + 1; i < end; i++) {
            LocalDate day = LocalDate.parse(at(daily, "time", i));
            String date = day.format(dateFormat);
            View phaseIcon = null;
            if (moon) {
                double phase = value(daily, "moon_phase", i);
                if (Double.isFinite(phase)) {
                    ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
                    LinearLayout phaseCell = largeText() ? column() : row();
                    phaseCell.setGravity(Gravity.CENTER);
                    MoonPhaseView glyph = new MoonPhaseView(this, phase, latitude < 0);
                    glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    phaseCell.addView(glyph, new LinearLayout.LayoutParams(dp(21), dp(21)));
                    String amount = percent(100 * MoonPhaseView.illumination(phase));
                    TextView illumination = label(amount, 11, MUTED);
                    if (!largeText()) illumination.setPadding(dp(4), 0, 0, 0);
                    illumination.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    phaseCell.addView(illumination);
                    String phaseName = getString(MOON_PHASE_NAMES[moonPhaseIndex(phase, day, zone)]);
                    phaseCell.setContentDescription(phaseName + ", " + getString(R.string.moon_illuminated, amount));
                    phaseIcon = phaseCell;
                } else {
                    phaseIcon = label("—", 13, MUTED);
                    ((TextView) phaseIcon).setGravity(Gravity.CENTER);
                    phaseIcon.setContentDescription(getString(R.string.phase_unavailable));
                }
            }
            addEventTableRow(table, date, phaseIcon, clock(at(daily, rise, i)),
                    clock(at(daily, set, i)), INK);
        }
    }

    private void addEventTableRow(LinearLayout table, String date, View phase, String rise, String set, int color) {
        LinearLayout line = row();
        line.setPadding(0, dp(5), 0, dp(5));
        line.addView(label(date, 13, color), new LinearLayout.LayoutParams(0, -2, 1.3f));
        if (phase != null) {
            if (phase instanceof TextView) ((TextView) phase).setGravity(Gravity.CENTER);
            line.addView(phase, new LinearLayout.LayoutParams(dp(72), -2));
        }
        for (String time : new String[]{rise, set}) {
            TextView value = label(time, 13, color);
            value.setGravity(Gravity.END);
            line.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        }
        table.addView(line);
    }

    private String windSpeed(double kmh, boolean includeUnit) {
        String reading = number(kmh * WIND_FACTORS[windUnit], windUnit == 2 ? "%.1f" : "%.0f");
        return includeUnit && Double.isFinite(kmh) ? reading + " " + WIND_UNITS[windUnit] : reading;
    }

    private String pressureReading(double hpa) {
        return number(hpa * PRESSURE_FACTORS[pressureUnit], pressureUnit == 2 ? "%,.2f" : "%,.0f");
    }

    private void settings() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = column();
        panel.setBackgroundColor(Color.WHITE);
        dialog.setContentView(panel);

        LinearLayout header = row();
        header.setPadding(dp(20), dp(12), dp(12), dp(10));
        TextView heading = label(getString(R.string.settings), 26, INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = button("×", dialog::dismiss);
        close.setTextSize(24);
        close.setContentDescription(getString(R.string.close_settings));
        header.addView(close);
        panel.addView(header);

        ScrollView scroller = new ScrollView(this);
        panel.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout options = column();
        options.setPadding(dp(20), 0, dp(20), dp(16));
        scroller.addView(options);
        RadioGroup temperatures = unitOption(options, getString(R.string.temperature),
                new String[]{getString(R.string.celsius), getString(R.string.fahrenheit)}, fahrenheit ? 1 : 0);
        RadioGroup winds = unitOption(options, getString(R.string.wind_speed), WIND_UNITS, windUnit);
        RadioGroup pressures = unitOption(options, getString(R.string.pressure), PRESSURE_UNITS, pressureUnit);
        RadioGroup clockFormats = unitOption(options, getString(R.string.time_format),
                new String[]{getString(R.string.clock_12), getString(R.string.clock_24)}, clock24 ? 1 : 0);

        LinearLayout actions = row();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(20), dp(10), dp(20), dp(16));
        actions.addView(button(getString(R.string.cancel), dialog::dismiss));
        TextView apply = button(getString(R.string.apply), () -> {
            fahrenheit = selectedUnit(temperatures) == 1;
            windUnit = selectedUnit(winds);
            pressureUnit = selectedUnit(pressures);
            clock24 = selectedUnit(clockFormats) == 1;
            getPreferences(MODE_PRIVATE).edit().putBoolean("fahrenheit", fahrenheit)
                    .putInt("windUnit", windUnit).putInt("pressureUnit", pressureUnit)
                    .putBoolean("clock24", clock24).apply();
            dialog.dismiss();
            if (data != null) render();
        });
        apply.setBackground(background(ACCENT, 14));
        apply.setTextColor(Color.WHITE);
        apply.setPadding(dp(24), dp(8), dp(24), dp(8));
        LinearLayout.LayoutParams applySize = new LinearLayout.LayoutParams(-2, -2);
        applySize.leftMargin = dp(10);
        actions.addView(apply, applySize);
        panel.addView(actions);

        android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.END | Gravity.TOP);
        window.setWindowAnimations(R.style.SettingsDrawerAnimation);
        dialog.show();
        int width = getResources().getConfiguration().screenWidthDp;
        window.setLayout(dp(Math.min(380, width - (largeText() ? 8 : 32))), -1);
    }

    private RadioGroup unitOption(LinearLayout parent, String name, String[] values, int selected) {
        TextView title = label(name, 15, INK);
        title.setPadding(0, dp(17), 0, dp(8));
        parent.addView(title);
        RadioGroup choices = new RadioGroup(this);
        boolean vertical = largeText();
        choices.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        choices.setId(View.generateViewId());
        title.setLabelFor(choices.getId());
        for (int i = 0; i < values.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(values[i]);
            option.setTextColor(INK);
            option.setTextSize(14);
            option.setGravity(Gravity.CENTER);
            option.setButtonDrawable((android.graphics.drawable.Drawable) null);
            option.setPadding(dp(4), dp(8), dp(4), dp(8));
            StateListDrawable surface = new StateListDrawable();
            surface.addState(new int[]{android.R.attr.state_checked}, background(0xffd9e3f8, 12));
            surface.addState(new int[]{}, background(0xfff1f3f8, 12));
            option.setBackground(surface);
            RadioGroup.LayoutParams size = vertical ? new RadioGroup.LayoutParams(-1, -2)
                    : new RadioGroup.LayoutParams(0, -2, 1);
            if (i > 0) {
                if (vertical) size.topMargin = dp(6);
                else size.leftMargin = dp(6);
            }
            option.setMinHeight(dp(46));
            choices.addView(option, size);
        }
        choices.check(choices.getChildAt(selected).getId());
        parent.addView(choices);
        return choices;
    }

    private static int selectedUnit(RadioGroup choices) {
        return choices.indexOfChild(choices.findViewById(choices.getCheckedRadioButtonId()));
    }

    private void locations() {
        stopLocating();
        choosingLocation = true;
        layout();
        LinearLayout searchBar = row();
        searchBar.setBackground(background(0xffdfe5f0, 13));
        searchBar.addView(button("‹", this::home));
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setTextColor(INK);
        entry.setHintTextColor(MUTED);
        entry.setTextSize(16);
        entry.setBackgroundColor(Color.TRANSPARENT);
        entry.setHint(getString(R.string.search_location));
        entry.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBar.addView(entry, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = button("×", () -> entry.setText(""));
        clear.setContentDescription(getString(R.string.clear_search));
        searchBar.addView(clear);
        clear.setVisibility(View.GONE);
        page.addView(searchBar);

        LinearLayout results = column();
        results.setPadding(0, dp(12), 0, 0);
        page.addView(results);
        LinearLayout shortcuts = column();
        shortcuts.setPadding(0, dp(12), 0, 0);
        page.addView(shortcuts);
        TextView currentTitle = label(getString(R.string.current_location), 18, INK);
        currentTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_current_location, 0, 0, 0);
        currentTitle.setCompoundDrawablePadding(dp(8));
        currentTitle.setPadding(0, 0, 0, dp(8));
        shortcuts.addView(currentTitle);
        LinearLayout currentCard = card();
        currentLocationTitle = label("", 16, INK);
        currentCard.addView(currentLocationTitle);
        currentLocationWeather = label("", 13, MUTED);
        currentLocationWeather.setPadding(0, dp(5), 0, 0);
        currentCard.addView(currentLocationWeather);
        updateCurrentLocationShortcut();
        currentCard.setOnClickListener(v -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(entry.getWindowToken(), 0);
            if (usingCurrentLocation && data != null) home();
            else currentLocation(true);
        });
        shortcuts.addView(currentCard);
        TextView savedTitle = label(getString(R.string.saved_locations), 18, INK);
        savedTitle.setPadding(0, dp(16), 0, dp(8));
        shortcuts.addView(savedTitle);
        LinearLayout savedList = column();
        shortcuts.addView(savedList);
        showSaved(savedList);
        entry.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                if (pendingSearch != null) main.removeCallbacks(pendingSearch);
                String query = text.toString().trim();
                clear.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
                shortcuts.setVisibility(query.isEmpty() ? View.VISIBLE : View.GONE);
                results.removeAllViews();
                if (query.length() < 2) return;
                pendingSearch = () -> search(entry, results, query, savedList);
                main.postDelayed(pendingSearch, 300);
            }
        });
        entry.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            if (pendingSearch != null) main.removeCallbacks(pendingSearch);
            String query = entry.getText().toString().trim();
            if (query.length() >= 2) search(entry, results, query, savedList);
            return true;
        });
        entry.requestFocus();
        entry.post(() -> {
            if (entry.isAttachedToWindow()) getSystemService(InputMethodManager.class).showSoftInput(entry, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void updateCurrentLocationShortcut() {
        if (currentLocationTitle == null) return;
        currentLocationTitle.setText(devicePlace == null ? getString(R.string.use_current_location) : devicePlace);
        currentLocationWeather.setVisibility(deviceWeather == null ? View.GONE : View.VISIBLE);
        if (deviceWeather != null) {
            currentLocationWeather.setText(temp(deviceWeather.optDouble("temperature_2m"))
                    + " · " + description(deviceWeather.optInt("weather_code")));
        }
    }

    private void search(EditText entry, LinearLayout results, String query, LinearLayout savedList) {
        if (!results.isAttachedToWindow()) return;
        results.removeAllViews();
        results.addView(loadingStatus(getString(R.string.searching)));
        network.execute(() -> {
            try {
                JSONArray items = OpenMeteo.search(query, appLocale().getLanguage()).optJSONArray("results");
                runOnUiThread(() -> {
                    if (!results.isAttachedToWindow() || !query.equals(entry.getText().toString().trim())) return;
                    results.removeAllViews();
                    if (items == null || items.length() == 0) results.addView(label(getString(R.string.no_locations_found), 15, MUTED));
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        locationRow(results, items.optJSONObject(i), false, savedList);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!results.isAttachedToWindow() || !query.equals(entry.getText().toString().trim())) return;
                    results.removeAllViews();
                    results.addView(label(getString(R.string.search_unavailable), 15, MUTED));
                });
            }
        });
    }

    private JSONArray saved() {
        try { return new JSONArray(getPreferences(MODE_PRIVATE).getString("saved", "[]")); }
        catch (org.json.JSONException error) { return new JSONArray(); }
    }

    private void showSaved(LinearLayout parent) {
        JSONArray items = saved();
        if (items.length() == 0) parent.addView(label(getString(R.string.no_saved_locations), 15, MUTED));
        for (int i = 0; i < items.length(); i++) locationRow(parent, items.optJSONObject(i), true, parent);
    }

    private void locationRow(LinearLayout parent, JSONObject item, boolean savedItem, LinearLayout savedList) {
        String name = item.optString("name");
        String placeDetails = item.optString("admin1");
        String country = item.optString("country");
        String countryCode = item.optString("country_code");
        if (countryCode.length() == 2) country = new Locale("", countryCode).getDisplayCountry(appLocale());
        if (!country.isEmpty() && !country.equals(placeDetails)) {
            placeDetails = placeDetails.isEmpty() ? country : placeDetails + ", " + country;
        }
        LinearLayout line = row();
        line.setBackground(background(Color.WHITE, 12));
        line.setPadding(dp(12), dp(5), dp(8), dp(5));
        LinearLayout open = column();
        open.setPadding(0, dp(7), dp(8), dp(7));
        open.addView(label(name, 16, INK));
        if (!placeDetails.isEmpty()) {
            TextView detail = label(placeDetails, 13, MUTED);
            detail.setPadding(0, dp(3), 0, 0);
            open.addView(detail);
        }
        open.setOnClickListener(v -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(open.getWindowToken(), 0);
            locationNotice = null;
            usingCurrentLocation = false;
            place = name;
            latitude = item.optDouble("latitude");
            longitude = item.optDouble("longitude");
            getPreferences(MODE_PRIVATE).edit().putString("place", place)
                    .putString("latitude", Double.toString(latitude)).putString("longitude", Double.toString(longitude)).apply();
            load();
        });
        line.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        long id = item.optLong("id");
        JSONArray previous = saved();
        boolean alreadySaved = false;
        for (int i = 0; i < previous.length(); i++) {
            if (previous.optJSONObject(i).optLong("id") == id) alreadySaved = true;
        }
        TextView toggle = button(savedItem ? getString(R.string.remove) : alreadySaved ? getString(R.string.saved) : getString(R.string.save), () -> {});
        toggle.setEnabled(savedItem || !alreadySaved);
        toggle.setOnClickListener(v -> {
            JSONArray stored = saved();
            JSONArray updated = new JSONArray();
            for (int i = 0; i < stored.length(); i++) {
                JSONObject other = stored.optJSONObject(i);
                if (other.optLong("id") != id) updated.put(other);
            }
            if (!savedItem) {
                updated.put(item);
                toggle.setText(getString(R.string.saved));
                toggle.setEnabled(false);
            }
            getPreferences(MODE_PRIVATE).edit().putString("saved", updated.toString()).apply();
            savedList.removeAllViews();
            showSaved(savedList);
        });
        line.addView(toggle);
        addSpaced(parent, line, 6);
    }

    private void home() {
        day = -1;
        choosingLocation = false;
        if (data == null) { load(); return; }
        render();
        ScrollView view = scroll;
        view.post(() -> view.scrollTo(0, homeScroll));
    }

    @Override public void onBackPressed() {
        if (day >= 0 || choosingLocation) home();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        stopLocating();
        if (pendingSearch != null) main.removeCallbacks(pendingSearch);
        network.shutdownNow();
        super.onDestroy();
    }

    private static double remainingMaximum(JSONObject hourly, String key, int start, String date) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (int i = start; i < length(hourly) && at(hourly, "time", i).startsWith(date); i++) {
            double candidate = value(hourly, key, i);
            if (Double.isFinite(candidate)) maximum = Math.max(maximum, candidate);
        }
        return maximum;
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
        if (index == 0) return getString(R.string.today);
        return LocalDate.parse(at(daily, "time", index)).format(DateTimeFormatter.ofPattern(italian() ? "EEEE d MMM" : "EEEE, MMM d", appLocale()));
    }

    private String range(JSONObject daily, int index) {
        return temp(value(daily, "temperature_2m_max", index)) + "/" + temp(value(daily, "temperature_2m_min", index));
    }

    private String temp(double celsius) {
        return number(displayTemperature(celsius), "%.0f") + "°";
    }

    private double displayTemperature(double celsius) {
        return fahrenheit ? celsius * 1.8 + 32 : celsius;
    }

    private String number(double value, String format) {
        return Double.isFinite(value) ? String.format(appLocale(), format, value) : "—";
    }

    private String rainChance(JSONObject data, String key, int index) {
        double chance = value(data, key, index);
        return chance > 0 ? number(chance, "%.0f") + "%" : "";
    }

    private String clock(String timestamp) {
        return formatTime(localTime(timestamp), clockFormat());
    }

    private DateTimeFormatter clockFormat() {
        return clock24 ? CLOCK_24 : CLOCK;
    }

    private static String formatTime(LocalTime time, DateTimeFormatter format) {
        return time == null ? "—" : time.format(format);
    }

    private static LocalTime localTime(String timestamp) {
        return timestamp.length() >= 16 ? LocalTime.parse(timestamp.substring(11, 16)) : null;
    }
    private static int length(JSONObject data) { return data.optJSONArray("time").length(); }
    private static String at(JSONObject data, String key, int index) { return data.optJSONArray(key).optString(index); }
    private static double value(JSONObject data, String key, int index) {
        JSONArray array = data.optJSONArray(key);
        return array == null ? Double.NaN : array.optDouble(index);
    }

    private static final char LIGHT_RAIN = '\uE000';
    private static final char MODERATE_RAIN = '\uE001';
    private static final char HEAVY_RAIN = '\uE002';

    private static String symbol(int code, boolean night) {
        if (code == 0) return night ? "🌙" : "☀️";
        if (code < 3) return night ? "☁️" : "🌤️";
        if (code == 3) return "☁️";
        if (code <= 48) return "🌫️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        if (code >= 95) return "⛈️";
        if (code == 65 || code == 67 || code == 82) return String.valueOf(HEAVY_RAIN);
        if (code == 63 || code == 57 || code == 81) return String.valueOf(MODERATE_RAIN);
        return String.valueOf(LIGHT_RAIN);
    }

    private String description(int code) {
        switch (code) {
            case 0: return getString(R.string.clear_weather);
            case 1: return getString(R.string.mostly_clear);
            case 2: return getString(R.string.partly_cloudy);
            case 3: return getString(R.string.cloudy);
            case 45: case 48: return getString(R.string.fog);
            case 51: case 53: case 55: return getString(R.string.drizzle);
            case 56: case 57: return getString(R.string.freezing_drizzle);
            case 61: return getString(R.string.light_rain);
            case 63: return getString(R.string.moderate_rain);
            case 65: return getString(R.string.heavy_rain);
            case 66: case 67: return getString(R.string.freezing_rain);
            case 71: case 73: case 75: case 77: case 85: case 86: return getString(R.string.snow);
            case 80: return getString(R.string.light_showers);
            case 81: return getString(R.string.moderate_showers);
            case 82: return getString(R.string.heavy_showers);
            case 95: case 96: case 99: return getString(R.string.thunderstorms);
            default: return getString(R.string.conditions_unavailable);
        }
    }

    private String eclipseTypeLabel(String type) {
        switch (type) {
            case "Partial solar eclipse": return getString(R.string.eclipse_partial_solar);
            case "Annular solar eclipse": return getString(R.string.eclipse_annular_solar);
            case "Total solar eclipse": return getString(R.string.eclipse_total_solar);
            case "Hybrid solar eclipse": return getString(R.string.eclipse_hybrid_solar);
            case "Partial lunar eclipse": return getString(R.string.eclipse_partial_lunar);
            case "Total lunar eclipse": return getString(R.string.eclipse_total_lunar);
            case "Penumbral lunar eclipse": return getString(R.string.eclipse_penumbral_lunar);
            default: return type;
        }
    }

    private String durationText(long minutes) {
        return minutes >= 60 ? getString(R.string.duration_hour_min, minutes / 60, minutes % 60)
                : getString(R.string.duration_minutes, minutes);
    }

    private Locale appLocale() {
        return Locale.forLanguageTag(getString(R.string.app_language_tag));
    }

    private boolean italian() {
        return appLocale().equals(Locale.ITALY);
    }

    private DateTimeFormatter shortDate(boolean year) {
        String pattern = italian() ? "EEE d MMM" : "EEE, MMM d";
        if (year) pattern += italian() ? " yyyy" : ", yyyy";
        return DateTimeFormatter.ofPattern(pattern, appLocale());
    }

    private boolean largeText() {
        android.content.res.Configuration configuration = getResources().getConfiguration();
        return configuration.fontScale >= 1.25f || configuration.screenWidthDp < 330;
    }

    private boolean stackedCards() {
        android.content.res.Configuration configuration = getResources().getConfiguration();
        return configuration.fontScale >= 1.55f || configuration.screenWidthDp < 330;
    }

    private int textWidth(TextView view, String text) {
        return (int) Math.ceil(view.getPaint().measureText(text));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card() {
        LinearLayout view = column();
        view.setBackground(background(Color.WHITE, 16));
        view.setPadding(dp(13), dp(13), dp(13), dp(13));
        return view;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private LinearLayout loadingStatus(String message) {
        LinearLayout status = row();
        status.setPadding(dp(4), dp(12), dp(4), dp(8));
        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        status.addView(spinner, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView text = label(message, 16, MUTED);
        text.setPadding(dp(10), 0, 0, 0);
        text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.addView(text);
        return status;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setTextSize(Math.max(10f, size * 0.95f));
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setTextColor(color);
        setLabelText(view, text);
        return view;
    }

    private void setLabelText(TextView view, String text) {
        android.text.SpannableString decorated = null;
        for (int i = 0; i < text.length(); i++) {
            char marker = text.charAt(i);
            if (marker < LIGHT_RAIN || marker > HEAVY_RAIN) continue;
            if (decorated == null) decorated = new android.text.SpannableString(text);
            int resource = marker == LIGHT_RAIN ? R.drawable.ic_rain_light
                    : marker == MODERATE_RAIN ? R.drawable.ic_rain_moderate : R.drawable.ic_rain_heavy;
            android.graphics.drawable.Drawable icon = getDrawable(resource);
            int pixels = Math.round(view.getTextSize() * 1.05f);
            icon.setBounds(0, 0, pixels, pixels);
            decorated.setSpan(new android.text.style.ImageSpan(icon, android.text.style.ImageSpan.ALIGN_BASELINE),
                    i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(decorated == null ? text : decorated);
        if (decorated != null) {
            view.setContentDescription(text.replace(String.valueOf(LIGHT_RAIN), getString(R.string.light_rain))
                    .replace(String.valueOf(MODERATE_RAIN), getString(R.string.moderate_rain))
                    .replace(String.valueOf(HEAVY_RAIN), getString(R.string.heavy_rain)));
        }
    }

    private TextView button(String text, Runnable click) {
        TextView view = label(text, 15, ACCENT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setMinHeight(dp(44));
        view.setOnClickListener(v -> click.run());
        return view;
    }
    private TextView header(String name, Runnable click) {
        TextView header = button(name, click);
        header.setTextSize(20);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setSingleLine();
        header.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(background(0xffdfe5f0, 13));
        header.setPadding(dp(14), dp(9), dp(14), dp(9));
        return header;
    }
    private void title(String text) {
        TextView title = label(text, 19, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, dp(23), 0, dp(11));
        page.addView(title);
    }
    private void addSpaced(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(gap);
        parent.addView(child, size);
    }
    private LinearLayout carousel(LinearLayout parent) {
        HorizontalScrollView view = new HorizontalScrollView(this);
        view.setHorizontalScrollBarEnabled(false);
        LinearLayout content = row();
        view.addView(content);
        parent.addView(view);
        return content;
    }
}
