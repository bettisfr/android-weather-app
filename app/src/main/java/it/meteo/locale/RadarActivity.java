package it.meteo.locale;

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
    private static final String PAGE = ASSET_ORIGIN + "/radar/index.html";
    private static final String METADATA = "https://radar-api.protezionecivile.it/findLastProductByType?type=SRI";
    private static final String USER_AGENT = "SkyHere/0.1 (+https://github.com/bettisfr/android-weather-app)";
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private WebView web;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(SkyTheme.withPreference(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        title.setText(getString(R.string.radar_title, getIntent().getStringExtra("place")));
        title.setTextColor(ink);
        title.setTextSize(19);
        title.setPadding(0, dp(7), dp(12), dp(7));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        screen.addView(header);

        web = new WebView(this);
        web.setBackgroundColor(surface);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setUserAgentString(web.getSettings().getUserAgentString() + " " + USER_AGENT);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (!"https".equals(url.getScheme()) || !"appassets.androidplatform.net".equals(url.getHost())) return null;
                String path = url.getPath();
                String type;
                if ("/radar/index.html".equals(path)) type = "text/html";
                else if ("/radar/leaflet.css".equals(path)) type = "text/css";
                else if ("/radar/leaflet.js".equals(path) || "/radar/radar.js".equals(path)) type = "application/javascript";
                else return null;
                try {
                    return new WebResourceResponse(type, "UTF-8", getAssets().open(path.substring(1)));
                } catch (IOException error) {
                    return null;
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (PAGE.equals(uri.toString())) return false;
                if (request.isForMainFrame() && "https".equals(uri.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (android.content.ActivityNotFoundException ignored) { }
                }
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (!PAGE.equals(url) || isFinishing()) return;
                view.evaluateJavascript("initialize(" + parameters() + ")", null);
                network.execute(RadarActivity.this::loadLatest);
            }
        });
        screen.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(screen);
        web.loadUrl(PAGE);
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

    private void loadLatest() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(METADATA).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Origin", "https://radar.protezionecivile.it");
            connection.setRequestProperty("User-Agent", USER_AGENT);
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
