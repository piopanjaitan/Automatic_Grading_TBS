#!/usr/bin/env python3
import json
import mimetypes
import os
import re
import shutil
import sqlite3
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse


ROOT = Path(__file__).resolve().parents[1]
PUBLIC_DIR = ROOT / "public"
SERVER_DIR = ROOT / "server"
UPLOAD_DIR = SERVER_DIR / "uploads"
DB_PATH = SERVER_DIR / "mgrading_dashboard.db"


def ensure_db():
    SERVER_DIR.mkdir(parents=True, exist_ok=True)
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS detections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                local_id INTEGER NOT NULL,
                tag_code TEXT,
                session_id TEXT,
                class_id INTEGER NOT NULL,
                label TEXT NOT NULL,
                confidence REAL NOT NULL,
                bbox_left REAL NOT NULL,
                bbox_top REAL NOT NULL,
                bbox_right REAL NOT NULL,
                bbox_bottom REAL NOT NULL,
                fingerprint TEXT,
                created_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1,
                frame_url TEXT,
                crop_url TEXT,
                annotated_url TEXT,
                uploaded_at INTEGER NOT NULL,
                UNIQUE(device_id, local_id)
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_detections_created_at ON detections(created_at DESC)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_detections_session ON detections(session_id)")


def json_response(handler, status, payload):
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.end_headers()
    handler.wfile.write(body)


def ts_ms_to_local(ms):
    if not ms:
        return None, None, None
    dt = datetime.fromtimestamp(int(ms) / 1000)
    return dt.isoformat(timespec="seconds"), dt.strftime("%Y-%m-%d"), dt.strftime("%H:%M:%S")


def safe_name(value, fallback):
    text = str(value or fallback)
    text = re.sub(r"[^A-Za-z0-9._-]+", "_", text).strip("._")
    return text or fallback


def parse_multipart(headers, body):
    content_type = headers.get("Content-Type", "")
    raw = (
        f"Content-Type: {content_type}\r\nMIME-Version: 1.0\r\n\r\n".encode("utf-8")
        + body
    )
    message = BytesParser(policy=policy.default).parsebytes(raw)
    fields = {}
    files = {}
    if not message.is_multipart():
        return fields, files
    for part in message.iter_parts():
        disposition = part.get("Content-Disposition", "")
        name = part.get_param("name", header="content-disposition")
        filename = part.get_param("filename", header="content-disposition")
        if not name:
            continue
        payload = part.get_payload(decode=True) or b""
        if filename:
            files[name] = {
                "filename": filename,
                "content_type": part.get_content_type(),
                "content": payload,
            }
        else:
            charset = part.get_content_charset() or "utf-8"
            fields[name] = payload.decode(charset, errors="replace")
    return fields, files


def save_upload_file(kind, metadata, file_info):
    if not file_info or not file_info.get("content"):
        return None
    created_at = int(metadata.get("created_at") or int(time.time() * 1000))
    _, date_key, _ = ts_ms_to_local(created_at)
    tag_code = safe_name(metadata.get("tag_code"), f"local_{metadata.get('local_id', '0')}")
    suffix = Path(file_info.get("filename") or "").suffix.lower()
    if suffix not in [".jpg", ".jpeg", ".png", ".webp"]:
        suffix = ".jpg"
    target_dir = UPLOAD_DIR / date_key.replace("-", "") / kind
    target_dir.mkdir(parents=True, exist_ok=True)
    filename = f"{tag_code}_{kind}_{created_at}{suffix}"
    target = target_dir / filename
    target.write_bytes(file_info["content"])
    return "/" + target.relative_to(ROOT).as_posix()


