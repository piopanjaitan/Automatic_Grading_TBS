package com.ustp.mgrading.data.local;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.ustp.mgrading.data.ml.DetectionResult;
import com.ustp.mgrading.data.remote.GradingSyncSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GradingTagRepository {
    private static final int DEFAULT_MATCH_DISTANCE = 10;
    private static final float MIN_LIST_CONFIDENCE = 0.50f;
    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED = "SYNCED";
    public static final String SYNC_FAILED = "FAILED";
    private final GradingDbHelper dbHelper;
    private final String deviceId;

    public GradingTagRepository(Context context) {
        dbHelper = new GradingDbHelper(context.getApplicationContext());
        deviceId = GradingSyncSettings.getDeviceId(context.getApplicationContext());
        migrateTagCodesToTbs();
        migrateMissingDeviceIds();
    }

    private void migrateTagCodesToTbs() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL("UPDATE " + GradingDbHelper.TABLE_TAGS
                + " SET tag_code='TBS-' || substr(tag_code, 5)"
                + " WHERE tag_code LIKE 'TPH-%'");
    }

    private void migrateMissingDeviceIds() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("device_id", deviceId);
        db.update(GradingDbHelper.TABLE_TAGS, values, "device_id IS NULL OR device_id=''", null);
    }

    public synchronized GradingTag findMatch(int classId, String fingerprint, List<Long> excludedIds) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<GradingTag> candidates = new ArrayList<>();
        try (Cursor cursor = db.query(
                GradingDbHelper.TABLE_TAGS,
                null,
                "class_id=?",
                new String[]{String.valueOf(classId)},
                null,
                null,
                "last_seen_at DESC"
        )) {
            while (cursor.moveToNext()) {
                GradingTag tag = fromCursor(cursor);
                if (excludedIds != null && excludedIds.contains(tag.getId())) {
                    continue;
                }
                candidates.add(tag);
            }
        }

        GradingTag best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (GradingTag candidate : candidates) {
            int distance = hammingDistance(fingerprint, candidate.getFingerprint());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= DEFAULT_MATCH_DISTANCE ? best : null;
    }

    public synchronized GradingTag insertTag(DetectionResult detection, String imagePath, String cropPath,
                                             String annotatedImagePath, String fingerprint, String sessionId, long now) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("tag_code", "");
        values.put("class_id", detection.getClassId());
        values.put("label", detection.getLabel());
        values.put("confidence", detection.getConfidence());
        values.put("bbox_left", detection.getBox().left);
        values.put("bbox_top", detection.getBox().top);
        values.put("bbox_right", detection.getBox().right);
        values.put("bbox_bottom", detection.getBox().bottom);
        values.put("image_path", imagePath);
        values.put("crop_path", cropPath);
        values.put("annotated_image_path", annotatedImagePath);
        values.put("fingerprint", fingerprint);
        values.put("session_id", sessionId);
        values.put("created_at", now);
        values.put("last_seen_at", now);
        values.put("seen_count", 1);
        values.put("device_id", deviceId);
        values.put("sync_status", SYNC_PENDING);
        values.putNull("remote_id");
        values.putNull("synced_at");
        values.put("sync_attempts", 0);
        values.putNull("sync_error");
        long id = db.insertOrThrow(GradingDbHelper.TABLE_TAGS, null, values);
        String tagCode = String.format(Locale.US, "TBS-%06d", id);
        ContentValues update = new ContentValues();
        update.put("tag_code", tagCode);
        db.update(GradingDbHelper.TABLE_TAGS, update, "id=?", new String[]{String.valueOf(id)});
        return getById(id);
    }

    public synchronized GradingTag markSeen(GradingTag tag, DetectionResult detection, String sessionId, long now, boolean incrementCount) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("confidence", Math.max(tag.getConfidence(), detection.getConfidence()));
        values.put("bbox_left", detection.getBox().left);
        values.put("bbox_top", detection.getBox().top);
        values.put("bbox_right", detection.getBox().right);
        values.put("bbox_bottom", detection.getBox().bottom);
        values.put("last_seen_at", now);
        values.put("seen_count", incrementCount ? tag.getSeenCount() + 1 : tag.getSeenCount());
        values.put("session_id", sessionId);
        values.put("sync_status", SYNC_PENDING);
        values.putNull("sync_error");
        db.update(GradingDbHelper.TABLE_TAGS, values, "id=?", new String[]{String.valueOf(tag.getId())});
        return getById(tag.getId());
    }

    public synchronized void updateAnnotatedImagePath(List<Long> tagIds, String annotatedImagePath) {
        if (tagIds == null || tagIds.isEmpty() || annotatedImagePath == null || annotatedImagePath.trim().isEmpty()) {
            return;
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("annotated_image_path", annotatedImagePath);
        values.put("sync_status", SYNC_PENDING);
        values.putNull("sync_error");
        for (Long tagId : tagIds) {
            if (tagId != null) {
                db.update(GradingDbHelper.TABLE_TAGS, values, "id=?", new String[]{String.valueOf(tagId)});
            }
        }
    }

    public synchronized List<GradingTag> getRecentTags(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<GradingTag> tags = new ArrayList<>();
        try (Cursor cursor = db.query(
                GradingDbHelper.TABLE_TAGS,
                null,
                "confidence>=?",
                new String[]{String.valueOf(MIN_LIST_CONFIDENCE)},
                null,
                null,
                "last_seen_at DESC",
                String.valueOf(limit)
        )) {
            while (cursor.moveToNext()) {
                tags.add(fromCursor(cursor));
            }
        }
        return tags;
    }

    public synchronized List<GradingTag> getAllSavedTags() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<GradingTag> tags = new ArrayList<>();
        try (Cursor cursor = db.query(
                GradingDbHelper.TABLE_TAGS,
                null,
                "confidence>=?",
                new String[]{String.valueOf(MIN_LIST_CONFIDENCE)},
                null,
                null,
                "last_seen_at DESC"
        )) {
            while (cursor.moveToNext()) {
                tags.add(fromCursor(cursor));
            }
        }
        return tags;
    }

    public synchronized List<GradingTag> getPendingSyncTags(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<GradingTag> tags = new ArrayList<>();
        try (Cursor cursor = db.query(
                GradingDbHelper.TABLE_TAGS,
                null,
                "confidence>=? AND (sync_status IS NULL OR sync_status!=?)",
                new String[]{String.valueOf(MIN_LIST_CONFIDENCE), SYNC_SYNCED},
                null,
                null,
                "created_at ASC",
                String.valueOf(limit)
        )) {
            while (cursor.moveToNext()) {
                tags.add(fromCursor(cursor));
            }
        }
        return tags;
    }

    public synchronized int countPendingSyncTags() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + GradingDbHelper.TABLE_TAGS
                        + " WHERE confidence>=? AND (sync_status IS NULL OR sync_status!=?)",
                new String[]{String.valueOf(MIN_LIST_CONFIDENCE), SYNC_SYNCED}
        )) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized int countFailedSyncTags() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + GradingDbHelper.TABLE_TAGS
                        + " WHERE confidence>=? AND sync_status=?",
                new String[]{String.valueOf(MIN_LIST_CONFIDENCE), SYNC_FAILED}
        )) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized void markSyncSuccess(long id, long remoteId, long now) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("sync_status", SYNC_SYNCED);
        values.put("remote_id", remoteId);
        values.put("synced_at", now);
        values.putNull("sync_error");
        db.update(GradingDbHelper.TABLE_TAGS, values, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void markSyncFailed(long id, String error) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("sync_status", SYNC_FAILED);
        values.put("sync_error", error == null ? "Upload gagal" : error);
        db.execSQL("UPDATE " + GradingDbHelper.TABLE_TAGS
                        + " SET sync_status=?, sync_error=?, sync_attempts=COALESCE(sync_attempts,0)+1 WHERE id=?",
                new Object[]{values.getAsString("sync_status"), values.getAsString("sync_error"), id});
    }

    private GradingTag getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.query(
                GradingDbHelper.TABLE_TAGS,
                null,
                "id=?",
                new String[]{String.valueOf(id)},
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return fromCursor(cursor);
            }
        }
        throw new IllegalStateException("Tag not found: " + id);
    }

    private GradingTag fromCursor(Cursor cursor) {
        return new GradingTag(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("tag_code")),
                cursor.getInt(cursor.getColumnIndexOrThrow("class_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("label")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("bbox_left")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("bbox_top")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("bbox_right")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("bbox_bottom")),
                cursor.getString(cursor.getColumnIndexOrThrow("image_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("crop_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("annotated_image_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("fingerprint")),
                cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
                cursor.getInt(cursor.getColumnIndexOrThrow("seen_count")),
                getStringOrDefault(cursor, "device_id", deviceId),
                getStringOrDefault(cursor, "sync_status", SYNC_PENDING),
                getNullableLong(cursor, "remote_id"),
                getNullableLong(cursor, "synced_at"),
                getIntOrDefault(cursor, "sync_attempts", 0),
                getStringOrDefault(cursor, "sync_error", null)
        );
    }

    private String getStringOrDefault(Cursor cursor, String columnName, String defaultValue) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0 || cursor.isNull(index)) {
            return defaultValue;
        }
        return cursor.getString(index);
    }

    private Long getNullableLong(Cursor cursor, String columnName) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0 || cursor.isNull(index)) {
            return null;
        }
        return cursor.getLong(index);
    }

    private int getIntOrDefault(Cursor cursor, String columnName, int defaultValue) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0 || cursor.isNull(index)) {
            return defaultValue;
        }
        return cursor.getInt(index);
    }

    private int hammingDistance(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return Integer.MAX_VALUE;
        }
        int distance = 0;
        for (int i = 0; i < a.length(); i++) {
            int left = Character.digit(a.charAt(i), 16);
            int right = Character.digit(b.charAt(i), 16);
            if (left < 0 || right < 0) {
                return Integer.MAX_VALUE;
            }
            distance += Integer.bitCount(left ^ right);
        }
        return distance;
    }
}
