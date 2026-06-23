package com.ustp.mgrading.ui.detection;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.common.util.concurrent.ListenableFuture;
import com.ustp.mgrading.databinding.ActivityDetectionBinding;
import com.ustp.mgrading.util.ImageUtils;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetectionActivity extends AppCompatActivity {
    private ActivityDetectionBinding binding;
    private DetectionViewModel viewModel;
    private GradingTagAdapter tagAdapter;
    private ExecutorService cameraExecutor;
    private ImageCapture imageCapture;
    private long lastAnalyzedMs = 0L;
    private volatile boolean photoModeActive = false;
    private volatile boolean liveDetectionActive = false;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCameraMode();
                } else {
                    binding.statusText.setText("Izin kamera dibutuhkan untuk mode real-time.");
                }
            });

    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        loadGalleryImage(uri);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDetectionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        cameraExecutor = Executors.newSingleThreadExecutor();
        viewModel = new ViewModelProvider(this).get(DetectionViewModel.class);
        tagAdapter = new GradingTagAdapter();

        setupSafeAreaInsets();
        setupTagList();
        bindViewModel();
        bindActions();
        ensureCameraPermission();
    }

    private void setupSafeAreaInsets() {
        int actionLeft = binding.actionBar.getPaddingLeft();
        int actionTop = binding.actionBar.getPaddingTop();
        int actionRight = binding.actionBar.getPaddingRight();
        int actionBottom = binding.actionBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootView, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.actionBar.setPadding(actionLeft, actionTop, actionRight, actionBottom + systemBars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(binding.rootView);
    }

    private void setupTagList() {
        binding.tagRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.tagRecyclerView.setAdapter(tagAdapter);
    }

    private void bindViewModel() {
        viewModel.getStatus().observe(this, binding.statusText::setText);
        viewModel.getSummary().observe(this, binding.resultText::setText);
        viewModel.getRecentTags().observe(this, tags -> {
            tagAdapter.submit(tags);
            binding.tagRecyclerView.setVisibility(tags == null || tags.isEmpty() ? View.GONE : View.VISIBLE);
        });
        viewModel.getDetections().observe(this, detections -> {
            DetectionViewModel.FrameInfo info = viewModel.getFrameInfo().getValue();
            if (info != null) {
                binding.overlayView.setDetections(detections, info.width, info.height);
            }
        });
        viewModel.getFrameInfo().observe(this, info -> {
            if (info != null && viewModel.getDetections().getValue() != null) {
                binding.overlayView.setDetections(viewModel.getDetections().getValue(), info.width, info.height);
            }
        });
        viewModel.getLiveSessionActive().observe(this, active -> {
            boolean isActive = active != null && active;
            liveDetectionActive = isActive;
            binding.cameraButton.setEnabled(!isActive);
            binding.stopButton.setEnabled(isActive);
            binding.cameraButton.setText(isActive ? "Live" : "Mulai");
            binding.detectionStateText.setText(isActive ? "Deteksi aktif" : "Deteksi tidak aktif");
            binding.detectionStateText.setTextColor(ContextCompat.getColor(this,
                    isActive ? android.R.color.white : com.ustp.mgrading.R.color.ustp_green_dark));
            binding.detectionStateText.setBackgroundColor(ContextCompat.getColor(this,
                    isActive ? com.ustp.mgrading.R.color.ustp_green_dark : android.R.color.white));
        });
    }

    private void bindActions() {
        binding.cameraButton.setOnClickListener(v -> {
            startCameraMode();
            viewModel.startLiveSession();
        });
        binding.stopButton.setOnClickListener(v -> viewModel.stopLiveSession());
        binding.captureButton.setOnClickListener(v -> capturePhoto());
        binding.galleryButton.setOnClickListener(v -> openGallery());
        binding.dataButton.setOnClickListener(v -> startActivity(new Intent(this, SavedTagsActivity.class)));
    }

    private void ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraMode();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCameraMode() {
        photoModeActive = false;
        binding.photoView.setVisibility(View.GONE);
        binding.previewView.setVisibility(View.VISIBLE);
        viewModel.clearResults();

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis);
            } catch (Exception e) {
                binding.statusText.setText("Gagal membuka kamera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        if (photoModeActive) {
            image.close();
            return;
        }
        if (!liveDetectionActive) {
            image.close();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAnalyzedMs < 180) {
            image.close();
            return;
        }
        lastAnalyzedMs = now;

        Bitmap bitmap = null;
        try {
            bitmap = ImageUtils.imageProxyToBitmap(image);
            if (viewModel.isDetectorReady()) {
                viewModel.detectFrame(bitmap);
                bitmap = null;
            }
        } catch (Exception ignored) {
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            image.close();
        }
    }

    private void capturePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap bitmap = null;
                try {
                    bitmap = ImageUtils.imageProxyToBitmap(image);
                    Bitmap uiBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    runOnUiThread(() -> showPhotoMode(uiBitmap));
                    viewModel.detectPhoto(bitmap);
                    bitmap = null;
                } catch (Exception e) {
                    runOnUiThread(() -> binding.statusText.setText("Gagal memproses foto: " + e.getMessage()));
                } finally {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    image.close();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> binding.statusText.setText("Gagal capture: " + exception.getMessage()));
            }
        });
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        galleryLauncher.launch(intent);
    }

    private void loadGalleryImage(Uri uri) {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            Bitmap mutable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            showPhotoMode(mutable);
            viewModel.detectPhoto(bitmap);
        } catch (IOException e) {
            binding.statusText.setText("Gagal membuka gambar: " + e.getMessage());
        }
    }

    private void showPhotoMode(Bitmap bitmap) {
        photoModeActive = true;
        binding.previewView.setVisibility(View.GONE);
        binding.photoView.setVisibility(View.VISIBLE);
        binding.photoView.setImageBitmap(bitmap);
        binding.overlayView.clear();
    }

    @Override
    protected void onDestroy() {
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
        }
        super.onDestroy();
    }
}