def upsert_detection(metadata, files):
    required = [
        "device_id", "local_id", "class_id", "label", "confidence",
        "bbox_left", "bbox_top", "bbox_right", "bbox_bottom", "created_at", "last_seen_at"
    ]
    missing = [key for key in required if metadata.get(key) in [None, ""]]
    if missing:
        raise ValueError("Missing metadata fields: " + ", ".join(missing))

    frame_url = save_upload_file("frame", metadata, files.get("frame"))
    crop_url = save_upload_file("crop", metadata, files.get("crop"))
    annotated_url = save_upload_file("annotated", metadata, files.get("annotated"))
    uploaded_at = int(time.time() * 1000)

    values = {
        "device_id": str(metadata["device_id"]),
        "local_id": int(metadata["local_id"]),
        "tag_code": metadata.get("tag_code"),
        "session_id": metadata.get("session_id"),
        "class_id": int(metadata["class_id"]),
        "label": str(metadata["label"]),
        "confidence": float(metadata["confidence"]),
        "bbox_left": float(metadata["bbox_left"]),
        "bbox_top": float(metadata["bbox_top"]),
        "bbox_right": float(metadata["bbox_right"]),
        "bbox_bottom": float(metadata["bbox_bottom"]),
        "fingerprint": metadata.get("fingerprint"),
        "created_at": int(metadata["created_at"]),
        "last_seen_at": int(metadata["last_seen_at"]),
        "seen_count": int(metadata.get("seen_count") or 1),
        "frame_url": frame_url,
        "crop_url": crop_url,
        "annotated_url": annotated_url,
        "uploaded_at": uploaded_at,
    }

    with sqlite3.connect(DB_PATH) as conn:
        existing = conn.execute(
            "SELECT id, frame_url, crop_url, annotated_url FROM detections WHERE device_id=? AND local_id=?",
            (values["device_id"], values["local_id"]),
        ).fetchone()
        if existing:
            remote_id = existing[0]
            values["frame_url"] = frame_url or existing[1]
            values["crop_url"] = crop_url or existing[2]
            values["annotated_url"] = annotated_url or existing[3]
            conn.execute(
                """
                UPDATE detections SET
                    tag_code=:tag_code, session_id=:session_id, class_id=:class_id, label=:label,
                    confidence=:confidence, bbox_left=:bbox_left, bbox_top=:bbox_top,
                    bbox_right=:bbox_right, bbox_bottom=:bbox_bottom, fingerprint=:fingerprint,
                    created_at=:created_at, last_seen_at=:last_seen_at, seen_count=:seen_count,
                    frame_url=:frame_url, crop_url=:crop_url, annotated_url=:annotated_url,
                    uploaded_at=:uploaded_at
                WHERE id=:id
                """,
                {**values, "id": remote_id},
            )
        else:
            cursor = conn.execute(
                """
                INSERT INTO detections (
                    device_id, local_id, tag_code, session_id, class_id, label, confidence,
                    bbox_left, bbox_top, bbox_right, bbox_bottom, fingerprint, created_at,
                    last_seen_at, seen_count, frame_url, crop_url, annotated_url, uploaded_at
                ) VALUES (
                    :device_id, :local_id, :tag_code, :session_id, :class_id, :label, :confidence,
                    :bbox_left, :bbox_top, :bbox_right, :bbox_bottom, :fingerprint, :created_at,
                    :last_seen_at, :seen_count, :frame_url, :crop_url, :annotated_url, :uploaded_at
                )
                """,
                values,
            )
            remote_id = cursor.lastrowid
    return remote_id, values


