package org.apache.cordova.firebase;

import android.os.Bundle;
import android.util.Log;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;

import com.dmarc.cordovacall.MyConnectionService; // TODO dereference by switching to implicit intent
import org.apache.cordova.firebase.FirebasePluginMessageReceiver;
import com.google.firebase.messaging.RemoteMessage;


import me.leolin.shortcutbadger.ShortcutBadger;

import android.content.SharedPreferences;

import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

public class CustomFCMReceiverPlugin {
    static final String TAG = "CustomFCMReceiverPlugin";
    private static final String PREFS_NAME = "CustomFCMReceiverPluginPrefs";
    private static final String PREF_LAST_BADGE_TIMESTAMP = "lastBadgeTimestampMs";
    private CustomFCMReceiver customFCMReceiver;

    private Context applicationContext;

    public void initialize(Context initialApplicationContext) {
        Log.d(TAG, "initialize");
        try {
            Log.d(TAG, "initialApplicationContext: " + initialApplicationContext.toString());
            applicationContext = initialApplicationContext;
            customFCMReceiver = new CustomFCMReceiver();
        } catch (Exception e) {
            handleException("Initializing plugin", e);
        }
    }

    protected static void handleError(String errorMsg) {
        Log.e(TAG, errorMsg);
    }

    protected static void handleException(String description, Exception exception) {
        handleError(description + ": " + exception.toString());
    }

    private long getLastBadgeTimestamp() {
        SharedPreferences prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(PREF_LAST_BADGE_TIMESTAMP, 0);
    }

    private void setLastBadgeTimestamp(long timestampMs) {
        SharedPreferences prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(PREF_LAST_BADGE_TIMESTAMP, timestampMs).apply();
    }

    private long getBadgeTimestamp(JSONObject payload, String type) {
        if ("badge_update".equals(type)) {
            return payload.optLong("timestamp_ms", -1);
        }

        JSONObject badgeCounts = payload.optJSONObject("badge_counts");
        if (badgeCounts != null) {
            return badgeCounts.optLong("timestamp_ms", -1);
        }

        return -1;
    }

    private Integer getBadgeTotal(JSONObject payload, String type) {
        Integer total = null;

        if ("badge_update".equals(type)) {
            int candidateTotal = payload.optInt("total", -1);
            if (candidateTotal >= 0) {
                total = candidateTotal;
            }
        }

        if (total == null) {
            JSONObject badgeCounts = payload.optJSONObject("badge_counts");
            if (badgeCounts != null) {
                int candidateTotal = badgeCounts.optInt("total", -1);
                if (candidateTotal >= 0) {
                    total = candidateTotal;
                }
            }
        }

        return total;
    }

    private boolean inspectAndHandleMessageData(Map<String, String> data) throws JSONException {
        boolean isHandled = false;
        Log.d(TAG, "inspectAndHandleMessageData: " + data);

        String payloadString = data.get("payload");
        if (payloadString == null) {
            return isHandled;
        }

        JSONObject payload = new JSONObject(payloadString);

        String type = payload.optString("type");
        Integer badgeTotal = getBadgeTotal(payload, type);
        if (badgeTotal != null) {
            long timestampMs = getBadgeTimestamp(payload, type);
            boolean shouldProcess = true;
            if (timestampMs > 0) {
                long lastTimestamp = getLastBadgeTimestamp();
                if (timestampMs <= lastTimestamp) {
                    shouldProcess = false;
                    Log.d(TAG, "Skipping stale badge update: timestamp_ms=" + timestampMs + " <= lastTimestamp=" + lastTimestamp);
                }
            }
            if (shouldProcess) {
                if (timestampMs > 0) {
                    setLastBadgeTimestamp(timestampMs);
                }
                FirebasePlugin.persistBadgeNumber(this.applicationContext, badgeTotal);
                ShortcutBadger.applyCount(this.applicationContext, badgeTotal);
                Log.d(TAG, "Persisted badge total=" + badgeTotal + " for type=" + type + " timestamp_ms=" + timestampMs);
            }
        }

        if (type.equals("badge_update")) {
            isHandled = true;
        } else if (type.equals("incoming_phone_call") || type.equals("incoming_video_call")) {
            isHandled = true;

            Intent intent = new Intent("INCOMING_CALL_INVITE");
            intent.setComponent(new ComponentName(this.applicationContext, MyConnectionService.class));
            intent.putExtra("payload", payloadString);

            // When you call startService() for an Android Service that is already running, a new instance of the service is not created.
            // Instead, the onStartCommand() method of the existing service instance is called again.
            // This allows you to deliver a new Intent to the running service,
            // enabling it to process new requests or update its state without creating redundant instances.
            // The ConnectionService needs to be started if for any reason its not currently running.
            Log.d(TAG, "launching startService() intent for MyConnectionService...");
            this.applicationContext.startService(intent);
        }

        return isHandled;
    }

    private class CustomFCMReceiver extends FirebasePluginMessageReceiver {
        @Override
        public boolean onMessageReceived(RemoteMessage remoteMessage) {
            Log.d("CustomFCMReceiver", "onMessageReceived");
            boolean isHandled = false;

            try {
                Map<String, String> data = remoteMessage.getData();
                isHandled = inspectAndHandleMessageData(data);
            } catch (Exception e) {
                handleException("onMessageReceived", e);
            }

            return isHandled;
        }

        @Override
        public boolean sendMessage(Bundle bundle) {
            Log.d("CustomFCMReceiver", "sendMessage");
            boolean isHandled = false;

            // We do not want to intercept sending a notification to Cordova
            return isHandled;
        }
    }
}
