# Migrasi Dashboard Lama mGradingUSTP ke VPS Sumapod

Dokumen ini menjelaskan cara deploy dashboard lama mGradingUSTP dari folder `Streamlit_CPU_Only`. Nama folder tetap dipertahankan, tetapi implementasi dashboard tidak lagi memakai Streamlit. Isi dashboard disamakan dengan dashboard lama di `/mnt/pioneer/Project_mGradingUSTP_Dashboard/`: static HTML/CSS/JS di `public/` dan satu backend Python standard-library di `server/server.py`.

## Target Akhir

- Dashboard publik: `https://dashboard-grading-ustp.my.id`
- Endpoint upload Android: `https://dashboard-grading-ustp.my.id/api/detections`
- URL yang diisi di aplikasi Android: `https://dashboard-grading-ustp.my.id`
- Endpoint health: `https://dashboard-grading-ustp.my.id/api/health`
- Metadata SQLite: `/opt/mgrading-dashboard/server/mgrading_dashboard.db`
- Gambar upload: `/opt/mgrading-dashboard/server/uploads/YYYYMMDD/{frame,crop,annotated}/`

## Struktur Folder Deploy

```text
Streamlit_CPU_Only/
  public/
    index.html
    app.js
    styles.css
    data/dashboard-data.js
  server/
    server.py
    mgrading_dashboard.db
    uploads/
  deploy/
    mgrading-dashboard.service
    nginx-dashboard-grading-ustp.my.id.conf
    VPS_Ubuntu_24_04_Checklist.md
  requirements.txt
```

## Arsitektur

Satu proses Python menjalankan dashboard lama:

- Serve halaman static dari `public/`
- Serve upload image dari `/server/uploads/...`
- Serve API dashboard dari `/api/dashboard-data`
- Terima upload Android di `/api/detections`

Service Python berjalan lokal di VPS:

```text
127.0.0.1:8080
```

Nginx menjadi reverse proxy publik:

```text
https://dashboard-grading-ustp.my.id/ -> http://127.0.0.1:8080/
```

Tidak ada service Streamlit, Uvicorn, atau FastAPI terpisah pada versi ini.

## Endpoint Backend

### GET `/api/health`

Mengembalikan status service. Response sukses minimal berisi:

```json
{"status":"ok","project":"mGradingUSTP"}
```

### GET `/api/dashboard-data`

Mengembalikan payload dashboard:

- `summary`
- `labels`
- `sessions`
- `dates`
- `confidenceBuckets`
- `records`

Frontend lama otomatis mencoba membaca endpoint ini dengan `fetch("/api/dashboard-data")`. Jika endpoint tidak tersedia, frontend masih punya fallback static dari `public/data/dashboard-data.js`.

### POST `/api/detections`

Android mengirim multipart form:

- `metadata`: JSON berisi `device_id`, `local_id`, `tag_code`, `session_id`, `class_id`, `label`, `confidence`, bbox, `fingerprint`, `created_at`, `last_seen_at`, dan `seen_count`
- `frame`: JPEG opsional
- `crop`: JPEG opsional
- `annotated`: JPEG opsional

Backend memakai unique key `device_id + local_id`. Upload ulang data yang sama akan update record lama, bukan insert duplikat.

## Persiapan DNS

1. Arahkan A record `dashboard-grading-ustp.my.id` ke IP public VPS Sumapod.
2. Jangan gunakan AAAA record jika VPS tidak punya IPv6.
3. Validasi:

```bash
dig +short dashboard-grading-ustp.my.id A
```

Output harus IP public VPS.

## Persiapan VPS Ubuntu 24.04

```bash
sudo apt update
sudo apt install -y python3-venv python3-pip nginx certbot python3-certbot-nginx
```

Pastikan port `80` dan `443` terbuka di firewall/security group.

Jika di VPS sudah ada domain lain, tidak masalah selama setiap domain punya `server_name` sendiri dan proxy ke port service yang benar. Untuk dashboard ini, `dashboard-grading-ustp.my.id` harus proxy ke `127.0.0.1:8080`. Jika domain dashboard dan domain lama sama-sama diarahkan ke `127.0.0.1:8501`, isi keduanya akan terlihat sama.

## Copy Aplikasi ke VPS

```bash
sudo mkdir -p /opt/mgrading-dashboard
sudo rsync -av Streamlit_CPU_Only/ /opt/mgrading-dashboard/
sudo chown -R www-data:www-data /opt/mgrading-dashboard
```

## Setup Python

Dashboard lama memakai Python standard library. Virtualenv tetap dibuat agar path service konsisten.

```bash
cd /opt/mgrading-dashboard
sudo -u www-data python3 -m venv .venv
sudo -H -u www-data .venv/bin/pip install --upgrade pip
sudo -H -u www-data .venv/bin/pip install -r requirements.txt
sudo -u www-data mkdir -p server/uploads
```

Database akan dibuat otomatis saat service pertama kali start jika belum ada.

Jika `pip` menampilkan warning cache seperti `/var/www/.cache/pip is not writable`, itu bukan error instalasi. Karena `requirements.txt` versi ini tidak membutuhkan dependency eksternal, warning tersebut bisa diabaikan selama command selesai tanpa pesan `ERROR`.

## Setup systemd

