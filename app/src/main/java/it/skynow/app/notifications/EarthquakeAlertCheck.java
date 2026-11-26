package it.skynow.app.notifications;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import it.skynow.app.seismic.EarthquakeFeed;

public final class EarthquakeAlertCheck extends JobService {
    private static final int JOB_ID = 175;
    private static final int LOOKBACK_HOURS = 3;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private Future<?> pending;

    public static void updateSchedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        SharedPreferences location = EarthquakeAlerts.settings(context);
        if (!EarthquakeAlerts.enabled(context) || !"IT".equalsIgnoreCase(location.getString("country", ""))) {
            jobs.cancel(JOB_ID);
            context.getSharedPreferences("earthquake_poll", MODE_PRIVATE).edit().clear().apply();
        } else if (jobs.getPendingJob(JOB_ID) == null) {
            jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, EarthquakeAlertCheck.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build());
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        pending = WORKER.submit(() -> {
            boolean retry = false;
            try {
                checkCatalog();
            } catch (Exception error) {
                retry = true;
                android.util.Log.w("SkyHere", "INGV background check failed", error);
            }
            if (!Thread.currentThread().isInterrupted()) jobFinished(params, retry);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (pending != null) pending.cancel(true);
        return true;
    }

    private void checkCatalog() throws Exception {
        if (!EarthquakeAlerts.canDeliver(this)) return;
        SharedPreferences location = EarthquakeAlerts.settings(this);
        double lat = Double.parseDouble(location.getString("latitude", "NaN"));
        double lon = Double.parseDouble(location.getString("longitude", "NaN"));
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        int radius = EarthquakeFeed.alertRadius(EarthquakeAlerts.filters(this));
        List<EarthquakeFeed.Event> events = EarthquakeFeed.fetch(lat, lon, radius, LOOKBACK_HOURS);
        if (Thread.currentThread().isInterrupted() || !EarthquakeAlerts.canDeliver(this)) return;

        SharedPreferences state = getSharedPreferences("earthquake_poll", MODE_PRIVATE);
        Set<String> previous = state.getStringSet("known", null);
        Set<String> current = new HashSet<>();
        for (EarthquakeFeed.Event event : events) {
            current.add(event.id);
            if (previous != null && !previous.contains(event.id)) EarthquakeAlerts.deliver(this, event);
        }
        state.edit().putStringSet("known", current).apply();
    }
}
