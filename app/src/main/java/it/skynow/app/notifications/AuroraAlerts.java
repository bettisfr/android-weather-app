package it.skynow.app.notifications;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import it.skynow.app.MainActivity;
import it.skynow.app.R;
import it.skynow.app.SkyLanguage;
import it.skynow.app.astronomy.AuroraForecast;
import java.time.Instant;

/** Notifies when the forecast place comes under NOAA's aurora oval at night. Off by default. */
public final class AuroraAlerts {
    /** Intent extra asking MainActivity to open the Astronomy tab. */
    public static final String OPEN_ASTRONOMY = "openAuroraAlert";
    private static final String CHANNEL = "aurora_alerts";
    private static final int JOB_ID = 176;
    private static final int CHECK_NOW_JOB_ID = 177;
    private static final long CHECK_INTERVAL_MS = 30 * 60_000L;

    private AuroraAlerts() {}

    private static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context) {
        return settings(context).getBoolean("enabled", false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        settings(context).edit().putBoolean("enabled", enabled).remove("covered").apply();
        updateSchedule(context);
        if (enabled) checkSoon(context);
    }

    /** Remembers the forecast place, the one the alerts are for. */
    public static void setLocation(Context context, double latitude, double longitude, String place) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) return;
        SharedPreferences prefs = settings(context);
        boolean moved = !Double.toString(latitude).equals(prefs.getString("latitude", ""))
                || !Double.toString(longitude).equals(prefs.getString("longitude", ""));
        SharedPreferences.Editor edit = prefs.edit().putString("latitude", Double.toString(latitude))
                .putString("longitude", Double.toString(longitude)).putString("place", place);
        // A new place starts uncovered and without a quiet period, so being under the oval there alerts at once.
        if (moved) edit.remove("covered").remove("alertedAt");
        edit.apply();
        updateSchedule(context);
        if (moved && enabled(context)) checkSoon(context);
    }

    public static void updateSchedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (!enabled(context) || settings(context).getString("latitude", "").isEmpty()) {
            jobs.cancel(JOB_ID);
            jobs.cancel(CHECK_NOW_JOB_ID);
        } else if (jobs.getPendingJob(JOB_ID) == null) {
            jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, AuroraAlertCheck.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(CHECK_INTERVAL_MS)
                    .setPersisted(true)
                    .build());
        }
    }

    /**
     * Checks once as soon as there is a network. The periodic job may first run up to an hour after it
     * is scheduled, which is too late right after alerts are turned on or the place changes.
     */
    private static void checkSoon(Context context) {
        context.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(CHECK_NOW_JOB_ID, new ComponentName(context, AuroraAlertCheck.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .build());
    }

    private static boolean canDeliver(Context context) {
        return enabled(context)
                && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
                && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    /** Checks the NOAA forecast and alerts if due. Blocking; run it off the main thread. */
    static void check(Context context) throws Exception {
        if (!canDeliver(context)) return;
        SharedPreferences prefs = settings(context);
        double latitude = Double.parseDouble(prefs.getString("latitude", "NaN"));
        double longitude = Double.parseDouble(prefs.getString("longitude", "NaN"));
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) return;
        Instant now = Instant.now();
        boolean covered = false;
        // Skip the download in daylight and twilight, when the aurora could not be seen anyway.
        if (AuroraAlertRule.darkEnough(now, latitude, longitude)) {
            AuroraForecast forecast = AuroraForecast.load();
            covered = forecast.isCurrentAt(now) && forecast.coversOnMap(latitude, longitude);
        }
        boolean wasCovered = prefs.getBoolean("covered", false);
        long lastAlert = prefs.getLong("alertedAt", 0);
        SharedPreferences.Editor edit = prefs.edit().putBoolean("covered", covered);
        if (AuroraAlertRule.shouldNotify(covered, wasCovered, now.toEpochMilli(), lastAlert)) {
            show(SkyLanguage.withPreference(context), prefs.getString("place", ""));
            edit.putLong("alertedAt", now.toEpochMilli());
        }
        edit.apply();
    }

    private static void show(Context context, String place) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, context.getString(R.string.aurora_alerts),
                NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(context, MainActivity.class).putExtra(OPEN_ASTRONOMY, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, JOB_ID, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = place.isEmpty() ? context.getString(R.string.aurora_alert_text_here)
                : context.getString(R.string.aurora_alert_text, place);
        manager.notify(CHANNEL, 0, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_weather_notification)
                .setContentTitle(context.getString(R.string.aurora_alert_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap).setAutoCancel(true).build());
    }
}