```bash
sudo cp deploy/mgrading-dashboard.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mgrading-dashboard
sudo systemctl status mgrading-dashboard
```

Lihat log:

```bash
journalctl -u mgrading-dashboard -f
```

## Setup Nginx

```bash
sudo cp deploy/nginx-dashboard-grading-ustp.my.id.conf /etc/nginx/sites-available/dashboard-grading-ustp.my.id
sudo ln -s /etc/nginx/sites-available/dashboard-grading-ustp.my.id /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

Konfigurasi Nginx dashboard cukup proxy semua route ke `127.0.0.1:8080`. Domain lama `grading-ustp.my.id` boleh tetap memakai service/port lain.

## Setup HTTPS Certbot

Jalankan setelah DNS sehat:

```bash
sudo certbot --nginx -d dashboard-grading-ustp.my.id
sudo certbot renew --dry-run
```

Jika Certbot gagal dengan `SERVFAIL looking up A/AAAA`, perbaiki DNS dulu. Pastikan A record domain mengarah ke IP public VPS, hapus AAAA record jika tidak memakai IPv6, lalu coba lagi setelah propagasi DNS sehat.

## Setting Android

Di aplikasi Android, isi URL dashboard:

```text
https://dashboard-grading-ustp.my.id
```

Lalu tekan `Sync Sekarang` dan pastikan status upload berubah menjadi `SYNCED`.

Jangan isi kolom URL Android dengan `https://dashboard-grading-ustp.my.id/api/detections`. Aplikasi Android sudah menambahkan path `/api/detections` sendiri. Jika endpoint lengkap dimasukkan ke setting, request akan menjadi `/api/detections/api/detections` dan server akan membalas `404`.

## Validasi

Validasi service lokal:

```bash
curl -s http://127.0.0.1:8080/api/health
curl -s http://127.0.0.1:8080/ | head
```

Validasi domain:

```bash
curl -s https://dashboard-grading-ustp.my.id/api/health
curl -s https://dashboard-grading-ustp.my.id/ | head
```

Validasi upload tanpa gambar:

```bash
curl -s -X POST https://dashboard-grading-ustp.my.id/api/detections \
  -F 'metadata={
    "device_id":"test-device",
    "local_id":999999,
    "tag_code":"TEST-999999",
    "session_id":"TEST-SESSION",
    "class_id":1,
    "label":"test",
    "confidence":0.91,
    "bbox_left":1,
    "bbox_top":2,
    "bbox_right":3,
    "bbox_bottom":4,
    "fingerprint":"testfingerprint",
    "created_at":1782110000000,
    "last_seen_at":1782110000000,
    "seen_count":1
  }'
```

Response sukses:

```json
{"status":"synced","remote_id":791,"frame_url":null,"crop_url":null,"annotated_url":null}
```

`frame_url`, `crop_url`, dan `annotated_url` bernilai `null` jika test `curl` tidak mengirim file gambar. Itu normal.

Validasi dashboard:

- Tampilan sama dengan dashboard lama.
- Filter tanggal, session, label, confidence, dan cari tag bekerja.
- KPI, chart SVG, date cards, gallery, tabel, detail dialog, dan export CSV bekerja.
- Gambar crop/frame/annotated tampil dari `/server/uploads/...`.

## Troubleshooting Cepat

### Browser membuka domain dashboard tetapi isinya sama dengan domain lama

Penyebab paling umum: dua `server_name` Nginx berbeda sama-sama proxy ke port yang sama. Cek:

```bash
sudo nginx -T | grep -n "server_name"
sudo nginx -T | sed -n '180,270p'
```

Untuk domain dashboard, pastikan:

```nginx
server_name dashboard-grading-ustp.my.id;
location / {
    proxy_pass http://127.0.0.1:8080;
}
```

### Android gagal sync dan log berisi `/api/detections/api/detections`

Penyebab: URL di setting Android diisi endpoint lengkap. Ganti menjadi base URL saja:

```text
https://dashboard-grading-ustp.my.id
```

Setelah itu tekan `Sync Sekarang`.

### Log berisi request `/_stcore/host-config` atau `/_stcore/health`

Itu request lama milik Streamlit dari browser/cache atau service lama. Versi dashboard ini tidak memakai Streamlit, jadi `404` untuk `/_stcore/*` tidak mempengaruhi upload Android.

Jika service Streamlit lama masih ada, hentikan:

```bash
sudo systemctl disable --now mgrading-streamlit 2>/dev/null
```

Lalu buka dashboard dengan hard refresh atau incognito.

### Service tidak menjawab di port 8080

Cek status dan log:

```bash
sudo systemctl status mgrading-dashboard
sudo journalctl -u mgrading-dashboard -n 100 --no-pager
curl -s http://127.0.0.1:8080/api/health
```

Jika file service belum ada, gunakan file yang benar:

```bash
sudo cp deploy/mgrading-dashboard.service /etc/systemd/system/
```

File `mgrading-api.service` dan `mgrading-streamlit.service` adalah template lama dan tidak dipakai lagi pada versi ini.

## Hardening Lanjutan

Tahap ini belum memakai login. Jika dashboard dibuka publik, pertimbangkan:

- Basic auth di Nginx.
- IP allowlist.
- Token/API key untuk upload Android.
- Backup periodik `server/mgrading_dashboard.db` dan `server/uploads/`.
