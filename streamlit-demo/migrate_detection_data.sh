#!/usr/bin/env bash
set -euo pipefail

REMOTE="${1:-}"
REMOTE_PROJECT="${2:-/home/ubuntu/Grading_TBS_CV_KEL_A_Github}"
LOCAL_APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE_APP_DIR="$REMOTE_PROJECT/streamlit-demo"
LOCAL_DATA="${LOCAL_APP_DIR}/runs/streamlit_live_detection"

if [ -z "$REMOTE" ]; then
  cat >&2 <<'EOF'
Usage:
  ./streamlit-demo/migrate_detection_data.sh ubuntu@IP_VPS [/home/ubuntu/Grading_TBS_CV_KEL_A_Github]

Contoh:
  ./streamlit-demo/migrate_detection_data.sh ubuntu@123.123.123.123
EOF
  exit 1
fi

if [ ! -d "$LOCAL_DATA" ]; then
  echo "Folder data lokal tidak ditemukan: $LOCAL_DATA" >&2
  exit 1
fi

if ! command -v rsync >/dev/null 2>&1; then
  echo "rsync belum terinstall di komputer lokal. Install rsync atau gunakan scp manual." >&2
  exit 1
fi

echo "Migrating detection data:"
echo "  Local : $LOCAL_DATA/"
echo "  Remote: $REMOTE:$REMOTE_APP_DIR/runs/streamlit_live_detection/"

ssh "$REMOTE" "mkdir -p '$REMOTE_APP_DIR/runs/streamlit_live_detection'"
rsync -avh --progress "$LOCAL_DATA/" "$REMOTE:$REMOTE_APP_DIR/runs/streamlit_live_detection/"
ssh "$REMOTE" "chown -R \$(id -un):\$(id -gn) '$REMOTE_APP_DIR/runs/streamlit_live_detection' || true"

cat <<EOF

Selesai. Di VPS jalankan:
  sudo systemctl restart grading-tbs-streamlit
  find $REMOTE_APP_DIR/runs/streamlit_live_detection -name detections.csv -print

EOF
