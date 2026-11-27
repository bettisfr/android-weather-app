package it.skynow.app.dashboard;

import android.app.Activity;
import android.app.Dialog;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.widget.ScrollView;
import it.skynow.app.R;
import it.skynow.app.SkyTheme;
import it.skynow.app.astronomy.IssPasses;
import it.skynow.app.astronomy.SkyTrack;
import it.skynow.app.astronomy.SkyTrajectory;
import it.skynow.app.ui.Formats;
import it.skynow.app.ui.SkyViews;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Full-screen sky track of the Sun, Moon, a planet or an ISS pass, with the live position and a
 * compass that follows where the phone's rear camera points.
 */
final class SkyTrackDialog {
    private final Activity activity;
    private final SkyViews views;
    private final AstronomyTab.Host host;
    private final AstronomyWidgets widgets;
    private final Runnable refreshVisibility;
    private boolean resumed;

    private double magneticDeclination = Double.NaN;
    private TextView skyTrackDirection;
    private TextView skyTrackCoordinates;
    private java.util.function.LongFunction<SkyTrajectory.Point> trackedCelestialPosition;
    private SkyTrack aimedSkyTrack;
    private LiveIssBadge trackedIss;
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
            String direction = unreliable || Math.abs(elevation) == 90 ? "—" : azimuth + "° " + widgets.compassDirection(azimuth);
            String reading = activity.getString(trueNorth ? R.string.phone_direction_reading : R.string.phone_direction_magnetic,
                    direction, elevation);
            if (lowAccuracy) reading += "\n" + activity.getString(R.string.phone_compass_low_accuracy);
            setPhoneReading(skyTrackDirection, reading);
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    SkyTrackDialog(Activity activity, SkyViews views, AstronomyTab.Host host, AstronomyWidgets widgets,
                   Runnable refreshVisibility) {
        this.activity = activity;
        this.views = views;
        this.host = host;
        this.widgets = widgets;
        this.refreshVisibility = refreshVisibility;
    }

    private Formats formats() {
        return host.formats();
    }

    void onResume() {
        resumed = true;
        startPhoneOrientation();
    }

    void onPause() {
        resumed = false;
        stopPhoneOrientation();
    }

    void setMagneticDeclination(double degrees) {
        magneticDeclination = degrees;
    }

    /** Follows an ISS pass in the open dialog; {@code live} shows while the pass is under way. */
    void trackIss(IssPasses.Pass pass, TextView live) {
        trackedIss = new LiveIssBadge(pass, live);
    }

    /** Follows a body's position in the open dialog; {@code position} returns null when it is not shown. */
    void trackCelestial(java.util.function.LongFunction<SkyTrajectory.Point> position) {
        trackedCelestialPosition = position;
    }

    /**
     * Moves the live marker of the open dialog to its position at {@code second}.
     * Returns the milliseconds until the next update is due, or {@link Long#MAX_VALUE}.
     */
    long updateLive(long millis, long second) {
        long delay = Long.MAX_VALUE;
        if (trackedIss != null && aimedSkyTrack != null) {
            delay = Math.min(delay, trackedIss.update(millis, second));
            SkyTrajectory.Point position = trackedIss.pass.positionAt(second);
            updateSkyTrackPosition(position);
            if (position != null) delay = Math.min(delay, 1000L - Math.floorMod(millis, 1000L));
        } else if (trackedCelestialPosition != null && aimedSkyTrack != null) {
            updateSkyTrackPosition(trackedCelestialPosition.apply(second));
            delay = Math.min(delay, 60_000L - Math.floorMod(millis, 60_000L));
        }
        return delay;
    }

    private void startPhoneOrientation() {
        if (!resumed || skyTrackDirection == null || phoneOrientationActive) return;
        SensorManager manager = activity.getSystemService(SensorManager.class);
        Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (sensor == null) sensor = manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        phoneOrientationActive = sensor != null
                && manager.registerListener(phoneOrientation, sensor, SensorManager.SENSOR_DELAY_GAME, 0);
        if (!phoneOrientationActive) skyTrackDirection.setText(R.string.phone_direction_unavailable);
    }

    private void stopPhoneOrientation() {
        if (phoneOrientationActive) activity.getSystemService(SensorManager.class).unregisterListener(phoneOrientation);
        phoneOrientationActive = false;
        lastPhoneReadingNanos = 0;
    }

    private void setPhoneReading(TextView direction, String reading) {
        if (direction != null && !reading.contentEquals(direction.getText())) direction.setText(reading);
    }

    private void updateSkyTrackPosition(SkyTrajectory.Point position) {
        boolean above = position != null && position.elevation >= 0;
        aimedSkyTrack.setCurrentPosition(above ? position : null);
        skyTrackCoordinates.setVisibility(above ? View.VISIBLE : View.GONE);
        if (above) {
            skyTrackCoordinates.setText(activity.getString(R.string.sky_track_now) + " · "
                    + widgets.skyCoordinatesText(position.azimuth, position.elevation));
        }
    }

