package it.skynow.app;

import it.skynow.app.update.GitHubUpdates;
import it.skynow.app.notifications.IssAlerts;
import it.skynow.app.notifications.EarthquakeAlerts;
import it.skynow.app.notifications.EarthquakeAlertCheck;
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
import it.skynow.app.ui.SignalColors;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final int[] MOON_PHASE_NAMES = {R.string.moon_new, R.string.moon_waxing_crescent, R.string.moon_first_quarter,
            R.string.moon_waxing_gibbous, R.string.moon_full, R.string.moon_waning_gibbous,
            R.string.moon_last_quarter, R.string.moon_waning_crescent};
    private static final int ASTRONOMY_PAGE_SIZE = 4;
    private static final int FORECAST_DAYS = 10;
    private static final int LOCATION_PERMISSION = 1;
    private static final int ASTRONOMY_NOTIFICATION_PERMISSION = 2;
    private static final long FORECAST_REFRESH_MS = 5 * 60 * 1000;
    private static final int REFRESH_PULL_DP = 72;
    private static final int DETAIL_CHART_HEIGHT_DP = 180;
    private static final int SKY_PATH_HEIGHT_DP = 108;
    private SkyViews views;
    private Formats formats;
    private EarthquakeTab earthquakes;
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
    private double latitude;
    private double longitude;
    private static final int SETTINGS_REQUEST = 403;
    private int appliedTheme;
    private int appliedLanguage;

    private int day = -1;
    private enum DashboardTab { WEATHER, ASTRONOMY, EARTHQUAKES }
    private String pendingAstronomyEventSection;
    private ZoneId astronomyNotificationZone;
    private boolean solarEclipsesExpanded;
    private boolean lunarEclipsesExpanded;
    private boolean transitsExpanded;
    private DashboardTab dashboardTab = DashboardTab.WEATHER;
    private final int[] dashboardOffsets = new int[DashboardTab.values().length];
    private Button earthquakeTabButton;
    private int homeScroll;
    private boolean choosingLocation;
    private android.window.OnBackInvokedCallback appBackCallback;
    private final Handler main = new Handler(Looper.getMainLooper());
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
    private boolean resumed;
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
            String reading = getString(trueNorth ? R.string.phone_direction_reading : R.string.phone_direction_magnetic,
                    direction, elevation);
            if (lowAccuracy) reading += "\n" + getString(R.string.phone_compass_low_accuracy);
            setPhoneReading(skyTrackDirection, reading);
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final Runnable updateSkyCoordinates = new Runnable() {
        @Override public void run() {
            Instant now = Instant.now();
            if (sunCoordinates != null) setSkyCoordinates(sunCoordinates, now, false);
            if (moonCoordinates != null) setSkyCoordinates(moonCoordinates, now, true);
            if (!planetRows.isEmpty()) updatePlanets(now);
            if (nextWarningChange != null && !now.isBefore(nextWarningChange)) renderWarnings(now);
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
            pendingAstronomyEventSection = getIntent().getStringExtra(IssAlerts.OPEN_EVENT_SECTION);
            if (pendingAstronomyEventSection != null || getIntent().getBooleanExtra("openIssAlerts", false)) {
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
        pendingAstronomyEventSection = section;
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
            astronomyNotificationZone = zone == null ? ZoneId.systemDefault() : ZoneId.of(zone);
            layout();
            page.addView(placeTitle());
            eclipsesAndTransits();
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
                : issPassUrl(pass, zone, observer[0], observer[1], observer[2], intent.getStringExtra(IssAlerts.PLACE));
        showIssPass(pass, zone, period, url);
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
        refreshAstronomyVisibility();
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
        main.removeCallbacks(updateAstronomyVisibility);
        stopPhoneOrientation();
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
            if (dashboardTab == DashboardTab.ASTRONOMY) {
                auroraAttempt = 0;
                kpAttempt = 0;
            }
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
                    int offset = refreshing && !manual && scroll != null && pendingAstronomyEventSection == null
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
                    astronomyNotificationZone = null;
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
                    if (refreshing || choosingLocation || astronomyNotificationZone != null) return;
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
                pendingAstronomyEventSection = null;
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
        visibilityBadges.clear();
        refreshAstronomyVisibility();
        placeHeader = null;
        regionalWarnings = null;
        auroraStatus = null;
        auroraNow = null;
        auroraTimes = null;
        currentKpView = null;
        currentKpBar = null;
        kpTable = null;
        forecastMaps = null;
        airQualityMapButton = null;
        earthquakes.detach();
        earthquakeTabButton = null;
        sunCoordinates = null;
        moonCoordinates = null;
        sunTrackHeading = null;
        moonTrackHeading = null;
        sunPath = null;
        moonPath = null;
        planetRows.clear();
        noObservablePlanets = null;
        planetWindowDate = null;
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
            screen.addView(detailHeader(getString(R.string.forecast_10_days), R.string.settings_back, this::home));
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
                sun(daily, 0);
                moon(daily, 0);
                astronomicalSeasons();
                planets();
                issPasses();
                aurora();
                eclipsesAndTransits();
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
                TextView chance = views.label(displayedProbability > 0 ? percent(displayedProbability) : "", 14, views.accent);
                chance.setGravity(roomy ? Gravity.START : Gravity.END);
                chance.setSingleLine(true);
                int chanceWidth = Math.max(views.dp(40), Math.max(views.textWidth(chance, percent(100)),
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
                titleRow.addView(nowBadge(), badgeSize);
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
                detailSummary(summary, getString(R.string.daily_high), percent(DailyWeather.hourlySummary(hourly, "relative_humidity_2m", date, true)));
                detailSummary(summary, getString(R.string.daily_average), percent(value(daily, "relative_humidity_2m_mean", index)));
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

    private String percent(double value) {
        return Double.isFinite(value) ? formats.number(value, "%.0f") + "%" : "—";
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

    private String solarNoon(JSONObject daily, int index) {
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        return Formats.formatTime(SkyPath.solarNoon(date, longitude, ZoneId.of(data.optString("timezone", "UTC"))), formats.clockFormat());
    }

    private LinearLayout dailySunTimes(JSONObject daily, int index) {
        LinearLayout tile = views.card();
        tile.addView(views.label(getString(R.string.sunrise_sunset), 14, views.muted));
        long seconds = daylightSeconds(daily, index);
        if (isPolarDaylight(seconds)) {
            TextView status = views.label(getString(polarDaylightLabel(seconds)), 13, views.ink);
            status.setPadding(0, views.dp(5), 0, 0);
            tile.addView(status);
        }
        String[] labels = {getString(R.string.sunrise), getString(R.string.solar_noon), getString(R.string.sunset)};
        String[] values = {isPolarDaylight(seconds) ? "—" : formats.clock(at(daily, "sunrise", index)), solarNoon(daily, index),
                isPolarDaylight(seconds) ? "—" : formats.clock(at(daily, "sunset", index))};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout line = views.row();
            line.setPadding(0, views.dp(5), 0, 0);
            line.addView(views.label(labels[i], 12, views.muted), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(views.label(values[i], 13, views.ink));
            tile.addView(line);
        }
        return tile;
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
        return getString(R.string.sky_coordinates, Math.floorMod(Math.round(azimuth), 360),
                compassDirection(azimuth), Math.round(elevation));
    }

    private void sun(JSONObject daily, int index) {
        LinearLayout tile = views.card();
        sunTrackHeading = addSkyTrackHeading(tile, astronomyTitleIcon(R.drawable.ic_sun_astronomy), getString(R.string.sun),
                () -> showDailySkyTrack(getString(R.string.sun), false, null, null));
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
            views.addSpaced(tile, views.label(getString(polarDaylightLabel(seconds)), 14, views.ink), 7);
        } else {
            LinearLayout times = views.row();
            times.setGravity(Gravity.TOP);
            addRiseSetTime(times, getString(R.string.sunrise), Formats.formatTime(sunrise, formats.clockFormat()), false);
            addRiseSetTime(times, getString(R.string.sunset), Formats.formatTime(sunset, formats.clockFormat()), true);
            views.addSpaced(tile, times, 7);
        }
        FrameLayout chart = new FrameLayout(this);
        sunPath = new SkyPath(this, date, zone, Instant.now(), latitude, longitude, false, formats.clock24);
        chart.addView(sunPath, new FrameLayout.LayoutParams(-1, -1));
        TextView maximum = views.label(getString(R.string.sun_max_today, solarMaximumElevation(date, zone, seconds)), 12, views.muted);
        maximum.setPaddingRelative(views.dp(4), 0, views.dp(4), 0);
        maximum.setBackground(views.background(getColor(R.color.sky_card), 4));
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
        String dawn = twilight > 0 && sunrise != null ? Formats.formatTime(sunrise.minusMinutes(twilight), formats.clockFormat()) : "—";
        String dusk = twilight > 0 && sunset != null ? Formats.formatTime(sunset.plusMinutes(twilight), formats.clockFormat()) : "—";
        if (!polar) legend.addView(views.label(getString(R.string.dawn) + "\n" + dawn, 12, views.muted), new LinearLayout.LayoutParams(0, -2, 1));
        TextView noonLabel = views.label(getString(R.string.solar_noon) + "\n" + solarNoon(daily, index), 12, views.muted);
        noonLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        legend.addView(noonLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView duskLabel = views.label(getString(R.string.dusk) + "\n" + dusk, 12, views.muted);
        duskLabel.setGravity(Gravity.END);
        if (!polar) legend.addView(duskLabel, new LinearLayout.LayoutParams(0, -2, 1));
        views.addSpaced(tile, legend, 7);
    }

    private String solarMaximumElevation(LocalDate date, ZoneId zone, long seconds) {
        double maximum = SkyPosition.maximumSolarElevation(date, zone, latitude, longitude);
        if (seconds > 0) maximum = Math.max(0, maximum);
        return maximum < 0 && Math.round(maximum) == 0 ? "<0°" : formats.number(maximum, "%.0f") + "°";
    }

    private void moon(JSONObject daily, int index) {
        LocalDate date = LocalDate.parse(at(daily, "time", index));
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        double phase = moonPhaseForToday(daily, index, date, zone);
        LinearLayout tile = views.card();
        moonTrackHeading = addSkyTrackHeading(tile, astronomyTitleIcon(R.drawable.ic_moon_astronomy), getString(R.string.moon),
                () -> showDailySkyTrack(getString(R.string.moon), true, null, null));
        LinearLayout readings = views.row();
        readings.setGravity(Gravity.TOP);
        LocalDateTime moonrise = nextLunarEvent(daily, "moonrise", index, null);
        for (String field : new String[]{"moonrise", "moonset"}) {
            boolean rise = field.equals("moonrise");
            String event = getString(rise ? R.string.moonrise : R.string.moonset);
            LocalDateTime eventTime = rise ? moonrise : nextLunarEvent(daily, field, index, moonrise);
            LinearLayout time = addRiseSetTime(readings, event,
                    eventTime == null ? getString(R.string.unavailable) : eventTime.format(formats.clockFormat()), !rise);
            if (eventTime != null) {
                LocalDate eventDate = eventTime.toLocalDate();
                String dayLabel = eventDate.equals(date) ? getString(R.string.today)
                        : eventDate.equals(date.plusDays(1)) ? getString(R.string.tomorrow)
                        : eventDate.format(formats.shortDate(false));
                TextView when = views.label(dayLabel, 11, views.muted);
                when.setPadding(0, views.dp(3), 0, 0);
                when.setGravity(rise ? Gravity.START : Gravity.END);
                time.addView(when);
            }
        }
        views.addSpaced(tile, readings, 7);
        moonPath = new SkyPath(this, date, zone, Instant.now(), latitude, longitude, true, formats.clock24);
        LinearLayout.LayoutParams chartSize = new LinearLayout.LayoutParams(-1, views.dp(SKY_PATH_HEIGHT_DP));
        chartSize.topMargin = views.dp(6);
        tile.addView(moonPath, chartSize);
        LinearLayout phaseDetails = views.row();
        phaseDetails.setPadding(views.dp(5), views.dp(3), views.dp(5), 0);
        LinearLayout phaseLabels = views.column();
        if (Double.isFinite(phase)) {
            int position = moonPhaseIndex(phase, date, zone);
            MoonPhaseView icon = new MoonPhaseView(this, phase, latitude < 0);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            phaseDetails.addView(icon, new LinearLayout.LayoutParams(views.dp(30), views.dp(30)));
            phaseLabels.setPadding(views.dp(8), 0, 0, 0);
            TextView illuminated = views.label(percent(100 * MoonPhaseView.illumination(phase)), 15, views.ink);
            illuminated.setContentDescription(getString(R.string.moon_illuminated, illuminated.getText()));
            phaseLabels.addView(illuminated);
            TextView phaseName = views.label(getString(MOON_PHASE_NAMES[position]), 13, views.muted);
            phaseName.setPadding(0, views.dp(6), 0, 0);
            phaseLabels.addView(phaseName);
        } else {
            phaseLabels.addView(views.label(getString(R.string.phase_unavailable), 13, views.muted));
        }
        phaseDetails.addView(phaseLabels, new LinearLayout.LayoutParams(0, -2, 1));
        views.addSpaced(tile, phaseDetails, 8);
        moonCoordinates = skyCoordinates(true);
        tile.addView(moonCoordinates);
        DateTimeFormatter format = formats.shortDate(false);
        LinearLayout phases = views.row();
        phases.setGravity(Gravity.TOP);
        phases.setPadding(0, views.dp(13), 0, 0);
        for (boolean full : new boolean[]{true, false}) {
            LinearLayout event = views.column();
            TextView phaseTitle = views.label(full ? getString(R.string.next_full_moon) : getString(R.string.next_new_moon), 13, views.muted);
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
            events = OrbitalApsides.upcoming(this, Instant.now(), moon);
        } catch (java.io.IOException error) {
            android.util.Log.w("SkyHere", "Orbital apsides unavailable", error);
            return;
        }
        if (events[0] == null && events[1] == null) return;
        int[] titles = moon ? new int[]{R.string.next_perigee, R.string.next_apogee}
                : new int[]{R.string.next_perihelion, R.string.next_aphelion};
        LinearLayout upcoming = views.row();
        upcoming.setPadding(0, views.dp(12), 0, 0);
        DateTimeFormatter date = formats.shortDate(true);
        for (int i = 0; i < titles.length; i++) {
            LinearLayout column = views.column();
            column.setPaddingRelative(0, 0, views.dp(6), 0);
            TextView title = views.label(getString(titles[i]), 13, views.muted);
            if (views.stackedCards()) title.setMinLines(2);
            column.addView(title);
            OrbitalApsides.Event event = events[i];
            if (event != null) {
                ZonedDateTime local = event.time.plusSeconds(30).atZone(zone);
                column.addView(views.label(date.format(local), 14, views.ink));
                column.addView(views.label(local.format(formats.clockFormat()) + " · "
                        + String.format(formats.appLocale(), "%,d km", event.distanceKm), 12, views.muted));
            } else {
                column.addView(views.label(getString(R.string.unavailable), 13, views.muted));
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
        TextView heading = views.label(getString(R.string.planets), 16, views.ink);
        title.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        CheckBox visibleOnly = new CheckBox(this);
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
            entry.setBackground(views.background(getColor(R.color.sky_subtle), 12));
            entry.setPadding(views.dp(11), views.dp(9), views.dp(11), views.dp(9));
            LinearLayout planetTitle = views.row();
            addCelestialTitleIcon(planetTitle, new PlanetIcon(this, body));
            TextView name = views.label(getString(names[body.ordinal()]), 16, views.ink);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            planetTitle.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView magnitude = astronomyBadge("");
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
                TextView optics = views.label(getString(R.string.uranus_binoculars), 12, views.muted);
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
        noObservablePlanets = views.label(getString(R.string.planets_none_visible_now), 13, views.muted);
        tile.addView(noObservablePlanets);
        TextView note = views.label(getString(R.string.planets_clear_sky), 11, views.muted);
        note.setPadding(0, views.dp(8), 0, 0);
        tile.addView(note);
        updatePlanets(Instant.now());
        views.addSpaced(page, tile, 9);
    }

    private void startPhoneOrientation() {
        if (!resumed || skyTrackDirection == null || phoneOrientationActive) return;
        SensorManager manager = getSystemService(SensorManager.class);
        Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (sensor == null) sensor = manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        phoneOrientationActive = sensor != null
                && manager.registerListener(phoneOrientation, sensor, SensorManager.SENSOR_DELAY_GAME, 0);
        if (!phoneOrientationActive) skyTrackDirection.setText(R.string.phone_direction_unavailable);
    }

    private void stopPhoneOrientation() {
        if (phoneOrientationActive) getSystemService(SensorManager.class).unregisterListener(phoneOrientation);
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
                    String start = window.start.atZone(zone).format(formats.clockFormat());
                    String end = window.end.equals(tomorrow) && formats.clock24 ? "24:00" : window.end.atZone(zone).format(formats.clockFormat());
                    hours.add(start + "–" + end);
                }
                row.windows.setText(hours.isEmpty() ? getString(R.string.planet_no_window)
                        : getString(R.string.planet_today_windows, android.text.TextUtils.join(" · ", hours)));
            }
            boolean observingNow = row.observesAt(now);
            anyObservable |= observingNow;
            row.entry.setVisibility(!observablePlanetsOnly || observingNow ? View.VISIBLE : View.GONE);
            setSkyTrackAvailable(row.entry, row.now, observingNow);
            row.position.setVisibility(observingNow ? View.VISIBLE : View.GONE);
            if (observingNow) {
                row.position.setText(getString(R.string.planet_position, Math.round(reading.azimuth) % 360,
                        compassDirection(reading.azimuth), Math.round(reading.elevation)));
            }
        }
        noObservablePlanets.setVisibility(observablePlanetsOnly && !anyObservable ? View.VISIBLE : View.GONE);
        planetWindowDate = today;
    }

    private String compassDirection(double azimuth) {
        String[] directions = formats.italian()
                ? new String[]{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO"}
                : new String[]{"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return directions[(int) Math.round(azimuth / 22.5) % directions.length];
    }

    private void aurora() {
        LinearLayout tile = views.card();
        LinearLayout heading = astronomyTitle(astronomyTitleIcon(R.drawable.ic_aurora_astronomy), getString(R.string.aurora));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setMinimumHeight(views.dp(32));
        auroraNow = nowActionBadge(() -> openMap(RadarActivity.Layer.AURORA));
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
        Button map = views.mapButton(R.string.aurora_map_short, () -> openMap(RadarActivity.Layer.AURORA));
        map.setContentDescription(getString(R.string.aurora_map));
        LinearLayout.LayoutParams mapSize = new LinearLayout.LayoutParams(-2, -2);
        mapSize.setMarginStart(views.dp(8));
        mapRow.addView(map, mapSize);
        tile.addView(mapRow);
        LinearLayout table = views.column();
        kpTable = table;
        table.setVisibility(View.GONE);
        Button toggle = views.accordionButton(getString(R.string.aurora_kp_forecast));
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
        DateTimeFormatter hours = DateTimeFormatter.ofPattern(formats.clock24 ? "HH:mm" : "h:mm a", formats.appLocale()).withZone(zone);
        DateTimeFormatter dates = formats.shortDate(false).withZone(zone);
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
            TextView activity = views.label("Kp " + formats.number(period.kp, "%.2f") + "/9", 14, views.accent);
            line.addView(activity);
            kpTable.addView(line);
        }
        int text = currentDate != null ? R.string.aurora_kp_hint
                : kpLoading ? R.string.aurora_loading : R.string.aurora_unavailable;
        views.addSpaced(kpTable, views.label(getString(text), 12, views.muted), 8);
    }

    private View kpBar() {
        View bar = new View(this);
        android.graphics.drawable.ClipDrawable fill = new android.graphics.drawable.ClipDrawable(
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{SignalColors.GREEN, SignalColors.YELLOW, SignalColors.RED}),
                Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        bar.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                views.background(getColor(R.color.sky_selected), 3), fill}));
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
        currentKpView.setText(getString(R.string.aurora_kp_current, formats.number(currentKp.kp, "%.2f"),
                Formats.formatTime(currentKp.time.atZone(zone).toLocalTime(), formats.clockFormat())));
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
        auroraTimes.setText(getString(R.string.aurora_times,
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
            auroraStatus.setText(getString(R.string.aurora_direction, compassDirection(direction)));
        }
    }

    private String auroraTimestamp(ZonedDateTime timestamp, LocalDate today) {
        String time = timestamp.format(formats.clockFormat());
        return timestamp.toLocalDate().equals(today) ? time
                : timestamp.format(DateTimeFormatter.ofPattern(formats.italian() ? "d/M" : "M/d", formats.appLocale())) + " " + time;
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
        tile.addView(astronomyTitle(astronomyTitleIcon(R.drawable.ic_iss_astronomy), getString(R.string.iss_passes)));
        TextView note = views.label(getString(R.string.iss_scope), 13, views.muted);
        views.addSpaced(tile, note, 8);
        TextView status = views.label(getString(R.string.iss_loading), 13, views.muted);
        tile.addView(status);
        views.addSpaced(page, tile, 9);
        double lat = latitude;
        double lon = longitude;
        double height = data.optDouble("elevation");
        ZoneId zone = ZoneId.of(data.optString("timezone", "UTC"));
        CompletableFuture.supplyAsync(() -> {
            try {
                return IssPasses.upcoming(getApplicationContext(), lat, lon, height, Instant.now());
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
        DateTimeFormatter dayFormat = formats.shortDate(true);
        DateTimeFormatter timeFormat = formats.clockFormat();
        for (int i = start; i < end; i++) {
            IssPasses.Pass pass = passes.get(i);
            ZonedDateTime rise = Instant.ofEpochSecond(pass.rise.time).atZone(zone);
            ZonedDateTime set = Instant.ofEpochSecond(pass.set.time).atZone(zone);
            String range = timeFormat.format(rise) + " – " + timeFormat.format(set);
            if (!rise.toLocalDate().equals(set.toLocalDate())) range += getString(R.string.next_day_suffix);
            long duration = pass.set.time - pass.rise.time;
            LinearLayout entry = views.column();
            entry.setPadding(views.dp(12), views.dp(8), views.dp(12), views.dp(8));
            entry.setBackground(views.background(getColor(R.color.sky_subtle), 12));
            LinearLayout heading = views.row();
            heading.setGravity(Gravity.TOP);
            String headingText = dayFormat.format(rise) + " · " + range;
            TextView period = views.label(headingText.replace(" – ", "\u00a0–\u00a0"), 15, views.ink);
            heading.addView(period, new LinearLayout.LayoutParams(0, -2, 1));
            TextView magnitude = astronomyBadge("");
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
            TextView details = views.label(getString(R.string.iss_details, duration / 60, duration % 60,
                    Math.round(pass.peak.elevation)), 13, views.muted);
            views.addSpaced(entry, details, 3);
            String appears = issBearing(pass.rise.azimuth);
            String culminates = issBearing(pass.peak.azimuth);
            String disappears = issBearing(pass.set.azimuth);
            TextView directions = views.label(getString(R.string.iss_directions_short, appears, culminates, disappears), 13, views.muted);
            directions.setContentDescription(getString(R.string.iss_directions, appears, culminates, disappears));
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
        TextView badge = observingBadge(getString(R.string.astronomy_visible_now));
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
            skyTrackCoordinates.setText(getString(R.string.sky_track_now) + " · "
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
        android.widget.ImageView icon = new android.widget.ImageView(this);
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

    private TextView astronomyBadge(String text) {
        TextView badge = views.label(text, 13, views.ink);
        badge.setPaddingRelative(views.dp(7), views.dp(3), views.dp(7), views.dp(3));
        return badge;
    }

    private TextView observingBadge(String text) {
        TextView badge = astronomyBadge(text);
        badge.setTextColor(getColor(R.color.sky_observing));
        badge.setBackground(views.background(SignalColors.tint(SignalColors.GREEN), 8));
        return badge;
    }

    private TextView nowBadge() {
        return observingBadge(getString(R.string.sky_track_now));
    }

    private TextView nowActionBadge(Runnable open) {
        TextView badge = observingBadge(getString(R.string.sky_track_now) + "  ›");
        badge.setContentDescription(getString(R.string.astronomy_open_now_details));
        views.cardAction(badge, open);
        return badge;
    }

    private void setMagnitudeBadge(TextView badge, double magnitude, int fill) {
        String value = formats.number(magnitude, "%+.1f");
        badge.setText(getString(R.string.astronomy_magnitude, value));
        badge.setContentDescription(getString(R.string.astronomy_magnitude_description, value));
        badge.setBackground(views.background(SignalColors.tint(fill), 8));
    }

    private void showIssPass(IssPasses.Pass pass, ZoneId zone, String period, String url) {
        LocalDate date = Instant.ofEpochSecond(pass.rise.time).atZone(zone).toLocalDate();
        showSkyTrack(getString(R.string.iss_track), period, pass.magnitude, magnitudeColor(pass.magnitude), panel -> {
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
        String period = date.format(formats.shortDate(true));
        if (track.continuous && track.rise != null && track.set != null && track.rise.time <= track.set.time) {
            period += " · " + skyTrackTime(track.rise, zone, date) + " – " + skyTrackTime(track.set, zone, date);
        }
        int fill = planet == null ? SignalColors.PURPLE : magnitudeColor(magnitude);
        showSkyTrack(getString(R.string.daily_sky_track, name), period, magnitude, fill, panel -> {
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
                panel.addView(views.label(getString(message), 14, views.muted));
            }
            addSkyTrackPoint(panel, nightOnly ? R.string.iss_track_start : R.string.sky_track_rise,
                    track.rise, SkyTrack.START_COLOR, zone, date);
            addSkyTrackPoint(panel, R.string.iss_track_peak, track.peak, SkyTrack.MAX_COLOR, zone, date);
            addSkyTrackPoint(panel, nightOnly ? R.string.iss_track_end : R.string.sky_track_set,
                    track.set, SkyTrack.END_COLOR, zone, date);
        });
        refreshAstronomyVisibility();
    }

    private LinearLayout detailHeader(String title, int backDescription, Runnable goBack) {
        LinearLayout header = views.row();
        TextView back = views.label("‹", 28, views.ink);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(backDescription));
        back.setOnClickListener(v -> goBack.run());
        header.addView(back, new LinearLayout.LayoutParams(views.dp(48), views.dp(48)));
        TextView titleView = views.label(title, 19, views.ink);
        titleView.setPaddingRelative(0, views.dp(7), views.dp(12), views.dp(7));
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
        return header;
    }

    private void showSkyTrack(String title, String period, double magnitude, int magnitudeFill,
                              java.util.function.Consumer<LinearLayout> content) {
        Dialog dialog = new Dialog(this, R.style.SkyHereTheme);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout screen = views.column();
        screen.setBackgroundColor(views.surfaceColor);
        screen.addView(detailHeader(title, R.string.sky_track_back, dialog::dismiss));

        ScrollView viewport = new ScrollView(this);
        viewport.setFillViewport(true);
        LinearLayout body = views.column();
        body.setPadding(views.dp(12), views.dp(4), views.dp(12), views.dp(12));
        LinearLayout panel = views.column();
        panel.setBackground(views.background(getColor(R.color.sky_card), 16));
        panel.setPadding(views.dp(14), views.dp(12), views.dp(14), views.dp(14));
        LinearLayout summary = views.row();
        summary.setGravity(Gravity.TOP);
        summary.addView(views.label(period, 14, views.muted), new LinearLayout.LayoutParams(0, -2, 1));
        if (Double.isFinite(magnitude)) {
            TextView badge = astronomyBadge("");
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
        SkyTheme.applySystemBars(window, this, views.surfaceColor);
        startPhoneOrientation();
    }

    private void addSkyTrack(LinearLayout panel, List<? extends SkyTrajectory.Point> points, SkyTrajectory.Point start,
                             SkyTrajectory.Point peak, SkyTrajectory.Point end, SkyTrajectory.Point now,
                             SkyTrajectory.Point brightest) {
        String[] cardinal = {compassDirection(0), compassDirection(90), compassDirection(180), compassDirection(270)};
        skyTrackCoordinates = views.label("", 15, getColor(R.color.sky_observing));
        skyTrackCoordinates.setPadding(0, views.dp(7), 0, views.dp(3));
        skyTrackCoordinates.setVisibility(View.GONE);
        panel.addView(skyTrackCoordinates);
        aimedSkyTrack = new SkyTrack(this, points, start, peak, end, now, brightest, cardinal);
        int chartSize = Math.min(380, getResources().getConfiguration().screenWidthDp - 52);
        panel.addView(aimedSkyTrack, new LinearLayout.LayoutParams(-1, views.dp(chartSize)));
        LinearLayout direction = views.column();
        direction.setBackground(views.background(getColor(R.color.sky_subtle), 12));
        direction.setPadding(views.dp(11), views.dp(9), views.dp(11), views.dp(9));
        direction.addView(views.label(getString(R.string.phone_direction), 14, views.ink));
        skyTrackDirection = views.label(getString(R.string.phone_direction_waiting), 17, views.ink);
        views.addSpaced(direction, skyTrackDirection, 5);
        views.addSpaced(direction, views.label(getString(R.string.phone_direction_hint), 12, views.muted), 5);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = views.dp(9);
        panel.addView(direction, size);
        addSkyTrackLegendItem(panel, getString(R.string.sky_track_now), SkyTrack.NOW_COLOR, 10);
    }

    private void addSkyTrackPoint(LinearLayout panel, int title, SkyTrajectory.Point point, int color,
                                  ZoneId zone, LocalDate date) {
        if (point == null) return;
        String time = skyTrackTime(point, zone, date);
        String position = point.elevation >= 0 ? issBearing(point.azimuth) + " · " + Math.round(point.elevation) + "°"
                : getString(R.string.planet_below_horizon);
        String details = getString(title) + " · " + time + " · " + position;
        addSkyTrackLegendItem(panel, details, color, 8);
    }

    private void addSkyTrackLegendItem(LinearLayout panel, String details, int color, int diameter) {
        LinearLayout item = views.row();
        View marker = new View(this);
        marker.setBackground(views.background(color, diameter / 2));
        LinearLayout.LayoutParams markerSize = new LinearLayout.LayoutParams(views.dp(diameter), views.dp(diameter));
        markerSize.setMarginEnd(views.dp(8));
        item.addView(marker, markerSize);
        item.addView(views.label(details, 14, views.ink), new LinearLayout.LayoutParams(0, -2, 1));
        views.addSpaced(panel, item, 6);
    }

    private String skyTrackTime(SkyTrajectory.Point point, ZoneId zone, LocalDate date) {
        ZonedDateTime localTime = Instant.ofEpochSecond(point.time).atZone(zone);
        String time = localTime.format(formats.clockFormat());
        return localTime.toLocalDate().equals(date) ? time : localTime.format(formats.shortDate(false)) + " " + time;
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

    private String issPassUrl(IssPasses.Pass pass, ZoneId zone, double latitude, double longitude,
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
                .appendQueryParameter("cul", formats.italian() ? "it" : "en").build().toString();
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
            if (transits.isEmpty()) content.addView(views.label(getString(R.string.no_transits), 14, views.muted));
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
        Button toggle = views.accordionButton(getString(title));
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
                getString(R.string.astronomical_seasons)));
        LinearLayout content = views.column();
        content.setPadding(0, views.dp(6), 0, 0);
        seasons.addView(content);
        LocalDate today = LocalDate.now(zone);
        DateTimeFormatter date = DateTimeFormatter.ofPattern("EEE d MMM uuuu", formats.appLocale());
        DateTimeFormatter time = formats.clockFormat();
        // UTC timestamps from the US Naval Observatory Earth Seasons API.
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                getAssets().open("astronomical-seasons-utc.txt"), java.nio.charset.StandardCharsets.UTF_8))) {
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
                heading.addView(views.label(getString(title), 15, views.ink), new LinearLayout.LayoutParams(0, -2, 1));
                TextView badge = nowBadge();
                badge.setVisibility(eventDate.equals(today) ? View.VISIBLE : View.GONE);
                heading.addView(badge);
                visibilityBadges.add(new VisibilityBadge(eventDate.atStartOfDay(zone).toEpochSecond(),
                        eventDate.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1, badge, null));
                item.addView(heading);
                item.addView(views.label(date.format(local) + " · " + time.format(local), 13, views.muted));
                long daylight = SkyPosition.daylightMinutes(eventDate, zone, latitude);
                item.addView(views.label(getString(R.string.season_day_night,
                        formats.durationText(daylight), formats.durationText(1440 - daylight)), 13, views.muted));
                content.addView(item);
            }
        } catch (java.io.IOException error) {
            android.util.Log.w("SkyHere", "Season calendar unavailable", error);
        }
        if (content.getChildCount() == 0) content.addView(views.label(getString(R.string.unavailable), 14, views.muted));
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.sky_card_outline));
        LinearLayout.LayoutParams dividerSize = new LinearLayout.LayoutParams(-1, views.dp(1));
        dividerSize.setMargins(0, views.dp(7), 0, views.dp(8));
        seasons.addView(divider, dividerSize);

        seasons.addView(views.label(place, 14, views.ink));
        seasons.addView(views.label(getString(R.string.season_coordinates,
                formats.number(latitude, "%.4f"), formats.number(longitude, "%.4f")), 13, views.muted));
        TextView rotation = views.label(getString(R.string.earth_rotation_speed,
                formats.number(SkyPosition.rotationSpeedKmh(latitude), "%,.0f")), 14, views.ink);
        rotation.setPadding(0, views.dp(4), 0, 0);
        seasons.addView(rotation);
        seasons.addView(views.label(getString(R.string.solar_declination,
                formats.number(SkyPosition.solarDeclinationDegrees(Instant.now()), "%.2f")), 13, views.muted));
        views.addSpaced(page, seasons, 9);
        refreshAstronomyVisibility();
    }

    private void appendTransits(LinearLayout list, List<TransitCatalog.Event> items, ZoneId zone, int start) {
        int end = Math.min(items.size(), start + ASTRONOMY_PAGE_SIZE);
        for (int i = start; i < end; i++) addTransit(list, items.get(i), zone);
        refreshAstronomyVisibility();
        if (end >= items.size()) return;
        Button more = views.moreButton();
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
                footer.addView(views.label(getString(R.string.no_eclipses_until, lastYear), 14, views.muted));
            }
            if (!hasMoreYears && shown >= items.size()) return;
            Button more = views.moreButton();
            more.setOnClickListener(v -> showNext());
            more.setContentDescription(lunar ? getString(R.string.more_lunar_eclipses) : getString(R.string.more_solar_eclipses));
            footer.addView(more);
        }

        private void fetch(int requestsLeft) {
            footer.addView(views.loadingStatus(getString(R.string.loading_eclipses)));
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
                    footer.addView(views.label(getString(R.string.eclipse_data_unavailable), 14, views.muted));
                    footer.addView(views.button(getString(R.string.retry), () -> fill(requestsLeft)));
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
        entry.setBackground(views.background(getColor(R.color.sky_subtle), 12));
        String eventUrl = event.detailsUrl(latitude, longitude);
        ArrayList<String> localDetails = new ArrayList<>();
        LinearLayout heading = views.row();
        EclipseIcon icon = new EclipseIcon(this, event);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(views.dp(36), views.dp(36));
        iconSize.rightMargin = views.dp(9);
        heading.addView(icon, iconSize);
        LinearLayout title = views.column();
        title.addView(views.label(EclipseCatalog.typeLabel(this, event.type), 15, views.ink));
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
        DateTimeFormatter date = formats.shortDate(true);
        TextView when = views.label(start.format(date), 13, views.muted);
        localDetails.add(EclipseCatalog.typeLabel(this, event.type) + " · " + when.getText());
        when.setPadding(0, views.dp(3), 0, 0);
        title.addView(when);
        String range = start.format(formats.clockFormat()) + " – " + end.format(formats.clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += getString(R.string.next_day_suffix);
        TextView visible = views.label(getString(R.string.visible_range, range), 14, views.ink);
        localDetails.add(visible.getText().toString());
        visible.setPadding(0, views.dp(7), 0, 0);
        entry.addView(visible);
        Duration duration = Duration.between(event.start, event.end);
        long minutes = Math.max(1, Math.round(duration.getSeconds() / 60.0));
        String durationText = formats.durationText(minutes);
        String durationLabel = getString(event.horizonLimited ? R.string.estimated_duration : R.string.duration_value, durationText);
        durationLabel += " · " + getString(event.estimatedPeak ? R.string.best_visible : R.string.peak_time,
                event.maximum.atZone(zone).format(formats.clockFormat()));
        TextView details = views.label(durationLabel, 13, views.muted);
        localDetails.add(durationLabel);
        details.setPadding(0, views.dp(3), 0, 0);
        entry.addView(details);
        String centralDuration = EclipseCatalog.centralPhaseDuration(this, event);
        if (centralDuration != null) {
            TextView centralLabel = views.label(centralDuration, 13, views.accent);
            localDetails.add(centralDuration);
            centralLabel.setPadding(0, views.dp(3), 0, 0);
            entry.addView(centralLabel);
        }
        if (Double.isFinite(event.obscuration)) {
            int disk = event.lunar ? (event.penumbral ? R.string.moon_penumbra : R.string.moon_shadow) : R.string.sun_covered;
            String coverageText = getString(disk, event.formattedObscuration(formats.appLocale()));
            if (event.estimatedPeak) coverageText = getString(R.string.approximate_value, coverageText);
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
            startActivity(visibilityMapIntent(event.catalogDate(), url, localDetails)
                    .putExtra("visibilityKind", event.lunar ? "lunar" : "solar")
                    .putExtra("visibilitySamples", event.lunarVisibilitySamples().toString()));
        } catch (org.json.JSONException error) {
            openAstronomyPage(url);
        }
    }

    private android.content.Intent visibilityMapIntent(LocalDate date, String url, ArrayList<String> localDetails) {
        return new android.content.Intent(this, RadarActivity.class)
                .putExtra("layer", RadarActivity.Layer.ECLIPSE.name())
                .putExtra("place", place).putExtra("lat", latitude).putExtra("lon", longitude)
                .putExtra("timeZone", astronomyEventZone().getId())
                .putExtra("clock24", formats.clock24)
                .putExtra("eventDate", date.toString()).putExtra("detailsUrl", url)
                .putStringArrayListExtra("localDetails", localDetails);
    }

    private void addTransit(LinearLayout parent, TransitCatalog.Event event, ZoneId zone) {
        LinearLayout entry = views.column();
        entry.setPadding(views.dp(11), views.dp(10), views.dp(11), views.dp(10));
        entry.setBackground(views.background(getColor(R.color.sky_subtle), 12));
        String eventUrl = event.detailsUrl(latitude, longitude);
        String transitName = getString(R.string.transit_of,
                getString(event.transit.planet.equals("Mercury") ? R.string.mercury : R.string.venus));
        TransitCatalog.DiskPosition position = event.transit.diskPosition(event.visibleStart, event.visibleEnd);

        LinearLayout header = views.row();
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
        TextView when = views.label(date.format(formats.shortDate(true)), 13, views.muted);
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
        String range = start.format(formats.clockFormat()) + " – " + end.format(formats.clockFormat());
        if (!start.toLocalDate().equals(end.toLocalDate())) range += getString(R.string.next_day_suffix);
        TextView visible = views.label(getString(R.string.estimated_visible_range, range), 14, views.ink);
        visible.setPadding(0, views.dp(7), 0, 0);
        entry.addView(visible);
        long minutes = Math.max(1, Math.round(Duration.between(event.visibleStart, event.visibleEnd).getSeconds() / 60.0));
        String duration = formats.durationText(minutes);
        TextView details = views.label(getString(R.string.estimated_duration, duration), 13, views.muted);
        details.setPadding(0, views.dp(3), 0, 0);
        entry.addView(details);
        ArrayList<String> localDetails = new ArrayList<>();
        localDetails.add(transitName + " · " + when.getText());
        localDetails.add(visible.getText().toString());
        localDetails.add(details.getText().toString());
        views.cardAction(entry, () -> {
            try {
                startActivity(visibilityMapIntent(event.catalogDate(), eventUrl, localDetails)
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
                : formats.durationText(minutes);
        return getString(R.string.daylight_duration, duration);
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
        String title = getString(R.string.next_days, upcomingDays);
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
                if (isDestroyed() || !table.isAttachedToWindow()) return;
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
            addEventTableRow(table, getString(R.string.date), views.label(getString(R.string.phase), 12, views.muted), views.muted,
                    getString(R.string.moonrise_column), getString(R.string.moonset_column));
        } else {
            addEventTableRow(table, getString(R.string.date), null, views.muted, getString(R.string.sunrise),
                    getString(R.string.sunset), getString(R.string.daylight_column), getString(R.string.max_elevation));
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
        DateTimeFormatter dateFormat = formats.shortDate(false);
        for (int i = from; i < end; i++) {
            LocalDate day = LocalDate.parse(at(daily, "time", i));
            String date = day.format(dateFormat);
            if (!moon) {
                long seconds = daylightSeconds(daily, i);
                String duration = seconds <= 0 ? "-" : String.format(formats.appLocale(), "%d:%02d:%02d",
                        seconds / 3600, seconds / 60 % 60, seconds % 60);
                boolean polar = isPolarDaylight(seconds);
                LinearLayout line = addEventTableRow(table, date, null, views.ink, polar ? "—" : formats.eventClock(at(daily, rise, i)),
                        polar ? "—" : formats.eventClock(at(daily, set, i)),
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
                MoonPhaseView glyph = new MoonPhaseView(this, phase, latitude < 0);
                glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                phaseCell.addView(glyph, new LinearLayout.LayoutParams(views.dp(21), views.dp(21)));
                String amount = percent(100 * MoonPhaseView.illumination(phase));
                TextView illumination = views.label(amount, 11, views.muted);
                if (!views.largeText()) illumination.setPadding(views.dp(4), 0, 0, 0);
                illumination.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                phaseCell.addView(illumination);
                String phaseName = getString(MOON_PHASE_NAMES[moonPhaseIndex(phase, day, zone)]);
                phaseCell.setContentDescription(phaseName + ", " + getString(R.string.moon_illuminated, amount));
                phaseIcon = phaseCell;
            } else {
                phaseIcon = views.label("—", 13, views.muted);
                ((TextView) phaseIcon).setGravity(Gravity.CENTER);
                phaseIcon.setContentDescription(getString(R.string.phase_unavailable));
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
            DateTimeFormatter timeFormat = formats.secondsClockFormat();
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
            cell.setTextColor(getColor(R.color.sky_solar_minimum));
            cell.setTypeface(cell.getTypeface(), Typeface.BOLD);
        } else if (value > previous && value > next) {
            cell.setTextColor(getColor(R.color.sky_solar_maximum));
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
        int available = views.dp(getResources().getConfiguration().screenWidthDp)
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
