package org.apache.cordova.firebase;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import com.dmarc.cordovacall.MyConnectionService; // TODO dereference by switching to implicit intent
import org.apache.cordova.firebase.FirebasePluginMessageReceiver;
import com.google.firebase.messaging.RemoteMessage;


import me.leolin.shortcutbadger.ShortcutBadger;

import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

public class CustomFCMReceiverPlugin {
    static final String TAG = "CustomFCMReceiverPlugin";
    private static CustomFCMReceiver customFCMReceiver;

    private static Context applicationContext;

    private static volatile boolean initialized = false;

    public void initialize(Context initialApplicationContext) {
        synchronized (CustomFCMReceiverPlugin.class) {
            // Always update the context in case it changed
            applicationContext = initialApplicationContext;

            if (initialized) {
                Log.d(TAG, "Already initialized, skipping duplicate registration");
                return;
            }
            Log.d(TAG, "initialize");
            try {
                Log.d(TAG, "initialApplicationContext: " + initialApplicationContext.toString());
                customFCMReceiver = new CustomFCMReceiver();
                initialized = true;
            } catch (Exception e) {
                handleException("Initializing plugin", e);
            }
        }
    }

    protected static void handleError(String errorMsg) {
        Log.e(TAG, errorMsg);
    }

    protected static void handleException(String description, Exception exception) {
        handleError(description + ": " + exception.toString());
    }

    private static boolean inspectAndHandleMessageData(Map<String, String> data) throws JSONException {
        boolean isHandled = false;
        Log.d(TAG, "inspectAndHandleMessageData: " + data);

        String payloadString = data.get("payload");
        if (payloadString == null) {
            return isHandled;
        }

        JSONObject payload = new JSONObject(payloadString);

        String type = payload.optString("type");
        if (type.equals("badge_update")) {
            isHandled = true;
            int total = payload.optInt("total", -1);
            if (total >= 0) {
                FirebasePlugin.persistBadgeNumber(applicationContext, total);
                ShortcutBadger.applyCount(applicationContext, total);
                updateBadgeNotification(total);
                Log.d(TAG, "Persisted badge_update total=" + total);
            }
        } else if (type.equals("incoming_phone_call") || type.equals("incoming_video_call")) {
            isHandled = true;

            Intent intent = new Intent("INCOMING_CALL_INVITE");
            intent.setComponent(new ComponentName(applicationContext, MyConnectionService.class));
            intent.putExtra("payload", payloadString);

            // When you call startService() for an Android Service that is already running, a new instance of the service is not created.
            // Instead, the onStartCommand() method of the existing service instance is called again.
            // This allows you to deliver a new Intent to the running service,
            // enabling it to process new requests or update its state without creating redundant instances.
            // The ConnectionService needs to be started if for any reason its not currently running.
            Log.d(TAG, "launching startService() intent for MyConnectionService...");
            applicationContext.startService(intent);
        }

        return isHandled;
    }

    private static final String BADGE_CHANNEL_ID = "iotum_badge_channel";
    private static final int BADGE_NOTIFICATION_ID = 9999;

    /**
     * Posts (or cancels) a silent, invisible notification whose sole purpose is to
     * carry the badge count for launchers (like Pixel) that derive badge numbers
     * from active notifications rather than ShortcutBadger.
     */
    private static void updateBadgeNotification(int count) {
        NotificationManager nm = (NotificationManager) applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (count <= 0) {
            nm.cancel(BADGE_NOTIFICATION_ID);
            return;
        }

        // Create a low-importance channel (no sound, no vibration, no heads-up)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(BADGE_CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(
                        BADGE_CHANNEL_ID,
                        "Badge Updates",
                        NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(true);
                channel.enableLights(false);
                channel.enableVibration(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(applicationContext, BADGE_CHANNEL_ID)
                .setSmallIcon(applicationContext.getApplicationInfo().icon)
                .setNumber(count)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setOngoing(false)
                .build();

        nm.notify(BADGE_NOTIFICATION_ID, notification);
    }

    private static class CustomFCMReceiver extends FirebasePluginMessageReceiver {
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