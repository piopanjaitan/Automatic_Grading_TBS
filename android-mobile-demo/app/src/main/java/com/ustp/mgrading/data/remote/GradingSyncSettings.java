package com.ustp.mgrading.data.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.UUID;

public final class GradingSyncSettings {
    private static final String PREFS = "mgrading_sync";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_DEVICE_ID = "device_id";

    private GradingSyncSettings() {
    }

    public static String getServerUrl(Context context) {
        return prefs(context).getString(KEY_SERVER_URL, "");
    }

    public static void setServerUrl(Context context, String serverUrl) {
        prefs(context).edit().putString(KEY_SERVER_URL, normalizeServerUrl(serverUrl)).apply();
    }

    public static String getDeviceId(Context context) {
        SharedPreferences prefs = prefs(context);
        String existing = prefs.getString(KEY_DEVICE_ID, "");
        if (existing != null && !existing.trim().isEmpty()) {
            return existing;
        }
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String deviceId = androidId == null || androidId.trim().isEmpty()
                ? "android-" + UUID.randomUUID()
                : "android-" + androidId;
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        return deviceId;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalizeServerUrl(String serverUrl) {
        if (serverUrl == null) {
            return "";
        }
        String value = serverUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
