import json
import os
import re
import threading
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

os.environ.setdefault("CUDA_VISIBLE_DEVICES", "")
os.environ.setdefault("OMP_NUM_THREADS", "2")
os.environ.setdefault("MKL_NUM_THREADS", "2")
os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
os.environ.setdefault("NUMEXPR_NUM_THREADS", "2")
os.environ.setdefault("YOLO_CONFIG_DIR", "/tmp/Ultralytics")

import av
import cv2
import numpy as np
import pandas as pd
import plotly.express as px
import streamlit as st
from streamlit_webrtc import RTCConfiguration, VideoProcessorBase, WebRtcMode, webrtc_streamer
from ultralytics import YOLO
from ultralytics.utils.plotting import Annotator, colors


PROJECT_ROOT = Path(__file__).resolve().parent
OUTPUT_ROOT = PROJECT_ROOT / "runs" / "streamlit_live_detection"
ACTIVE_SESSION_FILE = OUTPUT_ROOT / "active_session.txt"
CPU_THREAD_COUNT = int(os.environ.get("STREAMLIT_CPU_THREADS", "2"))
CPU_DEFAULT_IMGSZ = int(os.environ.get("STREAMLIT_CPU_IMGSZ", "416"))
CPU_DEFAULT_MAX_DET = int(os.environ.get("STREAMLIT_CPU_MAX_DET", "20"))
CPU_DEFAULT_SAVE_INTERVAL = float(os.environ.get("STREAMLIT_CPU_SAVE_INTERVAL", "5.0"))
CPU_DEFAULT_CONTINUOUS_REFRESH = float(os.environ.get("STREAMLIT_CPU_CONTINUOUS_REFRESH", "2.0"))

try:
    cv2.setNumThreads(CPU_THREAD_COUNT)
except Exception:
    pass

CLASS_NAMES = {
    0: "Kurang Masak (Underripe)",
    1: "Masak (Ripe)",
    2: "Mentah (Unripe)",
    3: "Terlalu Masak (Overripe)",
}

CLASS_COLORS = {
    "Kurang Masak (Underripe)": "#d8942c",
    "Masak (Ripe)": "#2f7d57",
    "Mentah (Unripe)": "#b7c93d",
    "Terlalu Masak (Overripe)": "#b84b3f",
}


def style_plotly(fig, height=320):
    fig.update_layout(
        height=height,
        font=dict(color="#f3f8ef", size=12),
        paper_bgcolor="rgba(0,0,0,0)",
        plot_bgcolor="rgba(7,20,15,0.45)",
        margin=dict(l=10, r=10, t=24, b=18),
        hoverlabel=dict(bgcolor="#10281d", font_color="#f3f8ef", bordercolor="#5ec27b"),
    )
    fig.update_xaxes(
        color="#f3f8ef",
        title_font=dict(color="#f3f8ef"),
        tickfont=dict(color="#dcebd8"),
        gridcolor="rgba(94,194,123,0.18)",
        zerolinecolor="rgba(94,194,123,0.28)",
        linecolor="rgba(94,194,123,0.45)",
    )
    fig.update_yaxes(
        color="#f3f8ef",
        title_font=dict(color="#f3f8ef"),
        tickfont=dict(color="#dcebd8"),
        gridcolor="rgba(94,194,123,0.18)",
        zerolinecolor="rgba(94,194,123,0.28)",
        linecolor="rgba(94,194,123,0.45)",
    )
    return fig


EVENT_COLUMNS = [
    "timestamp",
    "event_id",
    "raw_image_path",
    "annotated_image_path",
    "annotation_json_path",
    "annotation_yolo_path",
    "model_path",
    "device",
    "imgsz",
    "conf_threshold",
    "iou_threshold",
    "total_detections",
    "dominant_class",
    "top_confidence",
    "class_distribution",
    "inference_ms",
    "estimated_fps",
    "boxes_json",
]

BOX_COLUMNS = ["class_id", "class_name", "confidence", "x1", "y1", "x2", "y2"]


def env_truthy(name):
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def parse_csv_env(name):
    value = os.environ.get(name, "").strip()
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


def get_webrtc_ice_servers():
    raw_json = os.environ.get("STREAMLIT_WEBRTC_ICE_SERVERS", "").strip()
    if raw_json:
        try:
            parsed = json.loads(raw_json)
        except json.JSONDecodeError as error:
            st.sidebar.warning(f"STREAMLIT_WEBRTC_ICE_SERVERS bukan JSON valid: {error}")
        else:
            if isinstance(parsed, list):
                return parsed
            st.sidebar.warning("STREAMLIT_WEBRTC_ICE_SERVERS harus berupa JSON list.")

    ice_servers = []
    turn_urls = parse_csv_env("STREAMLIT_WEBRTC_TURN_URLS")
    turn_username = os.environ.get("STREAMLIT_WEBRTC_TURN_USERNAME", "").strip()
    turn_credential = os.environ.get("STREAMLIT_WEBRTC_TURN_CREDENTIAL", "").strip()
    if turn_urls:
        turn_server = {"urls": turn_urls}
        if turn_username and turn_credential:
            turn_server.update({"username": turn_username, "credential": turn_credential})
        ice_servers.append(turn_server)

    if not env_truthy("STREAMLIT_WEBRTC_DISABLE_DEFAULT_STUN"):
        ice_servers.append({"urls": ["stun:stun.l.google.com:19302"]})

    return ice_servers


def has_turn_server(ice_servers):
    for server in ice_servers:
        urls = server.get("urls", []) if isinstance(server, dict) else []
        if isinstance(urls, str):
            urls = [urls]
        if any(str(url).startswith("turn:") or str(url).startswith("turns:") for url in urls):
            return True
    return False


