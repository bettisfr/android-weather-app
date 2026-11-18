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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter DETAIL_CLOCK = DateTimeFormatter.ofPattern("h a", Locale.US);
    private static final String[] MOON_PHASE_NAMES = {"New moon", "Waxing crescent", "First quarter", "Waxing gibbous",
            "Full moon", "Waning gibbous", "Last quarter", "Waning crescent"};
    private static final String[] MOON_PHASE_ICONS = {"🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘"};
    private static final int FORECAST_DAYS = 10;
    private static final int LOCATION_PERMISSION = 1;
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
        clock24 = prefs.getBoolean("clock24", false);
        windUnit = prefs.getInt("windUnit", 0);
        pressureUnit = prefs.getInt("pressureUnit", 0);
        currentLocation(!prefs.getBoolean("askedForLocation", false));
    }

    private void currentLocation(boolean askPermission) {
        stopLocating();
        forecastRequest++;
        if (!hasLocationPermission()) {
            if (askPermission) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("askedForLocation", true).apply();
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            } else {
                fallbackLocation("Location permission is off");
            }
            return;
        }

        choosingLocation = false;
        day = -1;
        layout();
        page.addView(header("Current location", this::locations));
        page.addView(loadingStatus("Finding your location…"));
        LocationManager manager = getSystemService(LocationManager.class);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (locationListener != this) return;
                stopLocating();
                latitude = location.getLatitude();
                longitude = location.getLongitude();
                place = "Current location";
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
            locationTimeout = () -> fallbackLocation("Current location unavailable");
            main.postDelayed(locationTimeout, 12000);
        } else {
            fallbackLocation("Current location unavailable");
        }
    }

    private void resolveLocationName(double lat, double lon) {
        if (!Geocoder.isPresent()) return;
        Geocoder geocoder = new Geocoder(this, Locale.US);
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
            placeHeader.setContentDescription("Current location: " + place);
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
        page.addView(loadingStatus("Loading forecast…"));
        final int request = ++forecastRequest;
        final double lat = latitude;
        final double lon = longitude;
        network.execute(() -> {
            try {
                JSONObject response = OpenMeteo.forecast(lat, lon);
                runOnUiThread(() -> {
                    if (request != forecastRequest || isDestroyed()) return;
                    data = response;
                    if (usingCurrentLocation) {
                        deviceWeather = response.optJSONObject("current");
                        updateCurrentLocationShortcut();
                    }
                    if (!choosingLocation) render();
                });
            } catch (Exception error) {
                android.util.Log.w("MeteoLocale", "Forecast request failed", error);
                runOnUiThread(() -> {
                    if (request != forecastRequest || isDestroyed() || choosingLocation) return;
                    layout();
                    page.addView(placeTitle());
                    page.addView(label("Forecast unavailable", 22, INK));
                    page.addView(button("Retry", this::load));
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
            toolbar.addView(header("‹  10-day forecast", this::home));
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
            top.addView(button("Units", this::units));
            page.addView(top);
            if (locationNotice != null) page.addView(label(locationNotice, 13, MUTED));
            title("Now");
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
            TextView feelsLike = label("Feels like " + temp(current.optDouble("apparent_temperature")), 14, MUTED);
            feelsLike.setPadding(0, dp(6), 0, 0);
            feelsLike.setGravity(Gravity.END);
            summary.addView(feelsLike);
            hero.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
            page.addView(hero);
            TextView extremes = label("High " + temp(value(daily, "temperature_2m_max", 0)) + " · Low " + temp(value(daily, "temperature_2m_min", 0)), 15, MUTED);
            addSpaced(page, extremes, 10);
            forecast(hourly, todayHour, todayHour + 24, true);
            title("10-day forecast");
            for (int index = 0; index < Math.min(FORECAST_DAYS, length(daily)); index++) {
                final int selected = index;
                int weather = index == 0 ? todayWeather : (int) value(daily, "weather_code", index);
                LinearLayout tile = row();
                tile.setBackground(background(Color.WHITE, 12));
                tile.setPadding(dp(14), dp(11), dp(14), dp(11));
                tile.addView(label(date(daily, index), 16, INK), new LinearLayout.LayoutParams(0, -2, 1));
                double probability = index == 0 ? rainRemaining : value(daily, "precipitation_probability_max", index);
                TextView chance = label(probability > 0 ? percent(probability) : "", 14, ACCENT);
                chance.setGravity(Gravity.END);
                tile.addView(chance, new LinearLayout.LayoutParams(dp(40), -2));
                TextView dayIcon = label(symbol(weather, false), 23, INK);
                dayIcon.setGravity(Gravity.CENTER);
                tile.addView(dayIcon, new LinearLayout.LayoutParams(dp(38), -2));
                TextView temperatures = label(range(daily, index), 16, INK);
                temperatures.setGravity(Gravity.END);
                tile.addView(temperatures, new LinearLayout.LayoutParams(dp(fahrenheit ? 83 : 70), -2));
                tile.setOnClickListener(v -> {
                    homeScroll = scroll.getScrollY();
                    day = selected;
                    render();
                });
                addSpaced(page, tile, 5);
            }
            title("Current conditions");
            metrics(windSpeed(current.optDouble("wind_speed_10m"), true), current.optDouble("wind_direction_10m"),
                    current.optDouble("relative_humidity_2m"), value(hourly, "dew_point_2m", todayHour),
                    value(hourly, "uv_index", todayHour),
                    current.optDouble("surface_pressure"));
            sun(daily, 0);
            moon(daily, 0);
            details(hourly, daily, 0, todayHour, todayHour + 24, true);
            page.addView(button("Weather data · Open-Meteo", () -> startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://open-meteo.com/")))));
        } else {
            LinearLayout dates = carousel(page);
            View selectedChip = null;
            for (int index = 0; index < Math.min(FORECAST_DAYS, length(daily)); index++) {
                final int selected = index;
                String key = index == 0 ? "Today" : LocalDate.parse(at(daily, "time", index)).format(DateTimeFormatter.ofPattern("EEE", Locale.US));
                TextView chip = button(key + "\n" + symbol(index == 0 ? todayWeather : (int) value(daily, "weather_code", index), false) + "\n" + range(daily, index), () -> {
                    day = selected;
                    render();
                });
                chip.setBackground(background(index == day ? 0xffd9e3f8 : Color.WHITE, 10));
                chip.setPadding(dp(8), dp(11), dp(8), dp(11));
                chip.setLineSpacing(dp(4), 1f);
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(74), dp(96));
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
            title("Daily conditions");
            windTiles("Max wind", windSpeed(value(daily, "wind_speed_10m_max", day), true),
                    value(daily, "wind_direction_10m_dominant", day),
                    humidityTile("Average humidity", value(daily, "relative_humidity_2m_mean", day), Double.NaN));
            addMetricRow(uvTile("Max UV index", value(daily, "uv_index_max", day)),
                    dailySunTimes(daily, day));
            details(hourly, daily, day, start, end, day == 0);
        }
    }

    private void forecast(JSONObject hourly, int start, int end, boolean now) {
        title("Hourly forecast");
        LinearLayout box = card();
        LinearLayout hours = carousel(box);
        for (int i = start; i < Math.min(end, length(hourly)); i++) {
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(9), 0, dp(7));
            hours.addView(cell, new LinearLayout.LayoutParams(dp(67), -2));
            cell.addView(label(temp(value(hourly, "temperature_2m", i)), 21, INK), new LinearLayout.LayoutParams(-2, -2));
            TextView chance = label(rainChance(hourly, "precipitation_probability", i), 13, ACCENT);
            chance.setGravity(Gravity.CENTER);
            cell.addView(chance, new LinearLayout.LayoutParams(-1, dp(24)));
            TextView weather = label(symbol((int) value(hourly, "weather_code", i), value(hourly, "is_day", i) == 0), 26, INK);
            weather.setGravity(Gravity.CENTER);
            cell.addView(weather, new LinearLayout.LayoutParams(-1, dp(38)));
            cell.addView(label(now && i == start ? "Now" : clock(at(hourly, "time", i)), 13, MUTED), new LinearLayout.LayoutParams(-2, -2));
        }
        page.addView(box);
    }

    private void details(JSONObject hourly, JSONObject daily, int index, int start, int end, boolean now) {
        title("Hourly details");
        LinearLayout box = card();
        LinearLayout tabs = row();
        box.addView(tabs);
        LinearLayout body = column();
        box.addView(body);
        String[] titles = {"Precipitation", "Wind", "Humidity", "Temperature"};
        TextView[] buttons = new TextView[titles.length];
        java.util.function.IntConsumer select = tab -> {
            for (int i = 0; i < buttons.length; i++) buttons[i].setBackground(background(i == tab ? 0xffd9e3f8 : 0xfff1f3f8, 8));
            body.removeAllViews();
            LinearLayout summary = row();
            summary.setPadding(0, dp(14), 0, 0);
            body.addView(summary);
            String date = at(daily, "time", index);
            if (tab == 0) {
                detailSummary(summary, "Daily total", number(value(daily, "precipitation_sum", index), "%.1f") + " mm");
            } else if (tab == 1) {
                detailSummary(summary, "Daily high", windSpeed(value(daily, "wind_speed_10m_max", index), true));
                detailSummary(summary, "Daily average", windSpeed(hourlyDaySummary(hourly, "wind_speed_10m", date, false), true));
            } else if (tab == 2) {
                detailSummary(summary, "Daily high", percent(hourlyDaySummary(hourly, "relative_humidity_2m", date, true)));
                detailSummary(summary, "Daily average", percent(value(daily, "relative_humidity_2m_mean", index)));
            } else {
                detailSummary(summary, "Daily high", temp(value(daily, "temperature_2m_max", index)));
                detailSummary(summary, "Daily low", temp(value(daily, "temperature_2m_min", index)));
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
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(36), i == 0 || i == 3 ? 1.25f : 1f);
            if (i > 0) size.leftMargin = dp(4);
            tabs.addView(buttons[i], size);
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
        int legendHeight = dp(19);
        int legendGap = dp(5);
        int timeHeight = dp(20);
        int directionHeight = wind ? dp(25) : 0;
        chart.setPadding(0, topGap, 0, 0);
        body.addView(chart, new LinearLayout.LayoutParams(-1, dp(DETAIL_CHART_HEIGHT_DP)));
        String firstName = new String[]{"Amount", "Wind speed", "Humidity", "Temperature"}[tab];
        String secondName = new String[]{"Rain chance", "Gusts", "", "Feels like"}[tab];
        LinearLayout legend = row();
        legend.addView(label((precipitation ? "■ " : "● ") + firstName, 11, HourlyLineChart.PRIMARY));
        if (!humidity) {
            TextView secondKey = label("┄ " + secondName, 11, HourlyLineChart.SECONDARY);
            secondKey.setPadding(dp(12), 0, 0, 0);
            legend.addView(secondKey);
        }
        String unit = new String[]{"mm", WIND_UNITS[windUnit], "%", fahrenheit ? "°F" : "°C"}[tab];
        TextView units = label(precipitation ? "mm / %" : unit, 11, MUTED);
        units.setGravity(Gravity.END);
        legend.addView(units, new LinearLayout.LayoutParams(0, -2, 1));
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

        int scaleWidth = dp(24);
        int screenWidth = dp(getResources().getConfiguration().screenWidthDp);
        int hourWidth = Math.max(dp(32), (screenWidth - 2 * (dp(16) + dp(13)) - scaleWidth * (precipitation ? 2 : 1)) / 8);
        int plotHeight = dp(DETAIL_CHART_HEIGHT_DP) - topGap - legendHeight - legendGap - timeHeight - directionHeight;
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
            String hour = index == start && now ? "Now" : formatTime(localTime(at(hourly, "time", index)), clock24 ? CLOCK_24 : DETAIL_CLOCK);
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
        return Double.isFinite(value) ? name + " " + number(value, "%.1f") + " " + unit : name + " unavailable";
    }

    private void metrics(String wind, double direction, double humidity, double dewPoint, double uv, double pressure) {
        windTiles("Wind", wind, direction, humidityTile("Humidity", humidity, dewPoint));
        addMetricRow(uvTile("UV index", uv), gaugeTile("Pressure", pressureReading(pressure), PRESSURE_UNITS[pressureUnit],
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
            String[] compass = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            int index = Math.floorMod((int) Math.round(direction / 45), compass.length);
            int bearing = Math.floorMod((int) Math.round(direction), 360);
            text.addView(label("From " + compass[index] + " · " + bearing + "°", 13, MUTED));
        }
        readings.addView(new WindIndicator(this, direction), new LinearLayout.LayoutParams(dp(46), dp(46)));
        addMetricRow(wind, second);
    }

    private LinearLayout humidityTile(String title, double humidity, double dewPoint) {
        String detail = Double.isFinite(dewPoint) ? "Dew point " + temp(dewPoint) : null;
        return gaugeTile(title, percent(humidity), detail, new HumidityGauge(this, humidity), 38);
    }

    private LinearLayout uvTile(String title, double index) {
        String level = null;
        if (Double.isFinite(index)) {
            int rounded = (int) Math.round(index);
            if (rounded >= 11) level = "Extreme";
            else if (rounded >= 8) level = "Very high";
            else if (rounded >= 6) level = "High";
            else if (rounded >= 3) level = "Moderate";
            else level = "Low";
        }
        return gaugeTile(title, number(index, "%.1f"), level, new UvGauge(this, index), 38);
    }

    private LinearLayout gaugeTile(String title, String value, String detail, View gauge, int gaugeWidth) {
        LinearLayout tile = card();
        tile.addView(label(title, 14, MUTED));
        LinearLayout content = row();
        tile.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout text = column();
        content.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        text.addView(label(value, 25, INK));
        if (detail != null) {
            TextView subtitle = label(detail, 13, MUTED);
            subtitle.setPadding(0, dp(3), 0, 0);
            text.addView(subtitle);
        }
        content.addView(gauge, new LinearLayout.LayoutParams(dp(gaugeWidth), -1));
        return tile;
    }

    private static String percent(double value) {
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
        LinearLayout tiles = row();
        tiles.addView(first, new LinearLayout.LayoutParams(0, dp(100), 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(100), 1);
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
        tile.addView(label("Sunrise & sunset", 14, MUTED));
        String[] labels = {"Sunrise", "Solar noon", "Sunset"};
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
        tile.addView(label("Sunrise & sunset", 16, INK));
        LocalTime sunrise = localTime(at(daily, "sunrise", index));
        LocalTime sunset = localTime(at(daily, "sunset", index));
        LinearLayout content = row();
        LinearLayout times = column();
        content.addView(times, new LinearLayout.LayoutParams(dp(93), -2));
        addSunTime(times, "Sunrise", formatTime(sunrise, clockFormat()));
        addSunTime(times, "Sunset", formatTime(sunset, clockFormat()));
        TextView daylight = label(daylightDuration(daily, index), 11, MUTED);
        daylight.setPadding(0, dp(5), 0, 0);
        times.addView(daylight);
        int twilight = SunPath.civilTwilightMinutes(LocalDate.parse(at(daily, "time", index)), latitude);
        LinearLayout chart = column();
        content.addView(chart, new LinearLayout.LayoutParams(0, -2, 1));
        chart.addView(new SunPath(this, sunrise, sunset,
                localTime(data.optJSONObject("current").optString("time"))),
                new LinearLayout.LayoutParams(-1, dp(90)));
        LinearLayout legend = row();
        legend.setPadding(dp(5), 0, dp(5), 0);
        String dawn = twilight > 0 && sunrise != null ? formatTime(sunrise.minusMinutes(twilight), clockFormat()) : "—";
        String dusk = twilight > 0 && sunset != null ? formatTime(sunset.plusMinutes(twilight), clockFormat()) : "—";
        legend.addView(label("Dawn\n" + dawn, 12, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        TextView noonLabel = label("Solar noon\n" + solarNoon(daily, index), 12, MUTED);
        noonLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        legend.addView(noonLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView duskLabel = label("Dusk\n" + dusk, 12, MUTED);
        duskLabel.setGravity(Gravity.END);
        legend.addView(duskLabel, new LinearLayout.LayoutParams(0, -2, 1));
        chart.addView(legend);
        tile.addView(content);
        addUpcomingTimes(tile, daily, index, false);
        addSpaced(page, tile, 9);
    }

    private void moon(JSONObject daily, int index) {
        double phase = value(daily, "moon_phase", index);

        LinearLayout tile = card();
        tile.addView(label("Moon", 16, INK));
        LinearLayout readings = row();
        readings.setGravity(Gravity.TOP);
        readings.setPadding(0, dp(10), 0, 0);
        LinearLayout phaseDetails = column();
        readings.addView(phaseDetails, new LinearLayout.LayoutParams(0, -2, 1.25f));
        if (Double.isFinite(phase)) {
            int position = moonPhaseIndex(phase);
            TextView icon = label(MOON_PHASE_ICONS[position], 27, INK);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            phaseDetails.addView(icon);
            phaseDetails.addView(label(MOON_PHASE_NAMES[position], 13, MUTED));
        } else {
            phaseDetails.addView(label("Phase unavailable", 13, MUTED));
        }
        for (String event : new String[]{"Moonrise", "Moonset"}) {
            String field = event.toLowerCase(Locale.US);
            int eventDay = index;
            String timestamp = at(daily, field, eventDay);
            while (timestamp.length() < 16 && eventDay + 1 < length(daily)) {
                timestamp = at(daily, field, ++eventDay);
            }
            LinearLayout time = column();
            time.addView(label(event, 13, MUTED));
            TextView reading = label(timestamp.length() >= 16 ? clock(timestamp) : "Unavailable", 17, INK);
            reading.setPadding(0, dp(4), 0, 0);
            time.addView(reading);
            if (timestamp.length() >= 16 && eventDay > index) {
                LocalDate eventDate = LocalDate.parse(timestamp.substring(0, 10));
                LocalDate shownDate = LocalDate.parse(at(daily, "time", index));
                String date = eventDate.equals(shownDate.plusDays(1)) ? "Tomorrow"
                        : eventDate.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US));
                TextView when = label(date, 11, MUTED);
                when.setPadding(0, dp(3), 0, 0);
                time.addView(when);
            }
            readings.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tile.addView(readings);
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        DateTimeFormatter format = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);
        LinearLayout phases = row();
        phases.setPadding(0, dp(13), 0, 0);
        for (boolean full : new boolean[]{true, false}) {
            LinearLayout event = column();
            event.addView(label(full ? "Next full moon" : "Next new moon", 13, MUTED));
            TextView when = label(LunarPhases.nextDate(date, zone, full).format(format), 16, INK);
            when.setPadding(0, dp(4), 0, 0);
            event.addView(when);
            phases.addView(event, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tile.addView(phases);
        addUpcomingTimes(tile, daily, index, true);
        addSpaced(page, tile, 9);
    }

    private static int moonPhaseIndex(double phase) {
        return Math.floorMod((int) Math.round(phase * MOON_PHASE_NAMES.length), MOON_PHASE_NAMES.length);
    }

    private String daylightDuration(JSONObject daily, int index) {
        String rise = at(daily, "sunrise", index);
        String set = at(daily, "sunset", index);
        if (rise.length() < 16 || set.length() < 16) return "Daylight —";
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        long minutes = Duration.between(LocalDateTime.parse(rise).atZone(zone),
                LocalDateTime.parse(set).atZone(zone)).toMinutes();
        if (minutes < 0) return "Daylight —";
        return String.format(Locale.US, "Daylight %dh %02dm", minutes / 60, minutes % 60);
    }

    private void addUpcomingTimes(LinearLayout tile, JSONObject daily, int index, boolean moon) {
        int end = Math.min(length(daily), index + FORECAST_DAYS + 1);
        if (end <= index + 1) return;
        String title = "Next " + (end - index - 1) + " days";
        LinearLayout table = column();
        table.setVisibility(View.GONE);
        TextView toggle = button(title + "  ▾", () -> {});
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setPadding(0, dp(6), dp(10), dp(6));
        toggle.setOnClickListener(v -> {
            boolean open = table.getVisibility() != View.VISIBLE;
            table.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(title + (open ? "  ▴" : "  ▾"));
            if (android.os.Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(open ? "Expanded" : "Collapsed");
        });
        if (android.os.Build.VERSION.SDK_INT >= 30) toggle.setStateDescription("Collapsed");
        tile.addView(toggle);
        tile.addView(table);
        String rise = moon ? "Moonrise" : "Sunrise";
        String set = moon ? "Moonset" : "Sunset";
        addEventTableRow(table, "Date", moon ? label("Phase", 12, MUTED) : null, rise, set, MUTED);
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);
        for (int i = index + 1; i < end; i++) {
            String date = LocalDate.parse(at(daily, "time", i)).format(dateFormat);
            TextView phaseIcon = null;
            if (moon) {
                double phase = value(daily, "moon_phase", i);
                int position = moonPhaseIndex(phase);
                phaseIcon = label(Double.isFinite(phase) ? MOON_PHASE_ICONS[position] : "—", 16, INK);
                phaseIcon.setContentDescription(Double.isFinite(phase) ? MOON_PHASE_NAMES[position] : "Phase unavailable");
            }
            addEventTableRow(table, date, phaseIcon, clock(at(daily, rise.toLowerCase(Locale.US), i)),
                    clock(at(daily, set.toLowerCase(Locale.US), i)), INK);
        }
    }

    private void addEventTableRow(LinearLayout table, String date, TextView phase, String rise, String set, int color) {
        LinearLayout line = row();
        line.setPadding(0, dp(5), 0, dp(5));
        line.addView(label(date, 13, color), new LinearLayout.LayoutParams(0, -2, 1.3f));
        if (phase != null) {
            phase.setGravity(Gravity.CENTER);
            line.addView(phase, new LinearLayout.LayoutParams(dp(42), -2));
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

    private void units() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout options = column();
        options.setPadding(dp(20), dp(22), dp(20), dp(18));
        ScrollView sheet = new ScrollView(this);
        sheet.setBackground(background(Color.WHITE, 24));
        sheet.addView(options);
        dialog.setContentView(sheet);

        TextView heading = label("Units", 26, INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        options.addView(heading);
        RadioGroup temperatures = unitOption(options, "Temperature",
                new String[]{"Celsius (°C)", "Fahrenheit (°F)"}, fahrenheit ? 1 : 0);
        RadioGroup winds = unitOption(options, "Wind speed", WIND_UNITS, windUnit);
        RadioGroup pressures = unitOption(options, "Pressure", PRESSURE_UNITS, pressureUnit);
        RadioGroup clockFormats = unitOption(options, "Time format",
                new String[]{"12-hour (AM/PM)", "24-hour"}, clock24 ? 1 : 0);

        LinearLayout actions = row();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(22), 0, 0);
        actions.addView(button("Cancel", dialog::dismiss));
        TextView apply = button("Apply", () -> {
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
        options.addView(actions);

        android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.BOTTOM);
        dialog.show();
        window.setLayout(-1, -2);
    }

    private RadioGroup unitOption(LinearLayout parent, String name, String[] values, int selected) {
        TextView title = label(name, 15, INK);
        title.setPadding(0, dp(17), 0, dp(8));
        parent.addView(title);
        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
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
            RadioGroup.LayoutParams size = new RadioGroup.LayoutParams(0, -2, 1);
            if (i > 0) size.leftMargin = dp(6);
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
        entry.setHint("Search for a location");
        entry.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBar.addView(entry, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = button("×", () -> entry.setText(""));
        clear.setContentDescription("Clear search");
        searchBar.addView(clear);
        clear.setVisibility(View.GONE);
        page.addView(searchBar);

        LinearLayout results = column();
        results.setPadding(0, dp(12), 0, 0);
        page.addView(results);
        LinearLayout shortcuts = column();
        shortcuts.setPadding(0, dp(12), 0, 0);
        page.addView(shortcuts);
        TextView currentTitle = label("Current location", 18, INK);
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
        TextView savedTitle = label("Saved locations", 18, INK);
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
        currentLocationTitle.setText(devicePlace == null ? "Use current location" : devicePlace);
        currentLocationWeather.setVisibility(deviceWeather == null ? View.GONE : View.VISIBLE);
        if (deviceWeather != null) {
            currentLocationWeather.setText(temp(deviceWeather.optDouble("temperature_2m"))
                    + " · " + description(deviceWeather.optInt("weather_code")));
        }
    }

    private void search(EditText entry, LinearLayout results, String query, LinearLayout savedList) {
        if (!results.isAttachedToWindow()) return;
        results.removeAllViews();
        results.addView(loadingStatus("Searching…"));
        network.execute(() -> {
            try {
                JSONArray items = OpenMeteo.search(query).optJSONArray("results");
                runOnUiThread(() -> {
                    if (!results.isAttachedToWindow() || !query.equals(entry.getText().toString().trim())) return;
                    results.removeAllViews();
                    if (items == null || items.length() == 0) results.addView(label("No locations found", 15, MUTED));
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        locationRow(results, items.optJSONObject(i), false, savedList);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!results.isAttachedToWindow() || !query.equals(entry.getText().toString().trim())) return;
                    results.removeAllViews();
                    results.addView(label("Search unavailable", 15, MUTED));
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
        if (items.length() == 0) parent.addView(label("No saved locations", 15, MUTED));
        for (int i = 0; i < items.length(); i++) locationRow(parent, items.optJSONObject(i), true, parent);
    }

    private void locationRow(LinearLayout parent, JSONObject item, boolean savedItem, LinearLayout savedList) {
        String name = item.optString("name");
        String placeDetails = item.optString("admin1");
        String country = item.optString("country");
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
        TextView toggle = button(savedItem ? "Remove" : alreadySaved ? "Saved" : "Save", () -> {});
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
                toggle.setText("Saved");
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
        String time = current.optString("time");
        if (time.length() < 13) return 0;
        for (int i = 0; i < length(hourly); i++) if (at(hourly, "time", i).startsWith(time.substring(0, 13))) return i;
        return 0;
    }

    private String date(JSONObject daily, int index) {
        if (index == 0) return "Today";
        return LocalDate.parse(at(daily, "time", index)).format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US));
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

    private static String number(double value, String format) {
        return Double.isFinite(value) ? String.format(Locale.US, format, value) : "—";
    }

    private static String rainChance(JSONObject data, String key, int index) {
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

    private static String symbol(int code, boolean night) {
        if (code == 0) return night ? "🌙" : "☀\uFE0F";
        if (code < 3) return night ? "☁\uFE0F" : "🌤\uFE0F";
        if (code == 3) return "☁\uFE0F";
        if (code <= 48) return "🌫\uFE0F";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄\uFE0F";
        if (code >= 95) return "⛈\uFE0F";
        return "🌧\uFE0F";
    }

    private static String description(int code) {
        if (code == 0) return "Clear";
        if (code < 3) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code <= 48) return "Fog";
        if (code < 60) return "Drizzle";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorms";
        return "Rain";
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
        view.setText(text);
        view.setTextSize(Math.max(10f, size * 0.95f));
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setTextColor(color);
        return view;
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
