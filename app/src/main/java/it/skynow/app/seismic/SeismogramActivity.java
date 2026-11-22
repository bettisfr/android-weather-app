package it.skynow.app.seismic;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;

import it.skynow.app.R;
import it.skynow.app.SkyTheme;

public final class SeismogramActivity extends Activity {
    private static final String SOURCE = "https://www.iesn.it/drum-sismogrammi-in-diretta/";
    private static final Station[] STATIONS = {
        new Station("Perugia", 43.112, 12.389,
                "https://www.osservatoriometeoesismicoperugia.it/elf/drum_UPRP_EHN.gif"),
        new Station("Valfabbrica", 43.158, 12.601,
                "https://www.sara.pg.it/drums/drum_UVLF_EHZ.gif")
    };

    private WebView chart;
    private Spinner stations;
    private int loadedStation = -1;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            chart.evaluateJavascript("refresh()", null);
            chart.postDelayed(this, 60_000);
        }
    };

    public static Intent intent(Context context, double latitude, double longitude) {
        return new Intent(context, SeismogramActivity.class)
                .putExtra("latitude", latitude)
                .putExtra("longitude", longitude);
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

        stations = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{STATIONS[0].name, STATIONS[1].name});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stations.setAdapter(adapter);
        stations.setContentDescription(getString(R.string.seismograms));
        header.addView(stations, new LinearLayout.LayoutParams(0, -1, 1));
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

        stations.setSelection(state == null ? closestStation() : state.getInt("station"));
        stations.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loadedStation != position) loadStation(position);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private int closestStation() {
        double latitude = getIntent().getDoubleExtra("latitude", Double.NaN);
        double longitude = getIntent().getDoubleExtra("longitude", Double.NaN);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) return 0;
        float[] distance = new float[1];
        float closest = Float.POSITIVE_INFINITY;
        int nearest = 0;
        for (int index = 0; index < STATIONS.length; index++) {
            Station station = STATIONS[index];
            Location.distanceBetween(latitude, longitude, station.latitude, station.longitude, distance);
            if (distance[0] < closest) {
                closest = distance[0];
                nearest = index;
            }
        }
        return nearest;
    }

    private void loadStation(int position) {
        loadedStation = position;
        Station station = STATIONS[position];
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
        if (loadedStation >= 0) chart.evaluateJavascript("refresh()", null);
        chart.postDelayed(refresh, 60_000);
    }

    @Override protected void onStop() {
        chart.removeCallbacks(refresh);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("station", stations.getSelectedItemPosition());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        ((LinearLayout) chart.getParent()).removeView(chart);
        chart.destroy();
        super.onDestroy();
    }

    private static final class Station {
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
    }
}
