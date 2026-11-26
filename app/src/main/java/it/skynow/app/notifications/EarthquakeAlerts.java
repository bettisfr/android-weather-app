package it.skynow.app.notifications;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import it.skynow.app.MainActivity;
import it.skynow.app.R;
import it.skynow.app.maps.RadarActivity;
import it.skynow.app.seismic.EarthquakeFeed;

public final class EarthquakeAlerts extends FirebaseMessagingService {
    private static final String TOPIC = "terremoti-ingv";
    private static final String CHANNEL = "earthquake_alerts";

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE);
    }

    public static boolean configured(Context context) {
        return !FirebaseApp.getApps(context).isEmpty();
    }

    public static boolean enabled(Context context) {
        return settings(context).getBoolean("enabled", false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        settings(context).edit().putBoolean("enabled", enabled).apply();
        if (enabled) syncSubscription(context);
        else if (configured(context)) FirebaseMessaging.getInstance().unsubscribeFromTopic(TOPIC);
    }

    public static void syncSubscription(Context context) {
        if (enabled(context) && configured(context)) FirebaseMessaging.getInstance().subscribeToTopic(TOPIC);
    }

    public static void setLocation(Context context, double latitude, double longitude, String country) {
        settings(context).edit().putString("latitude", Double.toString(latitude))
                .putString("longitude", Double.toString(longitude)).putString("country", country).apply();
    }

    @Override public void onNewToken(String token) {
        syncSubscription(this);
    }

    @Override public void onMessageReceived(RemoteMessage message) {
        if (!enabled(this) || (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return;
        SharedPreferences location = settings(this);
        if (!"IT".equalsIgnoreCase(location.getString("country", ""))) return;
        SharedPreferences filters = getSharedPreferences(MainActivity.class.getSimpleName(), MODE_PRIVATE);
        try {
            Map<String, String> fields = message.getData();
            if (!"ingv_earthquake".equals(fields.get("type"))) return;
            EarthquakeFeed.Event event = EarthquakeFeed.fromAlert(fields);
            double lat = Double.parseDouble(location.getString("latitude", "NaN"));
            double lon = Double.parseDouble(location.getString("longitude", "NaN"));
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
            float[] distance = new float[1];
            Location.distanceBetween(lat, lon, event.latitude, event.longitude, distance);
            int minimum = filters.getInt("earthquakeMinimumMagnitude", 0);
            if (!event.matchesMagnitude(minimum) || distance[0] > 1000 * EarthquakeFeed.alertRadius(filters)) return;
            show(event, Math.round(distance[0] / 1000), filters.getBoolean("clock24", true));
        } catch (RuntimeException | org.json.JSONException invalidEvent) {
            android.util.Log.w("SkyHere", "Invalid INGV notification", invalidEvent);
        }
    }

    private void show(EarthquakeFeed.Event event, int distanceKm, boolean clock24) throws org.json.JSONException {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.earthquake_alerts),
                NotificationManager.IMPORTANCE_HIGH));
        String eventKey = event.id;
        SharedPreferences seen = getSharedPreferences("earthquake_alert_seen", MODE_PRIVATE);
        if (seen.contains(eventKey)) return;
        long now = Instant.now().getEpochSecond();
        SharedPreferences.Editor seenUpdate = seen.edit().putLong(eventKey, now);
        for (Map.Entry<String, ?> entry : seen.getAll().entrySet()) {
            if (entry.getValue() instanceof Long && (Long) entry.getValue() < now - 86400) {
                seenUpdate.remove(entry.getKey());
            }
        }
        Locale locale = Locale.forLanguageTag(getString(R.string.app_language_tag));
        String magnitude = event.magnitudeType + " " + String.format(locale, "%.1f", event.magnitude);
        String time = DateTimeFormatter.ofPattern(clock24 ? "HH:mm" : "h:mm a", locale)
                .withZone(ZoneId.of("Europe/Rome")).format(event.time);
        String detail = getString(R.string.earthquake_alert_detail, time, distanceKm);
        Intent map = new Intent(this, RadarActivity.class).setData(Uri.parse("skyhere:earthquake/" + event.id))
                .putExtra("layer", RadarActivity.Layer.EARTHQUAKES.name()).putExtra("place", event.zone)
                .putExtra("selectedEarthquake", event.mapData().toString())
                .putExtra("timeZone", "Europe/Rome").putExtra("clock24", clock24);
        PendingIntent open = PendingIntent.getActivity(this, 0, map, PendingIntent.FLAG_IMMUTABLE);
        manager.notify(eventKey, 0, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_weather_foreground)
                .setContentTitle(magnitude + " · " + event.zone)
                .setContentText(detail).setContentIntent(open).setAutoCancel(true).build());
        seenUpdate.apply();
    }
}
