# Migrasi Grading TBS USTP ke Sumopod CPU-Only

Dokumen ini menjelaskan deployment **Grading TBS USTP** ke VPS Sumopod 2 vCPU / 4 GB RAM dengan Ubuntu 24.04. Semua komponen berjalan di VPS: Streamlit, YOLO inference CPU, penyimpanan hasil deteksi, dan akses publik via Nginx/HTTPS.

## Ringkasan Arsitektur

```text
User Browser
    |
    | HTTPS https://grading-ustp.my.id
    v
Nginx VPS Sumopod :80/:443
    |
    | reverse proxy
    v
Streamlit Grading TBS USTP 127.0.0.1:8501
    |
    | YOLO inference CPU
    v
best.pt + runs/streamlit_live_detection/
```

## Database Aplikasi

Aplikasi ini tidak memakai PostgreSQL/MySQL. Database/history deteksi bersifat **file-based** dan tersimpan di:

```text
runs/streamlit_live_detection/
```

Isi folder ini meliputi:

- `active_session.txt`
- `session_YYYYmmdd_HHMMSS/detections.csv`
- gambar raw `det_00001_raw.jpg`
- gambar annotation `det_00001_annotated.jpg`
- annotation JSON
- YOLO labels TXT

Jika folder ini tidak ikut dicopy ke VPS, dashboard tetap online tetapi riwayat deteksi lama tidak muncul.

## Kesesuaian VPS 2 vCPU / 4 GB RAM

| Kebutuhan | Status | Catatan |
|---|---:|---|
| Upload gambar | Cocok | Mode paling stabil untuk demo publik. |
| Snapshot kamera browser | Cocok | Browser mengirim satu frame, server memproses CPU. |
| Server/IP camera continuous | Terbatas | Gunakan interval 5-10 detik agar CPU tidak penuh. |
| WebRTC live camera publik | Tidak direkomendasikan | Butuh TURN server dan lebih berat. |
| Training YOLO | Tidak cocok | Training tetap di komputer/GPU lokal. |
| Multi-user ramai | Tidak cocok | VPS 4 GB lebih cocok untuk 1 user aktif. |

## Struktur Minimal di VPS

```text
Grading_TBS_CV_KEL_A_Github/
├── streamlit-demo/
│   ├── app.py
│   ├── requirements.txt
│   ├── README.md
│   ├── start_cpu_only.sh
│   ├── migrate_detection_data.sh
│   ├── grading-tbs-streamlit.service
│   └── nginx_grading_tbs.conf
├── Huggingface/
│   └── models/
│       └── best.pt
└── runs/
    └── streamlit_live_detection/   # opsional jika membawa history lama
```

Tidak perlu copy dataset training, notebook, virtual environment, atau seluruh `runs/detect` ke VPS kecil.

## Default CPU-Only

| Parameter | Default | Alasan |
|---|---:|---|
| Device | CPU | VPS 2 vCPU tidak diasumsikan punya GPU. |
| CPU threads | 2 | Sesuai jumlah vCPU. |
| Image size | 416 | Lebih ringan dari 512/640. |
| Max detections | 20 | Mengurangi post-processing. |
| Save interval | 5 detik | Mengurangi beban disk dan CPU. |
| Mode input default | Snapshot/upload | Paling stabil untuk VPS kecil. |

## Light Mode Hijau Sawit

Dashboard memakai light mode dengan identitas visual sawit:

| Elemen | Warna |
|---|---|
| Background utama | Hijau sawit lembut `#eaf4e6` |
| Sidebar | Hijau muda `#dff0dc` |
| Hero | Gradient hijau tua dan hijau sawit |
| Panel/tabel | Putih agar data mudah dibaca |
| Tombol primary | Hijau sawit `#2f7d57` |
| Teks utama | Gelap `#18232a` |

Tujuannya agar tombol, tabel, dan widget Streamlit tetap terbaca jelas di browser desktop dan mobile.

## 1. Buat VPS Sumopod

Pilih konfigurasi:

- Provider: Tencent
- Region: Jakarta
- OS: Ubuntu 24.04
- CPU: 2 vCPU
- RAM: 4 GB
- Storage: minimal 40 GB

Buka port firewall/security group:

- `22` untuk SSH
- `80` untuk HTTP
- `443` untuk HTTPS

## 2. Install Dependency Server

```bash
sudo apt update
sudo apt install -y python3-venv python3-pip git ffmpeg libgl1 libglib2.0-0 nginx rsync
```

Opsional untuk monitoring:

```bash
sudo apt install -y htop tmux unzip
```

## 3. Upload Project Minimal

Dari komputer lokal:

```bash
ssh ubuntu@IP_VPS 'mkdir -p ~/Grading_TBS_CV_KEL_A_Github/Huggingface/models ~/Grading_TBS_CV_KEL_A_Github/runs'
scp -r streamlit-demo ubuntu@IP_VPS:~/Grading_TBS_CV_KEL_A_Github/
scp Huggingface/models/best.pt ubuntu@IP_VPS:~/Grading_TBS_CV_KEL_A_Github/Huggingface/models/best.pt
```

## 4. Setup Python Environment

Di VPS:

```bash
cd ~/Grading_TBS_CV_KEL_A_Github
python3 -m venv .venv-streamlit-cpu
source .venv-streamlit-cpu/bin/activate
pip install --upgrade pip
pip install -r streamlit-demo/requirements.txt
```

Validasi import:

```bash
python -c "import streamlit, ultralytics, cv2, torch; print('imports ok')"
```

## 5. Jalankan Manual

```bash
./streamlit-demo/start_cpu_only.sh
```

Buka sementara:

```text
http://IP_VPS:8501
```

Untuk production, gunakan Nginx reverse proxy dan jangan expose port 8501 langsung.

