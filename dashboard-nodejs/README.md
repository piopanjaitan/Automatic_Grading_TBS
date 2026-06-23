# mGradingUSTP Dashboard Backend

Folder ini berisi dashboard web dan backend upload untuk data deteksi dari aplikasi Android mGradingUSTP.

Live dashboard: <https://dashboard-grading-ustp.my.id/>

Walaupun folder monorepo bernama `dashboard-nodejs`, implementasi saat ini memakai frontend static HTML/CSS/JavaScript dan backend Python standard-library di `server/server.py`.

## Struktur

- `public/index.html`: halaman dashboard.
- `public/styles.css`: gaya visual dashboard.
- `public/app.js`: filter tanggal/session/label, chart SVG, tabel, galeri, detail gambar, dan export CSV.
- `public/data/dashboard-data.js`: sample/fallback data agar dashboard tetap bisa dibuka tanpa API aktif.
- `server/server.py`: backend HTTP untuk static files, API dashboard, dan upload Android.
- `deploy/`: systemd service, konfigurasi Nginx, dan checklist VPS.
- `docs/`: dokumentasi migrasi VPS dan decision report.

## Jalankan Lokal

```bash
python3 server/server.py
```

Buka:

```text
http://127.0.0.1:8080
```

Health check:

```bash
curl http://127.0.0.1:8080/api/health
```

Data dashboard:

```bash
curl http://127.0.0.1:8080/api/dashboard-data
```

## API Upload Mobile

Endpoint:

```text
POST /api/detections
Content-Type: multipart/form-data
```

Field:

- `metadata`: JSON string berisi `device_id`, `local_id`, `tag_code`, `session_id`, `class_id`, `label`, `confidence`, bbox, `fingerprint`, `created_at`, `last_seen_at`, dan `seen_count`.
- `frame`: JPG full frame, opsional.
- `crop`: JPG crop objek, opsional.
- `annotated`: JPG beranotasi, opsional.

Backend menyimpan metadata ke `server/mgrading_dashboard.db` dan gambar upload ke `server/uploads/YYYYMMDD/`.

## Runtime Data

File runtime berikut tidak disimpan di GitHub:

- `server/mgrading_dashboard.db`
- `server/uploads/`
- `public/media/`
- `.env`, log, cache, dan local virtualenv

Dokumentasi deploy tersedia di `docs/Migrasi_VPS_Streamlit_CPU_Only.md`.