def apply_page_style():
    st.markdown(
        """
        <style>
        :root {
          --ink:#f3f8ef; --muted:#b9c8b7; --line:#315541; --paper:#07140f;
          --paper-2:#0d2118; --panel:#10281d; --panel-2:#143522; --green:#38a169;
          --leaf:#5ec27b; --lime:#b7c93d; --amber:#f0b44c; --red:#e06a5f;
          --blue:#55a7d7; --dark:#06100c; --deep:#020806; --white:#ffffff;
        }
        .stApp {
          background:
            radial-gradient(circle at 12% 8%, rgba(56,161,105,.24), transparent 30%),
            radial-gradient(circle at 88% 12%, rgba(240,180,76,.14), transparent 28%),
            linear-gradient(135deg, var(--deep) 0%, var(--paper) 42%, #0b2419 100%);
          color: var(--ink);
        }
        .stApp [data-testid="stAppViewContainer"], .stApp [data-testid="stMain"] {
          background: transparent;
        }
        .stApp [data-testid="stMarkdownContainer"],
        .stApp [data-testid="stMarkdownContainer"] *,
        .stApp label, .stApp [data-testid="stWidgetLabel"], .stApp [data-testid="stWidgetLabel"] *,
        .stApp [data-testid="stSelectbox"] *, .stApp [data-testid="stRadio"] *,
        .stApp [data-testid="stCheckbox"] *, .stApp [data-testid="stSlider"] *,
        .stApp [data-testid="stFileUploader"] *, .stApp [data-testid="stCameraInput"] * {
          color: var(--ink) !important;
        }
        [data-testid="stSidebar"] {
          background: linear-gradient(180deg, #07140f 0%, #0f2a1d 100%);
          border-right: 1px solid var(--line);
        }
        [data-testid="stSidebar"] [data-testid="stMarkdownContainer"] *,
        [data-testid="stSidebar"] label, [data-testid="stSidebar"] p,
        [data-testid="stSidebar"] span { color: var(--ink) !important; }
        [data-testid="stCaptionContainer"], [data-testid="stCaptionContainer"] *, .small-muted {
          color: var(--muted) !important;
        }
        .hero {
          border: 1px solid rgba(94,194,123,.38);
          border-radius: 8px;
          padding: 28px 30px;
          color: #fff;
          background:
            radial-gradient(circle at 88% 16%, rgba(240,180,76,.30), transparent 28%),
            linear-gradient(115deg, rgba(2,8,6,.98), rgba(20,83,45,.95), rgba(56,161,105,.78));
          margin-bottom: 18px;
          box-shadow: 0 22px 48px rgba(0,0,0,.38);
        }
        .hero h1 { margin: 0 0 8px; font-size: 36px; line-height: 1.05; letter-spacing: 0; }
        .hero, .hero *, .step, .step * { color: #fff !important; }
        .hero p { margin: 0; color: rgba(255,255,255,.88) !important; font-size: 15px; }
        .hero .project-note { margin-top: 12px; color: #dff7df !important; font-weight: 800; letter-spacing: .2px; }
        .hero .team-list { margin-top: 6px; color: rgba(255,255,255,.9) !important; font-size: 14px; }
        .process { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 8px; margin: 10px 0 18px; }
        .step {
          min-height: 82px; border-radius: 8px; padding: 12px; color: #fff;
          border: 1px solid rgba(255,255,255,.10);
          box-shadow: 0 14px 28px rgba(0,0,0,.25);
        }
        .step b { display:block; margin-bottom:6px; font-size: 14px; }
        .step span { font-size:12px; opacity:.92; }
        .step:nth-child(1){background:#1f7a4a} .step:nth-child(2){background:#2f8f5f}
        .step:nth-child(3){background:#216047} .step:nth-child(4){background:#9b6a22}
        .step:nth-child(5){background:#8f3e38} .step:nth-child(6){background:#465b2f}
        .metric-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; margin: 8px 0 18px; }
        .metric-card, .panel {
          background: linear-gradient(180deg, rgba(16,40,29,.96), rgba(9,25,18,.98));
          border: 1px solid var(--line);
          border-radius: 8px;
          box-shadow: 0 16px 34px rgba(0,0,0,.30);
        }
        .metric-card { padding: 14px 14px 12px; }
        .metric-card b { display:block; font-size: 24px; line-height: 1; color: #ffffff; margin-bottom: 6px; }
        .metric-card span { color: var(--muted); font-size: 12px; }
        .panel { padding: 16px; margin-bottom: 16px; }
        div[data-testid="stButton"] > button, div[data-testid="stDownloadButton"] > button {
          border-radius: 8px !important;
          border: 1px solid rgba(94,194,123,.72) !important;
          color: #f7fff6 !important;
          background: linear-gradient(180deg, #1f7a4a, #14532d) !important;
          font-weight: 800 !important;
          box-shadow: 0 10px 20px rgba(0,0,0,.22) !important;
        }
        div[data-testid="stButton"] > button[kind="primary"],
        div[data-testid="stButton"] > button:hover,
        div[data-testid="stDownloadButton"] > button:hover {
          background: linear-gradient(180deg, #43b56f, #1f7a4a) !important;
          color: #ffffff !important;
          border-color: var(--leaf) !important;
        }
        div[data-testid="stButton"] > button *, div[data-testid="stDownloadButton"] > button * { color: inherit !important; }
        input, textarea, [data-baseweb="select"] *, [data-baseweb="input"] *, [data-baseweb="textarea"] * {
          color: var(--ink) !important;
        }
        [data-baseweb="select"] > div, [data-baseweb="input"] > div,
        input, textarea {
          background: #0b1d15 !important;
          border-color: var(--line) !important;
          color: var(--ink) !important;
        }
        [data-testid="stDataFrame"], [data-testid="stTable"] {
          background: #0b1d15 !important;
          border: 1px solid var(--line) !important;
          border-radius: 8px !important;
          overflow: hidden;
        }
        [data-testid="stDataFrame"] *, [data-testid="stTable"] *,
        [data-testid="stJson"] *, [data-testid="stExpander"] * {
          color: var(--ink) !important;
        }
        [data-testid="stDataFrame"] button, [data-testid="stDataFrame"] button * {
          color: var(--ink) !important;
          background: #10281d !important;
        }
        [data-testid="stAlert"] {
          border-radius: 8px;
          border: 1px solid var(--line);
          background: rgba(16,40,29,.9) !important;
        }
        [data-testid="stAlert"] * { color: var(--ink) !important; }
        [data-testid="stFileUploaderDropzone"], [data-testid="stCameraInput"] button {
          background: #0b1d15 !important;
          border-color: var(--line) !important;
          color: var(--ink) !important;
        }
        hr { border-color: var(--line) !important; }
        .small-muted { color: var(--muted); font-size: 13px; margin: 0; }
        @media(max-width: 900px) {
          .metric-grid, .process { grid-template-columns: 1fr; }
          .hero h1 { font-size: 28px; }
        }
        </style>
        """,
        unsafe_allow_html=True,
    )


def metric_cards(metrics):
    cards = "".join(
        f"<div class='metric-card'><b>{value}</b><span>{label}</span></div>"
        for label, value in metrics
    )
    st.markdown(f"<div class='metric-grid'>{cards}</div>", unsafe_allow_html=True)


