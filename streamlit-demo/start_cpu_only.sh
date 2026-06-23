#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

PYTHON_BIN_SELECTED="${PYTHON_BIN:-}"
if [ -z "$PYTHON_BIN_SELECTED" ]; then
  if [ -x ".venv-streamlit-cpu/bin/python" ]; then
    PYTHON_BIN_SELECTED=".venv-streamlit-cpu/bin/python"
  elif [ -x ".venv-streamlit/bin/python" ]; then
    PYTHON_BIN_SELECTED=".venv-streamlit/bin/python"
  elif command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN_SELECTED="$(command -v python3)"
  else
    echo "Python tidak ditemukan. Install python3-venv dan buat virtual environment dulu." >&2
    exit 1
  fi
fi

export CUDA_VISIBLE_DEVICES=""
export STREAMLIT_CPU_ONLY="1"
export STREAMLIT_CPU_THREADS="${STREAMLIT_CPU_THREADS:-2}"
export STREAMLIT_CPU_IMGSZ="${STREAMLIT_CPU_IMGSZ:-416}"
export STREAMLIT_CPU_MAX_DET="${STREAMLIT_CPU_MAX_DET:-20}"
export STREAMLIT_CPU_SAVE_INTERVAL="${STREAMLIT_CPU_SAVE_INTERVAL:-5.0}"
export STREAMLIT_CPU_CONTINUOUS_REFRESH="${STREAMLIT_CPU_CONTINUOUS_REFRESH:-2.0}"
export OMP_NUM_THREADS="${OMP_NUM_THREADS:-2}"
export MKL_NUM_THREADS="${MKL_NUM_THREADS:-2}"
export OPENBLAS_NUM_THREADS="${OPENBLAS_NUM_THREADS:-2}"
export NUMEXPR_NUM_THREADS="${NUMEXPR_NUM_THREADS:-2}"
export YOLO_CONFIG_DIR="${YOLO_CONFIG_DIR:-/tmp/Ultralytics}"

HOST="${STREAMLIT_HOST:-0.0.0.0}"
PORT="${STREAMLIT_PORT:-8501}"

echo "Launching Grading TBS USTP CPU-only Streamlit on ${HOST}:${PORT}"
echo "Python: ${PYTHON_BIN_SELECTED}"
echo "CPU threads: ${STREAMLIT_CPU_THREADS}, imgsz: ${STREAMLIT_CPU_IMGSZ}, max_det: ${STREAMLIT_CPU_MAX_DET}"

exec "$PYTHON_BIN_SELECTED" -m streamlit run "$ROOT/app.py" \
  --server.address "$HOST" \
  --server.port "$PORT" \
  --server.headless true \
  --server.enableCORS false \
  --server.enableXsrfProtection false
