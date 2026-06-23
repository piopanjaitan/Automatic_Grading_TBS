package com.ustp.mgrading.ui.detection;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.ustp.mgrading.data.ml.DetectionResult;

import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<DetectionResult> detections = new ArrayList<>();
    private int imageWidth = 0;
    private int imageHeight = 0;

    public OverlayView(Context context) {
        super(context);
        init();
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5f);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(34f);
        textPaint.setFakeBoldText(true);
        labelPaint.setStyle(Paint.Style.FILL);
    }

    public void setDetections(List<DetectionResult> results, int imageWidth, int imageHeight) {
        detections.clear();
        if (results != null) {
            detections.addAll(results);
        }
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        invalidate();
    }

    public void clear() {
        detections.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageWidth <= 0 || imageHeight <= 0 || detections.isEmpty()) {
            return;
        }

        float scale = Math.min(getWidth() / (float) imageWidth, getHeight() / (float) imageHeight);
        float offsetX = (getWidth() - imageWidth * scale) / 2f;
        float offsetY = (getHeight() - imageHeight * scale) / 2f;

        for (DetectionResult detection : detections) {
            int color = colorForClass(detection.getClassId());
            boxPaint.setColor(color);
            labelPaint.setColor(color);

            RectF source = detection.getBox();
            RectF box = new RectF(
                    offsetX + source.left * scale,
                    offsetY + source.top * scale,
                    offsetX + source.right * scale,
                    offsetY + source.bottom * scale
            );
            canvas.drawRect(box, boxPaint);

            String label = detection.getDisplayText();
            float textWidth = textPaint.measureText(label);
            float labelHeight = 42f;
            float labelTop = Math.max(0f, box.top - labelHeight);
            canvas.drawRect(box.left, labelTop, Math.min(getWidth(), box.left + textWidth + 18f), labelTop + labelHeight, labelPaint);
            canvas.drawText(label, box.left + 9f, labelTop + 30f, textPaint);
        }
    }

    private int colorForClass(int classId) {
        switch (classId) {
            case 0:
                return Color.rgb(184, 132, 0);
            case 1:
                return Color.rgb(36, 103, 200);
            case 2:
                return Color.rgb(31, 138, 91);
            case 3:
                return Color.rgb(201, 61, 61);
            default:
                return Color.rgb(15, 118, 110);
        }
    }
}
