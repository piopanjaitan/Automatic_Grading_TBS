package com.ustp.mgrading.data.ml;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class TfliteDetector implements AutoCloseable {
    public static final String MODEL_ASSET = "grading_tph_int8.tflite";
    private static final String LABEL_ASSET = "labels.txt";
    private static final int DEFAULT_INPUT_SIZE = 640;

    private final List<String> labels;
    private Interpreter interpreter;
    private int inputWidth = DEFAULT_INPUT_SIZE;
    private int inputHeight = DEFAULT_INPUT_SIZE;
    private DataType inputType = DataType.FLOAT32;
    private String status = "Model belum dimuat";

    public TfliteDetector(AssetManager assetManager) {
        labels = loadLabels(assetManager);
        try {
            if (!assetExists(assetManager, MODEL_ASSET)) {
                status = "Model TFLite belum tersedia: " + MODEL_ASSET;
                return;
            }
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
            interpreter = new Interpreter(loadModel(assetManager, MODEL_ASSET), options);
            Tensor inputTensor = interpreter.getInputTensor(0);
            int[] shape = inputTensor.shape();
            if (shape.length == 4) {
                inputHeight = shape[1];
                inputWidth = shape[2];
            }
            inputType = inputTensor.dataType();
            status = String.format(Locale.US, "Model siap (%dx%d, %s)", inputWidth, inputHeight, inputType.name());
        } catch (Exception e) {
            status = "Gagal memuat model: " + e.getMessage();
            if (interpreter != null) {
                interpreter.close();
                interpreter = null;
            }
        }
    }

    public boolean isReady() {
        return interpreter != null;
    }

    public String getStatus() {
        return status;
    }

    public List<DetectionResult> detect(Bitmap source, float confidenceThreshold, float iouThreshold) {
        if (interpreter == null || source == null || source.getWidth() == 0 || source.getHeight() == 0) {
            return Collections.emptyList();
        }
        PreprocessedImage image = preprocess(source);
        Object output = allocateOutput();
        interpreter.run(image.input, output);
        List<DetectionResult> raw = decodeOutput(output, source.getWidth(), source.getHeight(), image.scale, image.padX, image.padY, confidenceThreshold);
        return nonMaximumSuppression(raw, iouThreshold, 100);
    }

    private Object allocateOutput() {
        Tensor outputTensor = interpreter.getOutputTensor(0);
        int[] shape = outputTensor.shape();
        if (outputTensor.dataType() != DataType.FLOAT32) {
            throw new IllegalStateException("Output TFLite harus FLOAT32. Tipe saat ini: " + outputTensor.dataType());
        }
        if (shape.length == 3) {
            return new float[shape[0]][shape[1]][shape[2]];
        }
        if (shape.length == 2) {
            return new float[shape[0]][shape[1]];
        }
        throw new IllegalStateException("Shape output belum didukung: " + java.util.Arrays.toString(shape));
    }

    private PreprocessedImage preprocess(Bitmap source) {
        float scale = Math.min(inputWidth / (float) source.getWidth(), inputHeight / (float) source.getHeight());
        int resizedWidth = Math.round(source.getWidth() * scale);
        int resizedHeight = Math.round(source.getHeight() * scale);
        float padX = (inputWidth - resizedWidth) / 2f;
        float padY = (inputHeight - resizedHeight) / 2f;

        Bitmap canvasBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        canvas.drawColor(Color.rgb(114, 114, 114));
        RectF dst = new RectF(padX, padY, padX + resizedWidth, padY + resizedHeight);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, dst, paint);

        ByteBuffer input;
        if (inputType == DataType.FLOAT32) {
            input = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * 4);
            input.order(ByteOrder.nativeOrder());
            int[] pixels = new int[inputWidth * inputHeight];
            canvasBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
            for (int pixel : pixels) {
                input.putFloat(((pixel >> 16) & 0xFF) / 255f);
                input.putFloat(((pixel >> 8) & 0xFF) / 255f);
                input.putFloat((pixel & 0xFF) / 255f);
            }
        } else if (inputType == DataType.UINT8) {
            input = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3);
            input.order(ByteOrder.nativeOrder());
            int[] pixels = new int[inputWidth * inputHeight];
            canvasBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight);
            for (int pixel : pixels) {
                input.put((byte) ((pixel >> 16) & 0xFF));
                input.put((byte) ((pixel >> 8) & 0xFF));
                input.put((byte) (pixel & 0xFF));
            }
        } else {
            throw new IllegalStateException("Input TFLite belum didukung: " + inputType);
        }
        input.rewind();
        canvasBitmap.recycle();
        return new PreprocessedImage(input, scale, padX, padY);
    }

    private List<DetectionResult> decodeOutput(Object output, int originalWidth, int originalHeight, float scale, float padX, float padY, float threshold) {
        List<float[]> predictions = flattenPredictions(output);
        List<DetectionResult> results = new ArrayList<>();
        for (float[] prediction : predictions) {
            if (prediction.length < 5) {
                continue;
            }
            int classStart = 4;
            float objectness = 1f;
            if (prediction.length == labels.size() + 5) {
                objectness = prediction[4];
                classStart = 5;
            }

            int bestClass = -1;
            float bestClassScore = 0f;
            int classCount = Math.min(labels.size(), prediction.length - classStart);
            for (int i = 0; i < classCount; i++) {
                if (prediction[classStart + i] > bestClassScore) {
                    bestClassScore = prediction[classStart + i];
                    bestClass = i;
                }
            }
            float confidence = objectness * bestClassScore;
            if (bestClass < 0 || confidence < threshold) {
                continue;
            }

            float cx = prediction[0];
            float cy = prediction[1];
            float w = prediction[2];
            float h = prediction[3];
            if (cx <= 1f && cy <= 1f && w <= 1f && h <= 1f) {
                cx *= inputWidth;
                cy *= inputHeight;
                w *= inputWidth;
                h *= inputHeight;
            }
            float left = ((cx - w / 2f) - padX) / scale;
            float top = ((cy - h / 2f) - padY) / scale;
            float right = ((cx + w / 2f) - padX) / scale;
            float bottom = ((cy + h / 2f) - padY) / scale;
            RectF box = new RectF(
                    clamp(left, 0, originalWidth),
                    clamp(top, 0, originalHeight),
                    clamp(right, 0, originalWidth),
                    clamp(bottom, 0, originalHeight)
            );
            if (box.width() < 2f || box.height() < 2f) {
                continue;
            }
            results.add(new DetectionResult(box, bestClass, labels.get(bestClass), confidence));
        }
        return results;
    }

    private List<float[]> flattenPredictions(Object output) {
        List<float[]> predictions = new ArrayList<>();
        if (output instanceof float[][][]) {
            float[][] matrix = ((float[][][]) output)[0];
            if (matrix.length == 0) {
                return predictions;
            }
            int rows = matrix.length;
            int cols = matrix[0].length;
            if (rows <= 16 && cols > rows) {
                for (int c = 0; c < cols; c++) {
                    float[] prediction = new float[rows];
                    for (int r = 0; r < rows; r++) {
                        prediction[r] = matrix[r][c];
                    }
                    predictions.add(prediction);
                }
            } else {
                Collections.addAll(predictions, matrix);
            }
        } else if (output instanceof float[][]) {
            Collections.addAll(predictions, (float[][]) output);
        }
        return predictions;
    }

    private List<DetectionResult> nonMaximumSuppression(List<DetectionResult> results, float iouThreshold, int maxResults) {
        results.sort(Comparator.comparing(DetectionResult::getConfidence).reversed());
        List<DetectionResult> selected = new ArrayList<>();
        boolean[] removed = new boolean[results.size()];
        for (int i = 0; i < results.size(); i++) {
            if (removed[i]) {
                continue;
            }
            DetectionResult current = results.get(i);
            selected.add(current);
            if (selected.size() >= maxResults) {
                break;
            }
            for (int j = i + 1; j < results.size(); j++) {
                if (!removed[j] && current.getClassId() == results.get(j).getClassId()
                        && iou(current.getBox(), results.get(j).getBox()) > iouThreshold) {
                    removed[j] = true;
                }
            }
        }
        return selected;
    }

    private float iou(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float union = a.width() * a.height() + b.width() * b.height() - intersection;
        return union <= 0f ? 0f : intersection / union;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean assetExists(AssetManager assetManager, String name) throws IOException {
        String[] assets = assetManager.list("");
        if (assets == null) {
            return false;
        }
        for (String asset : assets) {
            if (name.equals(asset)) {
                return true;
            }
        }
        return false;
    }

    private static MappedByteBuffer loadModel(AssetManager assetManager, String assetName) throws IOException {
        AssetFileDescriptor descriptor = assetManager.openFd(assetName);
        FileInputStream inputStream = new FileInputStream(descriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, descriptor.getStartOffset(), descriptor.getDeclaredLength());
    }

    private static List<String> loadLabels(AssetManager assetManager) {
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(assetManager.open(LABEL_ASSET)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    result.add(line.trim());
                }
            }
        } catch (IOException ignored) {
            result.add("kurang masak");
            result.add("masak");
            result.add("mentah");
            result.add("terlalu masak");
        }
        return result;
    }

    @Override
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }

    private static class PreprocessedImage {
        final ByteBuffer input;
        final float scale;
        final float padX;
        final float padY;

        PreprocessedImage(ByteBuffer input, float scale, float padX, float padY) {
            this.input = input;
            this.scale = scale;
            this.padX = padX;
            this.padY = padY;
        }
    }
}