def process_strip():
    steps = [
        ("1. Foto", "Input gambar TBS dari kamera/browser."),
        ("2. Preprocess", "Resize dan normalisasi internal YOLO."),
        ("3. Deteksi", "YOLO menghasilkan bbox, class, confidence."),
        ("4. Grading", "Kelas kematangan TBS terdeteksi."),
        ("5. Validasi", "Operator mengecek hasil dan confidence."),
        ("6. Simpan", "Artifact disimpan untuk audit/retraining."),
    ]
    html = "".join(f"<div class='step'><b>{title}</b><span>{desc}</span></div>" for title, desc in steps)
    st.markdown(f"<div class='process'>{html}</div>", unsafe_allow_html=True)


def resolve_model_path(manual_path=""):
    candidates = []
    if manual_path:
        candidates.append(Path(manual_path).expanduser())

    candidates.extend(
        [
            PROJECT_ROOT / "Huggingface" / "models" / "best.pt",
            PROJECT_ROOT / "runs" / "detect" / "palm_oil_optimized-2" / "weights" / "best.pt",
        ]
    )

    runs_dir = PROJECT_ROOT / "runs" / "detect"
    if runs_dir.exists():
        candidates.extend(
            sorted(
                runs_dir.glob("*/weights/best.pt"),
                key=lambda path: path.stat().st_mtime,
                reverse=True,
            )
        )

    for candidate in candidates:
        if candidate.exists():
            return candidate.resolve()

    raise FileNotFoundError(
        "Tidak menemukan model best.pt. Isi path manual di sidebar atau jalankan training terlebih dahulu."
    )


@st.cache_resource(show_spinner=False)
def load_model(model_path):
    try:
        import torch

        torch.set_num_threads(CPU_THREAD_COUNT)
        torch.set_num_interop_threads(max(1, min(CPU_THREAD_COUNT, 2)))
    except Exception:
        pass
    return YOLO(str(model_path))


def get_device():
    return "cpu"


def format_device_name(device):
    return "CPU" if device == "cpu" else f"CUDA:{device}"


def create_session_dir():
    session_dir = OUTPUT_ROOT / f"session_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
    session_dir.mkdir(parents=True, exist_ok=True)
    return session_dir


def session_sort_key(session_dir):
    csv_path = session_dir / "detections.csv"
    try:
        return csv_path.stat().st_mtime if csv_path.exists() else session_dir.stat().st_mtime
    except OSError:
        return 0


def list_detection_sessions():
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    sessions = [path for path in OUTPUT_ROOT.glob("session_*") if path.is_dir()]
    return sorted(sessions, key=session_sort_key, reverse=True)


def session_has_saved_events(session_dir):
    csv_path = session_dir / "detections.csv"
    if not csv_path.exists():
        return False
    try:
        return csv_path.stat().st_size > len(",".join(EVENT_COLUMNS)) + 1
    except OSError:
        return False


def read_active_session_dir():
    try:
        raw_path = ACTIVE_SESSION_FILE.read_text().strip()
    except OSError:
        return None
    if not raw_path:
        return None
    session_dir = Path(raw_path)
    if session_dir.exists() and session_dir.is_dir():
        return session_dir
    return None


def write_active_session_dir(session_dir):
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    ACTIVE_SESSION_FILE.write_text(str(Path(session_dir).resolve()))


def load_session_history(session_dir):
    csv_path = session_dir / "detections.csv"
    if not csv_path.exists():
        return []

    try:
        df = pd.read_csv(csv_path, keep_default_na=False)
    except Exception as error:
        st.warning(f"Gagal membaca history session {session_dir.name}: {error}")
        return []

    for column in EVENT_COLUMNS:
        if column not in df.columns:
            df[column] = ""
    df = df[EVENT_COLUMNS]
    return df.to_dict("records")


def max_event_number(history):
    max_number = 0
    for event in history:
        match = re.search(r"(\d+)$", str(event.get("event_id", "")))
        if match:
            max_number = max(max_number, int(match.group(1)))
    return max_number


def resolve_initial_session():
    active_session = read_active_session_dir()
    if active_session:
        return active_session, load_session_history(active_session)

    sessions = list_detection_sessions()
    if sessions:
        sessions_with_events = [session for session in sessions if session_has_saved_events(session)]
        session_dir = sessions_with_events[0] if sessions_with_events else sessions[0]
        write_active_session_dir(session_dir)
        return session_dir, load_session_history(session_dir)

    session_dir = create_session_dir()
    write_active_session_dir(session_dir)
    return session_dir, []


def boxes_to_yolo_lines(boxes_data, image_width, image_height):
    lines = []
    for box in boxes_data:
        x1, y1, x2, y2 = box["x1"], box["y1"], box["x2"], box["y2"]
        x_center = ((x1 + x2) / 2) / image_width
        y_center = ((y1 + y2) / 2) / image_height
        width = (x2 - x1) / image_width
        height = (y2 - y1) / image_height
        lines.append(f'{box["class_id"]} {x_center:.6f} {y_center:.6f} {width:.6f} {height:.6f}')
    return "\n".join(lines) + ("\n" if lines else "")


