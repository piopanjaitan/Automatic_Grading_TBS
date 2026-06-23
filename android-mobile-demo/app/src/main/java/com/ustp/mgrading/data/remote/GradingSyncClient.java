package com.ustp.mgrading.data.remote;

import com.ustp.mgrading.data.local.GradingTag;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class GradingSyncClient {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final String serverUrl;

    public GradingSyncClient(String serverUrl) {
        this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
    }

    public SyncResponse upload(GradingTag tag) throws Exception {
        if (serverUrl.isEmpty()) {
            throw new IOException("URL server dashboard belum diisi");
        }
        String boundary = "mgrading-" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(serverUrl + "/api/detections").openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream output = new DataOutputStream(connection.getOutputStream())) {
            writeTextPart(output, boundary, "metadata", toMetadata(tag).toString());
            writeFilePart(output, boundary, "frame", tag.getImagePath());
            writeFilePart(output, boundary, "crop", tag.getCropPath());
            writeFilePart(output, boundary, "annotated", tag.getAnnotatedImagePath());
            output.writeBytes("--" + boundary + "--\r\n");
            output.flush();
        }

        int code = connection.getResponseCode();
        String responseText = readResponse(connection, code);
        if (code < 200 || code >= 300) {
            throw new IOException(String.format(Locale.US, "HTTP %d: %s", code, responseText));
        }
        JSONObject response = new JSONObject(responseText);
        return new SyncResponse(response.optLong("remote_id", 0L), responseText);
    }

    private JSONObject toMetadata(GradingTag tag) throws Exception {
        JSONObject json = new JSONObject();
        json.put("device_id", tag.getDeviceId());
        json.put("local_id", tag.getId());
        json.put("tag_code", tag.getTagCode());
        json.put("session_id", tag.getSessionId());
        json.put("class_id", tag.getClassId());
        json.put("label", tag.getLabel());
        json.put("confidence", tag.getConfidence());
        json.put("bbox_left", tag.getBboxLeft());
        json.put("bbox_top", tag.getBboxTop());
        json.put("bbox_right", tag.getBboxRight());
        json.put("bbox_bottom", tag.getBboxBottom());
        json.put("fingerprint", tag.getFingerprint());
        json.put("created_at", tag.getCreatedAt());
        json.put("last_seen_at", tag.getLastSeenAt());
        json.put("seen_count", tag.getSeenCount());
        return json;
    }

    private void writeTextPart(DataOutputStream output, String boundary, String name, String value) throws IOException {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n");
        output.writeBytes("Content-Type: text/plain; charset=utf-8\r\n\r\n");
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.writeBytes("\r\n");
    }

    private void writeFilePart(DataOutputStream output, String boundary, String name, String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            return;
        }
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getName() + "\"\r\n");
        output.writeBytes("Content-Type: image/jpeg\r\n\r\n");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
        }
        output.writeBytes("\r\n");
    }

    private String readResponse(HttpURLConnection connection, int code) throws IOException {
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (BufferedInputStream input = new BufferedInputStream(stream);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static class SyncResponse {
        public final long remoteId;
        public final String rawBody;

        SyncResponse(long remoteId, String rawBody) {
            this.remoteId = remoteId;
            this.rawBody = rawBody;
        }
    }
}