    void showSkyTrack(String title, String period, double magnitude, int magnitudeFill,
                              java.util.function.Consumer<LinearLayout> content) {
        Dialog dialog = new Dialog(activity, R.style.SkyHereTheme);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout screen = views.column();
        screen.setBackgroundColor(views.surfaceColor);
        screen.addView(views.detailHeader(title, R.string.sky_track_back, dialog::dismiss));

        ScrollView viewport = new ScrollView(activity);
        viewport.setFillViewport(true);
        LinearLayout body = views.column();
        body.setPadding(views.dp(12), views.dp(4), views.dp(12), views.dp(12));
        LinearLayout panel = views.column();
        panel.setBackground(views.background(activity.getColor(R.color.sky_card), 16));
        panel.setPadding(views.dp(14), views.dp(12), views.dp(14), views.dp(14));
        LinearLayout summary = views.row();
        summary.setGravity(Gravity.TOP);
        summary.addView(views.label(period, 14, views.muted), new LinearLayout.LayoutParams(0, -2, 1));
        if (Double.isFinite(magnitude)) {
            TextView badge = views.badge("");
            widgets.setMagnitudeBadge(badge, magnitude, magnitudeFill);
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
            refreshVisibility.run();
            skyTrackDirection = null;
            stopPhoneOrientation();
        });

        android.view.Window window = dialog.getWindow();
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        dialog.show();
        window.setLayout(-1, -1);
        SkyTheme.applySystemBars(window, activity, views.surfaceColor);
        startPhoneOrientation();
    }

    void addSkyTrack(LinearLayout panel, List<? extends SkyTrajectory.Point> points, SkyTrajectory.Point start,
                             SkyTrajectory.Point peak, SkyTrajectory.Point end, SkyTrajectory.Point now,
                             SkyTrajectory.Point brightest) {
        String[] cardinal = {widgets.compassDirection(0), widgets.compassDirection(90), widgets.compassDirection(180), widgets.compassDirection(270)};
        skyTrackCoordinates = views.label("", 15, activity.getColor(R.color.sky_observing));
        skyTrackCoordinates.setPadding(0, views.dp(7), 0, views.dp(3));
        skyTrackCoordinates.setVisibility(View.GONE);
        panel.addView(skyTrackCoordinates);
        aimedSkyTrack = new SkyTrack(activity, points, start, peak, end, now, brightest, cardinal);
        int chartSize = Math.min(380, activity.getResources().getConfiguration().screenWidthDp - 52);
        panel.addView(aimedSkyTrack, new LinearLayout.LayoutParams(-1, views.dp(chartSize)));
        LinearLayout direction = views.column();
        direction.setBackground(views.background(activity.getColor(R.color.sky_subtle), 12));
        direction.setPadding(views.dp(11), views.dp(9), views.dp(11), views.dp(9));
        direction.addView(views.label(activity.getString(R.string.phone_direction), 14, views.ink));
        skyTrackDirection = views.label(activity.getString(R.string.phone_direction_waiting), 17, views.ink);
        views.addSpaced(direction, skyTrackDirection, 5);
        views.addSpaced(direction, views.label(activity.getString(R.string.phone_direction_hint), 12, views.muted), 5);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = views.dp(9);
        panel.addView(direction, size);
        addSkyTrackLegendItem(panel, activity.getString(R.string.sky_track_now), SkyTrack.NOW_COLOR, 10);
    }

    void addSkyTrackPoint(LinearLayout panel, int title, SkyTrajectory.Point point, int color,
                                  ZoneId zone, LocalDate date) {
        if (point == null) return;
        String time = skyTrackTime(point, zone, date);
        String position = point.elevation >= 0 ? widgets.issBearing(point.azimuth) + " · " + Math.round(point.elevation) + "°"
                : activity.getString(R.string.planet_below_horizon);
        String details = activity.getString(title) + " · " + time + " · " + position;
        addSkyTrackLegendItem(panel, details, color, 8);
    }

    private void addSkyTrackLegendItem(LinearLayout panel, String details, int color, int diameter) {
        LinearLayout item = views.row();
        View marker = new View(activity);
        marker.setBackground(views.background(color, diameter / 2));
        LinearLayout.LayoutParams markerSize = new LinearLayout.LayoutParams(views.dp(diameter), views.dp(diameter));
        markerSize.setMarginEnd(views.dp(8));
        item.addView(marker, markerSize);
        item.addView(views.label(details, 14, views.ink), new LinearLayout.LayoutParams(0, -2, 1));
        views.addSpaced(panel, item, 6);
    }

    String skyTrackTime(SkyTrajectory.Point point, ZoneId zone, LocalDate date) {
        ZonedDateTime localTime = Instant.ofEpochSecond(point.time).atZone(zone);
        String time = localTime.format(formats().clockFormat());
        return localTime.toLocalDate().equals(date) ? time : localTime.format(formats().shortDate(false)) + " " + time;
    }

    TextView issVisibilityBadge() {
        TextView badge = views.observingBadge(activity.getString(R.string.astronomy_visible_now));
        badge.setVisibility(View.GONE);
        return badge;
    }

    static final class LiveIssBadge extends AstronomyTab.VisibilityBadge {
        final IssPasses.Pass pass;

        LiveIssBadge(IssPasses.Pass pass, TextView badge) {
            super(pass.rise.time, pass.set.time, badge, null);
            this.pass = pass;
        }
    }
}
