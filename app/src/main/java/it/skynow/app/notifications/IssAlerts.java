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
import it.skynow.app.SkyLanguage;
import it.skynow.app.astronomy.IssPasses;
import it.skynow.app.astronomy.EclipseCatalog;
import it.skynow.app.astronomy.TransitCatalog;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class IssAlerts extends BroadcastReceiver {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final int JOB = 173;
    public static final String TRACK = "issNotificationTrack";
    public static final String TIME_ZONE = "issNotificationZone";
    public static final String OBSERVER = "issNotificationObserver";
    public static final String PLACE = "issNotificationPlace";
    private static final String CHANNEL = "iss_passes";
    private static final int[] LEAD_MINUTES = {0, 0, 5, 10, 15};

    public enum Kind {
        SOLAR(1000, R.string.solar_eclipse_alerts),
        LUNAR(1001, R.string.lunar_eclipse_alerts),
        TRANSIT(1002, R.string.transit_alerts);

        final int id;
        public final int title;

        Kind(int id, int title) {
            this.id = id;
            this.title = title;
        }
    }

    public static final String OPEN_EVENT_SECTION = "openAstronomyEventSection";
    private static final int[] EVENT_LEAD_MINUTES = {0, 0, 15, 60, 1440};

    public static int option(Context context, Kind kind) {
        return settings(context).getInt("option_" + kind.name(), 1);
    }

    public static synchronized void setOption(Context context, Kind kind, int option) {
        settings(context).edit().putInt("option_" + kind.name(), option).apply();
        cancelAlarm(context, kind.id);
        stopJobIfDisabled(context);
    }

    public static boolean anyEnabled(Context context) {
        if (option(context) != 0) return true;
        for (Kind kind : Kind.values()) if (option(context, kind) != 0) return true;
        return false;
    }

    private static void stopJobIfDisabled(Context context) {
        if (!anyEnabled(context)) context.getSystemService(JobScheduler.class).cancel(JOB);
    }

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("iss_alerts", Context.MODE_PRIVATE);
    }

    public static int option(Context context) {
        return settings(context).getInt("option", 1);
    }

    public static void setOption(Context context, int option) {
        settings(context).edit().putInt("option", option).apply();
        cancelPasses(context);
        stopJobIfDisabled(context);
    }

    public static void update(Context context, double lat, double lon, double elevation, String zone, String place) {
        if (!anyEnabled(context) || !Double.isFinite(lat) || !Double.isFinite(lon)) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = settings(app);
        if (!Double.toString(lat).equals(prefs.getString("lat", ""))
                || !Double.toString(lon).equals(prefs.getString("lon", ""))) {
            cancelPasses(app);
            for (Kind kind : Kind.values()) cancelAlarm(app, kind.id);
        }
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
        if (!anyEnabled(context) || lat.isEmpty() || lon.isEmpty()) return;
        double latitude = Double.parseDouble(lat);
        double longitude = Double.parseDouble(lon);
        Instant now = Instant.now();
        for (Kind kind : Kind.values()) {
            int selection = option(context, kind);
            if (selection == 0) continue;
            try {
                refreshEvent(context, kind, selection, latitude, longitude, now);
            } catch (Exception error) {
                android.util.Log.w("SkyHere", "Astronomy event alerts could not be refreshed", error);
            }
        }
        if (option == 0) return;
        double elevation = Double.parseDouble(prefs.getString("elevation", "0"));
        List<IssPasses.Pass> passes = IssPasses.upcoming(context, latitude, longitude, elevation, now);
        synchronized (IssAlerts.class) {
            if (option != option(context) || !lat.equals(prefs.getString("lat", ""))
                    || !lon.equals(prefs.getString("lon", ""))) return;
            cancelPasses(context);
            long nowMillis = System.currentTimeMillis();
            int count = 0;
            for (IssPasses.Pass pass : passes) {
                long trigger = (pass.rise.time - LEAD_MINUTES[option] * 60L) * 1000;
                if (trigger <= nowMillis) continue;
                schedule(context, count++, trigger, pass);
            }
            prefs.edit().putInt("count", count).apply();
        }
    }

    private static void refreshEvent(Context context, Kind kind, int selection, double latitude,
                                     double longitude, Instant now) throws Exception {
        SharedPreferences prefs = settings(context);
        ZoneId zone = ZoneId.of(prefs.getString("zone", "UTC"));
        long lead = EVENT_LEAD_MINUTES[selection] * 60L;
        Instant earliest = now.plusSeconds(lead);
        Instant start = null;
        Instant end = null;
        String title = null;
        if (kind == Kind.TRANSIT) {
            for (TransitCatalog.Event event : TransitCatalog.upcoming(latitude, longitude, now)) {
                if (!event.visibleStart.isAfter(earliest)) continue;
                if (start != null && !event.visibleStart.isBefore(start)) continue;
                start = event.visibleStart;
                end = event.visibleEnd;
                title = event.transit.planet;
            }
        } else {
            boolean lunar = kind == Kind.LUNAR;
            int limit = lunar ? EclipseCatalog.LAST_OFFLINE_LUNAR_YEAR : EclipseCatalog.LAST_OFFLINE_SOLAR_YEAR;
            int firstYear = now.atZone(zone).getYear();
            for (int year = firstYear; year <= limit && start == null; year += EclipseCatalog.yearsPerRequest(lunar)) {
                for (EclipseCatalog.Event event : EclipseCatalog.upcoming(context, lunar, year, latitude, longitude, now)) {
                    if (!event.start.isAfter(earliest)) continue;
                    if (start != null && !event.start.isBefore(start)) continue;
                    start = event.start;
                    end = event.end;
                    title = event.type;
                }
            }
        }
        synchronized (IssAlerts.class) {
            if (selection != option(context, kind)
                    || latitude != Double.parseDouble(prefs.getString("lat", "NaN"))
                    || longitude != Double.parseDouble(prefs.getString("lon", "NaN"))) return;
            if (start == null) {
                cancelAlarm(context, kind.id);
                return;
            }
            Intent alarm = new Intent(context, IssAlerts.class).putExtra("id", kind.id)
                    .putExtra("kind", kind.name()).putExtra("title", title)
                    .putExtra("rise", start.getEpochSecond()).putExtra("end", end.getEpochSecond())
                    .putExtra(TIME_ZONE, zone.getId())
                    .putExtra(OBSERVER, new double[]{latitude, longitude})
                    .putExtra(PLACE, prefs.getString("place", ""));
            schedule(context, (start.getEpochSecond() - lead) * 1000, broadcast(context, kind.id, alarm));
        }
    }

    private static void cancelAlarm(Context context, int id) {
        PendingIntent existing = PendingIntent.getBroadcast(context, id, new Intent(context, IssAlerts.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (existing != null) {
            context.getSystemService(AlarmManager.class).cancel(existing);
            existing.cancel();
        }
    }

    private static synchronized void cancelPasses(Context context) {
        SharedPreferences prefs = settings(context);
        for (int i = 0; i < prefs.getInt("count", 0); i++) {
            cancelAlarm(context, i);
        }
        prefs.edit().remove("count").apply();
    }

    public static boolean canScheduleExactly(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    private static void schedule(Context context, int id, long trigger, IssPasses.Pass pass) {
        schedule(context, trigger, alarmIntent(context, id, pass));
    }

    private static void schedule(Context context, long trigger, PendingIntent intent) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
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
            alarm.putExtra(OBSERVER, new double[]{Double.parseDouble(prefs.getString("lat", "0")),
                    Double.parseDouble(prefs.getString("lon", "0")),
                    Double.parseDouble(prefs.getString("elevation", "0"))})
                    .putExtra(PLACE, prefs.getString("place", ""));
        }
        return broadcast(context, id, alarm);
    }

    private static PendingIntent broadcast(Context context, int id, Intent alarm) {
        return PendingIntent.getBroadcast(context, id, alarm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context context, Intent intent) {
        context = SkyLanguage.withPreference(context);
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (!anyEnabled(context)) return;
            context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB + 1,
                    new ComponentName(context, IssAlertRefresh.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
            return;
        }
        int id = intent.getIntExtra("id", -1);
        long now = Instant.now().getEpochSecond();
        if (id < 0 || now > intent.getLongExtra("end", 0)) return;
        String kindName = intent.getStringExtra("kind");
        Kind kind = kindName == null ? null : Kind.valueOf(kindName);
        if (kind == null ? option(context) == 0 : option(context, kind) == 0) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String channel = kind == null ? CHANNEL : "astronomy_" + kind.name().toLowerCase(java.util.Locale.ROOT);
        manager.createNotificationChannel(new NotificationChannel(channel,
                context.getString(kind == null ? R.string.iss_alerts : kind.title), NotificationManager.IMPORTANCE_HIGH));
        long minutes = (intent.getLongExtra("rise", 0) - now + 59) / 60;
        String message = minutes > 0 ? context.getResources().getQuantityString(R.plurals.iss_alert_soon, (int) minutes, minutes)
                : context.getString(R.string.iss_alert_now);
        Intent destination = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("openIssAlerts", true)
                .putExtra(TRACK, intent.getDoubleArrayExtra(TRACK))
                .putExtra(TIME_ZONE, intent.getStringExtra(TIME_ZONE))
                .putExtra(OBSERVER, intent.getDoubleArrayExtra(OBSERVER))
                .putExtra(PLACE, intent.getStringExtra(PLACE));
        String title = context.getString(R.string.iss_alerts);
        if (kind != null) {
            destination.removeExtra("openIssAlerts");
            destination.putExtra(OPEN_EVENT_SECTION, kind.name());
            String eventType = intent.getStringExtra("title");
            title = kind == Kind.TRANSIT ? context.getString(R.string.transit_of,
                    context.getString("Mercury".equals(eventType) ? R.string.mercury : R.string.venus))
                    : EclipseCatalog.typeLabel(context, eventType);
            message = minutes > 0 ? context.getResources().getQuantityString(
                    R.plurals.astronomy_event_soon, (int) minutes, minutes)
                    : context.getString(R.string.astronomy_event_now);
            String place = intent.getStringExtra(PLACE);
            if (place != null && !place.isEmpty()) message = place + " · " + message;
        }
        long start = intent.getLongExtra("rise", 0);
        if (start != 0) destination.setData(android.net.Uri.parse("skyhere:iss/" + id + "/" + start));
        PendingIntent open = PendingIntent.getActivity(context, id, destination,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(id, new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_weather_notification).setContentTitle(title)
                .setContentText(message).setContentIntent(open).setAutoCancel(true).build());
    }
}