class DetectionStore:
    def __init__(self, session_dir, history=None):
        self.lock = threading.Lock()
        self.session_dir = Path(session_dir)
        self.session_dir.mkdir(parents=True, exist_ok=True)
        self.csv_path = self.session_dir / "detections.csv"
        write_active_session_dir(self.session_dir)
        self.history = list(history or [])
        self.next_event_number = max_event_number(self.history) + 1
        self.last_save_time = 0.0
        self.latest_event = dict(self.history[-1]) if self.history else None
        self.latest_status = (
            f"Memuat session tersimpan: {self.session_dir.name} ({len(self.history)} event)"
            if self.history
            else "Menunggu deteksi..."
        )
        self.latest_inference_ms = 0.0
        self.latest_fps = 0.0
        self.latest_frame_detections = 0

    def load_session(self, session_dir):
        history = load_session_history(Path(session_dir))
        with self.lock:
            self.session_dir = Path(session_dir)
            self.csv_path = self.session_dir / "detections.csv"
            write_active_session_dir(self.session_dir)
            self.history = history
            self.next_event_number = max_event_number(self.history) + 1
            self.last_save_time = 0.0
            self.latest_event = dict(self.history[-1]) if self.history else None
            self.latest_status = f"Memuat session: {self.session_dir.name} ({len(self.history)} event)"
            self.latest_inference_ms = 0.0
            self.latest_fps = 0.0
            self.latest_frame_detections = 0

    def reset(self):
        with self.lock:
            self.session_dir = create_session_dir()
            self.csv_path = self.session_dir / "detections.csv"
            write_active_session_dir(self.session_dir)
            self.history = []
            self.next_event_number = 1
            self.last_save_time = 0.0
            self.latest_event = None
            self.latest_status = f"Session baru: {self.session_dir}"
            self.latest_inference_ms = 0.0
            self.latest_fps = 0.0
            self.latest_frame_detections = 0

    def snapshot(self):
        with self.lock:
            return {
                "session_dir": self.session_dir,
                "csv_path": self.csv_path,
                "history": list(self.history),
                "latest_event": dict(self.latest_event) if self.latest_event else None,
                "latest_status": self.latest_status,
                "latest_inference_ms": self.latest_inference_ms,
                "latest_fps": self.latest_fps,
                "latest_frame_detections": self.latest_frame_detections,
            }

    def save_event(self, event, raw_bgr, annotated_bgr, boxes_data):
        with self.lock:
            event_id = f"det_{self.next_event_number:05d}"
            self.next_event_number += 1
            raw_image_path = self.session_dir / f"{event_id}_raw.jpg"
            annotated_image_path = self.session_dir / f"{event_id}_annotated.jpg"
            annotation_json_path = self.session_dir / f"{event_id}_annotation.json"
            annotation_yolo_path = self.session_dir / f"{event_id}_labels.txt"

            event.update(
                {
                    "event_id": event_id,
                    "raw_image_path": str(raw_image_path),
                    "annotated_image_path": str(annotated_image_path),
                    "annotation_json_path": str(annotation_json_path),
                    "annotation_yolo_path": str(annotation_yolo_path),
                }
            )

            cv2.imwrite(str(raw_image_path), raw_bgr)
            cv2.imwrite(str(annotated_image_path), annotated_bgr)

            image_height, image_width = raw_bgr.shape[:2]
            annotation_payload = {
                "event": {key: value for key, value in event.items() if key != "boxes_json"},
                "image": {
                    "width": image_width,
                    "height": image_height,
                    "raw_image_path": str(raw_image_path),
                    "annotated_image_path": str(annotated_image_path),
                },
                "boxes": boxes_data,
            }
            annotation_json_path.write_text(json.dumps(annotation_payload, ensure_ascii=False, indent=2))
            annotation_yolo_path.write_text(boxes_to_yolo_lines(boxes_data, image_width, image_height))

            self.history.append(event)
            self.latest_event = event
            self.latest_status = f"Disimpan: {event_id}"
            self.last_save_time = time.time()
            write_active_session_dir(self.session_dir)
            pd.DataFrame(self.history, columns=EVENT_COLUMNS).to_csv(self.csv_path, index=False)
            return event

    def update_runtime(self, status, inference_ms, fps, frame_detections):
        with self.lock:
            self.latest_status = status
            self.latest_inference_ms = inference_ms
            self.latest_fps = fps
            self.latest_frame_detections = frame_detections


class YOLOVideoProcessor(VideoProcessorBase):
    def __init__(self):
        self.model = None
        self.model_path = None
        self.device = "cpu"
        self.store = None
        self.settings = {}

    def recv(self, frame):
        frame_bgr = frame.to_ndarray(format="bgr24")

        if self.model is None or self.store is None:
            return av.VideoFrame.from_ndarray(frame_bgr, format="bgr24")

        started_at = time.perf_counter()
        settings = dict(self.settings)
        conf_threshold = float(settings.get("conf_threshold", 0.25))
        iou_threshold = float(settings.get("iou_threshold", 0.45))
        img_size = int(settings.get("img_size", 512))
        max_det = int(settings.get("max_det", 30))

        try:
            results = self.model.predict(
                source=frame_bgr,
                conf=conf_threshold,
                iou=iou_threshold,
                imgsz=img_size,
                max_det=max_det,
                device=self.device,
                verbose=False,
            )
            result = results[0]
            raw_bgr = result.orig_img.copy()
            annotated_bgr = result.orig_img.copy()
            annotator = Annotator(annotated_bgr, line_width=2)

            labels = []
            confidences = []
            boxes_data = []
            model_names = getattr(self.model, "names", {})

            for box in result.boxes:
                class_id = int(box.cls[0])
                confidence = float(box.conf[0])
                class_name = CLASS_NAMES.get(
                    class_id,
                    model_names.get(class_id, f"Class {class_id}") if isinstance(model_names, dict) else f"Class {class_id}",
                )
                x1, y1, x2, y2 = [float(value) for value in box.xyxy[0].tolist()]
                labels.append(class_name)
                confidences.append(confidence)
                boxes_data.append(
                    {
                        "class_id": class_id,
                        "class_name": class_name,
                        "confidence": round(confidence, 4),
                        "x1": round(x1, 2),
                        "y1": round(y1, 2),
                        "x2": round(x2, 2),
                        "y2": round(y2, 2),
                    }
                )
                annotator.box_label(box.xyxy[0], f"{class_name} {confidence:.2f}", color=colors(class_id, True))

            annotated_bgr = annotator.result()
            inference_ms = (time.perf_counter() - started_at) * 1000
            estimated_fps = 1000 / inference_ms if inference_ms > 0 else 0.0
            label_counts = Counter(labels)
            distribution = "; ".join(f"{label}: {count}" for label, count in label_counts.items())
            dominant_class = label_counts.most_common(1)[0][0] if label_counts else "-"
            top_confidence = max(confidences) if confidences else 0.0

            auto_save = bool(settings.get("auto_save", True))
            save_interval = float(settings.get("save_interval", 2.0))
            should_save = (
                auto_save
                and labels
                and (time.time() - self.store.last_save_time) >= save_interval
            )

            if should_save:
                event = {
                    "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                    "event_id": "",
                    "raw_image_path": "",
                    "annotated_image_path": "",
                    "annotation_json_path": "",
                    "annotation_yolo_path": "",
                    "model_path": str(self.model_path),
                    "device": format_device_name(self.device),
                    "imgsz": img_size,
                    "conf_threshold": conf_threshold,
                    "iou_threshold": iou_threshold,
                    "total_detections": len(labels),
                    "dominant_class": dominant_class,
                    "top_confidence": round(top_confidence, 4),
                    "class_distribution": distribution,
                    "inference_ms": round(inference_ms, 2),
                    "estimated_fps": round(estimated_fps, 2),
                    "boxes_json": json.dumps(boxes_data, ensure_ascii=False),
                }
                self.store.save_event(event, raw_bgr, annotated_bgr, boxes_data)
            else:
                if not labels:
                    status = "Live: tidak ada objek terdeteksi"
                elif not auto_save:
                    status = f"Live: {len(labels)} deteksi, auto-save nonaktif"
                else:
                    remaining = max(0, save_interval - (time.time() - self.store.last_save_time))
                    status = f"Live: {len(labels)} deteksi, menunggu save {remaining:.1f}s"
                self.store.update_runtime(status, inference_ms, estimated_fps, len(labels))

            return av.VideoFrame.from_ndarray(annotated_bgr, format="bgr24")

        except Exception as error:
            self.store.update_runtime(f"Error inferensi: {error}", 0.0, 0.0, 0)
            return av.VideoFrame.from_ndarray(frame_bgr, format="bgr24")


