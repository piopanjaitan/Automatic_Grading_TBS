package com.ustp.mgrading.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GradingImageStore {
    private final File rootDir;
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);

    public GradingImageStore(Context context) {
        File externalDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (externalDir == null) {
            externalDir = context.getFilesDir();
        }
        rootDir = new File(externalDir, "grading");
    }

    public Bitmap crop(Bitmap source, RectF box) {
        int left = clamp(Math.round(box.left), 0, source.getWidth() - 1);
        int top = clamp(Math.round(box.top), 0, source.getHeight() - 1);
        int right = clamp(Math.round(box.right), left + 1, source.getWidth());
        int bottom = clamp(Math.round(box.bottom), top + 1, source.getHeight());
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }

    public String saveJpeg(Bitmap bitmap, String prefix, long timestamp) throws IOException {
        File dayDir = new File(rootDir, dayFormat.format(new Date(timestamp)));
        if (!dayDir.exists() && !dayDir.mkdirs()) {
            throw new IOException("Gagal membuat folder: " + dayDir.getAbsolutePath());
        }
        File file = new File(dayDir, prefix + "_" + timestamp + "_" + System.nanoTime() + ".jpg");
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                throw new IOException("Gagal menyimpan JPEG: " + file.getAbsolutePath());
            }
        }
        return file.getAbsolutePath();
    }

    public String averageHash(Bitmap source) {
        Bitmap scaled = Bitmap.createScaledBitmap(source, 8, 8, true);
        int[] gray = new int[64];
        long sum = 0;
        int index = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int color = scaled.getPixel(x, y);
                int value = (int) (Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f);
                gray[index++] = value;
                sum += value;
            }
        }
        if (scaled != source) {
            scaled.recycle();
        }
        int avg = (int) (sum / 64);
        long bits = 0L;
        for (int i = 0; i < gray.length; i++) {
            if (gray[i] >= avg) {
                bits |= (1L << i);
            }
        }
        return String.format(Locale.US, "%016x", bits);
    }

    public Bitmap drawTaggedFrame(Bitmap source, java.util.List<com.ustp.mgrading.data.ml.DetectionResult> detections) {
        Bitmap output = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(output);
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5f);
        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setStyle(Paint.Style.FILL);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(34f);
        textPaint.setFakeBoldText(true);
        for (com.ustp.mgrading.data.ml.DetectionResult detection : detections) {
            int color = colorForClass(detection.getClassId());
            boxPaint.setColor(color);
            labelPaint.setColor(color);
            RectF box = detection.getBox();
            canvas.drawRect(box, boxPaint);
            String label = detection.getDisplayText();
            float width = textPaint.measureText(label);
            float top = Math.max(0f, box.top - 42f);
            canvas.drawRect(box.left, top, Math.min(output.getWidth(), box.left + width + 18f), top + 42f, labelPaint);
            canvas.drawText(label, box.left + 9f, top + 30f, textPaint);
        }
        return output;
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