def build_dashboard_payload():
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute("SELECT * FROM detections ORDER BY created_at ASC, id ASC").fetchall()

    records = []
    labels = Counter()
    dates = Counter()
    sessions = Counter()
    confidence_buckets = Counter()
    label_conf_sum = defaultdict(float)
    session_conf_sum = defaultdict(float)
    date_conf_sum = defaultdict(float)

    for row in rows:
        created_iso, created_date, created_time = ts_ms_to_local(row["created_at"])
        last_seen_iso, _, _ = ts_ms_to_local(row["last_seen_at"])
        label = row["label"] or "unknown"
        session_id = row["session_id"] or "NO_SESSION"
        confidence = float(row["confidence"])
        bucket_start = max(0.5, min(0.9, int(confidence * 10) / 10))
        bucket = f"{bucket_start:.1f}-{bucket_start + 0.1:.1f}"

        labels[label] += 1
        dates[created_date] += 1
        sessions[session_id] += 1
        confidence_buckets[bucket] += 1
        label_conf_sum[label] += confidence
        session_conf_sum[session_id] += confidence
        date_conf_sum[created_date] += confidence

        records.append({
            "id": row["id"],
            "deviceId": row["device_id"],
            "localId": row["local_id"],
            "tagCode": row["tag_code"],
            "classId": row["class_id"],
            "label": label,
            "confidence": round(confidence, 6),
            "confidencePct": round(confidence * 100, 1),
            "bbox": {
                "left": row["bbox_left"],
                "top": row["bbox_top"],
                "right": row["bbox_right"],
                "bottom": row["bbox_bottom"],
            },
            "frameUrl": row["frame_url"],
            "cropUrl": row["crop_url"],
            "annotatedUrl": row["annotated_url"],
            "fingerprint": row["fingerprint"],
            "sessionId": session_id,
            "createdAt": created_iso,
            "createdDate": created_date,
            "createdTime": created_time,
            "lastSeenAt": last_seen_iso,
            "seenCount": row["seen_count"],
        })

    total = len(records)
    confidences = [r["confidence"] for r in records]
    return {
        "project": "mGradingUSTP",
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "source": {
            "dbPath": str(DB_PATH),
            "mediaRoot": str(UPLOAD_DIR),
            "table": "detections",
            "mode": "local-api",
        },
        "summary": {
            "totalRecords": total,
            "totalSessions": len(sessions),
            "totalDates": len(dates),
            "firstDetectionAt": records[0]["createdAt"] if records else None,
            "lastDetectionAt": records[-1]["createdAt"] if records else None,
            "minConfidence": round(min(confidences), 4) if confidences else 0,
            "avgConfidence": round(sum(confidences) / total, 4) if total else 0,
            "maxConfidence": round(max(confidences), 4) if confidences else 0,
            "frameImages": sum(1 for r in records if r["frameUrl"]),
            "cropImages": sum(1 for r in records if r["cropUrl"]),
            "annotatedImages": sum(1 for r in records if r["annotatedUrl"]),
        },
        "labels": [
            {"label": label, "count": count, "share": round(count / total, 4) if total else 0,
             "avgConfidence": round(label_conf_sum[label] / count, 4)}
            for label, count in labels.most_common()
        ],
        "sessions": [
            {"sessionId": session_id, "count": count, "avgConfidence": round(session_conf_sum[session_id] / count, 4)}
            for session_id, count in sessions.most_common()
        ],
        "dates": [
            {"date": date, "count": dates[date], "avgConfidence": round(date_conf_sum[date] / dates[date], 4)}
            for date in sorted(dates)
        ],
        "confidenceBuckets": [
            {"bucket": bucket, "count": confidence_buckets[bucket]}
            for bucket in sorted(confidence_buckets)
        ],
        "records": records,
    }


class DashboardHandler(BaseHTTPRequestHandler):
    server_version = "mGradingDashboard/1.0"

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        path = unquote(urlparse(self.path).path)
        if path == "/api/health":
            json_response(self, 200, {"status": "ok", "project": "mGradingUSTP", "time": int(time.time() * 1000)})
            return
        if path == "/api/dashboard-data":
            json_response(self, 200, build_dashboard_payload())
            return
        self.serve_file(path)

    def do_POST(self):
        path = unquote(urlparse(self.path).path)
        if path != "/api/detections":
            json_response(self, 404, {"error": "Not found"})
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length)
        try:
            fields, files = parse_multipart(self.headers, body)
            metadata_text = fields.get("metadata")
            if not metadata_text:
                raise ValueError("Missing metadata multipart field")
            metadata = json.loads(metadata_text)
            remote_id, values = upsert_detection(metadata, files)
            json_response(self, 200, {
                "status": "synced",
                "remote_id": remote_id,
                "frame_url": values["frame_url"],
                "crop_url": values["crop_url"],
                "annotated_url": values["annotated_url"],
            })
        except Exception as exc:
            json_response(self, 400, {"status": "error", "error": str(exc)})

    def serve_file(self, path):
        if path == "/":
            path = "/index.html"
        if path.startswith("/server/uploads/"):
            base = ROOT
        else:
            base = PUBLIC_DIR
        target = (base / path.lstrip("/")).resolve()
        try:
            target.relative_to(base.resolve())
        except ValueError:
            self.send_error(403)
            return
        if not target.exists() or not target.is_file():
            self.send_error(404)
            return
        content_type, _ = mimetypes.guess_type(target.name)
        content_type = content_type or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(target.stat().st_size))
        self.end_headers()
        with target.open("rb") as fh:
            shutil.copyfileobj(fh, self.wfile)

    def log_message(self, fmt, *args):
        sys.stderr.write("[%s] %s\n" % (self.log_date_time_string(), fmt % args))


def main():
    ensure_db()
    host = os.environ.get("MGRADING_HOST", "0.0.0.0")
    port = int(os.environ.get("MGRADING_PORT", "8080"))
    server = ThreadingHTTPServer((host, port), DashboardHandler)
    print(f"mGradingUSTP dashboard server: http://{host}:{port}")
    print(f"DB: {DB_PATH}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped")


if __name__ == "__main__":
    main()
