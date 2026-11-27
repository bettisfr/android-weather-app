package it.skynow.app;

import it.skynow.app.update.GitHubUpdates;
import it.skynow.app.notifications.IssAlerts;
import it.skynow.app.notifications.EarthquakeAlerts;
import it.skynow.app.notifications.EarthquakeAlertCheck;
import it.skynow.app.astronomy.IssPasses;
import it.skynow.app.maps.RadarActivity;
import it.skynow.app.dashboard.AstronomyTab;
import it.skynow.app.dashboard.EarthquakeTab;
import it.skynow.app.dashboard.WeatherTab;
import it.skynow.app.ui.Formats;
import it.skynow.app.ui.SkyViews;
import it.skynow.app.weather.ForecastSeries;
import it.skynow.app.weather.ForecastCache;
import it.skynow.app.weather.OpenMeteo;
import it.skynow.app.weather.WeatherCodes;

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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION = 1;
    private static final int ASTRONOMY_NOTIFICATION_PERMISSION = 2;
    private static final long FORECAST_REFRESH_MS = 5 * 60 * 1000;
    private static final int REFRESH_PULL_DP = 72;
    private SkyViews views;
    private Formats formats;
    private EarthquakeTab earthquakes;
    private AstronomyTab astronomy;
    private WeatherTab weather;
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
            weather.tick(now);
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
        weather = new WeatherTab(this, views, new WeatherTab.Host() {
            @Override public Formats formats() { return formats; }
            @Override public boolean inItaly() { return MainActivity.this.inItaly(); }
            @Override public String selectedRegion() { return selectedRegion; }
            @Override public String locationNotice() { return locationNotice; }
            @Override public Instant cachedForecastAt() { return cachedForecastAt; }
            @Override public void openMap(RadarActivity.Layer layer) { MainActivity.this.openMap(layer); }
            @Override public void showDay(int index) { MainActivity.this.showDay(index); }
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
                    weather.refreshWarnings();
                    weather.renderWarnings(Instant.now());
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
        weather.refreshWarnings();
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
            weather.resetRefreshThrottles();
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
                        for (int i = 0; i < Math.min(WeatherTab.MAX_DAYS, length(refreshed)); i++) {
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
        weather.detach();
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
        weather.attach(page, data, day, latitude, longitude);
    }

    private void updateItalianMaps() {
        weather.updateItalianMaps();
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

    private void showDay(int index) {
        if (day < 0) homeScroll = scroll.getScrollY();
        day = index;
        render();
    }

    private void openMap(RadarActivity.Layer layer) {
        if (data == null) return;
        startActivity(new android.content.Intent(this, RadarActivity.class)
                .putExtra("layer", layer.name()).putExtra("place", place).putExtra("lat", latitude).putExtra("lon", longitude)
                .putExtra("timeZone", data.optString("timezone", "UTC")).putExtra("clock24", formats.clock24));
    }

    private boolean inItaly() {
        return "IT".equalsIgnoreCase(selectedCountry);
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

    private static int length(JSONObject data) { return ForecastSeries.length(data); }
    private static String at(JSONObject data, String key, int index) { return ForecastSeries.text(data, key, index); }

    private String description(int code) {
        return WeatherCodes.description(this, code);
    }

    private TextView title(String text) {
        TextView title = views.sectionTitle(text);
        page.addView(title);
        return title;
    }
}
