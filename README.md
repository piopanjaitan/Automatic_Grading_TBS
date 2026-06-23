# Grading TBS Computer Vision - Kelompok A

Repository ini berisi project Computer Vision untuk melakukan grading TBS (Tandan Buah Segar) menggunakan model YOLO, demo aplikasi Android, demo Streamlit, dan dashboard Node.js untuk menampung data upload dari mobile.

## Komponen Project

1. **Training YOLO**
   - Berisi notebook, konfigurasi dataset, dan catatan proses training model grading TBS.
   - Lokasi: `training-yolo/`

2. **Live Demo Mobile Android**
   - Berisi aplikasi Android untuk menjalankan demo grading TBS secara mobile.
   - Lokasi: `android-mobile-demo/`

3. **Live Demo Streamlit**
   - Berisi aplikasi demo berbasis Streamlit untuk menjalankan inference dan visualisasi hasil grading.
   - Lokasi: `streamlit-demo/`

4. **Dashboard Node.js**
   - Berisi dashboard/API untuk menerima, menyimpan, dan menampilkan data upload dari aplikasi mobile.
   - Lokasi: `dashboard-nodejs/`

## Struktur Folder

```text
Grading_TBS_CV_KEL_A_Github/
├── training-yolo/
├── android-mobile-demo/
├── streamlit-demo/
├── dashboard-nodejs/
├── README.md
└── .gitignore
```

## Catatan File Besar

Dataset, video, file hasil training, model berukuran besar, database lokal, dan file upload tidak disimpan langsung di GitHub. File besar sebaiknya disimpan di layanan terpisah seperti Google Drive, Roboflow, Kaggle, Hugging Face, GitHub Release, atau Git LFS.

Contoh file/folder yang tidak dimasukkan langsung ke repository:

- Dataset gambar dan label penuh
- File video
- File model besar seperti `.pt`, `.onnx`, dan `.tflite`
- Folder hasil training seperti `runs/`
- Folder upload dari aplikasi mobile atau dashboard
- File konfigurasi lokal seperti `.env`

## Status

README ini masih sementara dan akan diperbarui setelah masing-masing subproject dipindahkan ke folder yang sesuai.
# Automatic_Grading_TBS
