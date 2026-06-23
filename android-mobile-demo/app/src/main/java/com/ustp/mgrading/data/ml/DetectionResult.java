package com.ustp.mgrading.data.ml;

import android.graphics.RectF;

public class DetectionResult {
    private final RectF box;
    private final int classId;
    private final String label;
    private final float confidence;
    private final String tagCode;

    public DetectionResult(RectF box, int classId, String label, float confidence) {
        this(box, classId, label, confidence, null);
    }

    public DetectionResult(RectF box, int classId, String label, float confidence, String tagCode) {
        this.box = box;
        this.classId = classId;
        this.label = label;
        this.confidence = confidence;
        this.tagCode = tagCode;
    }

    public RectF getBox() {
        return box;
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

    public String getTagCode() {
        return tagCode;
    }

    public String getDisplayText() {
        String confidenceText = String.format(java.util.Locale.US, "%.0f%%", confidence * 100f);
        if (tagCode == null || tagCode.trim().isEmpty()) {
            return label + " " + confidenceText;
        }
        return tagCode + " - " + label + " " + confidenceText;
    }
}
