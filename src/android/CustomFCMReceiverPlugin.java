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
    private static volatile CustomFCMReceiver customFCMReceiver;

    private static volatile Context applicationContext;

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
                Context ctx = applicationContext;
                if (ctx == null) {
                    Log.w(TAG, "Cannot handle badge_update: context is null");
                    return isHandled;
                }
                FirebasePlugin.persistBadgeNumber(ctx, total);
                // Samsung launchers use ShortcutBadger; other launchers (e.g., Pixel)
                // derive badges from active notifications. Use only one mechanism per
                // device to avoid double-counting.
                if (isSamsungDevice()) {
                    ShortcutBadger.applyCount(ctx, total);
                } else {
                    updateBadgeNotification(total);
                }
                Log.d(TAG, "Persisted badge_update total=" + total);
            }
        } else if (type.equals("incoming_phone_call") || type.equals("incoming_video_call")) {
            isHandled = true;

            Context ctx = applicationContext;
            if (ctx == null) {
                Log.w(TAG, "Cannot handle call intent: context is null");
                return isHandled;
            }

            Intent intent = new Intent("INCOMING_CALL_INVITE");
            intent.setComponent(new ComponentName(ctx, MyConnectionService.class));
            intent.putExtra("payload", payloadString);

            // When you call startService() for an Android Service that is already running, a new instance of the service is not created.
            // Instead, the onStartCommand() method of the existing service instance is called again.
            // This allows you to deliver a new Intent to the running service,
            // enabling it to process new requests or update its state without creating redundant instances.
            // The ConnectionService needs to be started if for any reason its not currently running.
            Log.d(TAG, "launching startService() intent for MyConnectionService...");
            ctx.startService(intent);
        }

        return isHandled;
    }

    private static final String BADGE_CHANNEL_ID = "iotum_badge_channel";
    private static final int BADGE_NOTIFICATION_ID = 9999;

    /**
     * Returns true if the device is manufactured by Samsung, which uses
     * ShortcutBadger for badge counts rather than notification-derived badges.
     */
    private static boolean isSamsungDevice() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /**
     * Posts (or cancels) a low-importance notification whose sole purpose is to
     * carry the badge count for launchers (like Pixel) that derive badge numbers
     * from active notifications rather than ShortcutBadger.
     *
     * Note: IMPORTANCE_MIN suppresses sound/vibration/heads-up but the notification
     * may still appear as a minimal entry in the notification shade.
     */
    private static void updateBadgeNotification(int count) {
        Context ctx = applicationContext;
        if (ctx == null) {
            Log.w(TAG, "Cannot update badge notification: context is null");
            return;
        }

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Cancel path runs regardless of notification permission state to avoid
        // leaving a stale badge notification after permissions are revoked.
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
                int nameResId = ctx.getResources().getIdentifier(
                        "badge_channel_name", "string", ctx.getPackageName());
                if (nameResId != 0) {
                    channelName = ctx.getString(nameResId);
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

        // On Android 13+ (targetSdk 33), posting notifications without POST_NOTIFICATIONS
        // permission throws SecurityException. Only gate the notify() call so channel
        // creation above still runs regardless of permission state.
        NotificationManagerCompat nmc = NotificationManagerCompat.from(ctx);
        if (!nmc.areNotificationsEnabled()) {
            Log.d(TAG, "Notifications disabled, skipping badge notification post");
            return;
        }

        Notification notification = new NotificationCompat.Builder(ctx, BADGE_CHANNEL_ID)
                .setSmallIcon(ctx.getApplicationInfo().icon)
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