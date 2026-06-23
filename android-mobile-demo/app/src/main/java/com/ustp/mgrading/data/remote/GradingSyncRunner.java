package com.ustp.mgrading.data.remote;

import android.content.Context;
import android.util.Log;

import com.ustp.mgrading.data.local.GradingTag;
import com.ustp.mgrading.data.local.GradingTagRepository;

import java.util.List;

public class GradingSyncRunner {
    private static final String TAG = "MGradingSync";
    private static final int DEFAULT_LIMIT = 25;

    public SyncSummary syncPending(Context context) {
        return syncPending(context, DEFAULT_LIMIT, null);
    }

    public SyncSummary syncAllPending(Context context, ProgressListener listener) {
        Context appContext = context.getApplicationContext();
        GradingTagRepository repository = new GradingTagRepository(appContext);
        int pendingCount = repository.countPendingSyncTags();
        return syncPending(appContext, Math.max(pendingCount, 1), listener);
    }

    private SyncSummary syncPending(Context context, int limit, ProgressListener listener) {
        Context appContext = context.getApplicationContext();
        GradingTagRepository repository = new GradingTagRepository(appContext);
        String serverUrl = GradingSyncSettings.getServerUrl(appContext);
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            int remaining = repository.countPendingSyncTags();
            notifyProgress(listener, 0, remaining, 0, 0, null, "URL server dashboard belum diisi");
            return new SyncSummary(0, 0, remaining, "URL server dashboard belum diisi", remaining);
        }

        List<GradingTag> pending = repository.getPendingSyncTags(limit);
        GradingSyncClient client = new GradingSyncClient(serverUrl);
        int success = 0;
        int failed = 0;
        String lastError = null;
        int total = pending.size();
        notifyProgress(listener, 0, total, success, failed, null, null);

        for (GradingTag tag : pending) {
            try {
                GradingSyncClient.SyncResponse response = client.upload(tag);
                repository.markSyncSuccess(tag.getId(), response.remoteId, System.currentTimeMillis());
                success++;
                Log.i(TAG, "Uploaded " + tag.getTagCode() + " to " + serverUrl);
            } catch (Exception e) {
                lastError = e.getMessage();
                repository.markSyncFailed(tag.getId(), lastError);
                failed++;
                Log.e(TAG, "Upload failed for " + tag.getTagCode() + ": " + lastError, e);
            }
            notifyProgress(listener, success + failed, total, success, failed, tag.getTagCode(), lastError);
        }

        int remaining = repository.countPendingSyncTags();
        return new SyncSummary(success, failed, remaining, lastError, total);
    }

    private void notifyProgress(ProgressListener listener, int processed, int total, int success, int failed,
                                String currentTagCode, String lastError) {
        if (listener != null) {
            listener.onProgress(processed, total, success, failed, currentTagCode, lastError);
        }
    }

    public interface ProgressListener {
        void onProgress(int processed, int total, int success, int failed, String currentTagCode, String lastError);
    }

    public static class SyncSummary {
        public final int success;
        public final int failed;
        public final int remaining;
        public final String lastError;
        public final int total;

        SyncSummary(int success, int failed, int remaining, String lastError, int total) {
            this.success = success;
            this.failed = failed;
            this.remaining = remaining;
            this.lastError = lastError;
            this.total = total;
        }
    }
}
