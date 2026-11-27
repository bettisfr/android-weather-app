package it.skynow.app;

import it.skynow.app.update.GitHubUpdates;
import it.skynow.app.notifications.IssAlerts;
import it.skynow.app.notifications.EarthquakeAlerts;
import it.skynow.app.notifications.EarthquakeAlertCheck;
import it.skynow.app.astronomy.IssPasses;
import it.skynow.app.maps.RadarActivity;
import it.skynow.app.ui.SignalColors;
import it.skynow.app.dashboard.AstronomyTab;
import it.skynow.app.dashboard.EarthquakeTab;
import it.skynow.app.ui.Formats;
import it.skynow.app.ui.SkyViews;
import it.skynow.app.weather.AirQualityBand;
import it.skynow.app.weather.DailyWeather;
import it.skynow.app.weather.ForecastSeries;
import it.skynow.app.weather.ForecastCache;
import it.skynow.app.weather.MeteoAlarm;
import it.skynow.app.weather.OpenMeteo;
import it.skynow.app.weather.WeatherCodes;
import it.skynow.app.weather.views.HourlyLineChart;
import it.skynow.app.weather.views.HumidityGauge;
import it.skynow.app.weather.views.PressureGauge;
import it.skynow.app.weather.views.UvGauge;
import it.skynow.app.weather.views.WindIndicator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final int FORECAST_DAYS = 10;
    private static final int LOCATION_PERMISSION = 1;
    private static final int ASTRONOMY_NOTIFICATION_PERMISSION = 2;
    private static final long FORECAST_REFRESH_MS = 5 * 60 * 1000;
    private static final int REFRESH_PULL_DP = 72;
    private static final int DETAIL_CHART_HEIGHT_DP = 180;
    private SkyViews views;
    private Formats formats;
    private EarthquakeTab earthquakes;
    private AstronomyTab astronomy;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private LinearLayout page;
    private ScrollView scroll;
    private ProgressBar refreshIndicator;
    private boolean manualRefresh;
    private boolean canPullToRefresh;
    private boolean pullingToRefresh;
    private float pullStartX;
    private float pullStartY;
    private JSONObject data;
    private String place;
    private String selectedCountry = "";
    private String selectedRegion = "";
    private LinearLayout regionalWarnings;
    private List<MeteoAlarm.Warning> warnings = List.of();
    private long warningsAttempt;
    private boolean warningsLoading;
    private Instant nextWarningChange;
    private double latitude;
    private double longitude;
    private static final int SETTINGS_REQUEST = 403;
    private int appliedTheme;
    private int appliedLanguage;

    private int day = -1;
    private enum DashboardTab { WEATHER, ASTRONOMY, EARTHQUAKES }
    private DashboardTab dashboardTab = DashboardTab.WEATHER;
    private final int[] dashboardOffsets = new int[DashboardTab.values().length];
    private Button earthquakeTabButton;
    private int homeScroll;
    private boolean choosingLocation;
    private android.window.OnBackInvokedCallback appBackCallback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;

    private final Runnable updateSkyCoordinates = new Runnable() {
        @Override public void run() {
            Instant now = Instant.now();
            astronomy.tick(now);
            if (nextWarningChange != null && !now.isBefore(nextWarningChange)) renderWarnings(now);
            refreshWarnings();
            main.postDelayed(this, 60000 - Math.floorMod(now.toEpochMilli(), 60000));
        }
    };
    private LocationListener locationListener;
    private Runnable locationTimeout;
    private Runnable pendingSearch;
    private String locationNotice;
    private int forecastRequest;
    private boolean forecastLoading;
    private Instant cachedForecastAt;
    private long forecastAttemptAt;
    private ZonedDateTime forecastHour;
    private boolean usingCurrentLocation;
    private TextView placeHeader;
    private View forecastMaps;
    private Button airQualityMapButton;
    private double deviceLatitude = Double.NaN;
    private double deviceLongitude = Double.NaN;
    private String deviceCountry = "";
    private TextView currentLocationTitle;
    private TextView currentLocationWeather;
    private String devicePlace;
    private JSONObject deviceWeather;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(SkyTheme.withPreference(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        views = new SkyViews(this);
        earthquakes = new EarthquakeTab(this, views, new EarthquakeTab.Host() {
            @Override public boolean inItaly() { return MainActivity.this.inItaly(); }
            @Override public double deviceLatitude() { return deviceLatitude; }
            @Override public double deviceLongitude() { return deviceLongitude; }
            @Override public String deviceCountry() { return deviceCountry; }
            @Override public String devicePlace() { return devicePlace; }
            @Override public Formats formats() { return formats; }
        });
        astronomy = new AstronomyTab(this, views, new AstronomyTab.Host() {
            @Override public Formats formats() { return formats; }
            @Override public void openMap(RadarActivity.Layer layer) { MainActivity.this.openMap(layer); }
        });
        SkyTheme.applySystemBars(this);
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        place = prefs.getString("place", "Bastia Umbra");
        latitude = Double.parseDouble(prefs.getString("latitude", "43.066"));
        longitude = Double.parseDouble(prefs.getString("longitude", "12.548"));
        appliedTheme = SkyTheme.preference(this);
        appliedLanguage = SkyLanguage.preference(this);
        readDisplayPreferences(prefs);
        if (state != null && state.containsKey("forecastPlace")) {
            place = state.getString("forecastPlace");
            latitude = state.getDouble("forecastLatitude");
            longitude = state.getDouble("forecastLongitude");
            selectedCountry = state.getString("forecastCountry", "");
            usingCurrentLocation = state.getBoolean("forecastUsesGps");
            locationNotice = state.getString("forecastLocationNotice");
            deviceLatitude = state.getDouble("deviceLatitude", Double.NaN);
            deviceLongitude = state.getDouble("deviceLongitude", Double.NaN);
            deviceCountry = state.getString("deviceCountry", "");
            dashboardTab = DashboardTab.values()[state.getInt("dashboardTab", 0)];
            int[] offsets = state.getIntArray("dashboardOffsets");
            if (offsets != null && offsets.length == dashboardOffsets.length) {
                System.arraycopy(offsets, 0, dashboardOffsets, 0, offsets.length);
            }
            load();
        } else {
            astronomy.setPendingEventSection(getIntent().getStringExtra(IssAlerts.OPEN_EVENT_SECTION));
            if (astronomy.hasPendingEventSection() || getIntent().getBooleanExtra("openIssAlerts", false)) {
                dashboardTab = DashboardTab.ASTRONOMY;
            }
            currentLocation(!prefs.getBoolean("askedForLocation", false));
            openAstronomyNotification(getIntent());
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openAstronomyNotification(intent);
    }

    private void openAstronomyNotification(Intent intent) {
        String section = intent.getStringExtra(IssAlerts.OPEN_EVENT_SECTION);
        if (section == null) {
            openIssNotification(intent);
            return;
        }
        astronomy.setPendingEventSection(section);
        day = -1;
        dashboardTab = DashboardTab.ASTRONOMY;
        if (data != null) {
            render();
        } else {
            double[] observer = intent.getDoubleArrayExtra(IssAlerts.OBSERVER);
            if (observer != null && observer.length == 2) {
                latitude = observer[0];
                longitude = observer[1];
            }
            String zone = intent.getStringExtra(IssAlerts.TIME_ZONE);
            astronomy.setNotificationZone(zone == null ? ZoneId.systemDefault() : ZoneId.of(zone));
            layout();
            page.addView(placeTitle());
            astronomy.attachEvents(page, scroll, latitude, longitude, place);
        }
    }

    private void openIssNotification(Intent intent) {
        if (!intent.getBooleanExtra("openIssAlerts", false)) return;
        day = -1;
        dashboardTab = DashboardTab.ASTRONOMY;
        dashboardOffsets[dashboardTab.ordinal()] = 0;
        if (data != null) render();
        IssPasses.Pass pass = IssPasses.Pass.fromNotificationTrack(intent.getDoubleArrayExtra(IssAlerts.TRACK));
        if (pass == null) return;
        String zoneId = intent.getStringExtra(IssAlerts.TIME_ZONE);
        ZoneId zone = zoneId == null ? ZoneId.systemDefault() : ZoneId.of(zoneId);
        ZonedDateTime rise = Instant.ofEpochSecond(pass.rise.time).atZone(zone);
        ZonedDateTime set = Instant.ofEpochSecond(pass.set.time).atZone(zone);
        String period = formats.shortDate(true).format(rise) + " · " + formats.clockFormat().format(rise)
                + " – " + formats.clockFormat().format(set);
        if (!rise.toLocalDate().equals(set.toLocalDate())) period += getString(R.string.next_day_suffix);
        double[] observer = intent.getDoubleArrayExtra(IssAlerts.OBSERVER);
        String url = observer == null || observer.length != 3 ? null
                : astronomy.issPassUrl(pass, zone, observer[0], observer[1], observer[2], intent.getStringExtra(IssAlerts.PLACE));
        astronomy.showIssPass(pass, zone, period, url);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (data == null) return;
        state.putString("forecastPlace", place);
        state.putDouble("forecastLatitude", latitude);
        state.putDouble("forecastLongitude", longitude);
        state.putString("forecastCountry", selectedCountry);
        state.putBoolean("forecastUsesGps", usingCurrentLocation);
        state.putString("forecastLocationNotice", locationNotice);
        state.putDouble("deviceLatitude", deviceLatitude);
        state.putDouble("deviceLongitude", deviceLongitude);
        state.putString("deviceCountry", deviceCountry);
        if (day < 0 && !choosingLocation) dashboardOffsets[dashboardTab.ordinal()] = scroll.getScrollY();
        state.putInt("dashboardTab", dashboardTab.ordinal());
        state.putIntArray("dashboardOffsets", dashboardOffsets);
    }

    private void currentLocation(boolean askPermission) {
        currentLocation(askPermission, false);
    }

    private void currentLocation(boolean askPermission, boolean manual) {
        stopLocating();
        manualRefresh = manual;
        forecastRequest++;
        forecastLoading = false;
        if (!hasLocationPermission()) {
            if (askPermission) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("askedForLocation", true).apply();
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            } else {
                locationFailed(R.string.location_permission_off, manual);
            }
            return;
        }

        choosingLocation = false;
        day = -1;
        if (data == null) {
            ForecastCache cache = ForecastCache.read(this);
            if (cache != null) {
                place = cache.place;
                latitude = cache.latitude;
                longitude = cache.longitude;
                selectedCountry = cache.country;
                selectedRegion = cache.region;
                usingCurrentLocation = false;
                showCachedForecast(cache);
            }
        }
        if (data == null) {
            layout();
            page.addView(views.header(getString(R.string.current_location), this::locations));
            page.addView(views.loadingStatus(getString(R.string.finding_location)));
        }
        LocationManager manager = getSystemService(LocationManager.class);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (locationListener != this) return;
                stopLocating();
                astronomy.setMagneticDeclination(new GeomagneticField((float) location.getLatitude(),
                        (float) location.getLongitude(), (float) location.getAltitude(), location.getTime()).getDeclination());
                latitude = location.getLatitude();
                longitude = location.getLongitude();
                deviceLatitude = latitude;
                deviceLongitude = longitude;
                deviceCountry = "";
                selectedCountry = "";
                selectedRegion = "";
                place = getString(R.string.current_location);
                devicePlace = null;
                deviceWeather = null;
                usingCurrentLocation = true;
                locationNotice = null;
                load(manual);
            }

            @Override
            @SuppressWarnings("deprecation") // Required by LocationListener before Android 11.
            public void onStatusChanged(String provider, int status, Bundle extras) {}
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
            locationTimeout = () -> locationFailed(R.string.current_location_unavailable, manual);
            main.postDelayed(locationTimeout, 12000);
        } else {
            locationFailed(R.string.current_location_unavailable, manual);
        }
    }

    private void resolveLocation(double lat, double lon) {
        if (!Geocoder.isPresent()) return;
        boolean gps = usingCurrentLocation;
        Geocoder geocoder = new Geocoder(this, formats.appLocale());
        java.util.function.Consumer<List<Address>> resolved = addresses -> {
            if (addresses == null || addresses.isEmpty()) return;
            Address address = addresses.get(0);
            String locality = address.getLocality();
            String name = locality == null ? address.getSubAdminArea() : locality;
            String country = address.getCountryCode();
            String region = address.getAdminArea();
            runOnUiThread(() -> {
                if (isDestroyed() || gps != usingCurrentLocation || lat != latitude || lon != longitude) return;
                if (country != null) {
                    selectedCountry = country;
                    if (gps) {
                        deviceCountry = country;
                        EarthquakeAlerts.setLocation(this, lat, lon, country);
                    }
                    if (region != null) selectedRegion = region;
                    refreshWarnings();
                    renderWarnings(Instant.now());
                    updateItalianMaps();
                    earthquakes.refresh();
                    if (!gps) getPreferences(MODE_PRIVATE).edit().putString("countryCode", country).apply();
                }
                if (gps && name != null && !name.trim().isEmpty()) {
                    place = name;
                    devicePlace = name;
                    if (placeHeader != null) updatePlaceHeader();
                    updateCurrentLocationShortcut();
                }
            });
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(lat, lon, 1, resolved::accept);
        } else {
            network.execute(() -> {
                try {
                    @SuppressWarnings("deprecation") // Synchronous fallback before Android 13.
                    List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                    resolved.accept(addresses);
                } catch (java.io.IOException error) {
                    android.util.Log.d("MeteoLocale", "Location name unavailable", error);
                }
            });
        }
    }

    private TextView placeTitle() {
        placeHeader = views.header(place, this::locations);
        updatePlaceHeader();
        return placeHeader;
    }

    private void updatePlaceHeader() {
        placeHeader.setText(place);
        if (usingCurrentLocation) {
            placeHeader.setContentDescription(getString(R.string.current_location_named, place));
            placeHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_current_location, 0, 0, 0);
            placeHeader.setCompoundDrawablePadding(views.dp(8));
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

    private void locationFailed(int message, boolean manual) {
        stopLocating();
        manualRefresh = false;
        if (manual && data != null) {
            if (refreshIndicator != null) refreshIndicator.setVisibility(View.GONE);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return;
        }
        fallbackLocation(getString(message));
    }

    private void fallbackLocation(String reason) {
        stopLocating();
        usingCurrentLocation = false;
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        place = prefs.getString("place", "Bastia Umbra");
        latitude = Double.parseDouble(prefs.getString("latitude", "43.066"));
        longitude = Double.parseDouble(prefs.getString("longitude", "12.548"));
        selectedCountry = prefs.getString("countryCode", prefs.contains("place") ? "" : "IT");
        selectedRegion = "";
        locationNotice = reason;
        load();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_PERMISSION) currentLocation(false, manualRefresh);
    }

    private void showCachedForecast(ForecastCache cache) {
        data = cache.forecast;
        cachedForecastAt = cache.updatedAt;
        render();
    }

    private void load() {
        load(false);
    }

    private void load(boolean manual) {
        int offset = !manual && cachedForecastAt != null && scroll != null ? scroll.getScrollY() : 0;
        data = null;
        cachedForecastAt = null;
        day = -1;
        homeScroll = 0;
        choosingLocation = false;
        ForecastCache cache = ForecastCache.read(this);
        boolean hasCache = cache != null && cache.matches(latitude, longitude, usingCurrentLocation);
        if (hasCache) {
            showCachedForecast(cache);
            ScrollView target = scroll;
            if (offset > 0) target.post(() -> target.scrollTo(0, offset));
        } else {
            layout();
            page.addView(placeTitle());
            page.addView(views.loadingStatus(getString(R.string.loading_forecast)));
        }
        requestForecast(hasCache, manual);
        if ((usingCurrentLocation && devicePlace == null) || selectedCountry.isEmpty()
                || (inItaly() && selectedRegion.isEmpty())) resolveLocation(latitude, longitude);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (getResources().getBoolean(R.bool.check_for_release_updates)) GitHubUpdates.checkAutomatically(this);
        EarthquakeAlerts.syncSubscription(this);
        EarthquakeAlertCheck.updateSchedule(this);
        requestAstronomyNotificationPermission();
        refreshWarnings();
        astronomy.onResume();
        updateSkyCoordinates.run();
        if (data == null || choosingLocation || locationListener != null || forecastLoading) return;
        ZonedDateTime localHour = Instant.now().atZone(ZoneId.of(data.optString("timezone", "UTC")))
                .truncatedTo(ChronoUnit.HOURS);
        if (SystemClock.elapsedRealtime() - forecastAttemptAt >= FORECAST_REFRESH_MS
                || !localHour.equals(forecastHour)) requestForecast(true);
    }

    @Override protected void onPause() {
        resumed = false;
        astronomy.onPause();
        main.removeCallbacks(updateSkyCoordinates);
        super.onPause();
    }

    private void requestAstronomyNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33 || !resumed || data == null
                || choosingLocation || locationListener != null
                || (!IssAlerts.anyEnabled(this) && !EarthquakeAlerts.enabled(this))
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.getBoolean("askedForAstronomyNotifications", false)) return;
        prefs.edit().putBoolean("askedForAstronomyNotifications", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, ASTRONOMY_NOTIFICATION_PERMISSION);
    }

    private void requestForecast(boolean refreshing) {
        requestForecast(refreshing, false);
    }

    private void requestForecast(boolean refreshing, boolean manual) {
        manualRefresh = manual;
        if (manual) {
            warningsAttempt = 0;
            if (dashboardTab == DashboardTab.ASTRONOMY) astronomy.resetRefreshThrottles();
        }
        if (refreshIndicator != null) {
            refreshIndicator.setAlpha(1f);
            refreshIndicator.setVisibility(manual ? View.VISIBLE : View.GONE);
        }
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
                String serialized = response.toString();
                Instant updatedAt = Instant.now();
                runOnUiThread(() -> {
                    if (request != forecastRequest || isDestroyed()) return;
                    forecastLoading = false;
                    manualRefresh = false;
                    forecastHour = Instant.now().atZone(ZoneId.of(response.optString("timezone", "UTC")))
                            .truncatedTo(ChronoUnit.HOURS);
                    int offset = refreshing && !manual && scroll != null && !astronomy.hasPendingEventSection()
                            ? scroll.getScrollY() : 0;
                    if (refreshing && day >= 0 && data != null) {
                        String selectedDate = at(data.optJSONObject("daily"), "time", day);
                        JSONObject refreshed = response.optJSONObject("daily");
                        day = 0;
                        for (int i = 0; i < Math.min(FORECAST_DAYS, length(refreshed)); i++) {
                            if (selectedDate.equals(at(refreshed, "time", i))) day = i;
                        }
                    }
                    data = response;
                    astronomy.setNotificationZone(null);
                    IssAlerts.update(this, lat, lon, response.optDouble("elevation"),
                            response.optString("timezone", "UTC"), place);
                    cachedForecastAt = null;
                    ForecastCache.write(this, serialized, updatedAt, lat, lon, place, selectedCountry, selectedRegion);
                    if (usingCurrentLocation) {
                        deviceWeather = response.optJSONObject("current");
                        updateCurrentLocationShortcut();
                    }
                    if (!choosingLocation) {
                        render();
                        requestAstronomyNotificationPermission();
                        ScrollView updated = scroll;
                        if (offset > 0) updated.post(() -> updated.scrollTo(0, offset));
                    }
                });
            } catch (Exception error) {
                android.util.Log.w("MeteoLocale", "Forecast request failed", error);
                runOnUiThread(() -> {
                    if (request != forecastRequest || isDestroyed()) return;
                    forecastLoading = false;
                    manualRefresh = false;
                    if (refreshIndicator != null) refreshIndicator.setVisibility(View.GONE);
                    if (manual && !choosingLocation) {
                        Toast.makeText(this, R.string.forecast_unavailable, Toast.LENGTH_SHORT).show();
                    }
                    if (refreshing || choosingLocation || astronomy.notificationZone() != null) return;
                    layout();
                    page.addView(placeTitle());
                    page.addView(views.label(getString(R.string.forecast_unavailable), 22, views.ink));
                    page.addView(views.button(getString(R.string.retry), this::load));
                });
            }
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                astronomy.setPendingEventSection(null);
                canPullToRefresh = day < 0 && !choosingLocation && refreshIndicator != null
                        && !forecastLoading && locationListener == null
                        && !scroll.canScrollVertically(-1);
                pullStartX = event.getX();
                pullStartY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!canPullToRefresh && !pullingToRefresh) break;
                float dx = Math.abs(event.getX() - pullStartX);
                float dy = event.getY() - pullStartY;
                int slop = ViewConfiguration.get(this).getScaledTouchSlop();
                if (!pullingToRefresh) {
                    if (forecastLoading || scroll.canScrollVertically(-1) || dy < -slop
                            || (dx > slop && dx >= dy)) {
                        canPullToRefresh = false;
                        break;
                    }
                    if (dy <= slop) break;
                    pullingToRefresh = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                refreshIndicator.setAlpha(Math.max(0f, Math.min(1f, dy / views.dp(REFRESH_PULL_DP))));
                refreshIndicator.setVisibility(View.VISIBLE);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                canPullToRefresh = false;
                if (!pullingToRefresh) break;
                pullingToRefresh = false;
                if (event.getActionMasked() == MotionEvent.ACTION_UP && !forecastLoading
                        && event.getY() - pullStartY >= views.dp(REFRESH_PULL_DP)) {
                    dashboardOffsets[dashboardTab.ordinal()] = 0;
                    currentLocation(true, true);
                } else {
                    refreshIndicator.setVisibility(View.GONE);
                }
                return true;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private void layout() {
        astronomy.detach();
        placeHeader = null;
        regionalWarnings = null;
        forecastMaps = null;
        airQualityMapButton = null;
        earthquakes.detach();
        earthquakeTabButton = null;
        currentLocationTitle = null;
        currentLocationWeather = null;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(views.surfaceColor);
        scroll.setFillViewport(true);
        page = views.column();
        page.setPadding(views.dp(16), views.dp(12), views.dp(16), views.dp(20));
        scroll.addView(page);
        canPullToRefresh = false;
        pullingToRefresh = false;
        refreshIndicator = null;
        View forecastView = scroll;
        if (data != null && !choosingLocation) {
            FrameLayout viewport = new FrameLayout(this);
            viewport.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            refreshIndicator = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
            refreshIndicator.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(views.accent));
            refreshIndicator.setContentDescription(getString(R.string.loading_forecast));
            refreshIndicator.setBackground(views.background(getColor(R.color.sky_card), views.dp(18)));
            refreshIndicator.setPadding(views.dp(7), views.dp(7), views.dp(7), views.dp(7));
            refreshIndicator.setElevation(views.dp(3));
            refreshIndicator.setVisibility(manualRefresh ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams indicatorSize = new FrameLayout.LayoutParams(views.dp(36), views.dp(36),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            indicatorSize.topMargin = views.dp(8);
            viewport.addView(refreshIndicator, indicatorSize);
            forecastView = viewport;
        }
        if (day >= 0 && !choosingLocation) {
            LinearLayout screen = views.column();
            screen.setBackgroundColor(views.surfaceColor);
            screen.addView(views.detailHeader(getString(R.string.forecast_10_days), R.string.settings_back, this::home));
            screen.addView(forecastView, new LinearLayout.LayoutParams(-1, 0, 1));
            setContentView(screen);
        } else if (data != null && !choosingLocation) {
            LinearLayout screen = views.column();
            screen.setBackgroundColor(views.surfaceColor);
            LinearLayout top = views.row();
            top.setPadding(views.dp(16), views.dp(6), views.dp(16), views.dp(4));
            top.addView(placeTitle(), new LinearLayout.LayoutParams(0, -2, 1));
            TextView settings = views.button("⋮", this::settings);
            settings.setTextSize(24);
            settings.setMinWidth(views.dp(46));
            settings.setContentDescription(getString(R.string.settings));
            top.addView(settings);
            screen.addView(top);
            screen.addView(forecastView, new LinearLayout.LayoutParams(-1, 0, 1));
            screen.addView(dashboardNavigation());
            setContentView(screen);
        } else {
            setContentView(forecastView);
        }
        updateAppBackCallback();
    }

    private void render() {
        choosingLocation = false;
        if (dashboardTab == DashboardTab.EARTHQUAKES && !earthquakes.isAvailable()) dashboardTab = DashboardTab.WEATHER;
        layout();
        JSONObject current = data.optJSONObject("current");
        JSONObject hourly = data.optJSONObject("hourly");
        JSONObject daily = data.optJSONObject("daily");
        if (day < 0) {
            ScrollView target = scroll;
            int offset = dashboardOffsets[dashboardTab.ordinal()];
            target.post(() -> target.scrollTo(0, offset));
            if (dashboardTab == DashboardTab.ASTRONOMY) {
                astronomy.attach(page, scroll, data, latitude, longitude, place);
                return;
            }
            if (dashboardTab == DashboardTab.EARTHQUAKES) {
                earthquakes.attach(page);
                return;
            }
        }
        int todayHour = currentHour(current, hourly);
        String today = at(daily, "time", 0);
        double rainRemaining = DailyWeather.remainingMaximum(hourly, "precipitation_probability", todayHour, today);
        int forecastDays = Math.min(FORECAST_DAYS, length(daily));
        DailyWeather[] summaries = new DailyWeather[forecastDays];
        for (int index = 0; index < forecastDays; index++) {
            String date = at(daily, "time", index);
            double dailyCode = value(daily, "weather_code", index);
            int fallback = index == 0 ? current.optInt("weather_code") : (int) dailyCode;
            summaries[index] = DailyWeather.summarize(hourly, index == 0 ? todayHour : 0, date, fallback);
        }
        if (day < 0) {
            if (locationNotice != null) page.addView(views.label(locationNotice, 13, views.muted));
            if (cachedForecastAt != null) {
                ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
                ZonedDateTime updated = cachedForecastAt.atZone(zone);
                String timestamp = updated.format(formats.shortDate(true)) + " · " + updated.format(formats.clockFormat());
                views.addSpaced(page, views.label(getString(R.string.cached_forecast, timestamp), 13, views.muted), 4);
            }
            title(getString(R.string.now));
            LinearLayout hero = views.row();
            TextView temperature = views.label(formats.tempDecimal(current.optDouble("temperature_2m")), 76, views.ink);
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
            TextView feelsLike = views.label(getString(R.string.feels_like_value, formats.temp(current.optDouble("apparent_temperature"))), 14, views.muted);
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
            TextView extremes = views.label(getString(R.string.high_low, formats.temp(value(daily, "temperature_2m_max", 0)),
                    formats.temp(value(daily, "temperature_2m_min", 0))), 15, views.muted);
            views.addSpaced(page, extremes, 10);
            regionalWarnings = views.column();
            page.addView(regionalWarnings);
            renderWarnings(Instant.now());
            refreshWarnings();
            forecast(hourly, todayHour, todayHour + 24, true);
            title(getString(R.string.forecast_10_days));
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
                TextView chance = views.label(displayedProbability > 0 ? formats.percent(displayedProbability) : "", 14, views.accent);
                chance.setGravity(roomy ? Gravity.START : Gravity.END);
                chance.setSingleLine(true);
                int chanceWidth = Math.max(views.dp(40), Math.max(views.textWidth(chance, formats.percent(100)),
                        views.textWidth(chance, chance.getText().toString())) + views.dp(4));
                values.addView(chance, new LinearLayout.LayoutParams(chanceWidth, -2, roomy ? 1 : 0));
                TextView dayIcon = views.label(WeatherCodes.symbol(weather, dayWeather.night), 23, views.ink);
                dayIcon.setContentDescription(description(weather));
                dayIcon.setGravity(Gravity.CENTER);
                int iconWidth = Math.max(views.dp(38), dayIcon.getLineHeight() + views.dp(6));
                values.addView(dayIcon, new LinearLayout.LayoutParams(iconWidth, -2));
                TextView temperatures = views.label(range(daily, index), 16, views.ink);
                temperatures.setGravity(Gravity.END);
                int temperatureWidth = Math.max(views.dp(formats.temperatureUnit == 0 ? 70 : 83), views.textWidth(temperatures, range(daily, index)) + views.dp(6));
                values.addView(temperatures, new LinearLayout.LayoutParams(temperatureWidth, -2));
                tile.setOnClickListener(v -> {
                    homeScroll = scroll.getScrollY();
                    day = selected;
                    render();
                });
                views.addSpaced(page, tile, 5);
            }
            title(getString(R.string.current_conditions));
            metrics(formats.windSpeed(current.optDouble("wind_speed_10m"), true), current.optDouble("wind_direction_10m"),
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
                String key = index == 0 ? getString(R.string.today) : LocalDate.parse(at(daily, "time", index)).format(DateTimeFormatter.ofPattern("EEE", formats.appLocale()));
                DailyWeather summary = summaries[index];
                int chipWeather = summary.code;
                String chipRange = range(daily, index);
                TextView chip = views.button(key + "\n" + WeatherCodes.symbol(chipWeather, summary.night) + "\n" + chipRange, () -> {
                    day = selected;
                    render();
                });
                chip.setContentDescription(key + ", " + description(chipWeather) + ", " + chipRange);
                chip.setBackground(views.background(index == day ? getColor(R.color.sky_selected) : getColor(R.color.sky_card), 10));
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
            title(getString(R.string.daily_conditions));
            addMetricGrid(windTile(getString(R.string.max_wind), formats.windSpeed(value(daily, "wind_speed_10m_max", day), true),
                            value(daily, "wind_direction_10m_dominant", day)),
                    humidityTile(getString(R.string.average_humidity), value(daily, "relative_humidity_2m_mean", day), Double.NaN),
                    uvTile(getString(R.string.max_uv), value(daily, "uv_index_max", day)), dailySunTimes(daily, day));
            details(hourly, daily, day, start, end, day == 0);
        }
    }

    private void forecast(JSONObject hourly, int start, int end, boolean now) {
        if (day < 0) {
            LinearLayout heading = views.row();
            heading.setPadding(0, views.dp(12), 0, views.dp(7));
            TextView caption = views.label(getString(R.string.hourly_forecast), 19, views.ink);
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
            title(getString(R.string.hourly_forecast));
        }
        LinearLayout box = views.card();
        LinearLayout hours = views.carousel(box);
        for (int i = start; i < Math.min(end, length(hourly)); i++) {
            LinearLayout cell = views.column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, views.dp(9), 0, views.dp(7));
            String time = now && i == start ? getString(R.string.now) : formats.clock(at(hourly, "time", i));
            TextView timeLabel = views.label(time, 13, views.muted);
            int hourWidth = Math.max(views.dp(67), views.textWidth(timeLabel, formats.clock24 ? "23:00" : "12:00 PM") + views.dp(12));
            hours.addView(cell, new LinearLayout.LayoutParams(hourWidth, -2));
            cell.addView(views.label(formats.temp(value(hourly, "temperature_2m", i)), 21, views.ink), new LinearLayout.LayoutParams(-2, -2));
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

    private void updateItalianMaps() {
        int visibility = inItaly() ? View.VISIBLE : View.GONE;
        if (forecastMaps != null) forecastMaps.setVisibility(visibility);
        if (airQualityMapButton != null) airQualityMapButton.setVisibility(visibility);
        if (earthquakeTabButton != null) earthquakeTabButton.setVisibility(earthquakes.isAvailable() ? View.VISIBLE : View.GONE);
    }

    private LinearLayout dashboardNavigation() {
        LinearLayout navigation = views.row();
        navigation.setBackgroundColor(getColor(R.color.sky_card));
        navigation.setPadding(views.dp(8), views.dp(6), views.dp(8), views.dp(6));
        for (DashboardTab tab : DashboardTab.values()) {
            int label;
            switch (tab) {
                case ASTRONOMY: label = R.string.astronomy; break;
                case EARTHQUAKES: label = R.string.tab_earthquakes; break;
                default: label = R.string.tab_weather;
            }
            boolean selected = tab == dashboardTab;
            Button item = views.actionButton(getString(label), getColor(selected ? R.color.sky_selected : R.color.sky_card), 12);
            item.setSelected(selected);
            item.setMinHeight(views.dp(48));
            item.setPadding(views.dp(3), views.dp(6), views.dp(3), views.dp(6));
            item.setOnClickListener(v -> switchDashboardTab(tab));
            if (tab == DashboardTab.EARTHQUAKES) {
                earthquakeTabButton = item;
                item.setVisibility(earthquakes.isAvailable() ? View.VISIBLE : View.GONE);
            }
            navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return navigation;
    }

    private void switchDashboardTab(DashboardTab tab) {
        if (tab == dashboardTab) return;
        dashboardOffsets[dashboardTab.ordinal()] = scroll.getScrollY();
        dashboardTab = tab;
        render();
    }

    private void openItalianMap(RadarActivity.Layer layer) {
        if (inItaly()) openMap(layer);
    }

    private void openMap(RadarActivity.Layer layer) {
        if (data == null) return;
        startActivity(new android.content.Intent(this, RadarActivity.class)
                .putExtra("layer", layer.name()).putExtra("place", place).putExtra("lat", latitude).putExtra("lon", longitude)
                .putExtra("timeZone", data.optString("timezone", "UTC")).putExtra("clock24", formats.clock24));
    }

    private void refreshWarnings() {
        long elapsed = SystemClock.elapsedRealtime();
        if (!inItaly() || selectedRegion.isEmpty() || warningsLoading
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
        }).thenAccept(result -> main.post(() -> {
            if (isDestroyed()) return;
            warningsLoading = false;
            warnings = result == null ? List.of() : result;
            renderWarnings(Instant.now());
        }));
    }

    private void renderWarnings(Instant now) {
        nextWarningChange = null;
        if (regionalWarnings == null) return;
        regionalWarnings.removeAllViews();
        if (!inItaly() || selectedRegion.isEmpty()) return;
        String region = MeteoAlarm.regionKey(selectedRegion);
        DateTimeFormatter validity = DateTimeFormatter.ofPattern("EEE d MMM, " + (formats.clock24 ? "HH:mm" : "h:mm a"), formats.appLocale())
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
            TextView details = views.label(getString(R.string.regional_warning_validity, warning.area,
                    validity.format(warning.start), validity.format(warning.expires)), 13, views.ink);
            details.setPadding(0, views.dp(4), 0, 0);
            banner.addView(details);
            banner.setOnClickListener(v -> startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(warning.url()))));
            views.addSpaced(regionalWarnings, banner, 8);
        }
    }

    private String warningTitle(String event) {
        if (!formats.italian()) return event;
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

    private boolean inItaly() {
        return "IT".equalsIgnoreCase(selectedCountry);
    }

    private void details(JSONObject hourly, JSONObject daily, int index, int start, int end, boolean now) {
        title(getString(R.string.hourly_details));
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
        String[] titles = {getString(R.string.precipitation), getString(R.string.wind), getString(R.string.humidity), getString(R.string.temperature)};
        TextView[] buttons = new TextView[titles.length];
        java.util.function.IntConsumer select = tab -> {
            for (int i = 0; i < buttons.length; i++) buttons[i].setBackground(views.background(i == tab ? getColor(R.color.sky_selected) : getColor(R.color.sky_subtle), 8));
            body.removeAllViews();
            LinearLayout summary = views.row();
            summary.setPadding(0, views.dp(14), 0, 0);
            body.addView(summary);
            String date = at(daily, "time", index);
            if (tab == 0) {
                detailSummary(summary, getString(R.string.daily_total), formats.number(value(daily, "precipitation_sum", index), "%.1f") + " mm");
            } else if (tab == 1) {
                detailSummary(summary, getString(R.string.daily_high), formats.windSpeed(value(daily, "wind_speed_10m_max", index), true));
                detailSummary(summary, getString(R.string.daily_average), formats.windSpeed(DailyWeather.hourlySummary(hourly, "wind_speed_10m", date, false), true));
            } else if (tab == 2) {
                detailSummary(summary, getString(R.string.daily_high), formats.percent(DailyWeather.hourlySummary(hourly, "relative_humidity_2m", date, true)));
                detailSummary(summary, getString(R.string.daily_average), formats.percent(value(daily, "relative_humidity_2m_mean", index)));
            } else {
                detailSummary(summary, getString(R.string.daily_high), formats.temp(value(daily, "temperature_2m_max", index)));
                detailSummary(summary, getString(R.string.daily_low), formats.temp(value(daily, "temperature_2m_min", index)));
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
        String firstName = new String[]{getString(R.string.amount), getString(R.string.wind_speed), getString(R.string.humidity), getString(R.string.temperature)}[tab];
        String secondName = new String[]{getString(R.string.rain_chance), getString(R.string.gusts), "", getString(R.string.feels_like)}[tab];
        LinearLayout legend = views.row();
        TextView firstLegend = views.label((precipitation ? "■ " : "● ") + firstName, 11, getColor(R.color.sky_accent));
        if (views.largeText()) legend.addView(firstLegend, new LinearLayout.LayoutParams(0, -2, 1));
        else legend.addView(firstLegend);
        if (!humidity) {
            TextView secondKey = views.label("┄ " + secondName, 11, getColor(R.color.sky_chart_secondary));
            secondKey.setPadding(views.dp(12), 0, 0, 0);
            if (views.largeText()) legend.addView(secondKey, new LinearLayout.LayoutParams(0, -2, 1));
            else legend.addView(secondKey);
        }
        String unit = new String[]{"mm", formats.windUnitLabel(), "%", SettingsActivity.TEMPERATURE_UNITS[formats.temperatureUnit]}[tab];
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
            first[i] = wind ? formats.displayWind(a) : tab == 3 ? formats.displayTemperature(a) : a;
            if (!humidity) {
                double b = value(hourly, secondField, start + i);
                second[i] = wind ? formats.displayWind(b) : tab == 3 ? formats.displayTemperature(b) : b;
            }
        }

        int scaleWidth = Math.max(views.dp(24), views.textWidth(axisLabel, "100.0") + views.dp(5));
        int screenWidth = views.dp(getResources().getConfiguration().screenWidthDp);
        int minimumHourWidth = views.textWidth(axisLabel, formats.clock24 ? "23:00" : "12 PM") + views.dp(7);
        if (now) minimumHourWidth = Math.max(minimumHourWidth, views.textWidth(axisLabel, getString(R.string.now)) + views.dp(4));
        int hourWidth = Math.max(Math.max(views.dp(32), minimumHourWidth),
                (screenWidth - 2 * (views.dp(16) + views.dp(13)) - scaleWidth * (precipitation ? 2 : 1)) / 8);
        int plotHeight = chartHeight - topGap - legendHeight - legendGap - timeHeight - directionHeight;
        HourlyLineChart plot = new HourlyLineChart(this, first, second, hourWidth, precipitation, humidity, wind,
                precipitation || (wind && formats.windUnit == 2) ? 1 : 0);
        LinearLayout plotRow = views.row();
        plotRow.setGravity(Gravity.TOP);
        chart.addView(plotRow, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams leftScale = new LinearLayout.LayoutParams(scaleWidth, plotHeight);
        leftScale.topMargin = directionHeight;
        plotRow.addView(plot.scale(false), leftScale);
        HorizontalScrollView viewport = new HorizontalScrollView(this);
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
                FrameLayout cell = new FrameLayout(this);
                cell.addView(new WindIndicator(this, value(hourly, "wind_direction_10m", start + i)),
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
            String hour = index == start && now ? getString(R.string.now) : Formats.formatTime(Formats.localTime(at(hourly, "time", index)), formats.hourClockFormat());
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
        return Double.isFinite(value) ? getString(R.string.chart_reading, name, formats.number(value, "%.1f"), unit)
                : getString(R.string.chart_reading_missing, name);
    }

    private void airQuality() {
        LinearLayout tile = views.card();
        LinearLayout heading = views.row();
        heading.setGravity(Gravity.TOP);
        LinearLayout summary = views.column();
        summary.addView(views.label(getString(R.string.air_quality), 14, views.muted));
        TextView index = views.label(getString(R.string.air_quality_loading), 19, views.ink);
        LinearLayout.LayoutParams indexSize = new LinearLayout.LayoutParams(-2, -2);
        indexSize.topMargin = views.dp(5);
        summary.addView(index, indexSize);
        heading.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        airQualityMapButton = views.mapButton(R.string.aurora_map_short, () -> openItalianMap(RadarActivity.Layer.AIR_QUALITY));
        airQualityMapButton.setContentDescription(getString(R.string.air_quality_map));
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
        }).thenAccept(result -> main.post(() -> {
            if (isDestroyed() || !tile.isAttachedToWindow()) return;
            double aqi = result == null ? Double.NaN : result.optDouble("european_aqi");
            if (!Double.isFinite(aqi)) {
                index.setText(R.string.air_quality_unavailable);
                return;
            }
            AirQualityBand band = AirQualityBand.forIndex(Math.round(aqi));
            index.setText(getString(R.string.air_quality_index, Math.round(aqi), getString(band.label)));
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
        String[] symbols = {getString(R.string.air_quality_pm25), "PM10", "O₃", "NO₂", "SO₂"};
        int[] names = {R.string.air_quality_pm25, R.string.air_quality_pm10, R.string.air_quality_ozone,
                R.string.air_quality_nitrogen_dioxide, R.string.air_quality_sulphur_dioxide};
        String unit = getString(R.string.air_quality_unit);
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
                String value = formats.number(concentration, "%.1f");
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
                String description = lineReading(getString(names[i]), concentration, unit);
                if (band != null) {
                    reading.setBackground(views.background(SignalColors.tint(band.color), 7));
                    reading.setPaddingRelative(views.dp(6), views.dp(2), views.dp(6), views.dp(2));
                    description += " · " + getString(band.label);
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
                line.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
            }
            views.addSpaced(grid, line, start == 0 ? 0 : 8);
        }
    }

    private void metrics(String wind, double direction, double humidity, double dewPoint, double uv, double pressure) {
        String pressureValue = formats.pressureReading(pressure);
        android.text.SpannableString pressureWithUnit = new android.text.SpannableString(
                pressureValue + "\u00a0" + formats.pressureUnitLabel());
        pressureWithUnit.setSpan(new android.text.style.RelativeSizeSpan(13f / 25),
                pressureValue.length(), pressureWithUnit.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        double elevation = data.optDouble("elevation");
        String altitude = Double.isFinite(elevation) ? getString(R.string.location_elevation, formats.number(elevation, "%,.0f")) : null;
        LinearLayout pressureTile = gaugeTile(getString(R.string.pressure), pressureWithUnit,
                altitude, new PressureGauge(this, pressure, formats::pressureReading), 60);
        addMetricGrid(windTile(getString(R.string.wind), wind, direction),
                humidityTile(getString(R.string.humidity), humidity, dewPoint),
                uvTile(getString(R.string.uv_index), uv), pressureTile);
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
            String[] compass = formats.italian() ? new String[]{"N", "NE", "E", "SE", "S", "SO", "O", "NO"}
                    : new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            int index = Math.floorMod((int) Math.round(direction / 45), compass.length);
            int bearing = Math.floorMod((int) Math.round(direction), 360);
            text.addView(views.label(getString(R.string.from_bearing, compass[index], bearing), 13, views.muted));
        }
        readings.addView(new WindIndicator(this, direction), new LinearLayout.LayoutParams(views.dp(46), views.dp(46)));
        return wind;
    }

    private LinearLayout humidityTile(String title, double humidity, double dewPoint) {
        String detail = Double.isFinite(dewPoint) ? getString(R.string.dew_point, formats.temp(dewPoint)) : null;
        return gaugeTile(title, formats.percent(humidity), detail, new HumidityGauge(this, humidity), 38);
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
        return gaugeTile(title, formats.number(index, "%.1f"), level, new UvGauge(this, index), 38);
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
        int width = views.dp(getResources().getConfiguration().screenWidthDp) - page.getPaddingLeft() - page.getPaddingRight();
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
        tile.addView(views.label(getString(R.string.sunrise_sunset), 14, views.muted));
        long seconds = AstronomyTab.daylightSeconds(daily, index);
        if (AstronomyTab.isPolarDaylight(seconds)) {
            TextView status = views.label(getString(AstronomyTab.polarDaylightLabel(seconds)), 13, views.ink);
            status.setPadding(0, views.dp(5), 0, 0);
            tile.addView(status);
        }
        String[] labels = {getString(R.string.sunrise), getString(R.string.solar_noon), getString(R.string.sunset)};
        String[] values = {AstronomyTab.isPolarDaylight(seconds) ? "—" : formats.clock(at(daily, "sunrise", index)), AstronomyTab.solarNoon(daily, index, longitude, ZoneId.of(data.optString("timezone", "UTC")), formats),
                AstronomyTab.isPolarDaylight(seconds) ? "—" : formats.clock(at(daily, "sunset", index))};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout line = views.row();
            line.setPadding(0, views.dp(5), 0, 0);
            line.addView(views.label(labels[i], 12, views.muted), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(views.label(values[i], 13, views.ink));
            tile.addView(line);
        }
        return tile;
    }

    private void settings() {
        startActivityForResult(new android.content.Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.PREFERENCES_FILE, getLocalClassName()), SETTINGS_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SETTINGS_REQUEST || resultCode != RESULT_OK) return;
        boolean changed = readDisplayPreferences(getPreferences(MODE_PRIVATE));
        if (this.data != null) IssAlerts.update(this, latitude, longitude, this.data.optDouble("elevation"),
                this.data.optString("timezone", "UTC"), place);
        if (SkyTheme.preference(this) != appliedTheme || SkyLanguage.preference(this) != appliedLanguage) recreate();
        else if (changed && this.data != null) render();
    }

    private boolean readDisplayPreferences(android.content.SharedPreferences prefs) {
        Formats selected = new Formats(this, SettingsActivity.temperatureUnit(prefs), prefs.getBoolean("clock24", true),
                prefs.getInt("windUnit", 0), prefs.getInt("pressureUnit", 0));
        boolean changed = formats == null || formats.temperatureUnit != selected.temperatureUnit
                || formats.clock24 != selected.clock24 || formats.windUnit != selected.windUnit
                || formats.pressureUnit != selected.pressureUnit;
        changed |= earthquakes.readPreferences(prefs);
        formats = selected;
        return changed;
    }

    private void locations() {
        if (data != null && day < 0 && !choosingLocation) dashboardOffsets[dashboardTab.ordinal()] = scroll.getScrollY();
        stopLocating();
        choosingLocation = true;
        layout();
        LinearLayout searchBar = views.row();
        searchBar.setBackground(views.background(getColor(R.color.sky_header), 13));
        searchBar.addView(views.button("‹", this::home));
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setTextColor(views.ink);
        entry.setHintTextColor(views.muted);
        entry.setTextSize(16);
        entry.setBackgroundColor(Color.TRANSPARENT);
        entry.setHint(getString(R.string.search_location));
        entry.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBar.addView(entry, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = views.button("×", () -> entry.setText(""));
        clear.setContentDescription(getString(R.string.clear_search));
        searchBar.addView(clear);
        clear.setVisibility(View.GONE);
        page.addView(searchBar);

        LinearLayout results = views.column();
        results.setPadding(0, views.dp(12), 0, 0);
        page.addView(results);
        LinearLayout shortcuts = views.column();
        shortcuts.setPadding(0, views.dp(12), 0, 0);
        page.addView(shortcuts);
        TextView currentTitle = views.label(getString(R.string.current_location), 18, views.ink);
        currentTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_current_location, 0, 0, 0);
        currentTitle.setCompoundDrawablePadding(views.dp(8));
        currentTitle.setPadding(0, 0, 0, views.dp(8));
        shortcuts.addView(currentTitle);
        LinearLayout currentCard = views.card();
        currentLocationTitle = views.label("", 16, views.ink);
        currentCard.addView(currentLocationTitle);
        currentLocationWeather = views.label("", 13, views.muted);
        currentLocationWeather.setPadding(0, views.dp(5), 0, 0);
        currentCard.addView(currentLocationWeather);
        updateCurrentLocationShortcut();
        currentCard.setOnClickListener(v -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(entry.getWindowToken(), 0);
            if (usingCurrentLocation && data != null) home();
            else currentLocation(true);
        });
        shortcuts.addView(currentCard);
        TextView savedTitle = views.label(getString(R.string.saved_locations), 18, views.ink);
        savedTitle.setPadding(0, views.dp(16), 0, views.dp(8));
        shortcuts.addView(savedTitle);
        LinearLayout savedList = views.column();
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
            currentLocationWeather.setText(formats.temp(deviceWeather.optDouble("temperature_2m"))
                    + " · " + description(deviceWeather.optInt("weather_code")));
        }
    }

    private void search(EditText entry, LinearLayout results, String query, LinearLayout savedList) {
        if (!results.isAttachedToWindow()) return;
        results.removeAllViews();
        results.addView(views.loadingStatus(getString(R.string.searching)));
        network.execute(() -> {
            try {
                JSONArray items = OpenMeteo.search(query, formats.appLocale().getLanguage()).optJSONArray("results");
                runOnUiThread(() -> {
                    if (!results.isAttachedToWindow() || !query.equals(entry.getText().toString().trim())) return;
                    results.removeAllViews();
                    if (items == null || items.length() == 0) results.addView(views.label(getString(R.string.no_locations_found), 15, views.muted));
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        locationRow(results, items.optJSONObject(i), false, savedList);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!results.isAttachedToWindow() || !query.equals(entry.getText().toString().trim())) return;
                    results.removeAllViews();
                    results.addView(views.label(getString(R.string.search_unavailable), 15, views.muted));
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
        if (items.length() == 0) parent.addView(views.label(getString(R.string.no_saved_locations), 15, views.muted));
        for (int i = 0; i < items.length(); i++) locationRow(parent, items.optJSONObject(i), true, parent);
    }

    private void locationRow(LinearLayout parent, JSONObject item, boolean savedItem, LinearLayout savedList) {
        String name = item.optString("name");
        String placeDetails = item.optString("admin1");
        String country = item.optString("country");
        String countryCode = item.optString("country_code");
        if (countryCode.length() == 2) country = new Locale("", countryCode).getDisplayCountry(formats.appLocale());
        if (!country.isEmpty() && !country.equals(placeDetails)) {
            placeDetails = placeDetails.isEmpty() ? country : placeDetails + ", " + country;
        }
        LinearLayout line = views.row();
        line.setBackground(views.cardBackground(12));
        line.setPadding(views.dp(12), views.dp(5), views.dp(8), views.dp(5));
        LinearLayout open = views.column();
        open.setPadding(0, views.dp(7), views.dp(8), views.dp(7));
        open.addView(views.label(name, 16, views.ink));
        if (!placeDetails.isEmpty()) {
            TextView detail = views.label(placeDetails, 13, views.muted);
            detail.setPadding(0, views.dp(3), 0, 0);
            open.addView(detail);
        }
        open.setOnClickListener(v -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(open.getWindowToken(), 0);
            locationNotice = null;
            usingCurrentLocation = false;
            place = name;
            latitude = item.optDouble("latitude");
            longitude = item.optDouble("longitude");
            selectedCountry = countryCode;
            selectedRegion = item.optString("admin1");
            getPreferences(MODE_PRIVATE).edit().putString("place", place)
                    .putString("latitude", Double.toString(latitude)).putString("longitude", Double.toString(longitude))
                    .putString("countryCode", selectedCountry).apply();
            load();
        });
        line.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        long id = item.optLong("id");
        JSONArray previous = saved();
        boolean alreadySaved = false;
        for (int i = 0; i < previous.length(); i++) {
            if (previous.optJSONObject(i).optLong("id") == id) alreadySaved = true;
        }
        TextView toggle = views.button(savedItem ? getString(R.string.remove) : alreadySaved ? getString(R.string.saved) : getString(R.string.save), () -> {});
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
        views.addSpaced(parent, line, 6);
    }

    private void home() {
        boolean returningFromDay = day >= 0;
        day = -1;
        choosingLocation = false;
        if (data == null) { load(); return; }
        render();
        if (returningFromDay) {
            ScrollView view = scroll;
            view.post(() -> view.scrollTo(0, homeScroll));
        }
    }

    private boolean handlesAppBack() {
        return day >= 0 || choosingLocation || (data != null && dashboardTab != DashboardTab.WEATHER);
    }

    private void handleAppBack() {
        if (day >= 0 || choosingLocation) home();
        else if (data != null && dashboardTab != DashboardTab.WEATHER) switchDashboardTab(DashboardTab.WEATHER);
    }

    private void updateAppBackCallback() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (handlesAppBack()) {
            if (appBackCallback != null) return;
            appBackCallback = this::handleAppBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, appBackCallback);
        } else {
            unregisterAppBackCallback();
        }
    }

    private void unregisterAppBackCallback() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && appBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(appBackCallback);
            appBackCallback = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation") // Back dispatch on Android 12 and earlier.
    public void onBackPressed() {
        if (handlesAppBack()) handleAppBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        unregisterAppBackCallback();
        stopLocating();
        if (pendingSearch != null) main.removeCallbacks(pendingSearch);
        network.shutdownNow();
        super.onDestroy();
    }

    private String weatherNote(DailyWeather summary) {
        return getString(R.string.forecast_weather_event, description(summary.eventCode), formats.clock(summary.eventTime));
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
        return LocalDate.parse(at(daily, "time", index)).format(DateTimeFormatter.ofPattern(formats.italian() ? "EEEE d MMM" : "EEEE, MMM d", formats.appLocale()));
    }

    private String range(JSONObject daily, int index) {
        return formats.temp(value(daily, "temperature_2m_max", index)) + "/" + formats.temp(value(daily, "temperature_2m_min", index));
    }

    private String rainChance(JSONObject data, String key, int index) {
        double chance = value(data, key, index);
        return chance > 0 ? formats.number(chance, "%.0f") + "%" : "";
    }

    private static int length(JSONObject data) { return ForecastSeries.length(data); }
    private static String at(JSONObject data, String key, int index) { return ForecastSeries.text(data, key, index); }
    private static double value(JSONObject data, String key, int index) { return ForecastSeries.value(data, key, index); }

    private String description(int code) {
        return WeatherCodes.description(this, code);
    }

    private TextView title(String text) {
        TextView title = views.sectionTitle(text);
        page.addView(title);
        return title;
    }
}
