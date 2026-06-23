#!/usr/bin/env bash
set -euo pipefail

REMOTE="${1:-}"
REMOTE_PROJECT="${2:-/home/ubuntu/Grading_TBS_CV_KEL_A_Github}"
SERVICE_NAME="${SERVICE_NAME:-grading-tbs-streamlit}"
LOCAL_APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE_APP_DIR="$REMOTE_PROJECT/streamlit-demo"

if [ -z "$REMOTE" ]; then
  cat >&2 <<'EOF'
Usage:
  ./streamlit-demo/deploy_to_vps.sh ubuntu@IP_VPS [/home/ubuntu/Grading_TBS_CV_KEL_A_Github]

Contoh:
  ./streamlit-demo/deploy_to_vps.sh ubuntu@123.123.123.123
  ./streamlit-demo/deploy_to_vps.sh root@123.123.123.123 /root/Grading_TBS_CV_KEL_A_Github
EOF
  exit 1
fi

if ! command -v rsync >/dev/null 2>&1; then
  echo "rsync belum terinstall di komputer lokal. Install rsync dulu." >&2
  exit 1
fi

echo "Deploying Grading TBS USTP CPU-only app"
echo "  Local app     : $LOCAL_APP_DIR"
echo "  Remote        : $REMOTE"
echo "  Remote app    : $REMOTE_APP_DIR"
echo "  Service       : $SERVICE_NAME"

ssh "$REMOTE" "mkdir -p '$REMOTE_APP_DIR'"

rsync -avh --delete \
  --exclude 'runs/' \
  --exclude '.venv-streamlit-cpu/' \
  --exclude '__pycache__/' \
  "$LOCAL_APP_DIR/" \
  "$REMOTE:$REMOTE_APP_DIR/"

ssh "$REMOTE" "
  set -e
  echo '--- Remote file check ---'
  grep -n 'Grading TBS USTP' '$REMOTE_APP_DIR/app.py' | head
  echo '--- Service definition ---'
  systemctl cat '$SERVICE_NAME' || true
  echo '--- Restarting service ---'
  sudo systemctl daemon-reload
  sudo systemctl restart '$SERVICE_NAME'
  sleep 2
  sudo systemctl --no-pager --full status '$SERVICE_NAME' | sed -n '1,18p'
"

cat <<EOF

Deploy selesai. Buka ulang dengan hard refresh:
  Ctrl+F5 / Cmd+Shift+R

Jika masih belum berubah, jalankan di VPS:
  ps -ef | grep streamlit | grep -v grep
  sudo systemctl cat $SERVICE_NAME
  journalctl -u $SERVICE_NAME -n 80 --no-pager

EOF
