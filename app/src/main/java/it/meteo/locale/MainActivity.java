package it.meteo.locale;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.view.MotionEvent;
import android.view.ViewConfiguration;
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
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter CLOCK_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private static final DateTimeFormatter CLOCK_SECONDS = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US);
    private static final DateTimeFormatter CLOCK_24_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US);
    private static final DateTimeFormatter DETAIL_CLOCK = DateTimeFormatter.ofPattern("h a", Locale.US);
    private static final int[] MOON_PHASE_NAMES = {R.string.moon_new, R.string.moon_waxing_crescent, R.string.moon_first_quarter,
            R.string.moon_waxing_gibbous, R.string.moon_full, R.string.moon_waning_gibbous,
            R.string.moon_last_quarter, R.string.moon_waning_crescent};
    private static final int ASTRONOMY_PAGE_SIZE = 4;
    private static final int FORECAST_DAYS = 10;
    private static final int LOCATION_PERMISSION = 1;
    private static final long FORECAST_REFRESH_MS = 5 * 60 * 1000;
    private static final int REFRESH_PULL_DP = 72;
    private static final int DETAIL_CHART_HEIGHT_DP = 180;
    private static final int SKY_PATH_TIMES_WIDTH_DP = 93;
    private static final int SKY_PATH_HEIGHT_DP = 90;
    private int ink;
    private int muted;
    private int accent;
    private int surfaceColor;
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
    private Instant renderedWarningUntil;
    private AuroraForecast auroraForecast;
    private long auroraAttempt;
    private boolean auroraLoading;
    private TextView auroraStatus;
    private TextView auroraTimes;
    private TextView currentKpView;
    private View currentKpBar;
    private AuroraForecast.KpReading currentKp;
    private LinearLayout kpTable;
    private List<AuroraForecast.KpPeriod> kpPeriods = List.of();
    private long kpAttempt;
    private boolean kpLoading;
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
    private enum DashboardTab { WEATHER, ASTRONOMY, EARTHQUAKES }
    private DashboardTab dashboardTab = DashboardTab.WEATHER;
    private final int[] dashboardOffsets = new int[DashboardTab.values().length];
    private Button earthquakeTabButton;
    private int homeScroll;
    private boolean choosingLocation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView sunCoordinates;
    private TextView moonCoordinates;
    private final List<PlanetRow> planetRows = new ArrayList<>();
    private LocalDate planetWindowDate;
    private double magneticDeclination = Double.NaN;
    private boolean resumed;
    private TextView phoneDirection;
    private final float[] phoneRotation = new float[9];
    private final SensorEventListener phoneOrientation = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (phoneDirection == null) return;
            SensorManager.getRotationMatrixFromVector(phoneRotation, event.values);
            // The rear camera points along the negative device Z axis.
            double east = -phoneRotation[2];
            double north = -phoneRotation[5];
            double horizontal = Math.hypot(east, north);
            long elevation = Math.round(Math.toDegrees(Math.atan2(-phoneRotation[8], horizontal)));
            double heading = Math.toDegrees(Math.atan2(east, north));
            boolean trueNorth = Double.isFinite(magneticDeclination);
            if (trueNorth) heading += magneticDeclination;
            long azimuth = Math.floorMod(Math.round(heading), 360);
            boolean unreliable = event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE;
            boolean lowAccuracy = unreliable || event.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW;
            String direction = unreliable || Math.abs(elevation) == 90 ? "—" : azimuth + "° " + compassDirection(azimuth);
            String reading = getString(trueNorth ? R.string.phone_direction_reading : R.string.phone_direction_magnetic,
                    direction, elevation);
            if (lowAccuracy) reading += "\n" + getString(R.string.phone_compass_low_accuracy);
            if (!reading.contentEquals(phoneDirection.getText())) phoneDirection.setText(reading);
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final Runnable updateSkyCoordinates = new Runnable() {
        @Override public void run() {
            Instant now = Instant.now();
            if (sunCoordinates != null) setSkyCoordinates(sunCoordinates, now, false);
            if (moonCoordinates != null) setSkyCoordinates(moonCoordinates, now, true);
            if (!planetRows.isEmpty()) updatePlanets(now);
            if (renderedWarningUntil != null && !now.isBefore(renderedWarningUntil)) renderWarnings(now);
            refreshWarnings();
            refreshAurora();
            if (kpTable != null && kpTable.isShown()) refreshKp();
            updateAurora(now);
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
    private TextView earthquakeTitle;
    private LinearLayout earthquakePanel;
    private int earthquakeRadius;
    private int earthquakeMinimumMagnitude;
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
        ink = getColor(R.color.sky_text);
        muted = getColor(R.color.sky_muted);
        accent = getColor(R.color.sky_accent);
        surfaceColor = getColor(R.color.sky_background);
        SkyTheme.applySystemBars(this);
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        place = prefs.getString("place", "Bastia Umbra");
        latitude = Double.parseDouble(prefs.getString("latitude", "43.066"));
        longitude = Double.parseDouble(prefs.getString("longitude", "12.548"));
        fahrenheit = prefs.getBoolean("fahrenheit", false);
        clock24 = prefs.getBoolean("clock24", true);
        windUnit = prefs.getInt("windUnit", 0);
        pressureUnit = prefs.getInt("pressureUnit", 0);
        earthquakeRadius = prefs.getInt("earthquakeRadius", EarthquakeFeed.DEFAULT_RADIUS);
        earthquakeMinimumMagnitude = prefs.getInt("earthquakeMinimumMagnitude", 0);
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
            currentLocation(!prefs.getBoolean("askedForLocation", false));
        }
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
            page.addView(header(getString(R.string.current_location), this::locations));
            page.addView(loadingStatus(getString(R.string.finding_location)));
        }
        LocationManager manager = getSystemService(LocationManager.class);
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (locationListener != this) return;
                stopLocating();
                magneticDeclination = new GeomagneticField((float) location.getLatitude(),
                        (float) location.getLongitude(), (float) location.getAltitude(), location.getTime()).getDeclination();
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
            locationTimeout = () -> locationFailed(R.string.current_location_unavailable, manual);
            main.postDelayed(locationTimeout, 12000);
        } else {
            locationFailed(R.string.current_location_unavailable, manual);
        }
    }

    private void resolveLocation(double lat, double lon) {
        if (!Geocoder.isPresent()) return;
        boolean gps = usingCurrentLocation;
        Geocoder geocoder = new Geocoder(this, appLocale());
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
                    if (gps) deviceCountry = country;
                    if (region != null) selectedRegion = region;
                    refreshWarnings();
                    renderWarnings(Instant.now());
                    updateItalianMaps();
                    renderEarthquakes();
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
        int offset = cachedForecastAt != null && scroll != null ? scroll.getScrollY() : 0;
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
            page.addView(loadingStatus(getString(R.string.loading_forecast)));
        }
        requestForecast(hasCache, manual);
        if ((usingCurrentLocation && devicePlace == null) || selectedCountry.isEmpty()
                || (inItaly() && selectedRegion.isEmpty())) resolveLocation(latitude, longitude);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshWarnings();
        startPhoneOrientation();
        updateSkyCoordinates.run();
        if (data == null || choosingLocation || locationListener != null || forecastLoading) return;
        ZonedDateTime localHour = Instant.now().atZone(ZoneId.of(data.optString("timezone", "UTC")))
                .truncatedTo(ChronoUnit.HOURS);
        if (SystemClock.elapsedRealtime() - forecastAttemptAt >= FORECAST_REFRESH_MS
                || !localHour.equals(forecastHour)) requestForecast(true);
    }

    @Override protected void onPause() {
        resumed = false;
        getSystemService(SensorManager.class).unregisterListener(phoneOrientation);
        main.removeCallbacks(updateSkyCoordinates);
        super.onPause();
    }

    private void requestForecast(boolean refreshing) {
        requestForecast(refreshing, false);
    }

    private void requestForecast(boolean refreshing, boolean manual) {
        manualRefresh = manual;
        if (manual) warningsAttempt = 0;
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
                    cachedForecastAt = null;
                    ForecastCache.write(this, serialized, updatedAt, lat, lon, place, selectedCountry, selectedRegion);
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
                    manualRefresh = false;
                    if (refreshIndicator != null) refreshIndicator.setVisibility(View.GONE);
                    if (manual && !choosingLocation) {
                        Toast.makeText(this, R.string.forecast_unavailable, Toast.LENGTH_SHORT).show();
                    }
                    if (refreshing || choosingLocation) return;
                    layout();
                    page.addView(placeTitle());
                    page.addView(label(getString(R.string.forecast_unavailable), 22, ink));
                    page.addView(button(getString(R.string.retry), this::load));
                });
            }
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                canPullToRefresh = dashboardTab == DashboardTab.WEATHER && refreshIndicator != null && !forecastLoading && locationListener == null
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
                refreshIndicator.setAlpha(Math.max(0f, Math.min(1f, dy / dp(REFRESH_PULL_DP))));
                refreshIndicator.setVisibility(View.VISIBLE);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                canPullToRefresh = false;
                if (!pullingToRefresh) break;
                pullingToRefresh = false;
                if (event.getActionMasked() == MotionEvent.ACTION_UP && !forecastLoading
                        && event.getY() - pullStartY >= dp(REFRESH_PULL_DP)) {
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
        placeHeader = null;
        regionalWarnings = null;
        auroraStatus = null;
        auroraTimes = null;
        currentKpView = null;
        currentKpBar = null;
        kpTable = null;
        forecastMaps = null;
        airQualityMapButton = null;
        earthquakeTitle = null;
        earthquakePanel = null;
        earthquakeTabButton = null;
        sunCoordinates = null;
        moonCoordinates = null;
        if (phoneDirection != null) {
            getSystemService(SensorManager.class).unregisterListener(phoneOrientation);
            phoneDirection = null;
        }
        planetRows.clear();
        planetWindowDate = null;
        currentLocationTitle = null;
        currentLocationWeather = null;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(surfaceColor);
        scroll.setFillViewport(true);
        page = column();
        page.setPadding(dp(16), dp(12), dp(16), dp(20));
        scroll.addView(page);
        canPullToRefresh = false;
        pullingToRefresh = false;
        refreshIndicator = null;
        View forecastView = scroll;
        if (data != null && !choosingLocation) {
            FrameLayout viewport = new FrameLayout(this);
            viewport.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            refreshIndicator = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
            refreshIndicator.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
            refreshIndicator.setContentDescription(getString(R.string.loading_forecast));
            refreshIndicator.setBackground(background(getColor(R.color.sky_card), dp(18)));
            refreshIndicator.setPadding(dp(7), dp(7), dp(7), dp(7));
            refreshIndicator.setElevation(dp(3));
            refreshIndicator.setVisibility(manualRefresh ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams indicatorSize = new FrameLayout.LayoutParams(dp(36), dp(36),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            indicatorSize.topMargin = dp(8);
            viewport.addView(refreshIndicator, indicatorSize);
            forecastView = viewport;
        }
        if (day >= 0 && !choosingLocation) {
            LinearLayout screen = column();
            screen.setBackgroundColor(surfaceColor);
            LinearLayout toolbar = column();
            toolbar.setPadding(dp(16), dp(8), dp(16), 0);
            toolbar.addView(header("‹  " + getString(R.string.forecast_10_days), this::home));
            screen.addView(toolbar);
            screen.addView(forecastView, new LinearLayout.LayoutParams(-1, 0, 1));
            setContentView(screen);
        } else if (data != null && !choosingLocation) {
            LinearLayout screen = column();
            screen.setBackgroundColor(surfaceColor);
            LinearLayout top = row();
            top.setPadding(dp(16), dp(6), dp(16), dp(4));
            top.addView(placeTitle(), new LinearLayout.LayoutParams(0, -2, 1));
            TextView settings = button("⋮", this::settings);
            settings.setTextSize(24);
            settings.setMinWidth(dp(46));
            settings.setContentDescription(getString(R.string.settings));
            top.addView(settings);
            screen.addView(top);
            screen.addView(forecastView, new LinearLayout.LayoutParams(-1, 0, 1));
            screen.addView(dashboardNavigation());
            setContentView(screen);
        } else {
            setContentView(forecastView);
        }
    }

    private void render() {
        choosingLocation = false;
        if (dashboardTab == DashboardTab.EARTHQUAKES && !showsEarthquakeTab()) dashboardTab = DashboardTab.WEATHER;
        layout();
        JSONObject current = data.optJSONObject("current");
        JSONObject hourly = data.optJSONObject("hourly");
        JSONObject daily = data.optJSONObject("daily");
        if (day < 0) {
            ScrollView target = scroll;
            int offset = dashboardOffsets[dashboardTab.ordinal()];
            target.post(() -> target.scrollTo(0, offset));
            if (dashboardTab == DashboardTab.ASTRONOMY) {
                sun(daily, 0);
                moon(daily, 0);
                planets();
                issPasses();
                aurora();
                eclipsesAndTransits();
                return;
            }
            if (dashboardTab == DashboardTab.EARTHQUAKES) {
                earthquakeTitle = title(getString(R.string.seismic_activity));
                earthquakePanel = card();
                page.addView(earthquakePanel);
                renderEarthquakes();
                return;
            }
        }
        int todayHour = currentHour(current, hourly);
        String today = at(daily, "time", 0);
        double rainRemaining = remainingMaximum(hourly, "precipitation_probability", todayHour, today);
        int forecastDays = Math.min(FORECAST_DAYS, length(daily));
        DayWeather[] summaries = new DayWeather[forecastDays];
        for (int index = 0; index < forecastDays; index++) {
            String date = at(daily, "time", index);
            double dailyCode = value(daily, "weather_code", index);
            int fallback = index == 0 ? current.optInt("weather_code") : (int) dailyCode;
            summaries[index] = summarizeWeather(hourly, index == 0 ? todayHour : 0, date, fallback);
        }
        if (day < 0) {
            if (locationNotice != null) page.addView(label(locationNotice, 13, muted));
            if (cachedForecastAt != null) {
                ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
                ZonedDateTime updated = cachedForecastAt.atZone(zone);
                String timestamp = updated.format(shortDate(true)) + " · " + updated.format(clockFormat());
                addSpaced(page, label(getString(R.string.cached_forecast, timestamp), 13, muted), 4);
            }
            title(getString(R.string.now));
            LinearLayout hero = row();
            TextView temperature = label(temp(current.optDouble("temperature_2m")), 76, ink);
            temperature.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            temperature.setLetterSpacing(-0.045f);
            hero.addView(temperature);
            TextView icon = label(symbol(current.optInt("weather_code"), current.optInt("is_day") == 0), 37, ink);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setPadding(dp(6), 0, 0, 0);
            hero.addView(icon);
            LinearLayout summary = column();
            summary.setGravity(Gravity.END);
            summary.setPadding(dp(12), 0, 0, 0);
            TextView condition = label(description(current.optInt("weather_code")), 17, ink);
            condition.setGravity(Gravity.END);
            summary.addView(condition);
            TextView feelsLike = label(getString(R.string.feels_like_value, temp(current.optDouble("apparent_temperature"))), 14, muted);
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
                    temp(value(daily, "temperature_2m_min", 0))), 15, muted);
            addSpaced(page, extremes, 10);
            regionalWarnings = column();
            page.addView(regionalWarnings);
            renderWarnings(Instant.now());
            refreshWarnings();
            forecast(hourly, todayHour, todayHour + 24, true);
            title(getString(R.string.forecast_10_days));
            for (int index = 0; index < forecastDays; index++) {
                final int selected = index;
                DayWeather dayWeather = summaries[index];
                int weather = dayWeather.code;
                boolean roomy = largeText();
                LinearLayout tile = roomy ? column() : row();
                tile.setBackground(cardBackground(12));
                tile.setPadding(dp(14), dp(11), dp(14), dp(11));
                TextView dateLabel = label(date(daily, index), 16, ink);
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
                TextView chance = label(probability > 0 ? percent(probability) : "", 14, accent);
                chance.setGravity(roomy ? Gravity.START : Gravity.END);
                chance.setSingleLine(true);
                int chanceWidth = Math.max(dp(40), Math.max(textWidth(chance, percent(100)),
                        textWidth(chance, chance.getText().toString())) + dp(4));
                values.addView(chance, new LinearLayout.LayoutParams(chanceWidth, -2, roomy ? 1 : 0));
                TextView dayIcon = label(symbol(weather, dayWeather.night), 23, ink);
                dayIcon.setContentDescription(description(weather));
                dayIcon.setGravity(Gravity.CENTER);
                int iconWidth = Math.max(dp(38), dayIcon.getLineHeight() + dp(6));
                values.addView(dayIcon, new LinearLayout.LayoutParams(iconWidth, -2));
                TextView temperatures = label(range(daily, index), 16, ink);
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
            airQuality();
            updateItalianMaps();
            details(hourly, daily, 0, todayHour, todayHour + 24, true);
        } else {
            LinearLayout dates = carousel(page);
            View selectedChip = null;
            for (int index = 0; index < forecastDays; index++) {
                final int selected = index;
                String key = index == 0 ? getString(R.string.today) : LocalDate.parse(at(daily, "time", index)).format(DateTimeFormatter.ofPattern("EEE", appLocale()));
                DayWeather summary = summaries[index];
                int chipWeather = summary.code;
                String chipRange = range(daily, index);
                TextView chip = button(key + "\n" + symbol(chipWeather, summary.night) + "\n" + chipRange, () -> {
                    day = selected;
                    render();
                });
                chip.setContentDescription(key + ", " + description(chipWeather) + ", " + chipRange);
                chip.setBackground(background(index == day ? getColor(R.color.sky_selected) : getColor(R.color.sky_card), 10));
                chip.setPadding(dp(8), dp(11), dp(8), dp(11));
                chip.setLineSpacing(dp(4), 1f);
                chip.setMinHeight(dp(96));
                int chipWidth = Math.max(dp(74), textWidth(chip, chipRange) + dp(22));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(chipWidth, -2);
                if (index > 0) size.leftMargin = dp(8);
                dates.addView(chip, size);
                if (index == day) selectedChip = chip;
            }
            View active = selectedChip;
            HorizontalScrollView strip = (HorizontalScrollView) dates.getParent();
            if (active != null) strip.post(() -> strip.scrollTo(active.getLeft() - (strip.getWidth() - active.getWidth()) / 2, 0));
            DayWeather summary = summaries[day];
            int weather = summary.code;
            title(date(daily, day));
            String temperatureRange = range(daily, day);
            TextView dailyTemperature = label(temperatureRange + "  " + symbol(weather, summary.night), 49, ink);
            dailyTemperature.setContentDescription(temperatureRange);
            page.addView(dailyTemperature);
            page.addView(label(description(weather), 17, muted));
            if (summary.eventTime != null) page.addView(label(weatherNote(summary), 14, muted));
            String date = at(daily, "time", day);
            int start = 0;
            while (start < length(hourly) && !at(hourly, "time", start).startsWith(date)) start++;
            int end = start;
            while (end < length(hourly) && at(hourly, "time", end).startsWith(date)) end++;
            if (day == 0) start = todayHour;
            forecast(hourly, start, end, day == 0);
            title(getString(R.string.daily_conditions));
            addMetricGrid(windTile(getString(R.string.max_wind), windSpeed(value(daily, "wind_speed_10m_max", day), true),
                            value(daily, "wind_direction_10m_dominant", day)),
                    humidityTile(getString(R.string.average_humidity), value(daily, "relative_humidity_2m_mean", day), Double.NaN),
                    uvTile(getString(R.string.max_uv), value(daily, "uv_index_max", day)), dailySunTimes(daily, day));
            details(hourly, daily, day, start, end, day == 0);
        }
    }

    private void forecast(JSONObject hourly, int start, int end, boolean now) {
        if (day < 0) {
            LinearLayout heading = row();
            heading.setPadding(0, dp(12), 0, dp(7));
            TextView caption = label(getString(R.string.hourly_forecast), 19, ink);
            caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            heading.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout maps = row();
            maps.addView(mapButton(R.string.radar, () -> openItalianMap(RadarActivity.Layer.RADAR)));
            Button satellite = mapButton(R.string.satellite, () -> openItalianMap(RadarActivity.Layer.SATELLITE));
            LinearLayout.LayoutParams satelliteSize = new LinearLayout.LayoutParams(-2, -2);
            satelliteSize.setMarginStart(dp(5));
            maps.addView(satellite, satelliteSize);
            heading.addView(maps);
            forecastMaps = maps;
            page.addView(heading);
        } else {
            title(getString(R.string.hourly_forecast));
        }
        LinearLayout box = card();
        LinearLayout hours = carousel(box);
        for (int i = start; i < Math.min(end, length(hourly)); i++) {
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(9), 0, dp(7));
            String time = now && i == start ? getString(R.string.now) : clock(at(hourly, "time", i));
            TextView timeLabel = label(time, 13, muted);
            int hourWidth = Math.max(dp(67), textWidth(timeLabel, clock24 ? "23:00" : "12:00 PM") + dp(12));
            hours.addView(cell, new LinearLayout.LayoutParams(hourWidth, -2));
            cell.addView(label(temp(value(hourly, "temperature_2m", i)), 21, ink), new LinearLayout.LayoutParams(-2, -2));
            TextView chance = label(rainChance(hourly, "precipitation_probability", i), 13, accent);
            chance.setGravity(Gravity.CENTER);
            chance.setMinHeight(dp(24));
            cell.addView(chance, new LinearLayout.LayoutParams(-1, -2));
            int code = (int) value(hourly, "weather_code", i);
            TextView weather = label(symbol(code, value(hourly, "is_day", i) == 0), 26, ink);
            weather.setContentDescription(description(code));
            weather.setGravity(Gravity.CENTER);
            weather.setMinHeight(dp(38));
            cell.addView(weather, new LinearLayout.LayoutParams(-1, -2));
            cell.addView(timeLabel, new LinearLayout.LayoutParams(-2, -2));
        }
        page.addView(box);
    }

    private Button mapButton(int title, Runnable open) {
        Button button = actionButton(getString(title), getColor(R.color.sky_action), 24);
        int foreground = getColor(R.color.sky_on_action);
        button.setTextColor(foreground);
        button.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(foreground));
        button.setCompoundDrawablePadding(dp(7));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_radar, 0, 0, 0);
        button.setOnClickListener(view -> open.run());
        return button;
    }

    private void updateItalianMaps() {
        int visibility = inItaly() ? View.VISIBLE : View.GONE;
        if (forecastMaps != null) forecastMaps.setVisibility(visibility);
        if (airQualityMapButton != null) airQualityMapButton.setVisibility(visibility);
        if (earthquakeTabButton != null) earthquakeTabButton.setVisibility(showsEarthquakeTab() ? View.VISIBLE : View.GONE);
    }

    private boolean showsEarthquakeTab() {
        return inItaly() && (deviceCountry.isEmpty() || "IT".equalsIgnoreCase(deviceCountry));
    }

    private LinearLayout dashboardNavigation() {
        LinearLayout navigation = row();
        navigation.setBackgroundColor(getColor(R.color.sky_card));
        navigation.setPadding(dp(8), dp(6), dp(8), dp(6));
        for (DashboardTab tab : DashboardTab.values()) {
            int label;
            switch (tab) {
                case ASTRONOMY: label = R.string.astronomy; break;
                case EARTHQUAKES: label = R.string.tab_earthquakes; break;
                default: label = R.string.tab_weather;
            }
            boolean selected = tab == dashboardTab;
            Button item = actionButton(getString(label), getColor(selected ? R.color.sky_selected : R.color.sky_card), 12);
            item.setSelected(selected);
            item.setMinHeight(dp(48));
            item.setPadding(dp(3), dp(6), dp(3), dp(6));
            item.setOnClickListener(v -> switchDashboardTab(tab));
            if (tab == DashboardTab.EARTHQUAKES) {
                earthquakeTabButton = item;
                item.setVisibility(showsEarthquakeTab() ? View.VISIBLE : View.GONE);
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
                .putExtra("timeZone", data.optString("timezone", "UTC")).putExtra("clock24", clock24));
    }

    private void renderEarthquakes() {
        LinearLayout panel = earthquakePanel;
        if (panel == null) return;
        boolean show = showsEarthquakeTab();
        earthquakeTitle.setVisibility(show ? View.VISIBLE : View.GONE);
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        panel.removeAllViews();
        LinearLayout heading = row();
        heading.setGravity(Gravity.TOP);
        heading.setBaselineAligned(false);
        heading.addView(label(getString(R.string.earthquakes), 16, ink), new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(heading);
        if (!"IT".equalsIgnoreCase(deviceCountry) || !Double.isFinite(deviceLatitude) || !Double.isFinite(deviceLongitude)) {
            panel.addView(label(getString(R.string.earthquake_location_required), 13, muted));
            return;
        }
        double lat = deviceLatitude;
        double lon = deviceLongitude;
        int radius = earthquakeRadius;
        Button map = mapButton(R.string.aurora_map_short, () -> startActivity(new android.content.Intent(this, RadarActivity.class)
                .putExtra("layer", RadarActivity.Layer.EARTHQUAKES.name())
                .putExtra("place", devicePlace == null ? getString(R.string.current_location) : devicePlace)
                .putExtra("lat", lat).putExtra("lon", lon).putExtra("radiusKm", radius)
                .putExtra("minimumMagnitude", earthquakeMinimumMagnitude)
                .putExtra("timeZone", "Europe/Rome").putExtra("clock24", clock24)));
        map.setContentDescription(getString(R.string.earthquake_map));
        heading.addView(map);
        addSpaced(panel, label(getString(R.string.earthquake_scope, radius), 13, muted), 8);
        TextView status = label(getString(R.string.earthquake_loading), 13, muted);
        panel.addView(status);
        CompletableFuture.supplyAsync(() -> {
            try {
                return EarthquakeFeed.recent(lat, lon, radius);
            } catch (Exception error) {
                android.util.Log.w("SkyHere", "INGV events unavailable", error);
                return null;
            }
        }).thenAccept(events -> main.post(() -> {
            if (isDestroyed() || panel != earthquakePanel || status.getParent() != panel) return;
            if (events == null || events.isEmpty()) {
                status.setText(events == null ? R.string.earthquake_unavailable : R.string.earthquake_empty);
                return;
            }
            panel.removeView(status);
            Spinner filter = new Spinner(this);
            int[] thresholds = {0, 2, 3, 4};
            String[] options = {getString(R.string.earthquake_all_magnitudes), "M ≥ 2", "M ≥ 3", "M ≥ 4"};
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            filter.setAdapter(adapter);
            filter.setContentDescription(getString(R.string.earthquake_magnitude));
            for (int i = 0; i < thresholds.length; i++) {
                if (thresholds[i] == earthquakeMinimumMagnitude) filter.setSelection(i);
            }
            addSpaced(panel, filter, 5);
            LinearLayout rows = column();
            panel.addView(rows);
            TextView empty = label(getString(R.string.earthquake_filter_empty), 13, muted);
            empty.setVisibility(View.GONE);
            panel.addView(empty);
            Button more = moreButton();
            more.setVisibility(View.GONE);
            panel.addView(more);
            filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    earthquakeMinimumMagnitude = thresholds[position];
                    getPreferences(MODE_PRIVATE).edit().putInt("earthquakeMinimumMagnitude", earthquakeMinimumMagnitude).apply();
                    List<EarthquakeFeed.Event> visible = new ArrayList<>();
                    for (EarthquakeFeed.Event event : events) {
                        if (event.matchesMagnitude(earthquakeMinimumMagnitude)) visible.add(event);
                    }
                    rows.removeAllViews();
                    empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
                    more.setOnClickListener(v -> appendEarthquakes(rows, more, visible, lat, lon, 15));
                    appendEarthquakes(rows, more, visible, lat, lon, 5);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }));
    }

    private void appendEarthquakes(LinearLayout rows, Button more, List<EarthquakeFeed.Event> events,
                                   double lat, double lon, int count) {
        int start = rows.getChildCount();
        int end = Math.min(start + count, events.size());
        DateTimeFormatter date = DateTimeFormatter.ofPattern("EEE d MMM, " + (clock24 ? "HH:mm" : "h:mm a"), appLocale())
                .withZone(ZoneId.of("Europe/Rome"));
        float[] distance = new float[1];
        for (int i = start; i < end; i++) {
            EarthquakeFeed.Event event = events.get(i);
            Location.distanceBetween(lat, lon, event.latitude, event.longitude, distance);
            LinearLayout entry = column();
            entry.setPadding(dp(12), dp(8), dp(12), dp(8));
            entry.setBackground(background(surfaceColor, 12));
            LinearLayout heading = row();
            heading.setGravity(Gravity.TOP);
            TextView magnitude = label(event.magnitudeType + " " + number(event.magnitude, "%.1f"), 13, 0xff000000);
            magnitude.setPadding(dp(5), dp(2), dp(5), dp(2));
            magnitude.setBackground(background(EarthquakeFeed.magnitudeColor(event.magnitude), 6));
            heading.addView(magnitude);
            TextView zone = label(event.zone, 15, ink);
            LinearLayout.LayoutParams zoneSize = new LinearLayout.LayoutParams(0, -2, 1);
            zoneSize.setMarginStart(dp(6));
            heading.addView(zone, zoneSize);
            entry.addView(heading);
            entry.addView(label(getString(R.string.earthquake_details, date.format(event.time), Math.round(distance[0] / 1000),
                    number(event.depth, "%.1f")), 13, muted));
            entry.setOnClickListener(v -> openAstronomyPage(event.url()));
            addSpaced(rows, entry, 5);
        }
        more.setVisibility(end < events.size() ? View.VISIBLE : View.GONE);
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
        renderedWarningUntil = null;
        if (regionalWarnings == null) return;
        regionalWarnings.removeAllViews();
        if (!inItaly() || selectedRegion.isEmpty()) return;
        String region = MeteoAlarm.regionKey(selectedRegion);
        DateTimeFormatter validity = DateTimeFormatter.ofPattern("EEE d MMM, " + (clock24 ? "HH:mm" : "h:mm a"), appLocale())
                .withZone(ZoneId.of("Europe/Rome"));
        for (MeteoAlarm.Warning warning : warnings) {
            if (!MeteoAlarm.regionKey(warning.area).equals(region) || !now.isBefore(warning.expires)) continue;
            if (renderedWarningUntil == null || warning.expires.isBefore(renderedWarningUntil)) {
                renderedWarningUntil = warning.expires;
            }
            LinearLayout banner = card();
            String event = warning.event.toLowerCase(Locale.ROOT);
            int color = event.startsWith("red ") ? getColor(R.color.alert_red)
                    : event.startsWith("orange ") ? getColor(R.color.alert_orange) : getColor(R.color.alert_yellow);
            banner.setBackground(background(color, 12));
            TextView heading = label(warningTitle(warning.event), 16, 0xff322414);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            banner.addView(heading);
            TextView details = label(getString(R.string.regional_warning_validity, warning.area,
                    validity.format(warning.start), validity.format(warning.expires)), 13, 0xff322414);
            details.setPadding(0, dp(4), 0, 0);
            banner.addView(details);
            banner.setOnClickListener(v -> startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(warning.url()))));
            addSpaced(regionalWarnings, banner, 8);
        }
    }

    private String warningTitle(String event) {
        if (!italian()) return event;
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
            for (int i = 0; i < buttons.length; i++) buttons[i].setBackground(background(i == tab ? getColor(R.color.sky_selected) : getColor(R.color.sky_subtle), 8));
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
        summary.addView(label(heading, 13, muted));
        summary.addView(label(value, 25, ink));
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
        int legendHeight = Math.max(dp(19), label("", 11, muted).getLineHeight() * (largeText() ? 2 : 1));
        int legendGap = dp(5);
        TextView axisLabel = label("", 11, muted);
        int timeHeight = Math.max(dp(20), axisLabel.getLineHeight() + dp(5));
        int directionHeight = dp(25);
        chart.setPadding(0, topGap, 0, 0);
        int chartHeight = dp(DETAIL_CHART_HEIGHT_DP) + (largeText() ? dp(32) : 0);
        body.addView(chart, new LinearLayout.LayoutParams(-1, chartHeight));
        String firstName = new String[]{getString(R.string.amount), getString(R.string.wind_speed), getString(R.string.humidity), getString(R.string.temperature)}[tab];
        String secondName = new String[]{getString(R.string.rain_chance), getString(R.string.gusts), "", getString(R.string.feels_like)}[tab];
        LinearLayout legend = row();
        TextView firstLegend = label((precipitation ? "■ " : "● ") + firstName, 11, getColor(R.color.sky_accent));
        if (largeText()) legend.addView(firstLegend, new LinearLayout.LayoutParams(0, -2, 1));
        else legend.addView(firstLegend);
        if (!humidity) {
            TextView secondKey = label("┄ " + secondName, 11, getColor(R.color.sky_chart_secondary));
            secondKey.setPadding(dp(12), 0, 0, 0);
            if (largeText()) legend.addView(secondKey, new LinearLayout.LayoutParams(0, -2, 1));
            else legend.addView(secondKey);
        }
        String unit = new String[]{"mm", WIND_UNITS[windUnit], "%", fahrenheit ? "°F" : "°C"}[tab];
        TextView units = label(precipitation ? "mm / %" : unit, 11, muted);
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
        if (now) minimumHourWidth = Math.max(minimumHourWidth, textWidth(axisLabel, getString(R.string.now)) + dp(4));
        int hourWidth = Math.max(Math.max(dp(32), minimumHourWidth),
                (screenWidth - 2 * (dp(16) + dp(13)) - scaleWidth * (precipitation ? 2 : 1)) / 8);
        int plotHeight = chartHeight - topGap - legendHeight - legendGap - timeHeight - directionHeight;
        HourlyLineChart plot = new HourlyLineChart(this, first, second, hourWidth, precipitation, humidity, wind,
                precipitation || (wind && windUnit == 2) ? 1 : 0);
        LinearLayout plotRow = row();
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
        LinearLayout series = column();
        viewport.addView(series);
        if (wind) {
            LinearLayout directions = row();
            for (int i = 0; i < count; i++) {
                FrameLayout cell = new FrameLayout(this);
                cell.addView(new WindIndicator(this, value(hourly, "wind_direction_10m", start + i)),
                        new FrameLayout.LayoutParams(dp(22), directionHeight, Gravity.CENTER));
                directions.addView(cell, new LinearLayout.LayoutParams(hourWidth, directionHeight));
            }
            series.addView(directions);
        } else {
            series.setPadding(0, directionHeight, 0, 0);
        }
        series.addView(plot, new LinearLayout.LayoutParams(hourWidth * count, plotHeight));
        LinearLayout hours = row();
        series.addView(hours);
        for (int i = 0; i < count; i++) {
            int index = start + i;
            String hour = index == start && now ? getString(R.string.now) : formatTime(localTime(at(hourly, "time", index)), clock24 ? CLOCK_24 : DETAIL_CLOCK);
            LinearLayout cell = column();
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView time = label(hour, 11, muted);
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

    private void airQuality() {
        LinearLayout tile = card();
        tile.addView(label(getString(R.string.air_quality), 14, muted));
        LinearLayout footer = row();
        LinearLayout readings = column();
        footer.addView(readings, new LinearLayout.LayoutParams(0, -2, 1));
        TextView index = label(getString(R.string.air_quality_loading), 19, ink);
        readings.addView(index, new LinearLayout.LayoutParams(-2, -2));
        TextView particles = label("", 13, muted);
        particles.setPadding(0, dp(5), 0, 0);
        readings.addView(particles);
        airQualityMapButton = mapButton(R.string.aurora_map_short, () -> openItalianMap(RadarActivity.Layer.AIR_QUALITY));
        airQualityMapButton.setContentDescription(getString(R.string.air_quality_map));
        LinearLayout.LayoutParams mapSize = new LinearLayout.LayoutParams(-2, -2);
        mapSize.gravity = Gravity.BOTTOM;
        mapSize.setMarginStart(dp(8));
        footer.addView(airQualityMapButton, mapSize);
        LinearLayout.LayoutParams footerSize = new LinearLayout.LayoutParams(-1, -2);
        footerSize.topMargin = dp(5);
        tile.addView(footer, footerSize);
        addSpaced(page, tile, 9);
        double lat = latitude;
        double lon = longitude;
        CompletableFuture.supplyAsync(() -> {
            try {
                return OpenMeteo.airQuality(lat, lon);
            } catch (Exception unavailable) {
                return null;
            }
        }).thenAccept(result -> main.post(() -> {
            if (isDestroyed() || !tile.isAttachedToWindow()) return;
            double aqi = result == null ? Double.NaN : result.optDouble("european_aqi");
            if (!Double.isFinite(aqi)) {
                index.setText(R.string.air_quality_unavailable);
                particles.setVisibility(View.GONE);
                return;
            }
            AirQualityBand band = AirQualityBand.forIndex(Math.round(aqi));
            index.setText(getString(R.string.air_quality_index, getString(band.label)));
            GradientDrawable marker = background(band.color, 6);
            marker.setBounds(0, 0, dp(10), dp(10));
            index.setCompoundDrawablesRelative(marker, null, null, null);
            index.setCompoundDrawablePadding(dp(7));
            index.setBackground(background((band.color & 0x00ffffff) | 0x30000000, 9));
            index.setPaddingRelative(dp(9), dp(4), dp(9), dp(4));
            particles.setText(getString(R.string.air_quality_particles, number(result.optDouble("pm2_5"), "%.1f"),
                    number(result.optDouble("pm10"), "%.1f")));
        }));
    }

    private void metrics(String wind, double direction, double humidity, double dewPoint, double uv, double pressure) {
        String pressureValue = pressureReading(pressure);
        android.text.SpannableString pressureWithUnit = new android.text.SpannableString(
                pressureValue + "\u00a0" + PRESSURE_UNITS[pressureUnit]);
        pressureWithUnit.setSpan(new android.text.style.RelativeSizeSpan(13f / 25),
                pressureValue.length(), pressureWithUnit.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        double elevation = data.optDouble("elevation");
        String altitude = Double.isFinite(elevation) ? getString(R.string.location_elevation, number(elevation, "%,.0f")) : null;
        LinearLayout pressureTile = gaugeTile(getString(R.string.pressure), pressureWithUnit,
                altitude, new PressureGauge(this, pressure, this::pressureReading), 60);
        addMetricGrid(windTile(getString(R.string.wind), wind, direction),
                humidityTile(getString(R.string.humidity), humidity, dewPoint),
                uvTile(getString(R.string.uv_index), uv), pressureTile);
    }

    private LinearLayout windTile(String title, String speed, double direction) {
        LinearLayout wind = card();
        wind.addView(label(title, 14, muted));
        LinearLayout readings = row();
        wind.addView(readings);
        LinearLayout text = column();
        readings.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        text.addView(label(speed, 22, ink));
        if (Double.isFinite(direction)) {
            String[] compass = italian() ? new String[]{"N", "NE", "E", "SE", "S", "SO", "O", "NO"}
                    : new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            int index = Math.floorMod((int) Math.round(direction / 45), compass.length);
            int bearing = Math.floorMod((int) Math.round(direction), 360);
            text.addView(label(getString(R.string.from_bearing, compass[index], bearing), 13, muted));
        }
        readings.addView(new WindIndicator(this, direction), new LinearLayout.LayoutParams(dp(46), dp(46)));
        return wind;
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

    private LinearLayout gaugeTile(String title, CharSequence value, String detail, View gauge, int gaugeWidth) {
        LinearLayout tile = card();
        tile.addView(label(title, 14, muted));
        LinearLayout content = row();
        tile.addView(content, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout text = column();
        content.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView reading = label("", 25, ink);
        reading.setText(value);
        text.addView(reading);
        if (detail != null) {
            TextView subtitle = label(detail, 13, muted);
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
        tile.addView(label(title, 14, muted));
        TextView reading = label(value, 22, ink);
        reading.setPadding(0, dp(8), 0, 0);
        tile.addView(reading);
        return tile;
    }

    private void addMetricGrid(View first, View second, View third, View fourth) {
        View[] cards = {first, second, third, fourth};
        boolean stacked = stackedCards();
        int width = dp(getResources().getConfiguration().screenWidthDp) - page.getPaddingLeft() - page.getPaddingRight();
        int tileWidth = Math.max(1, stacked ? width : (width - dp(10)) / 2);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(tileWidth, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int height = dp(100);
        for (View card : cards) {
            card.measure(widthSpec, heightSpec);
            height = Math.max(height, card.getMeasuredHeight());
        }
        for (View card : cards) card.setMinimumHeight(height);
        addMetricRow(first, second);
        addMetricRow(third, fourth);
    }

    private void addMetricRow(View first, View second) {
        if (stackedCards()) {
            addSpaced(page, first, 9);
            addSpaced(page, second, 9);
            return;
        }
        LinearLayout tiles = row();
        tiles.setGravity(Gravity.TOP);
        tiles.addView(first, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.leftMargin = dp(10);
        tiles.addView(second, params);
        addSpaced(page, tiles, 9);
    }

    private String solarNoon(JSONObject daily, int index) {
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        return formatTime(SkyPath.solarNoon(date, longitude, ZoneId.of(data.optString("timezone", "UTC"))), clockFormat());
    }

    private LinearLayout dailySunTimes(JSONObject daily, int index) {
        LinearLayout tile = card();
        tile.addView(label(getString(R.string.sunrise_sunset), 14, muted));
        long seconds = daylightSeconds(daily, index);
        if (isPolarDaylight(seconds)) {
            TextView status = label(getString(polarDaylightLabel(seconds)), 13, ink);
            status.setPadding(0, dp(5), 0, 0);
            tile.addView(status);
        }
        String[] labels = {getString(R.string.sunrise), getString(R.string.solar_noon), getString(R.string.sunset)};
        String[] values = {isPolarDaylight(seconds) ? "—" : clock(at(daily, "sunrise", index)), solarNoon(daily, index),
                isPolarDaylight(seconds) ? "—" : clock(at(daily, "sunset", index))};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout line = row();
            line.setPadding(0, dp(5), 0, 0);
            line.addView(label(labels[i], 12, muted), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(label(values[i], 13, ink));
            tile.addView(line);
        }
        return tile;
    }

    private void addSunTime(LinearLayout times, String name, String time) {
        TextView title = label(name, 13, muted);
        if (times.getChildCount() > 0) title.setPadding(0, dp(7), 0, 0);
        times.addView(title);
        times.addView(label(time, 19, ink));
    }

    private TextView skyCoordinates(boolean moon) {
        TextView reading = label("", 12, muted);
        reading.setPadding(0, dp(8), 0, 0);
        setSkyCoordinates(reading, Instant.now(), moon);
        return reading;
    }

    private void setSkyCoordinates(TextView reading, Instant now, boolean moon) {
        SkyPosition position = SkyPosition.at(now, latitude, longitude, moon);
        if (position.zenith >= 0 && position.zenith <= 90) {
            reading.setText(getString(R.string.sky_coordinates, Math.round(position.azimuth) % 360,
                    compassDirection(position.azimuth), Math.round(90 - position.zenith)));
        } else {
            reading.setText(R.string.sky_coordinates_below_horizon);
        }
    }

    private void sun(JSONObject daily, int index) {
        LinearLayout tile = card();
        tile.addView(label(getString(R.string.sun), 16, ink));
        long seconds = daylightSeconds(daily, index);
        addSunPathDetails(tile, daily, index, seconds);
        LinearLayout readings = row();
        readings.setGravity(Gravity.BOTTOM);
        sunCoordinates = skyCoordinates(false);
        readings.addView(sunCoordinates, new LinearLayout.LayoutParams(0, -2, 1));
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        TextView maximum = label(getString(R.string.sun_max_today, solarMaximumElevation(date, zone, seconds)), 12, muted);
        maximum.setGravity(Gravity.END);
        maximum.setPaddingRelative(dp(8), dp(8), 0, 0);
        readings.addView(maximum, new LinearLayout.LayoutParams(-2, -2));
        tile.addView(readings);
        addUpcomingTimes(tile, daily, index, false);
        addSpaced(page, tile, 9);
    }

    private void addSunPathDetails(LinearLayout tile, JSONObject daily, int index, long seconds) {
        LocalTime sunrise = localTime(at(daily, "sunrise", index));
        LocalTime sunset = localTime(at(daily, "sunset", index));
        boolean roomy = largeText();
        LinearLayout content = roomy ? column() : row();
        LinearLayout timeRow = roomy ? row() : column();
        content.addView(timeRow, new LinearLayout.LayoutParams(roomy ? -1 : dp(SKY_PATH_TIMES_WIDTH_DP), -2));
        boolean polar = isPolarDaylight(seconds);
        LinearLayout evening = roomy ? column() : timeRow;
        if (polar) {
            TextView status = label(getString(polarDaylightLabel(seconds)), 14, ink);
            status.setPadding(0, dp(9), 0, 0);
            timeRow.addView(status);
        } else {
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
        }
        TextView daylight = label(daylightSummary(seconds), 11, muted);
        daylight.setPadding(0, dp(5), 0, 0);
        daylight.setSingleLine(true);
        boolean daylightFits = !roomy && textWidth(daylight, daylight.getText().toString()) <= dp(SKY_PATH_TIMES_WIDTH_DP);
        if (daylightFits) (polar ? timeRow : evening).addView(daylight);
        int twilight = SkyPath.civilTwilightMinutes(LocalDate.parse(at(daily, "time", index)), latitude);
        LinearLayout chart = column();
        content.addView(chart, new LinearLayout.LayoutParams(roomy ? -1 : 0, -2, roomy ? 0 : 1));
        SkyPath path;
        if (polar) {
            LocalDate date = LocalDate.parse(at(daily, "time", index));
            ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
            LocalTime noon = SkyPath.solarNoon(date, longitude, zone);
            Instant transit = date.atTime(noon).atZone(zone).toInstant();
            double noonElevation = 90 - SkyPosition.at(transit, latitude, longitude, false).zenith;
            double midnightElevation = 90 - SkyPosition.at(transit.minusSeconds(43200), latitude, longitude, false).zenith;
            path = SkyPath.polarSun(this, noon, LocalTime.now(zone), seconds == 86400, noonElevation, midnightElevation);
        } else {
            path = new SkyPath(this, sunrise, sunset, localTime(data.optJSONObject("current").optString("time")));
        }
        chart.addView(path, new LinearLayout.LayoutParams(-1, dp(SKY_PATH_HEIGHT_DP)));
        LinearLayout legend = row();
        legend.setGravity(Gravity.TOP);
        legend.setPadding(dp(5), 0, dp(5), 0);
        String dawn = twilight > 0 && sunrise != null ? formatTime(sunrise.minusMinutes(twilight), clockFormat()) : "—";
        String dusk = twilight > 0 && sunset != null ? formatTime(sunset.plusMinutes(twilight), clockFormat()) : "—";
        if (!polar) legend.addView(label(getString(R.string.dawn) + "\n" + dawn, 12, muted), new LinearLayout.LayoutParams(0, -2, 1));
        TextView noonLabel = label(getString(R.string.solar_noon) + "\n" + solarNoon(daily, index), 12, muted);
        noonLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        legend.addView(noonLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView duskLabel = label(getString(R.string.dusk) + "\n" + dusk, 12, muted);
        duskLabel.setGravity(Gravity.END);
        if (!polar) legend.addView(duskLabel, new LinearLayout.LayoutParams(0, -2, 1));
        chart.addView(legend);
        tile.addView(content);
        if (!daylightFits) tile.addView(daylight);
    }

    private String solarMaximumElevation(LocalDate date, ZoneId zone, long seconds) {
        double maximum = SkyPosition.maximumSolarElevation(date, zone, latitude, longitude);
        if (seconds > 0) maximum = Math.max(0, maximum);
        return maximum < 0 && Math.round(maximum) == 0 ? "<0°" : number(maximum, "%.0f") + "°";
    }

    private void moon(JSONObject daily, int index) {
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        double phase = moonPhaseForToday(daily, index, date, zone);
        LinearLayout tile = card();
        tile.addView(label(getString(R.string.moon), 16, ink));
        TextView eventLabel = label("", 13, muted);
        int readingWidth = Math.max(dp(SKY_PATH_TIMES_WIDTH_DP), Math.max(
                textWidth(eventLabel, getString(R.string.moonrise)),
                textWidth(eventLabel, getString(R.string.moonset))) + dp(4));
        boolean roomy = largeText();
        LinearLayout content = roomy ? column() : row();
        LinearLayout readings = roomy ? row() : column();
        readings.setGravity(Gravity.TOP);
        readings.setPadding(0, dp(9), 0, 0);
        content.addView(readings, new LinearLayout.LayoutParams(roomy ? -1 : readingWidth, -2));
        ZonedDateTime startOfDay = date.atStartOfDay(zone);
        long riseMinute = -1;
        long setMinute = -1;
        for (String field : new String[]{"moonrise", "moonset"}) {
            boolean rise = field.equals("moonrise");
            String event = getString(rise ? R.string.moonrise : R.string.moonset);
            int eventDay = index;
            String timestamp = at(daily, field, eventDay);
            while (timestamp.length() < 16 && eventDay + 1 < length(daily)) {
                timestamp = at(daily, field, ++eventDay);
            }
            LinearLayout time = roomy ? column() : readings;
            addSunTime(time, event, timestamp.length() >= 16 ? clock(timestamp) : getString(R.string.unavailable));
            if (timestamp.length() >= 16) {
                LocalDateTime eventTime = LocalDateTime.parse(timestamp);
                long minute = Duration.between(startOfDay, eventTime.atZone(zone)).toMinutes();
                if (rise) riseMinute = minute;
                else setMinute = minute;
                LocalDate eventDate = eventTime.toLocalDate();
                String dayLabel = eventDate.equals(date) ? getString(R.string.today)
                        : eventDate.equals(date.plusDays(1)) ? getString(R.string.tomorrow)
                        : eventDate.format(shortDate(false));
                TextView when = label(dayLabel, 11, muted);
                when.setPadding(0, dp(3), 0, 0);
                time.addView(when);
            }
            if (roomy) readings.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout chart = column();
        if (riseMinute >= 0 && setMinute >= 0) {
            long nowMinute = Duration.between(startOfDay, ZonedDateTime.now(zone)).toMinutes();
            SkyPath path = SkyPath.moon(this, riseMinute, setMinute, nowMinute);
            if (path != null) {
                chart.addView(path, new LinearLayout.LayoutParams(-1, dp(SKY_PATH_HEIGHT_DP)));
            }
        }
        LinearLayout phaseDetails = row();
        phaseDetails.setPadding(dp(5), dp(3), dp(5), 0);
        LinearLayout phaseLabels = column();
        if (Double.isFinite(phase)) {
            int position = moonPhaseIndex(phase, date, zone);
            MoonPhaseView icon = new MoonPhaseView(this, phase, latitude < 0);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            phaseDetails.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            phaseLabels.setPadding(dp(8), 0, 0, 0);
            TextView illuminated = label(percent(100 * MoonPhaseView.illumination(phase)), 15, ink);
            illuminated.setContentDescription(getString(R.string.moon_illuminated, illuminated.getText()));
            phaseLabels.addView(illuminated);
            TextView phaseName = label(getString(MOON_PHASE_NAMES[position]), 13, muted);
            phaseName.setPadding(0, dp(6), 0, 0);
            phaseLabels.addView(phaseName);
        } else {
            phaseLabels.addView(label(getString(R.string.phase_unavailable), 13, muted));
        }
        phaseDetails.addView(phaseLabels, new LinearLayout.LayoutParams(0, -2, 1));
        chart.addView(phaseDetails);
        content.addView(chart, new LinearLayout.LayoutParams(roomy ? -1 : 0, -2, roomy ? 0 : 1));
        tile.addView(content);
        moonCoordinates = skyCoordinates(true);
        tile.addView(moonCoordinates);
        DateTimeFormatter format = shortDate(false);
        LinearLayout phases = row();
        phases.setGravity(Gravity.TOP);
        phases.setPadding(0, dp(13), 0, 0);
        for (boolean full : new boolean[]{true, false}) {
            LinearLayout event = column();
            TextView phaseTitle = label(full ? getString(R.string.next_full_moon) : getString(R.string.next_new_moon), 13, muted);
            if (stackedCards()) phaseTitle.setMinLines(2);
            event.addView(phaseTitle);
            TextView when = label(LunarPhases.nextDate(date, zone, full).format(format), 16, ink);
            when.setPadding(0, dp(4), 0, 0);
            event.addView(when);
            phases.addView(event, new LinearLayout.LayoutParams(0, -2, 1));
        }
        tile.addView(phases);
        addUpcomingTimes(tile, daily, index, true);
        addSpaced(page, tile, 9);
    }

    private static final class PlanetRow {
        final Planets.Body body;
        final TextView now, position, windows;
        List<Planets.Window> observingWindows = List.of();

        PlanetRow(Planets.Body body, TextView now, TextView position, TextView windows) {
            this.body = body;
            this.now = now;
            this.position = position;
            this.windows = windows;
        }
    }

    private void planets() {
        LinearLayout tile = card();
        TextView heading = label(getString(R.string.planets), 16, ink);
        heading.setPadding(0, 0, 0, dp(8));
        tile.addView(heading);
        int[] names = {R.string.mercury, R.string.venus, R.string.mars, R.string.jupiter, R.string.saturn};
        for (Planets.Body body : Planets.Body.values()) {
            LinearLayout entry = column();
            entry.setBackground(background(getColor(R.color.sky_subtle), 12));
            entry.setPadding(dp(11), dp(9), dp(11), dp(9));
            LinearLayout planetTitle = row();
            LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(23), dp(23));
            iconSize.setMarginEnd(dp(7));
            planetTitle.addView(new PlanetIcon(this, body), iconSize);
            TextView name = label(getString(names[body.ordinal()]), 16, ink);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            planetTitle.addView(name);
            entry.addView(planetTitle);
            TextView now = label("", 13, accent);
            now.setPadding(0, dp(4), 0, 0);
            entry.addView(now);
            TextView position = label("", 13, ink);
            position.setPadding(0, dp(4), 0, 0);
            entry.addView(position);
            TextView windows = label("", 12, muted);
            windows.setPadding(0, dp(5), 0, 0);
            entry.addView(windows);
            planetRows.add(new PlanetRow(body, now, position, windows));
            addSpaced(tile, entry, 7);
        }
        LinearLayout direction = column();
        direction.setBackground(background(getColor(R.color.sky_subtle), 12));
        direction.setPadding(dp(11), dp(9), dp(11), dp(9));
        direction.addView(label(getString(R.string.phone_direction), 14, ink));
        phoneDirection = label(getString(R.string.phone_direction_waiting), 17, ink);
        phoneDirection.setPadding(0, dp(8), 0, 0);
        direction.addView(phoneDirection);
        TextView directionHint = label(getString(R.string.phone_direction_hint), 11, muted);
        directionHint.setPadding(0, dp(8), 0, 0);
        direction.addView(directionHint);
        tile.addView(direction);
        TextView note = label(getString(R.string.planets_clear_sky), 11, muted);
        note.setPadding(0, dp(8), 0, 0);
        tile.addView(note);
        startPhoneOrientation();
        updatePlanets(Instant.now());
        addSpaced(page, tile, 9);
    }

    private void startPhoneOrientation() {
        if (!resumed || phoneDirection == null) return;
        SensorManager manager = getSystemService(SensorManager.class);
        Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (sensor == null) sensor = manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        if (sensor == null || !manager.registerListener(phoneOrientation, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            phoneDirection.setText(R.string.phone_direction_unavailable);
        }
    }

    private void updatePlanets(Instant now) {
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant();
        for (PlanetRow row : planetRows) {
            Planets.Reading reading = Planets.at(row.body, now, latitude, longitude);
            boolean above = reading.elevation >= 0;
            int status = reading.favorable ? R.string.planet_visible_now
                    : above ? R.string.planet_difficult : R.string.planet_below_horizon;
            row.now.setText(getString(R.string.planet_now_status, getString(status)));
            row.position.setVisibility(above ? View.VISIBLE : View.GONE);
            if (above) {
                row.position.setText(getString(R.string.planet_position, Math.round(reading.azimuth) % 360,
                        compassDirection(reading.azimuth), Math.round(reading.elevation), number(reading.magnitude, "%.1f")));
            }
            if (!today.equals(planetWindowDate)) {
                List<String> hours = new ArrayList<>();
                row.observingWindows = Planets.windows(row.body, today, zone, latitude, longitude);
                for (Planets.Window window : row.observingWindows) {
                    String start = window.start.atZone(zone).format(clockFormat());
                    String end = window.end.equals(tomorrow) && clock24 ? "24:00" : window.end.atZone(zone).format(clockFormat());
                    hours.add(start + "–" + end);
                }
                row.windows.setText(hours.isEmpty() ? getString(R.string.planet_no_window)
                        : getString(R.string.planet_today_windows, android.text.TextUtils.join(" · ", hours)));
            }
            boolean observingNow = row.observingWindows.stream()
                    .anyMatch(window -> !now.isBefore(window.start) && now.isBefore(window.end));
            row.now.setTextColor(observingNow ? getColor(R.color.sky_observing) : accent);
        }
        planetWindowDate = today;
    }

    private String compassDirection(double azimuth) {
        String[] directions = italian()
                ? new String[]{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO"}
                : new String[]{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return directions[(int) Math.round(azimuth / 22.5) % directions.length];
    }

    private void aurora() {
        LinearLayout tile = card();
        LinearLayout heading = row();
        heading.setGravity(Gravity.TOP);
        heading.setBaselineAligned(false);
        LinearLayout summary = column();
        summary.setPaddingRelative(0, 0, dp(8), 0);
        summary.addView(label(getString(R.string.aurora), 16, ink));
        auroraStatus = label("", 15, ink);
        auroraStatus.setPadding(0, dp(10), 0, 0);
        summary.addView(auroraStatus);
        heading.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        Button map = mapButton(R.string.aurora_map_short, () -> openMap(RadarActivity.Layer.AURORA));
        map.setContentDescription(getString(R.string.aurora_map));
        heading.addView(map);
        tile.addView(heading);
        auroraTimes = label("", 12, muted);
        auroraTimes.setPadding(0, dp(4), 0, 0);
        auroraTimes.setSingleLine(true);
        auroraTimes.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(auroraTimes);
        currentKpView = label("", 14, accent);
        currentKpView.setPadding(0, dp(8), 0, 0);
        tile.addView(currentKpView);
        currentKpBar = kpBar();
        LinearLayout.LayoutParams currentBarSize = new LinearLayout.LayoutParams(-1, dp(6));
        currentBarSize.topMargin = dp(5);
        tile.addView(currentKpBar, currentBarSize);
        LinearLayout table = column();
        kpTable = table;
        table.setVisibility(View.GONE);
        Button toggle = accordionButton(getString(R.string.aurora_kp_forecast));
        toggle.setOnClickListener(v -> {
            boolean open = table.getVisibility() != View.VISIBLE;
            table.setVisibility(open ? View.VISIBLE : View.GONE);
            updateAccordion(toggle, open);
            if (open) {
                refreshKp();
                renderKp();
            }
        });
        LinearLayout.LayoutParams toggleSize = new LinearLayout.LayoutParams(-1, -2);
        toggleSize.topMargin = dp(12);
        toggleSize.bottomMargin = dp(8);
        tile.addView(toggle, toggleSize);
        tile.addView(table);
        addSpaced(page, tile, 9);
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
            if (isDestroyed()) return;
            kpLoading = false;
            if (result != null) kpPeriods = result;
            renderKp();
        }));
    }

    private void renderKp() {
        if (kpTable == null || kpTable.getVisibility() != View.VISIBLE) return;
        kpTable.removeAllViews();
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        DateTimeFormatter hours = DateTimeFormatter.ofPattern(clock24 ? "HH:mm" : "h:mm a", appLocale()).withZone(zone);
        DateTimeFormatter dates = shortDate(false).withZone(zone);
        Instant now = Instant.now();
        LocalDate currentDate = null;
        for (AuroraForecast.KpPeriod period : kpPeriods) {
            if (!period.end().isAfter(now)) continue;
            LocalDate date = period.start.atZone(zone).toLocalDate();
            if (!date.equals(currentDate)) {
                TextView dayLabel = label(dates.format(period.start), 13, muted);
                dayLabel.setPadding(0, dp(currentDate == null ? 0 : 12), 0, 0);
                kpTable.addView(dayLabel);
                currentDate = date;
            }
            LinearLayout line = row();
            line.setPadding(0, dp(4), 0, dp(4));
            line.addView(label(hours.format(period.start) + "–" + hours.format(period.end()), 14, ink));
            View bar = kpBar();
            bar.getBackground().setLevel((int) Math.round(period.kp * 10000 / 9));
            LinearLayout.LayoutParams barSize = new LinearLayout.LayoutParams(0, dp(6), 1);
            barSize.setMarginStart(dp(10));
            barSize.setMarginEnd(dp(10));
            line.addView(bar, barSize);
            TextView activity = label("Kp " + number(period.kp, "%.2f") + "/9", 14, accent);
            line.addView(activity);
            kpTable.addView(line);
        }
        int text = currentDate != null ? R.string.aurora_kp_hint
                : kpLoading ? R.string.aurora_loading : R.string.aurora_unavailable;
        addSpaced(kpTable, label(getString(text), 12, muted), 8);
    }

    private View kpBar() {
        View bar = new View(this);
        android.graphics.drawable.ClipDrawable fill = new android.graphics.drawable.ClipDrawable(
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0xff41a872, 0xffe6b83b, 0xffdf5745}),
                Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        bar.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                background(getColor(R.color.sky_selected), 3), fill}));
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
            if (isDestroyed()) return;
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
            if (isDestroyed()) return;
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
        currentKpView.setText(getString(R.string.aurora_kp_current, number(currentKp.kp, "%.2f"),
                formatTime(currentKp.time.atZone(zone).toLocalTime(), clockFormat())));
    }

    private void updateAurora(Instant now) {
        if (auroraStatus == null) return;
        updateCurrentKp(now);
        if (auroraForecast == null || now.isAfter(auroraForecast.forecastTime.plusSeconds(1800))
                || now.isAfter(auroraForecast.observationTime.plusSeconds(7200))) {
            auroraStatus.setText(auroraLoading ? R.string.aurora_loading : R.string.aurora_unavailable);
            auroraTimes.setVisibility(View.GONE);
            return;
        }
        Instant forecastTime = auroraForecast.forecastTime;
        Instant viewingTime = forecastTime.isBefore(now) ? now : forecastTime;
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        LocalDate today = now.atZone(zone).toLocalDate();
        auroraTimes.setVisibility(View.VISIBLE);
        auroraTimes.setText(getString(R.string.aurora_times,
                auroraTimestamp(auroraForecast.observationTime.atZone(zone), today),
                auroraTimestamp(forecastTime.atZone(zone), today)));
        double direction = auroraForecast.estimatedDirection(latitude, longitude);
        if (!Double.isFinite(direction)) {
            auroraStatus.setText(R.string.aurora_unlikely);
        } else if (SkyPosition.at(viewingTime, latitude, longitude, false).zenith < 96) {
            auroraStatus.setText(R.string.aurora_too_light);
        } else if (auroraCloudCover(viewingTime, zone) >= 80) {
            auroraStatus.setText(R.string.aurora_cloudy);
        } else if (direction < 0) {
            auroraStatus.setText(R.string.aurora_possible);
        } else {
            auroraStatus.setText(getString(R.string.aurora_direction, compassDirection(direction)));
        }
    }

    private String auroraTimestamp(ZonedDateTime timestamp, LocalDate today) {
        String time = timestamp.format(clockFormat());
        return timestamp.toLocalDate().equals(today) ? time
                : timestamp.format(DateTimeFormatter.ofPattern(italian() ? "d/M" : "M/d", appLocale())) + " " + time;
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
        LinearLayout tile = card();
        tile.addView(label(getString(R.string.iss_passes), 16, ink));
        TextView note = label(getString(R.string.iss_scope), 13, muted);
        addSpaced(tile, note, 8);
        TextView status = label(getString(R.string.iss_loading), 13, muted);
        tile.addView(status);
        addSpaced(page, tile, 9);
        double lat = latitude;
        double lon = longitude;
        double height = data.optDouble("elevation");
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        CompletableFuture.supplyAsync(() -> {
            try {
                return IssPasses.upcoming(lat, lon, height, Instant.now());
            } catch (Exception error) {
                android.util.Log.w("SkyHere", "ISS predictions unavailable", error);
                return null;
            }
        }).thenAccept(passes -> main.post(() -> {
            if (isDestroyed() || !tile.isAttachedToWindow()) return;
            if (passes == null || passes.isEmpty()) {
                status.setText(passes == null ? R.string.iss_unavailable : R.string.iss_empty);
                return;
            }
            tile.removeView(status);
            LinearLayout rows = column();
            tile.addView(rows);
            Button more = moreButton();
            tile.addView(more);
            more.setOnClickListener(v -> appendIssPasses(rows, more, passes, zone));
            appendIssPasses(rows, more, passes, zone);
        }));
    }

    private void appendIssPasses(LinearLayout rows, Button more, List<IssPasses.Pass> passes, ZoneId zone) {
        int start = rows.getChildCount();
        int end = Math.min(start + ASTRONOMY_PAGE_SIZE, passes.size());
        DateTimeFormatter dayFormat = shortDate(true);
        DateTimeFormatter timeFormat = clockFormat();
        for (int i = start; i < end; i++) {
            IssPasses.Pass pass = passes.get(i);
            ZonedDateTime rise = Instant.ofEpochSecond(pass.rise.time).atZone(zone);
            ZonedDateTime set = Instant.ofEpochSecond(pass.set.time).atZone(zone);
            String range = timeFormat.format(rise) + " – " + timeFormat.format(set);
            if (!rise.toLocalDate().equals(set.toLocalDate())) range += getString(R.string.next_day_suffix);
            long duration = pass.set.time - pass.rise.time;
            LinearLayout entry = column();
            entry.setPadding(dp(12), dp(8), dp(12), dp(8));
            entry.setBackground(background(getColor(R.color.sky_subtle), 12));
            LinearLayout heading = row();
            heading.addView(label(dayFormat.format(rise) + " · " + range, 15, ink), new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(astronomyLinkIcon());
            entry.addView(heading);
            entry.addView(label(getString(R.string.iss_details, duration / 60, duration % 60,
                    Math.round(pass.peak.elevation), number(pass.magnitude, "%+.1f")), 13, muted));
            entry.addView(label(getString(R.string.iss_directions, issBearing(pass.rise.azimuth),
                    issBearing(pass.peak.azimuth), issBearing(pass.set.azimuth)), 13, muted));
            linkAstronomyCard(entry, issPassUrl(pass, zone));
            addSpaced(rows, entry, 6);
        }
        more.setVisibility(end < passes.size() ? View.VISIBLE : View.GONE);
    }

    private String issBearing(double azimuth) {
        return compassDirection(azimuth) + " " + (Math.round(azimuth) % 360) + "°";
    }

    private String issPassUrl(IssPasses.Pass pass, ZoneId zone) {
        double altitude = data.optDouble("elevation", 0);
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
                .appendQueryParameter("cul", italian() ? "it" : "en").build().toString();
    }

    private void eclipsesAndTransits() {
        LinearLayout card = card();
        LinearLayout content = column();
        content.setVisibility(View.GONE);
        Button toggle = accordionButton(getString(R.string.eclipses_transits));
        double lat = latitude;
        double lon = longitude;
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        toggle.setOnClickListener(v -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            updateAccordion(toggle, open);
            if (!open || content.getChildCount() != 0) return;
            for (boolean lunar : new boolean[]{false, true}) {
                TextView heading = label(lunar ? getString(R.string.lunar_eclipses) : getString(R.string.solar_eclipses), 16, ink);
                heading.setPadding(0, dp(10), 0, dp(4));
                content.addView(heading);
                LinearLayout events = column();
                content.addView(events);
                new EclipseList(events, lunar, lat, lon, zone).showNext();
            }
            TextView heading = label(getString(R.string.planetary_transits), 16, ink);
            heading.setPadding(0, dp(14), 0, dp(6));
            content.addView(heading);
            List<TransitCatalog.Event> transits = TransitCatalog.upcoming(lat, lon, Instant.now());
            if (transits.isEmpty()) content.addView(label(getString(R.string.no_transits), 14, muted));
            else {
                LinearLayout list = column();
                content.addView(list);
                appendTransits(list, transits, zone, 0);
            }
        });
        card.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        card.addView(content);
        page.addView(card);
    }

    private void appendTransits(LinearLayout list, List<TransitCatalog.Event> items, ZoneId zone, int start) {
        int end = Math.min(items.size(), start + ASTRONOMY_PAGE_SIZE);
        for (int i = start; i < end; i++) addTransit(list, items.get(i), zone);
        if (end >= items.size()) return;
        Button more = moreButton();
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
            if (shown == 0) footer.addView(label(getString(R.string.no_eclipses_until, year - 1), 14, muted));
            Button more = moreButton();
            more.setOnClickListener(v -> showNext());
            more.setContentDescription(lunar ? getString(R.string.more_lunar_eclipses) : getString(R.string.more_solar_eclipses));
            footer.addView(more);
        }

        private void fetch(int requestsLeft) {
            footer.addView(loadingStatus(getString(R.string.loading_eclipses)));
            int startYear = year;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return EclipseCatalog.upcoming(getApplicationContext(), lunar, startYear, latitude, longitude, Instant.now());
                } catch (Exception error) {
                    android.util.Log.w("SkyHere", "Eclipse catalog unavailable: " + error, error);
                    return null;
                }
            }).thenAccept(results -> runOnUiThread(() -> {
                if (isDestroyed() || !footer.isAttachedToWindow()) return;
                if (results == null) {
                    footer.removeAllViews();
                    footer.addView(label(getString(R.string.eclipse_data_unavailable), 14, muted));
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
        entry.setBackground(background(getColor(R.color.sky_subtle), 12));
        linkAstronomyCard(entry, event.detailsUrl(latitude, longitude));
        LinearLayout heading = row();
        EclipseIcon icon = new EclipseIcon(this, event);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconSize.rightMargin = dp(9);
        heading.addView(icon, iconSize);
        LinearLayout title = column();
        title.addView(label(eclipseTypeLabel(event.type), 15, ink));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(astronomyLinkIcon());
        entry.addView(heading);
        ZonedDateTime start = event.start.atZone(zone);
        ZonedDateTime end = event.end.atZone(zone);
        DateTimeFormatter date = shortDate(true);
        TextView when = label(start.format(date), 13, muted);
        when.setPadding(0, dp(3), 0, 0);
        title.addView(when);
        String range = start.format(clockFormat()) + " – " + end.format(clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += getString(R.string.next_day_suffix);
        TextView visible = label(getString(R.string.visible_range, range), 14, ink);
        visible.setPadding(0, dp(7), 0, 0);
        entry.addView(visible);
        Duration duration = Duration.between(event.start, event.end);
        long minutes = Math.max(1, Math.round(duration.getSeconds() / 60.0));
        String durationText = durationText(minutes);
        String durationLabel = getString(event.horizonLimited ? R.string.estimated_duration : R.string.duration_value, durationText);
        durationLabel += " · " + getString(event.estimatedPeak ? R.string.best_visible : R.string.peak_time,
                event.maximum.atZone(zone).format(clockFormat()));
        TextView details = label(durationLabel, 13, muted);
        details.setPadding(0, dp(3), 0, 0);
        entry.addView(details);
        if (event.totalitySeconds > 0) {
            long seconds = event.totalitySeconds;
            String totality = seconds >= 3600
                    ? getString(R.string.totality_hours, seconds / 3600, seconds / 60 % 60, seconds % 60)
                    : getString(R.string.totality_minutes, seconds / 60, seconds % 60);
            TextView totalityLabel = label(totality, 13, accent);
            totalityLabel.setPadding(0, dp(3), 0, 0);
            entry.addView(totalityLabel);
        }
        if (Double.isFinite(event.obscuration)) {
            int disk = event.lunar ? (event.penumbral ? R.string.moon_penumbra : R.string.moon_shadow) : R.string.sun_covered;
            String coverageText = getString(disk, percent(event.obscuration));
            if (event.estimatedPeak) coverageText = getString(R.string.approximate_value, coverageText);
            TextView coverage = label(coverageText, 13, accent);
            coverage.setPadding(0, dp(3), 0, 0);
            entry.addView(coverage);
        }
        addSpaced(parent, entry, 7);
    }

    private void addTransit(LinearLayout parent, TransitCatalog.Event event, ZoneId zone) {
        LinearLayout entry = column();
        entry.setPadding(dp(11), dp(10), dp(11), dp(10));
        entry.setBackground(background(getColor(R.color.sky_subtle), 12));
        linkAstronomyCard(entry, event.detailsUrl(latitude, longitude));

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
                getString(event.transit.planet.equals("Mercury") ? R.string.mercury : R.string.venus)), 15, ink));
        ZonedDateTime date = event.visibleStart.atZone(zone);
        TextView when = label(date.format(shortDate(true)), 13, muted);
        when.setPadding(0, dp(3), 0, 0);
        title.addView(when);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(astronomyLinkIcon());
        entry.addView(header);

        ZonedDateTime start = event.visibleStart.atZone(zone);
        ZonedDateTime end = event.visibleEnd.atZone(zone);
        String range = start.format(clockFormat()) + " – " + end.format(clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += getString(R.string.next_day_suffix);
        TextView visible = label(getString(R.string.estimated_visible_range, range), 14, ink);
        visible.setPadding(0, dp(7), 0, 0);
        entry.addView(visible);
        long minutes = Math.max(1, Math.round(Duration.between(event.visibleStart, event.visibleEnd).getSeconds() / 60.0));
        String duration = durationText(minutes);
        TextView details = label(getString(R.string.estimated_duration, duration), 13, muted);
        details.setPadding(0, dp(3), 0, 0);
        entry.addView(details);
        addSpaced(parent, entry, 7);
    }

    private TextView astronomyLinkIcon() {
        TextView link = label("↗", 19, accent);
        link.setContentDescription(getString(R.string.open_in_browser));
        link.setPadding(dp(8), 0, dp(3), 0);
        return link;
    }

    private void linkAstronomyCard(View card, String url) {
        card.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(getColor(R.color.sky_ripple)), null, background(getColor(R.color.sky_card), 12)));
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

    private long daylightSeconds(JSONObject daily, int index) {
        String rise = at(daily, "sunrise", index);
        String set = at(daily, "sunset", index);
        if (rise.length() < 16 || set.length() < 16) return -1;
        return Duration.between(LocalDateTime.parse(rise), LocalDateTime.parse(set)).getSeconds();
    }

    private static boolean isPolarDaylight(long seconds) {
        return seconds == 0 || seconds == 86400;
    }

    private static int polarDaylightLabel(long seconds) {
        return seconds == 0 ? R.string.polar_night : R.string.midnight_sun;
    }

    private String daylightSummary(long seconds) {
        if (seconds < 0) return getString(R.string.daylight_unavailable);
        long minutes = seconds / 60;
        String duration = minutes >= 60 ? getString(R.string.daylight_hours_minutes, minutes / 60, minutes % 60)
                : durationText(minutes);
        return getString(R.string.daylight_duration, duration);
    }

    private void addUpcomingTimes(LinearLayout tile, JSONObject daily, int index, boolean moon) {
        int end = Math.min(length(daily), index + OpenMeteo.UPCOMING_ASTRONOMY_DAYS + 1);
        if (end <= index + 1) return;
        String title = getString(R.string.next_days, end - index - 1);
        LinearLayout table = column();
        View collapsible = table;
        collapsible.setVisibility(View.GONE);
        Button toggle = accordionButton(title);
        toggle.setOnClickListener(v -> {
            boolean open = collapsible.getVisibility() != View.VISIBLE;
            collapsible.setVisibility(open ? View.VISIBLE : View.GONE);
            updateAccordion(toggle, open);
        });
        LinearLayout.LayoutParams toggleSize = new LinearLayout.LayoutParams(-1, -2);
        toggleSize.topMargin = dp(8);
        tile.addView(toggle, toggleSize);
        tile.addView(collapsible);
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        String rise = moon ? "moonrise" : "sunrise";
        String set = moon ? "moonset" : "sunset";
        if (moon) {
            addEventTableRow(table, getString(R.string.date), label(getString(R.string.phase), 12, muted), muted,
                    getString(R.string.moonrise_column), getString(R.string.moonset_column));
        } else {
            addEventTableRow(table, getString(R.string.date), null, muted, getString(R.string.sunrise),
                    getString(R.string.sunset), getString(R.string.daylight_column), getString(R.string.max_elevation));
        }
        DateTimeFormatter dateFormat = shortDate(false);
        for (int i = index + 1; i < end; i++) {
            LocalDate day = LocalDate.parse(at(daily, "time", i));
            String date = day.format(dateFormat);
            if (!moon) {
                long seconds = daylightSeconds(daily, i);
                String duration = seconds <= 0 ? "-" : String.format(appLocale(), "%d:%02d:%02d",
                        seconds / 3600, seconds / 60 % 60, seconds % 60);
                boolean polar = isPolarDaylight(seconds);
                addEventTableRow(table, date, null, ink, polar ? "—" : eventClock(at(daily, rise, i)),
                        polar ? "—" : eventClock(at(daily, set, i)),
                        duration, solarMaximumElevation(day, zone, seconds));
                continue;
            }
            View phaseIcon;
            double phase = value(daily, "moon_phase", i);
            if (Double.isFinite(phase)) {
                LinearLayout phaseCell = largeText() ? column() : row();
                phaseCell.setGravity(Gravity.CENTER);
                MoonPhaseView glyph = new MoonPhaseView(this, phase, latitude < 0);
                glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                phaseCell.addView(glyph, new LinearLayout.LayoutParams(dp(21), dp(21)));
                String amount = percent(100 * MoonPhaseView.illumination(phase));
                TextView illumination = label(amount, 11, muted);
                if (!largeText()) illumination.setPadding(dp(4), 0, 0, 0);
                illumination.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                phaseCell.addView(illumination);
                String phaseName = getString(MOON_PHASE_NAMES[moonPhaseIndex(phase, day, zone)]);
                phaseCell.setContentDescription(phaseName + ", " + getString(R.string.moon_illuminated, amount));
                phaseIcon = phaseCell;
            } else {
                phaseIcon = label("—", 13, muted);
                ((TextView) phaseIcon).setGravity(Gravity.CENTER);
                phaseIcon.setContentDescription(getString(R.string.phase_unavailable));
            }
            addEventTableRow(table, date, phaseIcon, ink, eventClock(at(daily, rise, i)), eventClock(at(daily, set, i)));
        }
        if (!moon) sizeSolarColumns(table, tile);
    }

    private void sizeSolarColumns(LinearLayout table, LinearLayout tile) {
        LinearLayout heading = (LinearLayout) table.getChildAt(0);
        int[] widths = new int[heading.getChildCount()];
        int available = dp(getResources().getConfiguration().screenWidthDp)
                - page.getPaddingLeft() - page.getPaddingRight() - tile.getPaddingLeft() - tile.getPaddingRight();
        int minimumDateWidth = 0;
        for (int row = 1; row < table.getChildCount(); row++) {
            LinearLayout line = (LinearLayout) table.getChildAt(row);
            for (int col = 0; col < widths.length; col++) {
                TextView cell = (TextView) line.getChildAt(col);
                String value = cell.getText().toString();
                widths[col] = Math.max(widths[col], textWidth(cell, value) + cell.getPaddingLeft() + dp(1));
                if (col == 0) {
                    for (String word : value.split(" ")) {
                        minimumDateWidth = Math.max(minimumDateWidth, textWidth(cell, word) + dp(1));
                    }
                }
            }
        }
        for (int col = 0; col < widths.length; col++) {
            TextView cell = (TextView) heading.getChildAt(col);
            for (String word : cell.getText().toString().split(" ")) {
                widths[col] = Math.max(widths[col], textWidth(cell, word) + cell.getPaddingLeft() + dp(1));
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
                    value.setPadding(0, dp(3), 0, 0);
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

    private void addEventTableRow(LinearLayout table, String date, View phase, int color, String... readings) {
        LinearLayout line = row();
        line.setPadding(0, dp(5), 0, dp(5));
        TextView dateLabel = label(date, 13, color);
        line.addView(dateLabel, new LinearLayout.LayoutParams(0, -2, 1.3f));
        if (phase != null) {
            if (phase instanceof TextView) ((TextView) phase).setGravity(Gravity.CENTER);
            line.addView(phase, new LinearLayout.LayoutParams(dp(72), -2));
        }
        for (String time : readings) {
            TextView value = label(time, 13, color);
            value.setGravity(Gravity.END);
            if (phase == null) value.setPadding(dp(6), 0, 0, 0);
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

    private void addCredits(LinearLayout options, ScrollView scroller) {
        LinearLayout sources = column();
        sources.setVisibility(View.GONE);
        Button toggle = accordionButton(getString(R.string.credits));
        toggle.setOnClickListener(v -> {
            boolean open = sources.getVisibility() != View.VISIBLE;
            sources.setVisibility(open ? View.VISIBLE : View.GONE);
            updateAccordion(toggle, open);
            if (open) scroller.post(() -> scroller.smoothScrollTo(0, toggle.getTop()));
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(12);
        options.addView(toggle, size);
        options.addView(sources);
        addCredit(sources, R.string.weather_data, "https://open-meteo.com/");
        addCredit(sources, R.string.warning_credit, "https://meteoalarm.org/");
        addCredit(sources, R.string.radar_credit, "https://mappe.protezionecivile.gov.it/it/mappe-e-dashboard-rischi/piattaforma-radar/");
        addCredit(sources, R.string.aurora_credit, AuroraForecast.MAP);
        addCredit(sources, R.string.air_quality_credit, "https://open-meteo.com/en/docs/air-quality-api");
        addCredit(sources, R.string.air_quality_map_credit, "https://airindex.eea.europa.eu/AQI/");
        addCredit(sources, R.string.satellite_credit, SatelliteImagery.EUMETSAT);
        addCredit(sources, R.string.satellite_mosaic_credit, SatelliteImagery.SOURCE);
        addCredit(sources, R.string.earthquake_credit, EarthquakeFeed.SOURCE);
        addCredit(sources, R.string.iss_credit, "https://celestrak.org/NORAD/documentation/gp-data-formats.php");
        addCredit(sources, R.string.eclipse_data, "https://opale.imcce.fr/");
        addCredit(sources, R.string.transit_credit, "https://eclipse.gsfc.nasa.gov/transit/transit.html");
    }

    private void addCredit(LinearLayout sources, int title, String url) {
        TextView link = button(getString(title), () -> openAstronomyPage(url));
        link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        sources.addView(link, new LinearLayout.LayoutParams(-1, -2));
    }

    private void settings() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = column();
        panel.setBackgroundColor(getColor(R.color.sky_card));
        dialog.setContentView(panel);

        LinearLayout header = row();
        header.setPadding(dp(20), dp(12), dp(12), dp(10));
        TextView heading = label(getString(R.string.settings), 26, ink);
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
        int originalTheme = SkyTheme.preference(this);
        RadioGroup themes = unitOption(options, getString(R.string.appearance_theme),
                new String[]{getString(R.string.theme_system), getString(R.string.theme_light),
                        getString(R.string.theme_dark)}, originalTheme);
        RadioGroup temperatures = unitOption(options, getString(R.string.temperature),
                new String[]{getString(R.string.celsius), getString(R.string.fahrenheit)}, fahrenheit ? 1 : 0);
        RadioGroup winds = unitOption(options, getString(R.string.wind_speed), WIND_UNITS, windUnit);
        RadioGroup pressures = unitOption(options, getString(R.string.pressure), PRESSURE_UNITS, pressureUnit);
        RadioGroup clockFormats = unitOption(options, getString(R.string.time_format),
                new String[]{getString(R.string.clock_12), getString(R.string.clock_24)}, clock24 ? 1 : 0);
        LinearLayout radiusHeading = row();
        radiusHeading.setPadding(0, dp(17), 0, dp(4));
        TextView radiusLabel = label(getString(R.string.earthquake_radius), 15, ink);
        radiusHeading.addView(radiusLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView radiusValue = label(getString(R.string.earthquake_radius_value, earthquakeRadius), 15, accent);
        radiusHeading.addView(radiusValue);
        options.addView(radiusHeading);
        SeekBar radiusSlider = new SeekBar(this);
        radiusSlider.setId(View.generateViewId());
        radiusLabel.setLabelFor(radiusSlider.getId());
        radiusSlider.setMin(1);
        radiusSlider.setMax(EarthquakeFeed.MAX_RADIUS);
        radiusSlider.setProgress(earthquakeRadius);
        radiusSlider.setKeyProgressIncrement(1);
        radiusSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                radiusValue.setText(getString(R.string.earthquake_radius_value, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {}
            @Override public void onStopTrackingTouch(SeekBar slider) {}
        });
        options.addView(radiusSlider, new LinearLayout.LayoutParams(-1, -2));
        String versionDate = LocalDate.parse(getString(R.string.sky_version_date))
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(appLocale()));
        TextView version = label(getString(R.string.app_version, getString(R.string.sky_version_name), versionDate), 12, muted);
        version.setPadding(0, dp(18), 0, 0);
        options.addView(version);
        addCredits(options, scroller);

        LinearLayout actions = row();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(20), dp(10), dp(20), dp(16));
        actions.addView(button(getString(R.string.cancel), dialog::dismiss));
        TextView apply = button(getString(R.string.apply), () -> {
            earthquakeRadius = radiusSlider.getProgress();
            fahrenheit = selectedUnit(temperatures) == 1;
            windUnit = selectedUnit(winds);
            pressureUnit = selectedUnit(pressures);
            clock24 = selectedUnit(clockFormats) == 1;
            getPreferences(MODE_PRIVATE).edit().putBoolean("fahrenheit", fahrenheit)
                    .putInt("windUnit", windUnit).putInt("pressureUnit", pressureUnit)
                    .putBoolean("clock24", clock24).putInt("earthquakeRadius", earthquakeRadius).apply();
            dialog.dismiss();
            int selectedTheme = selectedUnit(themes);
            if (selectedTheme != originalTheme) {
                SkyTheme.savePreference(this, selectedTheme);
                recreate();
            } else if (data != null) {
                render();
            }
        });
        apply.setBackground(background(accent, 14));
        apply.setTextColor(getColor(R.color.sky_on_accent));
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
        TextView title = label(name, 15, ink);
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
            option.setTextColor(ink);
            option.setTextSize(14);
            option.setGravity(Gravity.CENTER);
            option.setButtonDrawable((android.graphics.drawable.Drawable) null);
            option.setPadding(dp(4), dp(8), dp(4), dp(8));
            StateListDrawable surface = new StateListDrawable();
            surface.addState(new int[]{android.R.attr.state_checked}, background(getColor(R.color.sky_selected), 12));
            surface.addState(new int[]{}, background(getColor(R.color.sky_subtle), 12));
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
        if (data != null && day < 0 && !choosingLocation) dashboardOffsets[dashboardTab.ordinal()] = scroll.getScrollY();
        stopLocating();
        choosingLocation = true;
        layout();
        LinearLayout searchBar = row();
        searchBar.setBackground(background(getColor(R.color.sky_header), 13));
        searchBar.addView(button("‹", this::home));
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setTextColor(ink);
        entry.setHintTextColor(muted);
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
        TextView currentTitle = label(getString(R.string.current_location), 18, ink);
        currentTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_current_location, 0, 0, 0);
        currentTitle.setCompoundDrawablePadding(dp(8));
        currentTitle.setPadding(0, 0, 0, dp(8));
        shortcuts.addView(currentTitle);
        LinearLayout currentCard = card();
        currentLocationTitle = label("", 16, ink);
        currentCard.addView(currentLocationTitle);
        currentLocationWeather = label("", 13, muted);
        currentLocationWeather.setPadding(0, dp(5), 0, 0);
        currentCard.addView(currentLocationWeather);
        updateCurrentLocationShortcut();
        currentCard.setOnClickListener(v -> {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(entry.getWindowToken(), 0);
            if (usingCurrentLocation && data != null) home();
            else currentLocation(true);
        });
        shortcuts.addView(currentCard);
        TextView savedTitle = label(getString(R.string.saved_locations), 18, ink);
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
                    if (items == null || items.length() == 0) results.addView(label(getString(R.string.no_locations_found), 15, muted));
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        locationRow(results, items.optJSONObject(i), false, savedList);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!results.isAttachedToWindow() || !query.equals(entry.getText().toString().trim())) return;
                    results.removeAllViews();
                    results.addView(label(getString(R.string.search_unavailable), 15, muted));
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
        if (items.length() == 0) parent.addView(label(getString(R.string.no_saved_locations), 15, muted));
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
        line.setBackground(cardBackground(12));
        line.setPadding(dp(12), dp(5), dp(8), dp(5));
        LinearLayout open = column();
        open.setPadding(0, dp(7), dp(8), dp(7));
        open.addView(label(name, 16, ink));
        if (!placeDetails.isEmpty()) {
            TextView detail = label(placeDetails, 13, muted);
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

    @Override public void onBackPressed() {
        if (day >= 0 || choosingLocation) home();
        else if (data != null && dashboardTab != DashboardTab.WEATHER) switchDashboardTab(DashboardTab.WEATHER);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        stopLocating();
        if (pendingSearch != null) main.removeCallbacks(pendingSearch);
        network.shutdownNow();
        super.onDestroy();
    }

    private static final class DayWeather {
        final int code;
        final boolean night;
        final int eventCode;
        final String eventTime;

        DayWeather(int code, boolean night, int eventCode, String eventTime) {
            this.code = code;
            this.night = night;
            this.eventCode = eventCode;
            this.eventTime = eventTime;
        }
    }

    private static DayWeather summarizeWeather(JSONObject hourly, int eventStart, String date, int fallback) {
        int start = 0;
        int size = length(hourly);
        while (start < size && !at(hourly, "time", start).startsWith(date)) start++;
        int end = start;
        int[] counts = new int[100];
        int[] daylight = new int[100];
        boolean hasDaylight = false;
        boolean allNight = true;
        while (end < size && at(hourly, "time", end).startsWith(date)) {
            double rawCode = value(hourly, "weather_code", end);
            double isDay = value(hourly, "is_day", end++);
            if (!Double.isFinite(rawCode) || rawCode < 0 || rawCode >= counts.length) continue;
            int code = (int) rawCode;
            counts[code]++;
            allNight &= isDay == 0;
            if (isDay == 1) {
                daylight[code]++;
                hasDaylight = true;
            }
        }

        int[] preferred = hasDaylight ? daylight : counts;
        int predominant = fallback;
        int highestCount = 0;
        for (int code = 0; code < preferred.length; code++) {
            if (preferred[code] > highestCount) {
                predominant = code;
                highestCount = preferred[code];
            }
        }

        int eventCode = 0;
        int eventGroup = 0;
        String eventTime = null;
        int predominantGroup = weatherEventGroup(predominant);
        for (int hour = Math.max(start, eventStart); hour < end; hour++) {
            int code = (int) value(hourly, "weather_code", hour);
            int group = weatherEventGroup(code);
            if (group != 0 && group != predominantGroup
                    && (group > eventGroup || (group == eventGroup && code > eventCode))) {
                eventGroup = group;
                eventCode = code;
                eventTime = at(hourly, "time", hour);
            }
        }
        return new DayWeather(predominant, highestCount > 0 && allNight, eventCode, eventTime);
    }

    private static int weatherEventGroup(int code) {
        if (code >= 95) return 5;
        if (code == 56 || code == 57 || code == 66 || code == 67) return 4;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return 3;
        if ((code >= 51 && code <= 65) || (code >= 80 && code <= 82)) return 2;
        if (code == 45 || code == 48) return 1;
        return 0;
    }

    private String weatherNote(DayWeather summary) {
        return getString(R.string.forecast_weather_event, description(summary.eventCode), clock(summary.eventTime));
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

    private String eventClock(String timestamp) {
        return timestamp.length() < 19 ? clock(timestamp)
                : LocalTime.parse(timestamp.substring(11)).format(clock24 ? CLOCK_24_SECONDS : CLOCK_SECONDS);
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
    private static final char FOG = '\uE003';
    private static final char SNOW = '\uE004';
    private static final char RIME_FOG = '\uE005';
    private static final char LIGHT_DRIZZLE = '\uE006';
    private static final char MODERATE_DRIZZLE = '\uE007';
    private static final char HEAVY_DRIZZLE = '\uE008';
    private static final char CLOUDY_NIGHT = '\uE009';
    private static final char CLOUDY_DAY = '\uE00A';
    private static final char MOSTLY_CLEAR_NIGHT = '\uE00B';
    private static final char MOSTLY_CLEAR_DAY = '\uE00C';
    private static final char LIGHT_SNOW = '\uE00D';
    private static final char MODERATE_SNOW = '\uE00E';
    private static final char SNOW_GRAINS = '\uE00F';
    private static final char LIGHT_FREEZING_DRIZZLE = '\uE010';
    private static final char HEAVY_FREEZING_DRIZZLE = '\uE011';
    private static final char LIGHT_SHOWERS = '\uE012';
    private static final char MODERATE_SHOWERS = '\uE013';
    private static final char HEAVY_SHOWERS = '\uE014';
    private static final char LIGHT_FREEZING_RAIN = '\uE015';
    private static final char HEAVY_FREEZING_RAIN = '\uE016';
    private static final char LIGHT_SNOW_SHOWERS = '\uE017';
    private static final char HEAVY_SNOW_SHOWERS = '\uE018';
    private static final char THUNDERSTORM = '\uE019';
    private static final char THUNDERSTORM_LIGHT_HAIL = '\uE01A';
    private static final char THUNDERSTORM_HEAVY_HAIL = '\uE01B';

    private static String symbol(int code, boolean night) {
        if (code == 0) return night ? "🌙" : "☀️";
        if (code == 1) return String.valueOf(night ? MOSTLY_CLEAR_NIGHT : MOSTLY_CLEAR_DAY);
        if (code == 2) return String.valueOf(night ? CLOUDY_NIGHT : CLOUDY_DAY);
        if (code == 3) return "☁️";
        if (code == 48) return String.valueOf(RIME_FOG);
        if (code < 48) return String.valueOf(FOG);
        if (code == 51) return String.valueOf(LIGHT_DRIZZLE);
        if (code == 53) return String.valueOf(MODERATE_DRIZZLE);
        if (code == 55) return String.valueOf(HEAVY_DRIZZLE);
        if (code == 56) return String.valueOf(LIGHT_FREEZING_DRIZZLE);
        if (code == 57) return String.valueOf(HEAVY_FREEZING_DRIZZLE);
        if (code == 71) return String.valueOf(LIGHT_SNOW);
        if (code == 73) return String.valueOf(MODERATE_SNOW);
        if (code == 77) return String.valueOf(SNOW_GRAINS);
        if (code == 75) return String.valueOf(SNOW);
        if (code == 85) return String.valueOf(LIGHT_SNOW_SHOWERS);
        if (code == 86) return String.valueOf(HEAVY_SNOW_SHOWERS);
        if (code == 96) return String.valueOf(THUNDERSTORM_LIGHT_HAIL);
        if (code == 99) return String.valueOf(THUNDERSTORM_HEAVY_HAIL);
        if (code >= 95) return String.valueOf(THUNDERSTORM);
        if (code == 80) return String.valueOf(LIGHT_SHOWERS);
        if (code == 81) return String.valueOf(MODERATE_SHOWERS);
        if (code == 82) return String.valueOf(HEAVY_SHOWERS);
        if (code == 66) return String.valueOf(LIGHT_FREEZING_RAIN);
        if (code == 67) return String.valueOf(HEAVY_FREEZING_RAIN);
        if (code == 65) return String.valueOf(HEAVY_RAIN);
        if (code == 63) return String.valueOf(MODERATE_RAIN);
        return String.valueOf(LIGHT_RAIN);
    }

    private String description(int code) {
        switch (code) {
            case 0: return getString(R.string.clear_weather);
            case 1: return getString(R.string.mostly_clear);
            case 2: return getString(R.string.partly_cloudy);
            case 3: return getString(R.string.cloudy);
            case 45: return getString(R.string.fog);
            case 48: return getString(R.string.rime_fog);
            case 51: return getString(R.string.light_drizzle);
            case 53: return getString(R.string.moderate_drizzle);
            case 55: return getString(R.string.heavy_drizzle);
            case 56: return getString(R.string.light_freezing_drizzle);
            case 57: return getString(R.string.heavy_freezing_drizzle);
            case 61: return getString(R.string.light_rain);
            case 63: return getString(R.string.moderate_rain);
            case 65: return getString(R.string.heavy_rain);
            case 66: return getString(R.string.light_freezing_rain);
            case 67: return getString(R.string.heavy_freezing_rain);
            case 71: return getString(R.string.light_snow);
            case 73: return getString(R.string.moderate_snow);
            case 75: return getString(R.string.heavy_snow);
            case 77: return getString(R.string.snow_grains);
            case 80: return getString(R.string.light_showers);
            case 81: return getString(R.string.moderate_showers);
            case 82: return getString(R.string.heavy_showers);
            case 85: return getString(R.string.light_snow_showers);
            case 86: return getString(R.string.heavy_snow_showers);
            case 95: return getString(R.string.thunderstorms);
            case 96: return getString(R.string.thunderstorm_light_hail);
            case 99: return getString(R.string.thunderstorm_heavy_hail);
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
        view.setBackground(cardBackground(16));
        view.setPadding(dp(13), dp(13), dp(13), dp(13));
        return view;
    }
    private GradientDrawable cardBackground(int radius) {
        GradientDrawable drawable = background(getColor(R.color.sky_card), radius);
        drawable.setStroke(dp(1), getColor(R.color.sky_card_outline));
        return drawable;
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
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accent));
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        status.addView(spinner, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView text = label(message, 16, muted);
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
            if (marker < LIGHT_RAIN || marker > THUNDERSTORM_HEAVY_HAIL) continue;
            if (decorated == null) decorated = new android.text.SpannableString(text);
            int resource = marker == LIGHT_RAIN ? R.drawable.ic_rain_light
                    : marker == MODERATE_RAIN ? R.drawable.ic_rain_moderate
                    : marker == HEAVY_RAIN ? R.drawable.ic_rain_heavy
                    : marker == FOG ? R.drawable.ic_fog
                    : marker == SNOW ? R.drawable.ic_snow
                    : marker == RIME_FOG ? R.drawable.ic_rime_fog
                    : marker == LIGHT_DRIZZLE ? R.drawable.ic_drizzle_light
                    : marker == MODERATE_DRIZZLE ? R.drawable.ic_drizzle_moderate
                    : marker == HEAVY_DRIZZLE ? R.drawable.ic_drizzle_heavy
                    : marker == CLOUDY_NIGHT ? R.drawable.ic_cloudy_night
                    : marker == CLOUDY_DAY ? R.drawable.ic_cloudy_day
                    : marker == MOSTLY_CLEAR_NIGHT ? R.drawable.ic_mostly_clear_night
                    : marker == MOSTLY_CLEAR_DAY ? R.drawable.ic_mostly_clear_day
                    : marker == LIGHT_SNOW ? R.drawable.ic_snow_light
                    : marker == MODERATE_SNOW ? R.drawable.ic_snow_moderate
                    : marker == SNOW_GRAINS ? R.drawable.ic_snow_grains
                    : marker == LIGHT_FREEZING_DRIZZLE ? R.drawable.ic_freezing_drizzle_light
                    : marker == HEAVY_FREEZING_DRIZZLE ? R.drawable.ic_freezing_drizzle_heavy
                    : marker == LIGHT_SHOWERS ? R.drawable.ic_showers_light
                    : marker == MODERATE_SHOWERS ? R.drawable.ic_showers_moderate
                    : marker == HEAVY_SHOWERS ? R.drawable.ic_showers_heavy
                    : marker == LIGHT_FREEZING_RAIN ? R.drawable.ic_freezing_rain_light
                    : marker == HEAVY_FREEZING_RAIN ? R.drawable.ic_freezing_rain_heavy
                    : marker == LIGHT_SNOW_SHOWERS ? R.drawable.ic_snow_showers_light
                    : marker == HEAVY_SNOW_SHOWERS ? R.drawable.ic_snow_showers_heavy
                    : marker == THUNDERSTORM ? R.drawable.ic_thunderstorm
                    : marker == THUNDERSTORM_LIGHT_HAIL ? R.drawable.ic_thunderstorm_light_hail
                    : R.drawable.ic_thunderstorm_heavy_hail;
            android.graphics.drawable.Drawable icon = getDrawable(resource);
            int pixels = Math.round(view.getTextSize() * 1.05f);
            icon.setBounds(0, 0, pixels, pixels);
            decorated.setSpan(new WeatherIconSpan(icon),
                    i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(decorated == null ? text : decorated);
    }

    private TextView button(String text, Runnable click) {
        TextView view = label(text, 15, accent);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setMinHeight(dp(44));
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private Button actionButton(String text, int fill, int radius) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(accent);
        view.setLetterSpacing(0);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(dp(44));
        view.setPaddingRelative(dp(12), dp(4), dp(12), dp(4));
        view.setStateListAnimator(null);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(getColor(R.color.sky_ripple)), background(fill, radius), null));
        return view;
    }

    private Button moreButton() {
        return actionButton(getString(R.string.more), surfaceColor, 12);
    }

    private Button accordionButton(String text) {
        Button toggle = actionButton(text, surfaceColor, 12);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        toggle.setCompoundDrawablePadding(dp(12));
        updateAccordion(toggle, false);
        return toggle;
    }

    private void updateAccordion(Button toggle, boolean expanded) {
        android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_expand);
        if (expanded) {
            android.graphics.drawable.RotateDrawable rotated = new android.graphics.drawable.RotateDrawable();
            rotated.setDrawable(arrow);
            rotated.setFromDegrees(180);
            rotated.setToDegrees(180);
            rotated.setLevel(10000);
            arrow = rotated;
        }
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            toggle.setStateDescription(getString(expanded ? R.string.expanded : R.string.collapsed));
        }
    }
    private TextView header(String name, Runnable click) {
        TextView header = button(name, click);
        header.setTextSize(20);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setSingleLine();
        header.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(background(getColor(R.color.sky_header), 13));
        header.setPadding(dp(14), dp(9), dp(14), dp(9));
        return header;
    }
    private TextView title(String text) {
        TextView title = label(text, 19, ink);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, dp(23), 0, dp(11));
        page.addView(title);
        return title;
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