def init_session_state():
    if "detection_store" not in st.session_state:
        OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
        session_dir, history = resolve_initial_session()
        st.session_state.detection_store = DetectionStore(session_dir, history)
        st.session_state.active_session_dir = str(session_dir)


def history_to_df(history):
    if not history:
        return pd.DataFrame(columns=EVENT_COLUMNS)
    df = pd.DataFrame(history, columns=EVENT_COLUMNS)
    numeric_columns = [
        "imgsz",
        "conf_threshold",
        "iou_threshold",
        "total_detections",
        "top_confidence",
        "inference_ms",
        "estimated_fps",
    ]
    for column in numeric_columns:
        df[column] = pd.to_numeric(df[column], errors="coerce")
    df["total_detections"] = df["total_detections"].fillna(0).astype(int)
    return df


def parse_boxes(record):
    if not record:
        return pd.DataFrame(columns=BOX_COLUMNS)
    raw_boxes = record.get("boxes_json", "[]")
    if raw_boxes is None or (isinstance(raw_boxes, float) and pd.isna(raw_boxes)):
        raw_boxes = "[]"
    try:
        boxes = json.loads(str(raw_boxes))
    except (TypeError, json.JSONDecodeError):
        boxes = []
    return pd.DataFrame(boxes, columns=BOX_COLUMNS) if boxes else pd.DataFrame(columns=BOX_COLUMNS)


def run_yolo_detection(frame_bgr, model, device, settings):
    started_at = time.perf_counter()
    conf_threshold = float(settings.get("conf_threshold", 0.25))
    iou_threshold = float(settings.get("iou_threshold", 0.45))
    img_size = int(settings.get("img_size", 512))
    max_det = int(settings.get("max_det", 30))

    results = model.predict(
        source=frame_bgr,
        conf=conf_threshold,
        iou=iou_threshold,
        imgsz=img_size,
        max_det=max_det,
        device=device,
        verbose=False,
    )
    result = results[0]
    raw_bgr = result.orig_img.copy()
    annotated_bgr = result.orig_img.copy()
    annotator = Annotator(annotated_bgr, line_width=2)

    labels = []
    confidences = []
    boxes_data = []
    model_names = getattr(model, "names", {})

    for box in result.boxes:
        class_id = int(box.cls[0])
        confidence = float(box.conf[0])
        class_name = CLASS_NAMES.get(
            class_id,
            model_names.get(class_id, f"Class {class_id}") if isinstance(model_names, dict) else f"Class {class_id}",
        )
        x1, y1, x2, y2 = [float(value) for value in box.xyxy[0].tolist()]
        labels.append(class_name)
        confidences.append(confidence)
        boxes_data.append(
            {
                "class_id": class_id,
                "class_name": class_name,
                "confidence": round(confidence, 4),
                "x1": round(x1, 2),
                "y1": round(y1, 2),
                "x2": round(x2, 2),
                "y2": round(y2, 2),
            }
        )
        annotator.box_label(box.xyxy[0], f"{class_name} {confidence:.2f}", color=colors(class_id, True))

    annotated_bgr = annotator.result()
    inference_ms = (time.perf_counter() - started_at) * 1000
    estimated_fps = 1000 / inference_ms if inference_ms > 0 else 0.0
    label_counts = Counter(labels)
    distribution = "; ".join(f"{label}: {count}" for label, count in label_counts.items())
    dominant_class = label_counts.most_common(1)[0][0] if label_counts else "-"
    top_confidence = max(confidences) if confidences else 0.0

    return {
        "raw_bgr": raw_bgr,
        "annotated_bgr": annotated_bgr,
        "labels": labels,
        "boxes_data": boxes_data,
        "distribution": distribution,
        "dominant_class": dominant_class,
        "top_confidence": top_confidence,
        "inference_ms": inference_ms,
        "estimated_fps": estimated_fps,
        "img_size": img_size,
        "conf_threshold": conf_threshold,
        "iou_threshold": iou_threshold,
    }


def build_detection_event(detection, model_path, device):
    return {
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "event_id": "",
        "raw_image_path": "",
        "annotated_image_path": "",
        "annotation_json_path": "",
        "annotation_yolo_path": "",
        "model_path": str(model_path),
        "device": format_device_name(device),
        "imgsz": detection["img_size"],
        "conf_threshold": detection["conf_threshold"],
        "iou_threshold": detection["iou_threshold"],
        "total_detections": len(detection["labels"]),
        "dominant_class": detection["dominant_class"],
        "top_confidence": round(detection["top_confidence"], 4),
        "class_distribution": detection["distribution"],
        "inference_ms": round(detection["inference_ms"], 2),
        "estimated_fps": round(detection["estimated_fps"], 2),
        "boxes_json": json.dumps(detection["boxes_data"], ensure_ascii=False),
    }


def normalize_capture_source(source):
    source_text = str(source).strip()
    if source_text.isdigit():
        return int(source_text)
    return source_text


def read_server_camera_frame(source):
    capture_source = normalize_capture_source(source)
    cap = cv2.VideoCapture(capture_source)
    try:
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        ok, frame_bgr = cap.read()
    finally:
        cap.release()
    if not ok or frame_bgr is None:
        raise RuntimeError(f"Tidak bisa membaca frame dari camera source: {source}")
    return frame_bgr


def detect_static_frame(frame_bgr, model, model_path, device, settings, store):
    detection = run_yolo_detection(frame_bgr, model, device, settings)

    if detection["labels"]:
        event = build_detection_event(detection, model_path, device)
        saved_event = store.save_event(
            event,
            detection["raw_bgr"],
            detection["annotated_bgr"],
            detection["boxes_data"],
        )
        store.update_runtime(
            f"Snapshot tersimpan: {saved_event['event_id']}",
            detection["inference_ms"],
            detection["estimated_fps"],
            len(detection["labels"]),
        )
        return detection["annotated_bgr"], saved_event, detection["boxes_data"]

    store.update_runtime(
        "Snapshot: tidak ada objek terdeteksi",
        detection["inference_ms"],
        detection["estimated_fps"],
        0,
    )
    return detection["annotated_bgr"], None, []


