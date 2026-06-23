package com.ustp.mgrading.ui.detection;

import android.app.Application;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ustp.mgrading.data.local.GradingTag;
import com.ustp.mgrading.data.local.GradingTagRepository;
import com.ustp.mgrading.data.ml.DetectionResult;
import com.ustp.mgrading.data.ml.TfliteDetector;
import com.ustp.mgrading.data.remote.GradingSyncScheduler;
import com.ustp.mgrading.util.GradingImageStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DetectionViewModel extends AndroidViewModel {
    private static final float CONFIDENCE_THRESHOLD = 0.25f;
    private static final float AUTO_TAG_THRESHOLD = 0.50f;
    private static final float IOU_THRESHOLD = 0.45f;
    private static final int RECENT_TAG_LIMIT = 20;

    private final MutableLiveData<String> status = new MutableLiveData<>();
    private final MutableLiveData<List<DetectionResult>> detections = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<FrameInfo> frameInfo = new MutableLiveData<>(new FrameInfo(0, 0));
    private final MutableLiveData<String> summary = new MutableLiveData<>("Tidak ada objek terdeteksi");
    private final MutableLiveData<List<GradingTag>> recentTags = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Boolean> liveSessionActive = new MutableLiveData<>(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean frameBusy = new AtomicBoolean(false);
    private final TfliteDetector detector;
    private final GradingTagRepository tagRepository;
    private final GradingImageStore imageStore;
    private final Object sessionLock = new Object();
    private final Set<Long> sessionSeenTagIds = new HashSet<>();
    private String currentSessionId = null;

    public DetectionViewModel(@NonNull Application application) {
        super(application);
        detector = new TfliteDetector(application.getAssets());
        tagRepository = new GradingTagRepository(application);
        imageStore = new GradingImageStore(application);
        status.setValue(detector.getStatus());
        recentTags.setValue(tagRepository.getRecentTags(RECENT_TAG_LIMIT));
        GradingSyncScheduler.schedule(application);
    }

    public LiveData<String> getStatus() {
        return status;
    }

    public LiveData<List<DetectionResult>> getDetections() {
        return detections;
    }

    public LiveData<FrameInfo> getFrameInfo() {
        return frameInfo;
    }

    public LiveData<String> getSummary() {
        return summary;
    }

    public LiveData<List<GradingTag>> getRecentTags() {
        return recentTags;
    }

    public LiveData<Boolean> getLiveSessionActive() {
        return liveSessionActive;
    }

    public boolean isDetectorReady() {
        return detector.isReady();
    }

    public void startLiveSession() {
        synchronized (sessionLock) {
            currentSessionId = "LIVE-" + System.currentTimeMillis();
            sessionSeenTagIds.clear();
        }
        liveSessionActive.setValue(true);
        summary.setValue("Deteksi live aktif. Arahkan kamera ke TBS satu per satu atau zoom out.");
    }

    public void stopLiveSession() {
        synchronized (sessionLock) {
            currentSessionId = null;
            sessionSeenTagIds.clear();
        }
        liveSessionActive.setValue(false);
        detections.setValue(Collections.emptyList());
        frameInfo.setValue(new FrameInfo(0, 0));
        summary.setValue("Deteksi live dihentikan. Tekan Mulai untuk mulai deteksi.");
    }

    public void detectFrame(Bitmap bitmap) {
        if (!detector.isReady() || bitmap == null || !isLiveSessionActive() || !frameBusy.compareAndSet(false, true)) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            return;
        }
        executor.execute(() -> {
            try {
                if (!isLiveSessionActive()) {
                    return;
                }
                List<DetectionResult> results = detector.detect(bitmap, CONFIDENCE_THRESHOLD, IOU_THRESHOLD);
                if (!isLiveSessionActive()) {
                    return;
                }
                TaggingResult taggingResult = processDetections(bitmap, results, isLiveSessionActive(), currentSessionIdSnapshot(), true);
                detections.postValue(taggingResult.detections);
                frameInfo.postValue(new FrameInfo(bitmap.getWidth(), bitmap.getHeight()));
                summary.postValue(toSummary(taggingResult.persistedDetections));
            } catch (Exception e) {
                status.postValue("Gagal inferensi frame: " + e.getMessage());
            } finally {
                bitmap.recycle();
                frameBusy.set(false);
            }
        });
    }

    public void detectPhoto(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        if (!detector.isReady()) {
            detections.setValue(Collections.emptyList());
            frameInfo.setValue(new FrameInfo(bitmap.getWidth(), bitmap.getHeight()));
            summary.setValue("Model TFLite belum tersedia. Letakkan " + TfliteDetector.MODEL_ASSET + " di assets.");
            return;
        }
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                List<DetectionResult> results = detector.detect(bitmap, CONFIDENCE_THRESHOLD, IOU_THRESHOLD);
                String sessionId = currentSessionIdSnapshot();
                if (sessionId == null) {
                    sessionId = "PHOTO-" + now;
                }
                TaggingResult taggingResult = processDetections(bitmap, results, true, sessionId, isLiveSessionActive());
                if (!taggingResult.persistedDetections.isEmpty()) {
                    Bitmap annotated = null;
                    try {
                        annotated = imageStore.drawTaggedFrame(bitmap, taggingResult.persistedDetections);
                        String annotatedPath = imageStore.saveJpeg(annotated, "annotated", now);
                        tagRepository.updateAnnotatedImagePath(taggingResult.tagIds, annotatedPath);
                        GradingSyncScheduler.schedule(getApplication());
                        recentTags.postValue(tagRepository.getRecentTags(RECENT_TAG_LIMIT));
                    } finally {
                        if (annotated != null) {
                            annotated.recycle();
                        }
                    }
                }
                detections.postValue(taggingResult.detections);
                frameInfo.postValue(new FrameInfo(bitmap.getWidth(), bitmap.getHeight()));
                summary.postValue(toSummary(taggingResult.persistedDetections));
            } catch (Exception e) {
                status.postValue("Gagal inferensi foto: " + e.getMessage());
            } finally {
                bitmap.recycle();
            }
        });
    }

    public void clearResults() {
        detections.setValue(Collections.emptyList());
        frameInfo.setValue(new FrameInfo(0, 0));
        summary.setValue("Tekan Mulai untuk mulai deteksi");
    }

    private TaggingResult processDetections(Bitmap source, List<DetectionResult> results, boolean persistTags,
                                            String sessionId, boolean dedupeLiveSession) {
        if (results == null || results.isEmpty()) {
            return new TaggingResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        if (!persistTags) {
            return new TaggingResult(results, Collections.emptyList(), Collections.emptyList());
        }
        long now = System.currentTimeMillis();
        String framePath = null;
        List<DetectionResult> taggedResults = new ArrayList<>();
        List<DetectionResult> persistedDetections = new ArrayList<>();
        List<Long> affectedTagIds = new ArrayList<>();
        List<Long> frameMatchedTagIds = new ArrayList<>();
        boolean changedRecentTags = false;

        for (DetectionResult detection : results) {
            if (detection.getConfidence() < AUTO_TAG_THRESHOLD) {
                continue;
            }

            Bitmap crop = null;
            try {
                crop = imageStore.crop(source, detection.getBox());
                String fingerprint = imageStore.averageHash(crop);
                GradingTag match = tagRepository.findMatch(detection.getClassId(), fingerprint, frameMatchedTagIds);
                GradingTag tag;
                if (match != null) {
                    boolean firstSeenInSession = rememberLiveSessionTagIfNeeded(match.getId(), dedupeLiveSession);
                    tag = firstSeenInSession
                            ? tagRepository.markSeen(match, detection, sessionId, now, true)
                            : match;
                } else {
                    if (framePath == null) {
                        framePath = imageStore.saveJpeg(source, "frame", now);
                    }
                    String cropPath = imageStore.saveJpeg(crop, "crop", now);
                    tag = tagRepository.insertTag(detection, framePath, cropPath, null, fingerprint, sessionId, now);
                    rememberLiveSessionTagIfNeeded(tag.getId(), dedupeLiveSession);
                }
                frameMatchedTagIds.add(tag.getId());
                affectedTagIds.add(tag.getId());
                DetectionResult taggedDetection = withTag(detection, tag.getTagCode());
                taggedResults.add(taggedDetection);
                persistedDetections.add(taggedDetection);
                changedRecentTags = true;
            } catch (Exception e) {
                status.postValue("Gagal auto tag: " + e.getMessage());
            } finally {
                if (crop != null) {
                    crop.recycle();
                }
            }
        }

        if (changedRecentTags) {
            GradingSyncScheduler.schedule(getApplication());
            recentTags.postValue(tagRepository.getRecentTags(RECENT_TAG_LIMIT));
        }
        return new TaggingResult(taggedResults, persistedDetections, affectedTagIds);
    }

    private DetectionResult withTag(DetectionResult detection, String tagCode) {
        return new DetectionResult(detection.getBox(), detection.getClassId(), detection.getLabel(), detection.getConfidence(), tagCode);
    }

    private boolean isLiveSessionActive() {
        Boolean active = liveSessionActive.getValue();
        return active != null && active;
    }

    private String currentSessionIdSnapshot() {
        synchronized (sessionLock) {
            return currentSessionId;
        }
    }

    private boolean rememberLiveSessionTagIfNeeded(long tagId, boolean dedupeLiveSession) {
        if (!dedupeLiveSession) {
            return true;
        }
        synchronized (sessionLock) {
            return sessionSeenTagIds.add(tagId);
        }
    }

    private String toSummary(List<DetectionResult> results) {
        if (results == null || results.isEmpty()) {
            return "Tidak ada objek terdeteksi";
        }
        int[] counts = new int[4];
        float best = 0f;
        String bestLabel = "";
        for (DetectionResult result : results) {
            if (result.getClassId() >= 0 && result.getClassId() < counts.length) {
                counts[result.getClassId()]++;
            }
            if (result.getConfidence() > best) {
                best = result.getConfidence();
                bestLabel = result.getLabel();
            }
        }
        return String.format(Locale.US,
                "Total %d objek. Kurang Masak: %d, Masak: %d, Mentah: %d, Terlalu Masak: %d. Tertinggi: %s %.0f%%",
                results.size(), counts[0], counts[1], counts[2], counts[3], bestLabel, best * 100f);
    }

    @Override
    protected void onCleared() {
        detector.close();
        executor.shutdownNow();
        super.onCleared();
    }

    public static class FrameInfo {
        public final int width;
        public final int height;

        public FrameInfo(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static class TaggingResult {
        final List<DetectionResult> detections;
        final List<DetectionResult> persistedDetections;
        final List<Long> tagIds;

        TaggingResult(List<DetectionResult> detections, List<DetectionResult> persistedDetections, List<Long> tagIds) {
            this.detections = detections;
            this.persistedDetections = persistedDetections;
            this.tagIds = tagIds;
        }
    }
}
