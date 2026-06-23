package com.ustp.mgrading.data.local;

public class GradingTag {
    private final long id;
    private final String tagCode;
    private final int classId;
    private final String label;
    private final float confidence;
    private final float bboxLeft;
    private final float bboxTop;
    private final float bboxRight;
    private final float bboxBottom;
    private final String imagePath;
    private final String cropPath;
    private final String annotatedImagePath;
    private final String fingerprint;
    private final String sessionId;
    private final long createdAt;
    private final long lastSeenAt;
    private final int seenCount;
    private final String deviceId;
    private final String syncStatus;
    private final Long remoteId;
    private final Long syncedAt;
    private final int syncAttempts;
    private final String syncError;

    public GradingTag(long id, String tagCode, int classId, String label, float confidence,
                      float bboxLeft, float bboxTop, float bboxRight, float bboxBottom,
                      String imagePath, String cropPath, String annotatedImagePath, String fingerprint, String sessionId,
                      long createdAt, long lastSeenAt, int seenCount, String deviceId, String syncStatus,
                      Long remoteId, Long syncedAt, int syncAttempts, String syncError) {
        this.id = id;
        this.tagCode = tagCode;
        this.classId = classId;
        this.label = label;
        this.confidence = confidence;
        this.bboxLeft = bboxLeft;
        this.bboxTop = bboxTop;
        this.bboxRight = bboxRight;
        this.bboxBottom = bboxBottom;
        this.imagePath = imagePath;
        this.cropPath = cropPath;
        this.annotatedImagePath = annotatedImagePath;
        this.fingerprint = fingerprint;
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.lastSeenAt = lastSeenAt;
        this.seenCount = seenCount;
        this.deviceId = deviceId;
        this.syncStatus = syncStatus;
        this.remoteId = remoteId;
        this.syncedAt = syncedAt;
        this.syncAttempts = syncAttempts;
        this.syncError = syncError;
    }

    public long getId() {
        return id;
    }

    public String getTagCode() {
        return tagCode;
    }

    public int getClassId() {
        return classId;
    }

    public String getLabel() {
        return label;
    }

    public float getConfidence() {
        return confidence;
    }

    public float getBboxLeft() {
        return bboxLeft;
    }

    public float getBboxTop() {
        return bboxTop;
    }

    public float getBboxRight() {
        return bboxRight;
    }

    public float getBboxBottom() {
        return bboxBottom;
    }

    public String getImagePath() {
        return imagePath;
    }

    public String getCropPath() {
        return cropPath;
    }

    public String getAnnotatedImagePath() {
        return annotatedImagePath;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public int getSeenCount() {
        return seenCount;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public Long getRemoteId() {
        return remoteId;
    }

    public Long getSyncedAt() {
        return syncedAt;
    }

    public int getSyncAttempts() {
        return syncAttempts;
    }

    public String getSyncError() {
        return syncError;
    }
}