## 6. Jalankan Sebagai Service systemd

Edit `streamlit-demo/grading-tbs-streamlit.service` jika user/path berbeda dari `ubuntu` dan `/home/ubuntu/Grading_TBS_CV_KEL_A_Github`.

Install service:

```bash
sudo cp streamlit-demo/grading-tbs-streamlit.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable grading-tbs-streamlit
sudo systemctl start grading-tbs-streamlit
```

Cek status:

```bash
sudo systemctl status grading-tbs-streamlit
journalctl -u grading-tbs-streamlit -f
```

## 7. Setup Nginx Domain

```bash
sudo cp streamlit-demo/nginx_grading_tbs.conf /etc/nginx/sites-available/grading-tbs
sudo nano /etc/nginx/sites-available/grading-tbs
```

Gunakan domain:

```nginx
server_name grading-ustp.my.id www.grading-ustp.my.id;
```

Aktifkan:

```bash
sudo ln -sf /etc/nginx/sites-available/grading-tbs /etc/nginx/sites-enabled/grading-tbs
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

## 8. HTTPS Dengan Certbot

Pastikan DNS `A record` domain mengarah ke IP VPS, lalu:

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d grading-ustp.my.id
```

Jika `www` juga aktif:

```bash
sudo certbot --nginx -d grading-ustp.my.id -d www.grading-ustp.my.id
```

Akses:

```text
https://grading-ustp.my.id
```

## 9. Migrasi Data Deteksi Lama

Dari komputer lokal, gunakan script:

```bash
cd /home/ridwan/Documents/Final_Project/Grading_TPH
./streamlit-demo/migrate_detection_data.sh ubuntu@IP_VPS /home/ubuntu/Grading_TBS_CV_KEL_A_Github
```

Atau manual:

```bash
rsync -avh --progress \
  runs/streamlit_live_detection/ \
  ubuntu@IP_VPS:/home/ubuntu/Grading_TBS_CV_KEL_A_Github/runs/streamlit_live_detection/
```

Di VPS:

```bash
sudo chown -R ubuntu:ubuntu /home/ubuntu/Grading_TBS_CV_KEL_A_Github/runs/streamlit_live_detection
sudo systemctl restart grading-tbs-streamlit
```

Validasi:

```bash
find /home/ubuntu/Grading_TBS_CV_KEL_A_Github/runs/streamlit_live_detection -name detections.csv -print
journalctl -u grading-tbs-streamlit -n 50
```

Buka sidebar dashboard dan cek dropdown `Active session`.

## 10. Operasional Harian

| Aktivitas | Command |
|---|---|
| Restart app | `sudo systemctl restart grading-tbs-streamlit` |
| Lihat log | `journalctl -u grading-tbs-streamlit -f` |
| Cek CPU/RAM | `htop` |
| Cek storage | `df -h` |
| Cek file deteksi | `ls -lh runs/streamlit_live_detection/` |

## 11. Tuning Performa VPS Kecil

Jika inference lambat atau CPU 100%, gunakan nilai lebih ringan:

```bash
export STREAMLIT_CPU_IMGSZ=320
export STREAMLIT_CPU_MAX_DET=10
export STREAMLIT_CPU_SAVE_INTERVAL=10
./streamlit-demo/start_cpu_only.sh
```

Untuk systemd, ubah environment di service:

```text
Environment=STREAMLIT_CPU_IMGSZ=320
Environment=STREAMLIT_CPU_MAX_DET=10
Environment=STREAMLIT_CPU_SAVE_INTERVAL=10.0
```

Lalu:

```bash
sudo systemctl daemon-reload
sudo systemctl restart grading-tbs-streamlit
```

## 12. Troubleshooting

| Masalah | Penyebab umum | Solusi |
|---|---|---|
| Model tidak ditemukan | `best.pt` belum dicopy | Copy ke `Huggingface/models/best.pt` atau isi path manual. |
| App lambat | Image size terlalu besar | Turunkan ke 320/416. |
| RAM penuh | Dependency/model terlalu berat atau banyak proses | Restart service, gunakan 1 user, cek `htop`. |
| WebRTC tidak jalan | HTTPS/TURN belum siap | Pakai snapshot/upload fallback. |
| Port tidak bisa dibuka | Firewall/security group belum dibuka | Buka port 80/443 atau cek Nginx. |
| History hilang | Folder `runs/streamlit_live_detection` tidak ikut dicopy | Jalankan `migrate_detection_data.sh`. |
| Tombol/tabel gelap | CSS lama/cache browser | Hard refresh browser dan restart service. |

## Checklist Go-Live

- [ ] VPS Ubuntu 24.04 aktif.
- [ ] Port 80/443 terbuka.
- [ ] Domain `grading-ustp.my.id` mengarah ke IP VPS.
- [ ] Project minimal sudah ada di `~/Grading_TBS_CV_KEL_A_Github`.
- [ ] Virtual environment `.venv-streamlit-cpu` sudah dibuat.
- [ ] `best.pt` tersedia.
- [ ] Manual run berhasil.
- [ ] systemd service aktif.
- [ ] Nginx reverse proxy aktif.
- [ ] HTTPS Certbot aktif.
- [ ] Data lama sudah dimigrasi jika diperlukan.
- [ ] Upload gambar menghasilkan bounding box.
- [ ] Refresh browser tidak menghapus history.

## Kesimpulan

Deployment **Grading TBS USTP** di Sumopod 2 vCPU / 4 GB RAM layak untuk demo CPU-only dan penggunaan ringan. Gunakan snapshot/upload sebagai mode utama, image size 416 atau lebih kecil, dan migrasikan folder `runs/streamlit_live_detection/` jika ingin membawa riwayat deteksi lama.
