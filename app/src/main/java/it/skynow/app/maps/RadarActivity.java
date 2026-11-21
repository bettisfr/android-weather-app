package it.skynow.app.maps;

import it.skynow.app.R;
import it.skynow.app.SkyTheme;
import it.skynow.app.astronomy.AuroraForecast;
import it.skynow.app.seismic.EarthquakeFeed;
import it.skynow.app.weather.AirQualityBand;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

public final class RadarActivity extends Activity {
    private static final String ASSET_ORIGIN = "https://appassets.androidplatform.net";
    public enum Layer {
        RADAR("index", R.string.radar_title), AURORA("aurora", R.string.aurora_map_title),
        AIR_QUALITY("aurora", R.string.air_quality_map_title), SATELLITE("satellite", R.string.satellite_title),
        EARTHQUAKES("earthquakes", R.string.earthquake_map_title),
        ECLIPSE("eclipse", R.string.eclipse_map_title);

        final String page;
        final int title;

        Layer(String page, int title) {
            this.page = ASSET_ORIGIN + "/radar/" + page + ".html";
            this.title = title;
        }
    }
    private static final String METADATA = "https://radar-api.protezionecivile.it/findLastProductByType?type=SRI";
    private static final String USER_AGENT_PROJECT = " (+https://github.com/bettisfr/android-weather-app)";
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private WebView web;
    private Layer layer;
    private String selectedEarthquake;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(SkyTheme.withPreference(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String requested = getIntent().getStringExtra("layer");
        layer = requested == null ? Layer.RADAR : Layer.valueOf(requested);
        if (layer == Layer.EARTHQUAKES) selectedEarthquake = getIntent().getStringExtra("selectedEarthquake");
        String page = layer.page;
        int surface = getColor(R.color.sky_background);
        int ink = getColor(R.color.sky_text);
        SkyTheme.applySystemBars(this);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(surface);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextColor(ink);
        back.setTextSize(28);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.radar_back));
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(this);
        int titleResource = layer == Layer.ECLIPSE && getIntent().getBooleanExtra("lunarEclipse", false)
                ? R.string.lunar_eclipse_map_title : layer.title;
        title.setText(getString(titleResource, getIntent().getStringExtra("place")));
        title.setTextColor(ink);
        title.setTextSize(19);
        title.setPadding(0, dp(7), dp(12), dp(7));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        screen.addView(header);

