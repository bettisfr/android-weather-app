package it.skynow.app.dashboard;

import it.skynow.app.net.Background;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import it.skynow.app.R;
import it.skynow.app.maps.RadarActivity;
import it.skynow.app.seismic.EarthquakeFeed;
import it.skynow.app.seismic.SeismogramActivity;
import it.skynow.app.ui.Formats;
import it.skynow.app.ui.SignalColors;
import it.skynow.app.ui.SkyViews;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The Earthquakes dashboard tab: nearby INGV seismograms and recent events around the phone. */
public final class EarthquakeTab {
    private static final int[] MAGNITUDE_THRESHOLDS = {0, 2, 3, 4};
    private static final int PAGE_SIZE = 10;
    private static final ZoneId ITALY = ZoneId.of("Europe/Rome");

    /** State the tab reads from the activity. */
    public interface Host {
        /** Whether the forecast location is in Italy. */
        boolean inItaly();
        double deviceLatitude();
        double deviceLongitude();
        /** ISO country of the phone's location, or empty while unknown. */
        String deviceCountry();
        /** Locality of the phone's location, or null while unknown. */
        String devicePlace();
        Formats formats();
    }

    private final Activity activity;
    private final SkyViews views;
    private final Host host;
    private int radiusKm;
    private int minimumMagnitude;
    private TextView title;
    private LinearLayout eventsPanel;
    private LinearLayout seismogramPanel;
    private Spinner station;

    public EarthquakeTab(Activity activity, SkyViews views, Host host) {
        this.activity = activity;
        this.views = views;
        this.host = host;
    }

    /** INGV covers Italy, so the tab is offered only there and only while the phone is not known to be abroad. */
    public boolean isAvailable() {
        String country = host.deviceCountry();
        return host.inItaly() && (country.isEmpty() || "IT".equalsIgnoreCase(country));
    }

    /** Reads the alert radius and magnitude filter; returns whether either changed. */
    public boolean readPreferences(SharedPreferences preferences) {
        int radius = EarthquakeFeed.alertRadius(preferences);
        int magnitude = preferences.getInt("earthquakeMinimumMagnitude", 0);
        boolean changed = radius != radiusKm || magnitude != minimumMagnitude;
        radiusKm = radius;
        minimumMagnitude = magnitude;
        return changed;
    }

    /** Builds the tab at the end of {@code page} and starts loading events. */
    public void attach(LinearLayout page) {
        title = views.sectionTitle(activity.getString(R.string.seismic_activity));
        page.addView(title);
        eventsPanel = views.card();
        seismogramPanel = views.card();
        views.addSpaced(page, seismogramPanel, 12);
        page.addView(eventsPanel);
        refresh();
    }

    /** Forgets the views of a page that is being replaced. */
    public void detach() {
        title = null;
        eventsPanel = null;
        seismogramPanel = null;
        station = null;
    }

