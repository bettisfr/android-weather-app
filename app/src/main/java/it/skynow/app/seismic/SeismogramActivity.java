package it.skynow.app.seismic;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import it.skynow.app.R;
import it.skynow.app.SkyTheme;

public final class SeismogramActivity extends Activity {
    private static final String SOURCE = "https://www.iesn.it/drum-sismogrammi-in-diretta/";
    private static final Station[] STATIONS = {
        new Station("Perugia", 43.112, 12.389,
                "https://www.osservatoriometeoesismicoperugia.it/elf/drum_UPRP_EHN.gif"),
        new Station("Valfabbrica", 43.158, 12.601,
                "https://www.sara.pg.it/drums/drum_UVLF_EHZ.gif"),
        new Station("Casalina", 42.993, 12.407,
                "https://www.osservatoriometeoesismicoperugia.it/elf/drum_UCAS_LHN.gif"),
        new Station("Olmeto", 42.983, 12.307,
                "https://www.osservatoriometeoesismicoperugia.it/elf/drum_UOLM_EHZ.gif"),
        new Station("Gubbio", 43.353, 12.573,
                "https://www.osservatoriometeoesismicoperugia.it/elf/drum_MCRS_EHZ.gif"),
        new Station("Colfiorito", 43.027, 12.887,
                "https://www.sara.pg.it/drums/drum_UORV_EHE.gif"),
        new Station("Sellano", 42.889, 12.926,
                "https://www.iesn.it/drum/drum_USLL_EHN.gif"),
        new Station("Norcia", 42.793, 13.096,
                "https://www.osservatoriometeoesismicoperugia.it/elf/drum_UNRC_EHZ.gif")
    };

    private WebView chart;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            chart.evaluateJavascript("refresh()", null);
            chart.postDelayed(this, 60_000);
        }
    };

    public static Intent intent(Context context, Station station) {
        return new Intent(context, SeismogramActivity.class)
                .putExtra("station", Arrays.asList(STATIONS).indexOf(station));
    }

    public static List<Station> nearestStations(double latitude, double longitude) {
        List<Station> stations = new ArrayList<>(Arrays.asList(STATIONS));
        if (Double.isFinite(latitude) && Double.isFinite(longitude)) {
            stations.sort(Comparator.comparingDouble(station -> station.distanceKm(latitude, longitude)));
        }
        return stations.subList(0, 5);
    }

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(SkyTheme.withPreference(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SkyTheme.applySystemBars(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(getColor(R.color.sky_background));

        LinearLayout header = new LinearLayout(this);
        header.setBackgroundColor(getColor(R.color.sky_card));
        Button back = new Button(this, null, android.R.attr.borderlessButtonStyle);
        back.setText("‹");
        back.setTextSize(24);
        back.setMinWidth(0);
        back.setMinimumWidth(0);
        back.setContentDescription(getString(R.string.settings_back));
        back.setOnClickListener(view -> finish());
        header.addView(back);

        Station station = STATIONS[getIntent().getIntExtra("station", 0)];
        TextView stationName = new TextView(this);
        stationName.setText(station.name);
        stationName.setTextColor(getColor(R.color.sky_text));
        stationName.setTextSize(18);
        stationName.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(stationName, new LinearLayout.LayoutParams(0, -1, 1));
        page.addView(header);

        chart = new WebView(this);
        chart.setBackgroundColor(getColor(R.color.sky_background));
        chart.getSettings().setAllowFileAccess(false);
        chart.getSettings().setAllowContentAccess(false);
        chart.getSettings().setJavaScriptEnabled(true);
        chart.getSettings().setBuiltInZoomControls(true);
        chart.getSettings().setDisplayZoomControls(false);
        page.addView(chart, new LinearLayout.LayoutParams(-1, 0, 1));

        Button source = new Button(this, null, android.R.attr.borderlessButtonStyle);
        source.setText(R.string.seismogram_source);
        source.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE)));
            } catch (ActivityNotFoundException ignored) {
                android.widget.Toast.makeText(this, R.string.unavailable, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        page.addView(source);
        setContentView(page);

        loadStation(station);
    }

    private void loadStation(Station station) {
        String loading = android.text.TextUtils.htmlEncode(getString(R.string.seismogram_loading));
        String unavailable = org.json.JSONObject.quote(getString(R.string.seismogram_unavailable));
        String html = "<!doctype html><meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<style>"
                + "body{margin:8px;font:16px sans-serif;text-align:center}"
                + "img{width:100%;max-width:960px;height:auto}"
                + "#status{padding:8px;background:white;color:#26364b}"
                + "</style>"
                + "<p id='status'>" + loading + "</p>"
                + "<img id='chart' alt='" + station.name + "' hidden>"
                + "<script>"
                + "const chart = document.getElementById('chart');"
                + "const message = document.getElementById('status');"
                + "function refresh() {"
                + "  const next = new Image();"
                + "  next.onload = () => { chart.src = next.src; chart.hidden = false; message.hidden = true; };"
                + "  next.onerror = () => { message.textContent = " + unavailable + "; message.hidden = false; };"
                + "  next.src = '" + station.url + "?t=' + Date.now();"
                + "}"
                + "refresh();"
                + "</script>";
        chart.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    @Override protected void onStart() {
        super.onStart();
        chart.postDelayed(refresh, 60_000);
    }

    @Override protected void onStop() {
        chart.removeCallbacks(refresh);
        super.onStop();
    }

    @Override protected void onDestroy() {
        ((LinearLayout) chart.getParent()).removeView(chart);
        chart.destroy();
        super.onDestroy();
    }

    public static final class Station {
        final String name;
        final double latitude;
        final double longitude;
        final String url;

        Station(String name, double latitude, double longitude, String url) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
            this.url = url;
        }

        public double distanceKm(double latitude, double longitude) {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) return Double.NaN;
            float[] result = new float[1];
            Location.distanceBetween(latitude, longitude, this.latitude, this.longitude, result);
            return result[0] / 1000.0;
        }

        @Override public String toString() {
            return name;
        }
    }
}
