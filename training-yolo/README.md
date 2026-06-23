# Training YOLO Grading TBS

Folder ini berisi file training dan evaluasi model YOLO untuk grading TBS.

## Isi Folder

- `notebooks/Grading_TPH-Copy1.ipynb`: notebook utama training dan evaluasi.
- `notebooks/Grading_TPH_EDA_and_Hyperparameter_tunning.ipynb`: notebook EDA dan tuning hyperparameter.
- `dataset/data.yaml`: konfigurasi dataset YOLO.
- `dataset/README.dataset.txt` dan `dataset/README.roboflow.txt`: metadata dataset Roboflow.
- `docs/`: dokumentasi evaluasi, CSV metrik, dan artifact visual training yang dipilih.

## Catatan Dataset

Folder dataset penuh `train/`, `valid/`, dan `test/` tidak dimasukkan ke repository karena ukurannya besar. Gunakan metadata di `dataset/` sebagai referensi untuk mengambil ulang dataset dari sumber eksternal.

## Model

Model final untuk demo disimpan di:

```text
../streamlit-demo/Huggingface/models/best.pt
```

Artifact training lengkap seperti `runs/`, checkpoint lain, ONNX, TFLite, dan calibration data tidak dimasukkan ke repository.