def detect_continuous_frame(frame_bgr, model, model_path, device, settings, store):
    detection = run_yolo_detection(frame_bgr, model, device, settings)
    labels = detection["labels"]
    save_enabled = bool(settings.get("auto_save", True))
    save_interval = float(settings.get("save_interval", 2.0))
    save_empty_frames = bool(settings.get("save_empty_frames", False))
    elapsed_since_save = time.time() - store.last_save_time

    should_save = save_enabled and (labels or save_empty_frames) and elapsed_since_save >= save_interval
    saved_event = None
    if should_save:
        event = build_detection_event(detection, model_path, device)
        saved_event = store.save_event(
            event,
            detection["raw_bgr"],
            detection["annotated_bgr"],
            detection["boxes_data"],
        )
        status = f"Continuous tersimpan: {saved_event['event_id']} ({len(labels)} deteksi)"
    elif not labels:
        status = "Continuous: tidak ada objek terdeteksi"
    elif not save_enabled:
        status = f"Continuous: {len(labels)} deteksi, auto-save nonaktif"
    else:
        remaining = max(0, save_interval - elapsed_since_save)
        status = f"Continuous: {len(labels)} deteksi, menunggu save {remaining:.1f}s"

    store.update_runtime(status, detection["inference_ms"], detection["estimated_fps"], len(labels))
    return detection["annotated_bgr"], saved_event, detection["boxes_data"]


def render_charts(history_df):
    if history_df.empty:
        st.info("Belum ada event tersimpan. Arahkan kamera ke TBS untuk mulai mengisi dashboard.")
        return

    boxes = []
    for _, row in history_df.iterrows():
        try:
            boxes.extend(json.loads(row.get("boxes_json", "[]")))
        except json.JSONDecodeError:
            continue

    boxes_df = pd.DataFrame(boxes)

    col_a, col_b = st.columns(2)
    with col_a:
        st.markdown("<div class='panel'>", unsafe_allow_html=True)
        st.subheader("Distribusi Kelas Terdeteksi")
        if boxes_df.empty:
            st.info("Belum ada bounding box.")
        else:
            class_counts = boxes_df["class_name"].value_counts().reset_index()
            class_counts.columns = ["class_name", "count"]
            fig = px.bar(
                class_counts,
                x="class_name",
                y="count",
                color="class_name",
                color_discrete_map=CLASS_COLORS,
                labels={"class_name": "Kelas", "count": "Jumlah"},
            )
            style_plotly(fig, height=320)
            fig.update_layout(showlegend=False)
            st.plotly_chart(fig, use_container_width=True)
        st.markdown("</div>", unsafe_allow_html=True)

    with col_b:
        st.markdown("<div class='panel'>", unsafe_allow_html=True)
        st.subheader("Timeline Event")
        timeline = history_df.copy()
        timeline["timestamp"] = pd.to_datetime(timeline["timestamp"], errors="coerce")
        timeline = timeline.dropna(subset=["timestamp"])
        if timeline.empty:
            st.info("Belum ada timestamp valid.")
        else:
            timeline = timeline.groupby(pd.Grouper(key="timestamp", freq="1min")).size().reset_index(name="events")
            fig = px.line(timeline, x="timestamp", y="events", markers=True, labels={"timestamp": "Waktu", "events": "Event"})
            style_plotly(fig, height=320)
            st.plotly_chart(fig, use_container_width=True)
        st.markdown("</div>", unsafe_allow_html=True)


def render_detail_panel(history_df, latest_event):
    if history_df.empty:
        st.info("Detail event akan tampil setelah ada deteksi tersimpan.")
        return

    event_ids = history_df["event_id"].tolist()
    default_index = 0
    if latest_event and latest_event.get("event_id") in event_ids:
        default_index = event_ids.index(latest_event["event_id"])

    selected_event_id = st.selectbox("Pilih event untuk audit", event_ids, index=default_index)
    record = history_df.loc[history_df["event_id"] == selected_event_id].iloc[0].to_dict()

    img_col, meta_col = st.columns([1.1, 1])
    with img_col:
        annotated_path = Path(str(record.get("annotated_image_path", "")))
        raw_path = Path(str(record.get("raw_image_path", "")))
        if annotated_path.exists():
            st.image(str(annotated_path), caption=f"Annotated image: {selected_event_id}", use_container_width=True)
        if raw_path.exists():
            with st.expander("Lihat raw image"):
                st.image(str(raw_path), caption="Raw image", use_container_width=True)

    with meta_col:
        st.markdown("#### Metadata Event")
        meta = {
            "timestamp": record.get("timestamp"),
            "total_detections": record.get("total_detections"),
            "dominant_class": record.get("dominant_class"),
            "top_confidence": record.get("top_confidence"),
            "class_distribution": record.get("class_distribution"),
            "inference_ms": record.get("inference_ms"),
            "estimated_fps": record.get("estimated_fps"),
            "device": record.get("device"),
        }
        st.json(meta)

        for label, path_key, mime in [
            ("Download Annotation JSON", "annotation_json_path", "application/json"),
            ("Download YOLO Labels TXT", "annotation_yolo_path", "text/plain"),
        ]:
            path = Path(str(record.get(path_key, "")))
            if path.exists():
                st.download_button(label, path.read_bytes(), file_name=path.name, mime=mime)

    st.markdown("#### Detail Bounding Box")
    st.dataframe(parse_boxes(record), use_container_width=True, hide_index=True)


