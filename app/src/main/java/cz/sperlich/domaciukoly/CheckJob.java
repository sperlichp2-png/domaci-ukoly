package cz.sperlich.domaciukoly;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Pravidelná kontrola stránky na pozadí. */
public class CheckJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                Sync.run(getApplicationContext());
            } catch (Throwable ignored) {
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