    /** Re-renders after the phone's location or country changes; does nothing while detached. */
    public void refresh() {
        LinearLayout panel = eventsPanel;
        if (panel == null) return;
        boolean show = isAvailable();
        renderSeismograms(show);
        title.setVisibility(show ? View.VISIBLE : View.GONE);
        panel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        panel.removeAllViews();
        LinearLayout heading = views.row();
        heading.setGravity(Gravity.TOP);
        heading.setBaselineAligned(false);
        heading.addView(panelTitle(R.string.earthquakes), new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(heading);
        double lat = host.deviceLatitude();
        double lon = host.deviceLongitude();
        if (!"IT".equalsIgnoreCase(host.deviceCountry()) || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            panel.addView(views.label(activity.getString(R.string.earthquake_location_required), 13, views.muted));
            return;
        }
        int radius = radiusKm;
        int magnitude = minimumMagnitude;
        String place = host.devicePlace();
        Button map = views.mapButton(R.string.aurora_map_short, () -> activity.startActivity(
                new Intent(activity, RadarActivity.class)
                        .putExtra("layer", RadarActivity.Layer.EARTHQUAKES.name())
                        .putExtra("place", place == null ? activity.getString(R.string.current_location) : place)
                        .putExtra("lat", lat).putExtra("lon", lon).putExtra("radiusKm", radius)
                        .putExtra("minimumMagnitude", magnitude)
                        .putExtra("timeZone", ITALY.getId()).putExtra("clock24", host.formats().clock24)));
        map.setContentDescription(activity.getString(R.string.earthquake_map));
        heading.addView(map);
        String magnitudeFilter = magnitude == 0 ? activity.getString(R.string.earthquake_all_magnitudes)
                : activity.getString(R.string.earthquake_minimum_magnitude_value, magnitude);
        String scope = activity.getString(R.string.earthquake_scope, radius) + "\n" + magnitudeFilter;
        views.addSpaced(panel, views.label(scope, 13, views.muted), 8);
        TextView status = views.label(activity.getString(R.string.earthquake_loading), 13, views.muted);
        panel.addView(status);
        CompletableFuture.supplyAsync(() -> {
            try {
                return EarthquakeFeed.recent(lat, lon, radius);
            } catch (Exception error) {
                android.util.Log.w("SkyHere", "INGV events unavailable", error);
                return null;
            }
        }, Background.IO).thenAccept(events -> activity.runOnUiThread(() -> {
            if (activity.isDestroyed() || panel != eventsPanel || status.getParent() != panel) return;
            if (events == null || events.isEmpty()) {
                status.setText(events == null ? R.string.earthquake_unavailable : R.string.earthquake_empty);
                return;
            }
            List<EarthquakeFeed.Event> visible = new ArrayList<>();
            for (EarthquakeFeed.Event event : events) {
                if (event.matchesMagnitude(magnitude)) visible.add(event);
            }
            if (visible.isEmpty()) {
                status.setText(R.string.earthquake_filter_empty);
                return;
            }
            panel.removeView(status);
            LinearLayout rows = views.column();
            panel.addView(rows);
            Button more = views.moreButton();
            panel.addView(more);
            more.setOnClickListener(v -> appendEvents(rows, more, visible, lat, lon));
            appendEvents(rows, more, visible, lat, lon);
        }));
    }

    private TextView panelTitle(int text) {
        TextView view = views.label(activity.getString(text), 16, views.ink);
        Drawable icon = activity.getDrawable(R.drawable.ic_seismic_wave).mutate();
        icon.setTint(views.ink);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        view.setCompoundDrawablePadding(views.dp(6));
        return view;
    }

    private void renderSeismograms(boolean show) {
        seismogramPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        Object selected = station == null ? null : station.getSelectedItem();
        double latitude = host.deviceLatitude();
        double longitude = host.deviceLongitude();
        List<SeismogramActivity.Station> nearest = SeismogramActivity.nearestStations(latitude, longitude);
        seismogramPanel.removeAllViews();
        seismogramPanel.addView(panelTitle(R.string.seismograms));
        LinearLayout controls = views.row();
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, views.dp(6), 0, 0);
        Spinner stations = new Spinner(activity, Spinner.MODE_DROPDOWN);
        station = stations;
        ArrayAdapter<SeismogramActivity.Station> adapter =
                new ArrayAdapter<SeismogramActivity.Station>(activity, android.R.layout.simple_spinner_item, nearest) {
                    private View showDistance(View view, int position) {
                        SeismogramActivity.Station item = getItem(position);
                        double distance = item.distanceKm(latitude, longitude);
                        if (Double.isFinite(distance)) {
                            ((TextView) view).setText(String.format(host.formats().appLocale(), "%s · %.1f km", item, distance));
                        }
                        return view;
                    }

                    @Override public View getView(int position, View recycled, ViewGroup parent) {
                        return showDistance(super.getView(position, recycled, parent), position);
                    }

                    @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
                        return showDistance(super.getDropDownView(position, recycled, parent), position);
                    }
                };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stations.setAdapter(adapter);
        stations.setSelection(Math.max(0, nearest.indexOf(selected)));
        stations.setContentDescription(activity.getString(R.string.seismogram_station));
        stations.setMinimumHeight(views.dp(44));
        controls.addView(stations, new LinearLayout.LayoutParams(0, -2, 1));

