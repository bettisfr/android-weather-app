package it.skynow.app.notifications;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import it.skynow.app.MainActivity;
import it.skynow.app.R;
import it.skynow.app.astronomy.IssPasses;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class IssAlerts extends BroadcastReceiver {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int JOB = 173;
    private static final int TEST = -1;
    public static final String TRACK = "issNotificationTrack";
    public static final String TIME_ZONE = "issNotificationZone";
    public static final String SIMULATED = "issNotificationSimulated";
    public static final String OBSERVER = "issNotificationObserver";
    public static final String PLACE = "issNotificationPlace";
    private static final String CHANNEL = "iss_passes";
    private static final int[] LEAD_MINUTES = {0, 0, 5, 10, 15};

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("iss_alerts", Context.MODE_PRIVATE);
    }

    public static int option(Context context) {
        return settings(context).getInt("option", 0);
    }

    public static void setOption(Context context, int option) {
        settings(context).edit().putInt("option", option).apply();
        cancelPasses(context);
        if (option == 0) {
            context.getSystemService(JobScheduler.class).cancel(JOB);
        }
    }

    public static void update(Context context, double lat, double lon, double elevation, String zone, String place) {
        if (option(context) == 0 || !Double.isFinite(lat) || !Double.isFinite(lon)) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = settings(app);
        if (!Double.toString(lat).equals(prefs.getString("lat", ""))
                || !Double.toString(lon).equals(prefs.getString("lon", ""))) cancelPasses(app);
        prefs.edit().putString("lat", Double.toString(lat)).putString("lon", Double.toString(lon))
                .putString("elevation", Double.toString(Double.isFinite(elevation) ? elevation : 0))
                .putString("zone", zone).putString("place", place).apply();
        JobScheduler scheduler = app.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(JOB) == null) {
            scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(app, IssAlertRefresh.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(12 * 60 * 60 * 1000L).setPersisted(true).build());
        }
        WORKER.execute(() -> {
            try {
                refresh(app);
            } catch (IOException error) {
                android.util.Log.w("SkyHere", "ISS alerts could not be refreshed", error);
            }
        });
    }

    static void refresh(Context context) throws IOException {
        SharedPreferences prefs = settings(context);
        int option = option(context);
        String lat = prefs.getString("lat", "");
        String lon = prefs.getString("lon", "");
        if (option == 0 || lat.isEmpty() || lon.isEmpty()) return;
        double elevation = Double.parseDouble(prefs.getString("elevation", "0"));
        List<IssPasses.Pass> passes = IssPasses.upcoming(context, Double.parseDouble(lat),
                Double.parseDouble(lon), elevation, Instant.now());
        synchronized (IssAlerts.class) {
            if (option != option(context) || !lat.equals(prefs.getString("lat", ""))
                    || !lon.equals(prefs.getString("lon", ""))) return;
            cancelPasses(context);
            long now = System.currentTimeMillis();
            int count = 0;
            for (IssPasses.Pass pass : passes) {
                long trigger = (pass.rise.time - LEAD_MINUTES[option] * 60L) * 1000;
                if (trigger <= now) continue;
                schedule(context, count++, trigger, pass);
            }
            prefs.edit().putInt("count", count).apply();
        }
    }

    private static synchronized void cancelPasses(Context context) {
        SharedPreferences prefs = settings(context);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        for (int i = 0; i < prefs.getInt("count", 0); i++) {
            alarms.cancel(alarmIntent(context, i, null));
        }
        prefs.edit().remove("count").apply();
    }

    public static boolean canScheduleExactly(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    public static void test(Context context) {
        long start = (System.currentTimeMillis() + 5999) / 1000;
        schedule(context, TEST, start * 1000, IssPasses.Pass.simulated(start));
    }

    private static void schedule(Context context, int id, long trigger, IssPasses.Pass pass) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent intent = alarmIntent(context, id, pass);
        if (canScheduleExactly(context)) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent);
                return;
            } catch (SecurityException ignored) {
                // The exact alarm permission can change between checking and scheduling.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent);
    }

    private static PendingIntent alarmIntent(Context context, int id, IssPasses.Pass pass) {
        Intent alarm = new Intent(context, IssAlerts.class).putExtra("id", id);
        if (pass != null) {
            SharedPreferences prefs = settings(context);
            alarm.putExtra(TRACK, pass.notificationTrack()).putExtra("rise", pass.rise.time)
                    .putExtra("end", pass.set.time)
                    .putExtra(TIME_ZONE, prefs.getString("zone", java.time.ZoneId.systemDefault().getId()));
            if (id != TEST) {
                alarm.putExtra(OBSERVER, new double[]{Double.parseDouble(prefs.getString("lat", "0")),
                        Double.parseDouble(prefs.getString("lon", "0")),
                        Double.parseDouble(prefs.getString("elevation", "0"))})
                        .putExtra(PLACE, prefs.getString("place", ""));
            }
        }
        return PendingIntent.getBroadcast(context, id, alarm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (option(context) == 0) return;
            context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB + 1,
                    new ComponentName(context, IssAlertRefresh.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
            return;
        }
        int id = intent.getIntExtra("id", TEST);
        long now = Instant.now().getEpochSecond();
        if (id != TEST && (option(context) == 0 || now > intent.getLongExtra("end", 0))) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.iss_alerts), NotificationManager.IMPORTANCE_HIGH));
        long minutes = (intent.getLongExtra("rise", 0) - now + 59) / 60;
        String message = id == TEST ? context.getString(R.string.iss_alert_test_message)
                : minutes > 0 ? context.getResources().getQuantityString(R.plurals.iss_alert_soon, (int) minutes, minutes)
                : context.getString(R.string.iss_alert_now);
        Intent destination = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("openIssAlerts", true).putExtra(SIMULATED, id == TEST)
                .putExtra(TRACK, intent.getDoubleArrayExtra(TRACK))
                .putExtra(TIME_ZONE, intent.getStringExtra(TIME_ZONE))
                .putExtra(OBSERVER, intent.getDoubleArrayExtra(OBSERVER))
                .putExtra(PLACE, intent.getStringExtra(PLACE));
        long start = intent.getLongExtra("rise", 0);
        if (start != 0) destination.setData(android.net.Uri.parse("skyhere:iss/" + id + "/" + start));
        PendingIntent open = PendingIntent.getActivity(context, id, destination,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(id, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_weather_foreground).setContentTitle(context.getString(R.string.iss_alerts))
                .setContentText(message).setContentIntent(open).setAutoCancel(true).build());
    }
}
