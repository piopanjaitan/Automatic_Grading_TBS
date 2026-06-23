package com.ustp.mgrading.data.remote;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GradingSyncJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public boolean onStartJob(JobParameters params) {
        executor.execute(() -> {
            GradingSyncRunner.SyncSummary summary = new GradingSyncRunner().syncPending(this);
            boolean reschedule = summary.failed > 0 || summary.remaining > 0;
            jobFinished(params, reschedule);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
