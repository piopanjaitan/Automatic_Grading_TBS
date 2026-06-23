package com.ustp.mgrading.data.remote;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

public final class GradingSyncScheduler {
    private static final int JOB_ID = 76031;

    private GradingSyncScheduler() {
    }

    public static void schedule(Context context) {
        schedule(context, false);
    }

    public static void syncNow(Context context) {
        schedule(context, true);
    }

    private static void schedule(Context context, boolean immediate) {
        Context appContext = context.getApplicationContext();
        if (GradingSyncSettings.getServerUrl(appContext).trim().isEmpty()) {
            return;
        }
        ComponentName service = new ComponentName(appContext, GradingSyncJobService.class);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("manual", immediate);
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(extras);
        if (immediate) {
            builder.setMinimumLatency(1000L);
            builder.setOverrideDeadline(5000L);
        } else {
            builder.setMinimumLatency(5000L);
            builder.setOverrideDeadline(60000L);
        }
        JobScheduler scheduler = (JobScheduler) appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.schedule(builder.build());
        }
    }
}
