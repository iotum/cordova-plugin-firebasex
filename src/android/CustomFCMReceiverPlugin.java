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
import androidx.core.app.NotificationManagerCompat;

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
            if (initialApplicationContext == null) {
                Log.w(TAG, "initialize called with null context, ignoring");
                return;
            }
            // Normalize to application context to avoid Activity leaks
            applicationContext = initialApplicationContext.getApplicationContext();

            if (initialized) {
                Log.d(TAG, "Already initialized, skipping duplicate registration");
                return;
            }
            Log.d(TAG, "initialize");
            try {
                Log.d(TAG, "applicationContext: " + applicationContext.toString());
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
     * Posts (or cancels) a low-importance notification whose sole purpose is to
     * carry the badge count for launchers (like Pixel) that derive badge numbers
     * from active notifications rather than ShortcutBadger.
     *
     * Note: IMPORTANCE_MIN suppresses sound/vibration/heads-up but the notification
     * may still appear as a minimal entry in the notification shade.
     */
    private static void updateBadgeNotification(int count) {
        if (applicationContext == null) {
            Log.w(TAG, "Cannot update badge notification: context is null");
            return;
        }

        NotificationManager nm = (NotificationManager) applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // On Android 13+ (targetSdk 33), posting notifications without POST_NOTIFICATIONS
        // permission throws SecurityException. Check before proceeding.
        NotificationManagerCompat nmc = NotificationManagerCompat.from(applicationContext);
        if (!nmc.areNotificationsEnabled()) {
            Log.d(TAG, "Notifications disabled, skipping badge notification");
            return;
        }

        if (count <= 0) {
            try {
                nm.cancel(BADGE_NOTIFICATION_ID);
            } catch (SecurityException e) {
                Log.w(TAG, "SecurityException cancelling badge notification: " + e.getMessage());
            }
            return;
        }

        // Create a low-importance channel (no sound, no vibration, no heads-up)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(BADGE_CHANNEL_ID);
            if (channel == null) {
                // Try to load channel name from app resources; fall back to default
                String channelName = "Badge Updates";
                int nameResId = applicationContext.getResources().getIdentifier(
                        "badge_channel_name", "string", applicationContext.getPackageName());
                if (nameResId != 0) {
                    channelName = applicationContext.getString(nameResId);
                }

                channel = new NotificationChannel(
                        BADGE_CHANNEL_ID,
                        channelName,
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
                .setContentTitle("")
                .setContentText("")
                .setNumber(count)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setSilent(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .build();

        try {
            nm.notify(BADGE_NOTIFICATION_ID, notification);
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException posting badge notification: " + e.getMessage());
        }
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