def main():
    st.set_page_config(
        page_title="Grading TBS USTP",
        page_icon="🌴",
        layout="wide",
        initial_sidebar_state="expanded",
    )
    apply_page_style()
    init_session_state()

    st.markdown(
        """
        <div class='hero'>
          <h1>Grading TBS USTP</h1>
          <p>Sistem deteksi dan audit grading TBS berbasis YOLO untuk monitoring kematangan buah sawit secara cepat, ringan, dan terdokumentasi.</p>
          <p class="project-note">Final Project Indonesia AI</p>
          <p class="team-list">1. Ridwan Pioneer Panjaitan &nbsp; 2. Felim &nbsp; 3. Dwi &nbsp; 4. Gunadi</p>
        </div>
        """,
        unsafe_allow_html=True,
    )
    process_strip()

    with st.sidebar:
        st.header("Kontrol Inferensi")
        st.info(
            "Mode VPS CPU aktif. Gunakan image size kecil dan Snapshot / upload fallback untuk performa stabil di server 2 vCPU / 4 GB RAM."
        )
        manual_model_path = st.text_input("Path model best.pt", value="")
        conf_threshold = st.slider("Confidence", 0.05, 0.90, 0.25, 0.05)
        iou_threshold = st.slider("IoU", 0.10, 0.90, 0.45, 0.05)
        img_size = st.slider("Image size", 320, 768, CPU_DEFAULT_IMGSZ, 32)
        max_det = st.slider("Max detections", 1, 50, CPU_DEFAULT_MAX_DET, 1)
        public_proxy_mode = env_truthy("STREAMLIT_PUBLIC_PROXY")
        camera_modes = [
            "Snapshot / upload fallback",
            "Server/IP camera continuous",
            "WebRTC live camera",
        ]
        camera_mode = st.radio(
            "Mode input kamera",
            camera_modes,
            index=0,
            help=(
                "Mode public proxy default ke Snapshot karena WebRTC live camera sering gagal lewat Cloudflare/VPN tanpa TURN."
                if public_proxy_mode
                else "Pakai fallback jika browser menolak Live Camera atau Streamlit dibuka lewat IP/HTTP."
            ),
        )
        if public_proxy_mode:
            st.info(
                "Mode public proxy aktif: gunakan Snapshot / upload fallback untuk demo publik paling stabil. "
                "WebRTC live camera butuh TURN relay agar stabil lewat Cloudflare/VPN."
            )
        server_camera_source = "0"
        continuous_running = False
        continuous_refresh_seconds = CPU_DEFAULT_CONTINUOUS_REFRESH
        save_empty_frames = False
        if camera_mode == "Server/IP camera continuous":
            server_camera_source = st.text_input(
                "Server/IP camera source",
                value=os.environ.get("STREAMLIT_CAMERA_SOURCE", "0"),
                help="Isi 0 untuk webcam server, path video, atau URL RTSP/HTTP yang bisa diakses dari mesin server.",
            )
            continuous_refresh_seconds = st.slider("Continuous refresh seconds", 1.0, 15.0, CPU_DEFAULT_CONTINUOUS_REFRESH, 0.5)
            save_empty_frames = st.checkbox("Save frames without detections", value=False)
            if "server_camera_running" not in st.session_state:
                st.session_state.server_camera_running = False
            start_col, stop_col = st.columns(2)
            with start_col:
                if st.button("Start Continuous", type="primary"):
                    st.session_state.server_camera_running = True
            with stop_col:
                if st.button("Stop Continuous", type="secondary"):
                    st.session_state.server_camera_running = False
            continuous_running = bool(st.session_state.server_camera_running)

        auto_save = st.checkbox("Auto-save detections", value=True)
        save_interval = st.slider("Save interval seconds", 2.0, 30.0, CPU_DEFAULT_SAVE_INTERVAL, 1.0)
        auto_refresh = st.checkbox(
            "Auto-refresh dashboard",
            value=False,
            help="Dinonaktifkan default karena rerun terlalu sering bisa membuat komponen Live Camera error.",
        )
        refresh_seconds = st.slider("Refresh seconds", 0.5, 5.0, 2.0, 0.5)

        st.markdown("---")
        st.subheader("Session Data")
        available_sessions = list_detection_sessions()
        if available_sessions:
            current_session = str(st.session_state.detection_store.session_dir)
            session_options = [str(path) for path in available_sessions]
            if current_session not in session_options:
                session_options.insert(0, current_session)
            selected_session = st.selectbox(
                "Active session",
                session_options,
                index=session_options.index(current_session),
                format_func=lambda value: Path(value).name,
                help="Session tersimpan di disk. Refresh browser akan memuat session aktif/terakhir lagi.",
            )
            if selected_session != current_session:
                st.session_state.detection_store.load_session(Path(selected_session))
                st.session_state.active_session_dir = selected_session
                st.rerun()
        else:
            st.caption("Belum ada session tersimpan.")

        if st.button("Start New Session", type="secondary"):
            st.session_state.detection_store.reset()
            st.session_state.active_session_dir = str(st.session_state.detection_store.session_dir)
            st.rerun()

    try:
        model_path = resolve_model_path(manual_model_path)
        model = load_model(model_path)
        device = get_device()
    except Exception as error:
        st.error(str(error))
        st.stop()

    store = st.session_state.detection_store
    snapshot = store.snapshot()
    history_df = history_to_df(snapshot["history"]).iloc[::-1].reset_index(drop=True)
    latest_event = snapshot["latest_event"]

    total_events = len(history_df)
    total_objects = int(history_df["total_detections"].sum()) if not history_df.empty else 0
    dominant_class = history_df["dominant_class"].mode().iloc[0] if not history_df.empty else "-"
    top_conf = f"{history_df['top_confidence'].max() * 100:.1f}%" if not history_df.empty else "-"
    latest_fps = f"{snapshot['latest_fps']:.1f}"

    metric_cards(
        [
            ("Total event", f"{total_events}"),
            ("Total objek", f"{total_objects}"),
            ("Kelas dominan", dominant_class),
            ("Top confidence", top_conf),
            ("FPS estimasi", latest_fps),
        ]
    )

    live_col, status_col = st.columns([1.35, 1])
    webrtc_ctx = None
    detection_settings = {
        "conf_threshold": conf_threshold,
        "iou_threshold": iou_threshold,
        "img_size": img_size,
        "max_det": max_det,
        "auto_save": auto_save,
        "save_interval": save_interval,
        "save_empty_frames": save_empty_frames,
    }

    with live_col:
        st.markdown("<div class='panel'>", unsafe_allow_html=True)
        st.subheader("Live Camera Detection")
        if camera_mode == "WebRTC live camera":
            st.caption("Izinkan akses kamera dari browser. Bounding box tampil langsung pada video stream.")
            st.info(
                "Catatan: WebRTC/camera browser biasanya hanya berjalan stabil di localhost atau HTTPS. "
                "Jika dashboard dibuka lewat tunnel publik, VPN, atau jaringan NAT ketat, koneksi live sering butuh TURN relay. "
                "Jika tombol Start tidak tersambung, gunakan mode Snapshot / upload fallback atau set konfigurasi TURN."
            )
            ice_servers = get_webrtc_ice_servers()
            if has_turn_server(ice_servers):
                st.success("WebRTC ICE: TURN/STUN aktif. Mode live punya peluang lebih stabil dari jaringan publik.")
            else:
                st.warning(
                    "WebRTC ICE: hanya STUN/default, belum ada TURN. Cloudflare Quick Tunnel dapat membuka UI, "
                    "tetapi live video bisa gagal dari jaringan publik/VPN."
                )
            try:
                rtc_config = RTCConfiguration({"iceServers": ice_servers})
                webrtc_ctx = webrtc_streamer(
                    key="grading-tbs-ustp-dashboard",
                    mode=WebRtcMode.SENDRECV,
                    rtc_configuration=rtc_config,
                    video_processor_factory=YOLOVideoProcessor,
                    media_stream_constraints={"video": True, "audio": False},
                    async_processing=True,
                )
                if webrtc_ctx.video_processor:
                    webrtc_ctx.video_processor.model = model
                    webrtc_ctx.video_processor.model_path = model_path
                    webrtc_ctx.video_processor.device = device
                    webrtc_ctx.video_processor.store = store
                    webrtc_ctx.video_processor.settings = detection_settings
            except Exception as error:
                st.error(f"Live Camera component error: {error}")
                st.warning(
                    "Penyebab umum: browser memblokir kamera, permission kamera belum diizinkan, WebRTC ICE gagal karena NAT/VPN, "
                    "atau belum ada TURN relay. Pindah ke Snapshot / upload fallback atau set konfigurasi TURN."
                )
        elif camera_mode == "Server/IP camera continuous":
            st.caption(
                "Mode ini membaca kamera dari sisi server, bukan browser. Cocok untuk Cloudflare public proxy jika kamera "
                "terpasang di server atau tersedia sebagai RTSP/IP camera."
            )
            st.write(f"**Camera source:** `{server_camera_source}`")
            if not continuous_running:
                st.info("Klik Start Continuous di sidebar untuk mulai deteksi dan penyimpanan berkala.")
            else:
                try:
                    frame_bgr = read_server_camera_frame(server_camera_source)
                    annotated_bgr, saved_event, boxes_data = detect_continuous_frame(
                        frame_bgr, model, model_path, device, detection_settings, store
                    )
                except Exception as error:
                    st.error(f"Continuous camera error: {error}")
                    st.warning(
                        "Pastikan source `0` tersedia sebagai webcam server, atau gunakan URL RTSP/HTTP yang bisa diakses "
                        "dari mesin server tempat Streamlit berjalan."
                    )
                    st.session_state.server_camera_running = False
                else:
                    st.image(
                        cv2.cvtColor(annotated_bgr, cv2.COLOR_BGR2RGB),
                        caption="Continuous server/IP camera detection",
                        use_container_width=True,
                    )
                    if saved_event:
                        st.success(f"Tersimpan: {saved_event['event_id']} dengan {len(boxes_data)} bounding box.")
                    else:
                        st.info("Frame diproses. Event baru disimpan saat ada deteksi dan interval save terpenuhi.")
                    time.sleep(continuous_refresh_seconds)
                    st.rerun()
        else:
            st.caption("Fallback ini tetap menyimpan raw image, annotated image, JSON annotation, YOLO TXT, dan CSV event.")
            camera_file = st.camera_input("Ambil snapshot dari kamera")
            upload_file = st.file_uploader("Atau upload gambar TBS", type=["jpg", "jpeg", "png"])
            input_file = camera_file or upload_file
            if input_file is None:
                st.info("Ambil snapshot atau upload gambar untuk menjalankan deteksi satu frame.")
            else:
                image_bytes = np.frombuffer(input_file.getvalue(), dtype=np.uint8)
                frame_bgr = cv2.imdecode(image_bytes, cv2.IMREAD_COLOR)
                if frame_bgr is None:
                    st.error("File gambar tidak bisa dibaca oleh OpenCV.")
                else:
                    with st.spinner("Menjalankan deteksi dan menyimpan hasil..."):
                        try:
                            annotated_bgr, saved_event, boxes_data = detect_static_frame(
                                frame_bgr, model, model_path, device, detection_settings, store
                            )
                        except Exception as error:
                            st.error(f"Error inferensi snapshot: {error}")
                        else:
                            st.image(
                                cv2.cvtColor(annotated_bgr, cv2.COLOR_BGR2RGB),
                                caption="Hasil deteksi snapshot",
                                use_container_width=True,
                            )
                            if saved_event:
                                st.success(
                                    f"Tersimpan sebagai {saved_event['event_id']} dengan {len(boxes_data)} bounding box."
                                )
                            else:
                                st.warning("Tidak ada objek terdeteksi, sehingga tidak ada event baru yang disimpan.")
        st.markdown("</div>", unsafe_allow_html=True)

    snapshot = store.snapshot()
    history_df = history_to_df(snapshot["history"]).iloc[::-1].reset_index(drop=True)
    latest_event = snapshot["latest_event"]

    with status_col:
        st.markdown("<div class='panel'>", unsafe_allow_html=True)
        st.subheader("Status Backend Lokal")
        st.write(f"**Model:** `{model_path}`")
        st.write(f"**Device:** `{format_device_name(device)}`")
        st.write(f"**Session:** `{snapshot['session_dir']}`")
        st.write(f"**Status:** {snapshot['latest_status']}")
        st.write(f"**Deteksi frame terakhir:** {snapshot['latest_frame_detections']}")
        st.write(f"**Inference terakhir:** {snapshot['latest_inference_ms']:.1f} ms")
        if Path(snapshot["csv_path"]).exists():
            st.download_button(
                "Download detections.csv",
                Path(snapshot["csv_path"]).read_bytes(),
                file_name=Path(snapshot["csv_path"]).name,
                mime="text/csv",
            )
        st.markdown("</div>", unsafe_allow_html=True)

        if latest_event:
            annotated_path = Path(latest_event.get("annotated_image_path", ""))
            if annotated_path.exists():
                st.image(str(annotated_path), caption="Event terbaru tersimpan", use_container_width=True)

    st.markdown("---")
    st.subheader("Grafik Monitoring")
    render_charts(history_df)

    st.subheader("Riwayat Deteksi")
    if history_df.empty:
        st.info("Belum ada event tersimpan.")
    else:
        st.dataframe(
            history_df[
                [
                    "timestamp",
                    "event_id",
                    "total_detections",
                    "dominant_class",
                    "top_confidence",
                    "class_distribution",
                    "inference_ms",
                    "estimated_fps",
                ]
            ],
            use_container_width=True,
            hide_index=True,
        )

    st.subheader("Audit Detail Event")
    render_detail_panel(history_df, latest_event)

    webrtc_playing = bool(webrtc_ctx and getattr(webrtc_ctx.state, "playing", False))
    if webrtc_playing and auto_refresh:
        st.caption("Auto-refresh dijeda saat Live Camera aktif agar komponen WebRTC tidak restart terus.")
    elif auto_refresh:
        time.sleep(refresh_seconds)
        st.rerun()


if __name__ == "__main__":
    main()
