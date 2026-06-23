package com.ustp.mgrading.ui.detection;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.ustp.mgrading.data.local.GradingTag;
import com.ustp.mgrading.data.local.GradingTagRepository;
import com.ustp.mgrading.data.remote.GradingSyncRunner;
import com.ustp.mgrading.data.remote.GradingSyncScheduler;
import com.ustp.mgrading.data.remote.GradingSyncSettings;
import com.ustp.mgrading.databinding.ActivitySavedTagsBinding;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SavedTagsActivity extends AppCompatActivity {
    private ActivitySavedTagsBinding binding;
    private GradingTagAdapter adapter;
    private GradingTagRepository repository;
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean syncInProgress = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySavedTagsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repository = new GradingTagRepository(this);
        adapter = new GradingTagAdapter();
        binding.savedRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.savedRecyclerView.setAdapter(adapter);

        binding.backButton.setOnClickListener(v -> finish());
        binding.refreshButton.setOnClickListener(v -> loadSavedTags());
        binding.saveServerButton.setOnClickListener(v -> saveServerUrl());
        binding.syncNowButton.setOnClickListener(v -> syncNow());
        binding.serverUrlInput.setText(GradingSyncSettings.getServerUrl(this));
        loadSavedTags();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSavedTags();
    }

    private void loadSavedTags() {
        List<GradingTag> tags = repository.getAllSavedTags();
        adapter.submit(tags);
        binding.emptyText.setVisibility(tags.isEmpty() ? View.VISIBLE : View.GONE);
        binding.savedRecyclerView.setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        binding.countText.setText(String.format(Locale.US, "Total %d data TBS", tags.size()));
        binding.classSummaryText.setText(toClassSummary(tags));
        int pending = repository.countPendingSyncTags();
        int failed = repository.countFailedSyncTags();
        String serverUrl = GradingSyncSettings.getServerUrl(this);
        binding.syncStatusText.setText(serverUrl.trim().isEmpty()
                ? String.format(Locale.US, "Sync dashboard: belum dikonfigurasi, belum terkirim %d data", pending)
                : String.format(Locale.US, "Sync dashboard: %s, belum terkirim %d data, gagal %d", serverUrl, pending, failed));
        if (!syncInProgress && pending == 0) {
            binding.syncProgressBar.setVisibility(View.GONE);
            binding.syncProgressText.setVisibility(View.GONE);
        }
    }

    private void saveServerUrl() {
        String serverUrl = binding.serverUrlInput.getText() == null ? "" : binding.serverUrlInput.getText().toString();
        GradingSyncSettings.setServerUrl(this, serverUrl);
        GradingSyncScheduler.syncNow(this);
        String normalizedUrl = serverUrl.trim().toLowerCase(Locale.US);
        if (normalizedUrl.contains("127.0.0.1") || normalizedUrl.contains("localhost")) {
            Toast.makeText(this, "Untuk HP fisik, gunakan IP laptop/server, bukan 127.0.0.1 atau localhost", Toast.LENGTH_LONG).show();
        }
        Toast.makeText(this, "URL dashboard disimpan", Toast.LENGTH_SHORT).show();
        loadSavedTags();
    }

    private void syncNow() {
        binding.syncNowButton.setEnabled(false);
        syncInProgress = true;
        updateSyncProgress(0, repository.countPendingSyncTags(), 0, 0, null, null);
        syncExecutor.execute(() -> {
            GradingSyncRunner.SyncSummary summary = new GradingSyncRunner().syncAllPending(this,
                    (processed, total, success, failed, currentTagCode, lastError) ->
                            mainHandler.post(() -> updateSyncProgress(processed, total, success, failed, currentTagCode, lastError)));
            mainHandler.post(() -> {
                syncInProgress = false;
                binding.syncNowButton.setEnabled(true);
                updateSyncProgress(summary.total, summary.total, summary.success, summary.failed, null, summary.lastError);
                String message = String.format(Locale.US,
                        "Sync selesai. Sukses %d, gagal %d, pending %d",
                        summary.success, summary.failed, summary.remaining);
                if (summary.lastError != null && summary.failed > 0) {
                    message += ". Error: " + summary.lastError;
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                loadSavedTags();
            });
        });
    }

    private void updateSyncProgress(int processed, int total, int success, int failed, String currentTagCode, String lastError) {
        int percent = total <= 0 ? 100 : Math.min(100, Math.round(processed * 100f / total));
        binding.syncProgressBar.setVisibility(View.VISIBLE);
        binding.syncProgressText.setVisibility(View.VISIBLE);
        binding.syncProgressBar.setProgress(percent);
        String status = String.format(Locale.US,
                "Sync dashboard: mengirim %d/%d data (%d%%). Sukses %d, gagal %d",
                processed, total, percent, success, failed);
        if (currentTagCode != null && !currentTagCode.trim().isEmpty()) {
            status += ". Terakhir: " + currentTagCode;
        }
        binding.syncStatusText.setText(status);
        String progress = String.format(Locale.US, "Upload %d%% (%d/%d)", percent, processed, total);
        if (lastError != null && failed > 0) {
            progress += " - error terakhir: " + lastError;
        }
        binding.syncProgressText.setText(progress);
    }

    private String toClassSummary(List<GradingTag> tags) {
        int[] counts = new int[4];
        for (GradingTag tag : tags) {
            if (tag.getClassId() >= 0 && tag.getClassId() < counts.length) {
                counts[tag.getClassId()]++;
            }
        }
        return String.format(Locale.US,
                "Kurang Masak: %d, Masak: %d, Mentah: %d, Terlalu Masak: %d",
                counts[0], counts[1], counts[2], counts[3]);
    }

    @Override
    protected void onDestroy() {
        syncExecutor.shutdownNow();
        super.onDestroy();
    }
}