        web = new WebView(this);
        web.setBackgroundColor(surface);
        web.getSettings().setJavaScriptEnabled(true);
        if (layer == Layer.ECLIPSE) web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString() + " " + userAgent());
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!"https".equals(url.getScheme()) || !"appassets.androidplatform.net".equals(url.getHost())) return null;
                String path = url.getPath();
                String type;
                if ("/radar/index.html".equals(path) || "/radar/aurora.html".equals(path) || "/radar/satellite.html".equals(path)
                        || "/radar/earthquakes.html".equals(path) || "/radar/eclipse.html".equals(path)) type = "text/html";
                else if ("/radar/leaflet.css".equals(path)) type = "text/css";
                else if ("/radar/leaflet.js".equals(path) || "/radar/map.js".equals(path) || "/radar/radar.js".equals(path)
                        || "/radar/aurora.js".equals(path) || "/radar/satellite.js".equals(path)
                        || "/radar/earthquakes.js".equals(path) || "/radar/eclipse.js".equals(path)
                        || "/radar/eclipse-path-2027.js".equals(path) || "/radar/eclipse-geometry.js".equals(path)
                        || "/radar/eclipse-elements.js".equals(path)) type = "application/javascript";
                else return null;
                try {
                    return new WebResourceResponse(type, "UTF-8", getAssets().open(path.substring(1)));
                } catch (IOException error) {
                    return null;
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (page.equals(uri.toString())) return false;
                if (request.isForMainFrame() && "https".equals(uri.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (android.content.ActivityNotFoundException ignored) { }
                }
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!page.equals(url) || isFinishing()) return;
                view.evaluateJavascript("initialize(" + parameters() + ")", null);
                if (layer == Layer.AIR_QUALITY || layer == Layer.ECLIPSE || selectedEarthquake != null) return;
                network.execute(() -> {
                    switch (layer) {
                        case AURORA: loadAurora(); break;
                        case SATELLITE: loadSatellite(); break;
                        case EARTHQUAKES: loadEarthquakes(); break;
                        default: loadLatest();
                    }
                });
            }
        });
        screen.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(screen);
        web.loadUrl(page);
    }

    private JSONObject parameters() {
        Intent options = getIntent();
        JSONObject result = new JSONObject();
        try {
            result.put("lat", options.getDoubleExtra("lat", 42));
            result.put("lon", options.getDoubleExtra("lon", 12));
            result.put("timeZone", options.getStringExtra("timeZone"));
            result.put("clock24", options.getBooleanExtra("clock24", true));
            result.put("dark", SkyTheme.isDark(this));
            result.put("language", getString(R.string.app_language_tag));
            result.put("updated", getString(R.string.map_updated));
            if (layer == Layer.ECLIPSE) {
                boolean lunar = options.getBooleanExtra("lunarEclipse", false);
                result.put("date", options.getStringExtra("eclipseDate"));
                result.put("lunar", lunar);
                result.put("contacts", new org.json.JSONArray(options.getStringExtra("lunarSamples")));
                result.put("place", options.getStringExtra("place"));
                result.put("detailsUrl", options.getStringExtra("detailsUrl"));
                result.put("localDetails", new org.json.JSONArray(options.getStringArrayListExtra("localDetails")));
                result.put("mapLabel", getString(R.string.eclipse_map));
                result.put("totality", getString(R.string.eclipse_totality_band));
                result.put("annularity", getString(R.string.eclipse_annularity_band));
                result.put("mixed", getString(R.string.eclipse_mixed_band));
                result.put("partial", getString(lunar ? R.string.eclipse_partial_lunar_band : R.string.eclipse_partial_solar_band));
                result.put("whole", getString(R.string.eclipse_whole_lunar_band));
                result.put("loading", getString(R.string.eclipse_map_loading));
                result.put("approximate", getString(R.string.eclipse_map_approximate));
                result.put("unavailable", getString(R.string.eclipse_map_unavailable));
                result.put("openDetails", getString(R.string.eclipse_open_timeanddate));
                return result;
            }
            if (layer == Layer.EARTHQUAKES) {
                int radius = options.getIntExtra("radiusKm", EarthquakeFeed.DEFAULT_RADIUS);
                int minimum = options.getIntExtra("minimumMagnitude", 0);
                result.put("radiusKm", radius);
                String scope = getString(R.string.earthquake_scope, radius);
                if (minimum > 0) scope += " · M ≥ " + minimum;
                result.put("scope", scope);
                result.put("mapLabel", getString(R.string.earthquake_map));
                result.put("loading", getString(R.string.earthquake_loading));
                result.put("empty", getString(minimum > 0 ? R.string.earthquake_filter_empty : R.string.earthquake_empty));
                result.put("depthLabel", getString(R.string.earthquake_depth));
                result.put("magnitudeLabel", getString(R.string.earthquake_magnitude));
                result.put("magnitudeBands", EarthquakeFeed.magnitudeBands());
                result.put("source", EarthquakeFeed.SOURCE);
                if (selectedEarthquake != null) {
                    result.put("selectedEvent", new JSONObject(selectedEarthquake));
                    result.put("openEvent", getString(R.string.earthquake_open_event));
                }
                return result;
            }
            if (layer == Layer.AIR_QUALITY) {
                result.put("airQuality", true);
                result.put("source", "https://airindex.eea.europa.eu/AQI/");
                result.put("time", java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                        .minus(Duration.ofHours(3)).toEpochMilli());
                result.put("coverage", getString(R.string.air_quality_map_coverage));
                result.put("unavailable", getString(R.string.air_quality_unavailable));
                result.put("mapLabel", getString(R.string.air_quality_map));
                result.put("forecast", getString(R.string.air_quality_grid));
                result.put("intensity", getString(R.string.air_quality));
                org.json.JSONArray bands = new org.json.JSONArray();
                for (AirQualityBand band : AirQualityBand.values()) {
                    bands.put(new JSONObject()
                            .put("label", getString(band.label))
                            .put("legend", getString(band == AirQualityBand.EXTREME ? R.string.aqi_extreme_short : band.label))
                            .put("color", String.format(java.util.Locale.ROOT, "#%06x", band.color & 0x00ffffff)));
                }
                result.put("bands", bands);
                result.put("loading", getString(R.string.air_quality_loading));
                return result;
            }
            if (layer == Layer.SATELLITE) {
                result.put("updated", getString(R.string.satellite_downloaded));
                result.put("regionalSource", SatelliteImagery.EUMETSAT);
                result.put("geocolourSource", SatelliteImagery.GEOCOLOUR_SOURCE);
                result.put("globalSource", SatelliteImagery.SOURCE);
                result.put("observed", getString(R.string.satellite_observed));
                result.put("auto", getString(R.string.satellite_auto));
                result.put("modeLabel", getString(R.string.satellite_mode));
                result.put("coverage", getString(R.string.satellite_coverage));
                result.put("timelineLabel", getString(R.string.satellite_timeline));
                result.put("play", getString(R.string.radar_play));
                result.put("pause", getString(R.string.radar_pause));
                result.put("loading", getString(R.string.satellite_loading));
                result.put("unavailable", getString(R.string.satellite_unavailable));
                return result;
            }
            if (layer == Layer.AURORA) {
                result.put("source", AuroraForecast.MAP);
                result.put("mapLabel", getString(R.string.aurora_map));
                result.put("forecast", getString(R.string.aurora_map_forecast));
                result.put("intensity", getString(R.string.aurora_map_intensity));
                result.put("low", getString(R.string.aurora_map_low));
                result.put("high", getString(R.string.aurora_map_high));
                result.put("loading", getString(R.string.aurora_loading));
                return result;
            }
            result.put("observed", getString(R.string.radar_observed));
            result.put("latest", getString(R.string.radar_latest));
            result.put("loading", getString(R.string.radar_loading));
            result.put("noTiles", getString(R.string.radar_no_tiles));
            result.put("mapLabel", getString(R.string.radar_map));
            result.put("timelineLabel", getString(R.string.radar_timeline));
            result.put("play", getString(R.string.radar_play));
            result.put("pause", getString(R.string.radar_pause));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return result;
    }

    private void loadSatellite() {
        try {
            JSONObject products = null;
            try {
                products = SatelliteImagery.regionalFrames(getIntent().getDoubleExtra("lat", 42),
                        getIntent().getDoubleExtra("lon", 12));
            } catch (Exception unavailable) {
                android.util.Log.w("SkyHere", "Meteosat imagery unavailable", unavailable);
            }
            if (products == null) {
                org.json.JSONArray frames = SatelliteImagery.recentFrames();
                if (frames.length() == 0) {
                    showMapError(R.string.satellite_unavailable);
                    return;
                }
                products = new JSONObject().put("infrared", new JSONObject().put("frames", frames));
            }
            String payload = products.toString();
            runOnUiThread(() -> evaluate("setProducts(" + payload + ")"));
        } catch (Exception error) {
            android.util.Log.w("SkyHere", "NOAA satellite imagery unavailable", error);
            showMapError(R.string.satellite_unavailable);
        }
    }

    private void showMapError(int message) {
        runOnUiThread(() -> evaluate("status(" + JSONObject.quote(getString(message)) + ")"));
    }

    private void loadEarthquakes() {
        Intent options = getIntent();
        try {
            org.json.JSONArray events = new org.json.JSONArray();
            int minimum = options.getIntExtra("minimumMagnitude", 0);
            for (EarthquakeFeed.Event event : EarthquakeFeed.recent(options.getDoubleExtra("lat", 42),
                    options.getDoubleExtra("lon", 12), options.getIntExtra("radiusKm", EarthquakeFeed.DEFAULT_RADIUS))) {
                if (event.matchesMagnitude(minimum)) events.put(event.mapData());
            }
            runOnUiThread(() -> evaluate("showEarthquakes(" + events + ")"));
        } catch (Exception error) {
            android.util.Log.w("SkyHere", "INGV map unavailable", error);
            showMapError(R.string.earthquake_unavailable);
        }
    }

    private void loadAurora() {
        try {
            AuroraForecast forecast = AuroraForecast.load();
            if (java.time.Instant.now().isAfter(forecast.forecastTime.plusSeconds(1800))) {
                runOnUiThread(() -> evaluate("status(" + JSONObject.quote(getString(R.string.aurora_unavailable)) + ")"));
                return;
            }
            String grid = forecast.mapGrid();
            runOnUiThread(() -> evaluate("setAurora(" + JSONObject.quote(grid) + ","
                    + forecast.forecastTime.toEpochMilli() + ")"));
        } catch (Exception error) {
            android.util.Log.w("SkyHere", "NOAA aurora map unavailable", error);
            runOnUiThread(() -> evaluate("status(" + JSONObject.quote(getString(R.string.aurora_unavailable)) + ")"));
        }
    }

    private void loadLatest() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(METADATA).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Origin", "https://radar.protezionecivile.it");
            connection.setRequestProperty("User-Agent", userAgent());
            JSONObject product;
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                product = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
                        .getJSONArray("lastProducts").getJSONObject(0);
            } finally {
                connection.disconnect();
            }
            long time = product.getLong("time");
            long step = Duration.parse(product.getString("period")).toMillis();
            runOnUiThread(() -> evaluate("setRadarTime(" + time + "," + step + ")"));
        } catch (Exception error) {
            android.util.Log.w("SkyHere", "Radar-DPC metadata unavailable", error);
            runOnUiThread(() -> evaluate("status(" + JSONObject.quote(getString(R.string.radar_unavailable)) + ")"));
        }
    }

    private void evaluate(String code) {
        if (!isFinishing() && !isDestroyed() && web != null) web.evaluateJavascript(code, null);
    }

    private String userAgent() {
        return "SkyHere/" + getString(R.string.sky_version_name) + USER_AGENT_PROJECT;
    }

    @Override protected void onPause() {
        evaluate("typeof setPlaying === 'function' && setPlaying(false)");
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        if (web != null) {
            ((LinearLayout) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
