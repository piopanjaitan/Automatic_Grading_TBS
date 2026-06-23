package com.ustp.mgrading.data.local;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class GradingDbHelper extends SQLiteOpenHelper {
    public static final String DATABASE_NAME = "mgrading.db";
    private static final String LEGACY_DATABASE_NAME = "mgrading_ustp.db";
    public static final int DATABASE_VERSION = 3;
    public static final String TABLE_TAGS = "grading_tags";

    public GradingDbHelper(Context context) {
        super(context, resolveExternalDatabasePath(context), null, DATABASE_VERSION);
    }

    public static File getExternalDatabaseFile(Context context) {
        File externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (externalDir == null) {
            externalDir = context.getExternalFilesDir(null);
        }
        if (externalDir == null) {
            externalDir = context.getFilesDir();
        }
        return new File(externalDir, DATABASE_NAME);
    }

    private static String resolveExternalDatabasePath(Context context) {
        File databaseFile = getExternalDatabaseFile(context);
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        migrateLegacyDatabaseIfNeeded(context, databaseFile);
        return databaseFile.getAbsolutePath();
    }

    private static void migrateLegacyDatabaseIfNeeded(Context context, File targetFile) {
        if (targetFile.exists()) {
            return;
        }
        File legacyFile = context.getDatabasePath(LEGACY_DATABASE_NAME);
        if (!legacyFile.exists()) {
            return;
        }
        try {
            copyFile(legacyFile, targetFile);
        } catch (IOException ignored) {
            // If migration fails, SQLiteOpenHelper will create a fresh external database.
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) > 0) {
                output.write(buffer, 0, length);
            }
            output.flush();
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TAGS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "tag_code TEXT UNIQUE,"
                + "class_id INTEGER NOT NULL,"
                + "label TEXT NOT NULL,"
                + "confidence REAL NOT NULL,"
                + "bbox_left REAL NOT NULL,"
                + "bbox_top REAL NOT NULL,"
                + "bbox_right REAL NOT NULL,"
                + "bbox_bottom REAL NOT NULL,"
                + "image_path TEXT NOT NULL,"
                + "crop_path TEXT NOT NULL,"
                + "annotated_image_path TEXT,"
                + "fingerprint TEXT NOT NULL,"
                + "session_id TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "last_seen_at INTEGER NOT NULL,"
                + "seen_count INTEGER NOT NULL DEFAULT 1,"
                + "device_id TEXT,"
                + "sync_status TEXT NOT NULL DEFAULT 'PENDING',"
                + "remote_id INTEGER,"
                + "synced_at INTEGER,"
                + "sync_attempts INTEGER NOT NULL DEFAULT 0,"
                + "sync_error TEXT"
                + ")");
        db.execSQL("CREATE INDEX idx_grading_tags_class_fp ON " + TABLE_TAGS + "(class_id, fingerprint)");
        db.execSQL("CREATE INDEX idx_grading_tags_last_seen ON " + TABLE_TAGS + "(last_seen_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            addColumnIfMissing(db, "annotated_image_path", "TEXT");
            addColumnIfMissing(db, "session_id", "TEXT");
        }
        if (oldVersion < 3) {
            addColumnIfMissing(db, "device_id", "TEXT");
            addColumnIfMissing(db, "sync_status", "TEXT NOT NULL DEFAULT 'PENDING'");
            addColumnIfMissing(db, "remote_id", "INTEGER");
            addColumnIfMissing(db, "synced_at", "INTEGER");
            addColumnIfMissing(db, "sync_attempts", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, "sync_error", "TEXT");
        }
    }

    private void addColumnIfMissing(SQLiteDatabase db, String columnName, String columnType) {
        if (!hasColumn(db, columnName)) {
            db.execSQL("ALTER TABLE " + TABLE_TAGS + " ADD COLUMN " + columnName + " " + columnType);
        }
    }

    private boolean hasColumn(SQLiteDatabase db, String columnName) {
        try (android.database.Cursor cursor = db.rawQuery("PRAGMA table_info(" + TABLE_TAGS + ")", null)) {
            while (cursor.moveToNext()) {
                String existingName = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                if (columnName.equals(existingName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
