package it.skynow.app.notifications;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Periodic background check behind {@link AuroraAlerts}. */
public final class AuroraAlertCheck extends JobService {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private Future<?> pending;

    @Override public boolean onStartJob(JobParameters params) {
        pending = WORKER.submit(() -> {
            boolean retry = false;
            try {
                AuroraAlerts.check(this);
            } catch (Exception error) {
                retry = true;
                android.util.Log.w("SkyHere", "Aurora background check failed", error);
            }
            if (!Thread.currentThread().isInterrupted()) jobFinished(params, retry);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (pending != null) pending.cancel(true);
        return true;
    }
}
