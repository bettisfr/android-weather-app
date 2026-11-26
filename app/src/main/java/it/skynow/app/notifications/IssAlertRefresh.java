package it.skynow.app.notifications;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.io.IOException;
import java.util.concurrent.Future;

public final class IssAlertRefresh extends JobService {
    private Future<?> refresh;

    @Override public boolean onStartJob(JobParameters params) {
        refresh = IssAlerts.WORKER.submit(() -> {
            boolean retry = false;
            try {
                IssAlerts.refresh(this);
            } catch (IOException error) {
                retry = true;
                android.util.Log.w("SkyHere", "ISS alerts could not be refreshed", error);
            }
            if (!Thread.currentThread().isInterrupted()) jobFinished(params, retry);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (refresh != null) refresh.cancel(true);
        return true;
    }
}
