# Checklist Deploy VPS Ubuntu 24.04

## DNS
- Arahkan A record `dashboard-grading-ustp.my.id` ke IP public VPS Sumapod.
- Validasi propagasi:

```bash
dig dashboard-grading-ustp.my.id
```

## Install Paket Sistem
```bash
sudo apt update
sudo apt install -y python3-venv python3-pip nginx certbot python3-certbot-nginx
```

## Copy Aplikasi
```bash
sudo mkdir -p /opt/mgrading-dashboard
sudo rsync -av Streamlit_CPU_Only/ /opt/mgrading-dashboard/
sudo chown -R www-data:www-data /opt/mgrading-dashboard
```

## Python Environment
```bash
cd /opt/mgrading-dashboard
sudo -u www-data python3 -m venv .venv
sudo -H -u www-data .venv/bin/pip install --upgrade pip
sudo -H -u www-data .venv/bin/pip install -r requirements.txt
sudo -u www-data mkdir -p server/uploads
```

Catatan: warning cache pip di `/var/www/.cache/pip` bukan masalah selama command selesai tanpa `ERROR`.

## systemd
```bash
sudo cp deploy/mgrading-dashboard.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mgrading-dashboard
sudo systemctl status mgrading-dashboard
```

## Nginx dan HTTPS
```bash
sudo cp deploy/nginx-dashboard-grading-ustp.my.id.conf /etc/nginx/sites-available/dashboard-grading-ustp.my.id
sudo ln -s /etc/nginx/sites-available/dashboard-grading-ustp.my.id /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
sudo certbot --nginx -d dashboard-grading-ustp.my.id
sudo certbot renew --dry-run
```

## Validasi
```bash
curl -s http://127.0.0.1:8080/api/health
curl -s https://dashboard-grading-ustp.my.id/api/health
curl -s https://dashboard-grading-ustp.my.id/api/dashboard-data | head
```

Set URL dashboard di Android menjadi `https://dashboard-grading-ustp.my.id`, lalu tekan `Sync Sekarang`.

Jangan isi URL Android dengan `https://dashboard-grading-ustp.my.id/api/detections`, karena aplikasi akan menambahkan `/api/detections` sendiri dan request menjadi `/api/detections/api/detections`.

## Troubleshooting Ringkas

- Jika `deploy/mgrading-api.service` tidak ada, itu normal. Versi ini hanya memakai `deploy/mgrading-dashboard.service`.
- Jika domain dashboard menampilkan isi domain lama, cek Nginx. `dashboard-grading-ustp.my.id` harus proxy ke `127.0.0.1:8080`, bukan port service domain lama.
- Jika Certbot gagal `SERVFAIL looking up A/AAAA`, perbaiki DNS A record dulu dan hapus AAAA jika VPS tidak memakai IPv6.
- Jika log berisi `/_stcore/host-config` atau `/_stcore/health`, itu sisa request Streamlit. Versi ini tidak memakai Streamlit.
- Jika Android log berisi `/api/detections/api/detections`, ganti URL Android ke base URL saja: `https://dashboard-grading-ustp.my.id`.
