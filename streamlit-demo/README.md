# Grading TBS USTP - Streamlit CPU-Only

Folder ini berisi dashboard Streamlit Grading TBS USTP versi CPU-only untuk demo publik ringan, upload/snapshot image, dan audit hasil deteksi.

Live demo: <https://grading-ustp.my.id/>

## Setup Cepat

Jalankan dari folder `streamlit-demo/`:

```bash
python3 -m venv .venv-streamlit-cpu
source .venv-streamlit-cpu/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
./start_cpu_only.sh
```

Buka:

```text
http://127.0.0.1:8501
```

## Model

Model final tersedia di:

```text
Huggingface/models/best.pt
```

Jika ingin memakai model lain, isi path model manual di sidebar aplikasi.

## Default CPU-Only

- Device selalu `CPU`.
- `CUDA_VISIBLE_DEVICES` dikosongkan.
- Thread CPU default 2.
- Image size default 416.
- Max detection default 20.
- Save interval default 5 detik.
- Mode default adalah `Snapshot / upload fallback`.

Nilai default bisa diubah lewat environment variable sebelum menjalankan launcher.

```bash
export STREAMLIT_CPU_IMGSZ=512
export STREAMLIT_CPU_SAVE_INTERVAL=10
./start_cpu_only.sh
```

## Deployment

Dokumentasi deployment VPS dan arsitektur live demo tersedia di:

- `docs/migrasi_sumopod_cpu_only.md`
- `docs/dokumentasi_streamlit_live_dashboard.md`

History deteksi baru akan disimpan di:

```text
runs/streamlit_live_detection/
```

Folder runtime tersebut sengaja di-ignore dari Git.