        Button open = views.iconActionButton(R.string.seismogram_open, R.drawable.ic_seismic_wave,
                () -> activity.startActivity(SeismogramActivity.intent(activity,
                        (SeismogramActivity.Station) stations.getSelectedItem())));
        LinearLayout.LayoutParams buttonSize = new LinearLayout.LayoutParams(-2, -2);
        buttonSize.setMarginStart(views.dp(8));
        controls.addView(open, buttonSize);
        seismogramPanel.addView(controls);
    }

    private void appendEvents(LinearLayout rows, Button more, List<EarthquakeFeed.Event> events, double lat, double lon) {
        Formats formats = host.formats();
        int start = rows.getChildCount();
        int end = Math.min(start + PAGE_SIZE, events.size());
        DateTimeFormatter date = DateTimeFormatter.ofPattern("EEE d MMM, " + (formats.clock24 ? "HH:mm" : "h:mm a"),
                formats.appLocale()).withZone(ITALY);
        float[] distance = new float[1];
        for (int i = start; i < end; i++) {
            EarthquakeFeed.Event event = events.get(i);
            Location.distanceBetween(lat, lon, event.latitude, event.longitude, distance);
            LinearLayout entry = views.column();
            entry.setPadding(views.dp(12), views.dp(8), views.dp(12), views.dp(8));
            entry.setBackground(views.background(views.surfaceColor, 12));
            LinearLayout heading = views.row();
            heading.setGravity(Gravity.TOP);
            TextView magnitude = views.label(event.magnitudeType + " " + formats.number(event.magnitude, "%.1f"), 13, views.ink);
            magnitude.setPadding(views.dp(5), views.dp(2), views.dp(5), views.dp(2));
            magnitude.setBackground(views.background(SignalColors.tint(EarthquakeFeed.magnitudeColor(event.magnitude)), 6));
            heading.addView(magnitude);
            TextView zone = views.label(event.zone, 15, views.ink);
            zone.setSingleLine(true);
            zone.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams zoneSize = new LinearLayout.LayoutParams(0, -2, 1);
            zoneSize.setMarginStart(views.dp(6));
            heading.addView(zone, zoneSize);
            heading.addView(views.chevron());
            entry.addView(heading);
            TextView details = views.label(activity.getString(R.string.earthquake_details, date.format(event.time),
                    Math.round(distance[0] / 1000), formats.number(event.depth, "%.1f")), 13, views.muted);
            details.setPadding(0, views.dp(2), 0, 0);
            entry.addView(details);
            views.cardAction(entry, () -> open(event));
            views.addSpaced(rows, entry, 5);
        }
        more.setVisibility(end < events.size() ? View.VISIBLE : View.GONE);
    }

    private void open(EarthquakeFeed.Event event) {
        try {
            activity.startActivity(new Intent(activity, RadarActivity.class)
                    .putExtra("layer", RadarActivity.Layer.EARTHQUAKES.name())
                    .putExtra("place", event.zone)
                    .putExtra("selectedEarthquake", event.mapData().toString())
                    .putExtra("timeZone", ITALY.getId()).putExtra("clock24", host.formats().clock24));
        } catch (org.json.JSONException error) {
            Toast.makeText(activity, activity.getString(R.string.earthquake_unavailable), Toast.LENGTH_SHORT).show();
        }
    }

    // Settings choices for the minimum magnitude.

    public static String[] magnitudeOptions(Context context) {
        String[] options = new String[MAGNITUDE_THRESHOLDS.length];
        options[0] = context.getString(R.string.earthquake_all_magnitudes);
        for (int i = 1; i < options.length; i++) options[i] = "M ≥ " + MAGNITUDE_THRESHOLDS[i];
        return options;
    }

    public static int magnitudeIndex(int threshold) {
        for (int i = 0; i < MAGNITUDE_THRESHOLDS.length; i++) {
            if (MAGNITUDE_THRESHOLDS[i] == threshold) return i;
        }
        return 0;
    }

    public static int magnitudeThreshold(int index) {
        return MAGNITUDE_THRESHOLDS[index];
    }
}
