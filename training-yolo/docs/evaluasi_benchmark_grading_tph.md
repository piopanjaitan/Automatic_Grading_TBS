# Evaluasi dan Benchmark Model Grading TPH

Tanggal dokumen: 9 Juni 2026  
Notebook utama: `Grading_TPH-Copy1.ipynb`  
Model utama: `runs/detect/palm_oil_v13/weights/best.pt`  
Dataset: `palm-oil-1`

## Ringkasan

Model `palm_oil_v13` adalah baseline YOLOv8n untuk deteksi tingkat kematangan TBS sawit. Evaluasi validasi yang sudah tersimpan menunjukkan performa kuat pada dataset validasi: precision sekitar `0.94`, recall `0.936`, `mAP50` `0.976`, dan `mAP50-95` `0.785`.

Dataset lokal berisi 7.065 gambar: 4.962 train, 1.403 valid, dan 700 test. Label terdiri dari 4 kelas: `kurang masak`, `masak`, `mentah`, dan `terlalu masak`. Ada label kosong pada tiap split, yang dapat diperlakukan sebagai background images dan perlu tetap dipantau saat evaluasi.

Ringkasan dataset tersedia di `docs/assets/grading_tph_dataset_summary.csv`. File label validasi berisi 2.655 objek, sementara output validasi Ultralytics mencatat 2.654 instance karena ada satu duplicate label yang dihapus saat scanning.

## Hasil Evaluasi Validasi

| Kelas | Images | Instances | Precision | Recall | mAP50 | mAP50-95 |
|---|---:|---:|---:|---:|---:|---:|
| all | 1.403 | 2.654 | 0,940 | 0,936 | 0,976 | 0,785 |
| kurang masak | 346 | 757 | 0,944 | 0,920 | 0,979 | 0,772 |
| masak | 417 | 556 | 0,949 | 0,942 | 0,980 | 0,839 |
| mentah | 298 | 706 | 0,919 | 0,945 | 0,963 | 0,743 |
| terlalu masak | 383 | 635 | 0,946 | 0,938 | 0,980 | 0,785 |

Artefak visual yang sudah tersedia:

- `runs/detect/palm_oil_v13/results.png`
- `runs/detect/palm_oil_v13/confusion_matrix.png`
- `runs/detect/palm_oil_v13/confusion_matrix_normalized.png`
- `runs/detect/palm_oil_v13/BoxPR_curve.png`
- `runs/detect/palm_oil_v13/BoxF1_curve.png`

## Benchmark

| Artefak / run | Format | Ukuran / latency |
|---|---|---:|
| `best.pt` | PyTorch PT | 5,938 MB |
| `best.onnx` | ONNX | 11,783 MB |
| `best_float16.tflite` | TFLite FP16 | 5,898 MB |
| `best_float32.tflite` | TFLite FP32 | 11,714 MB |
| `best_int8.tflite` | TFLite INT8 | 3,194 MB |
| Validasi `val6` | Ultralytics GPU | 1,9 ms/image total, sekitar 526 FPS |
| Single test prediction | Ultralytics GPU | 13,5 ms/image total, sekitar 74 FPS |

Catatan benchmark runtime berasal dari output notebook yang sudah terekam. Untuk angka final yang lebih defensible, jalankan cell benchmark baru di notebook pada kernel yang sama dengan training.

## Improvement Prioritas

1. Jalankan evaluasi eksplisit pada `test` split, bukan hanya validasi.
2. Tambahkan benchmark berulang dengan warmup, median, p95, dan FPS pada beberapa `imgsz`.
3. Ekspor ulang INT8 TFLite dengan `data=palm-oil-1/data.yaml`; ekspor lama memakai default `coco8.yaml`, sehingga kalibrasi INT8 belum valid untuk klaim deployment sawit.
4. Tambahkan error analysis: false positive, false negative, dan kelas yang sering tertukar.
5. Simpan semua hasil evaluasi ke CSV agar laporan bisa direproduksi tanpa membaca output notebook manual.

## File Pendukung

- `docs/assets/grading_tph_eval_summary.csv`
- `docs/assets/grading_tph_class_metrics.csv`
- `docs/assets/grading_tph_dataset_summary.csv`
- `docs/assets/grading_tph_benchmark.csv`
