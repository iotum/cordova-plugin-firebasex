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

import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONException;
import org.json.JSONObject;

public class CustomFCMReceiverPlugin {
    static final String TAG = "CustomFCMReceiverPlugin";
    private static final String PREFS_NAME = "CustomFCMReceiverPluginPrefs";
    private static final String PREF_LAST_BADGE_TIMESTAMP = "lastBadgeTimestampMs";
    private static volatile CustomFCMReceiver customFCMReceiver;

    private static volatile Context applicationContext;

    private static volatile boolean initialized = false;

    /** Tracks the last-processed badge timestamp to avoid processing out-of-order updates.
     *  Initialized lazily from SharedPreferences so state survives app restarts. */
    private static final AtomicLong lastBadgeTimestampMs = new AtomicLong(0);
    private static volatile boolean timestampInitialized = false;

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
                ensureTimestampInitialized();
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

    /**
     * Ensures the AtomicLong is initialized from SharedPreferences (once).
     * This allows the timestamp state to survive app restarts.
     */
    private static void ensureTimestampInitialized() {
        if (!timestampInitialized) {
            synchronized (CustomFCMReceiverPlugin.class) {
                if (!timestampInitialized) {
                    Context ctx = applicationContext;
                    if (ctx != null) {
                        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        long persisted = prefs.getLong(PREF_LAST_BADGE_TIMESTAMP, 0);
                        lastBadgeTimestampMs.set(persisted);
                    } else {
                        lastBadgeTimestampMs.set(0);
                    }
                    timestampInitialized = true;
                }
            }
        }
    }

    /**
     * Persists the last badge timestamp to SharedPreferences so it survives app restarts.
     */
    private static void persistBadgeTimestamp(long timestampMs) {
        Context ctx = applicationContext;
        if (ctx != null) {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putLong(PREF_LAST_BADGE_TIMESTAMP, timestampMs).apply();
        }
    }

    private static Integer getBadgeTotal(JSONObject payload) {
        JSONObject badgeCounts = payload.optJSONObject("badge_counts");
        if (badgeCounts != null) {
            int candidateTotal = badgeCounts.optInt("total", -1);
            if (candidateTotal >= 0) {
                return candidateTotal;
            }
        }
        return null;
    }

    private static long getBadgeTimestampMs(JSONObject payload) {
        JSONObject badgeCounts = payload.optJSONObject("badge_counts");
        if (badgeCounts != null) {
            long ts = badgeCounts.optLong("timestamp_ms", 0);
            if (ts > 0) return ts;
        }
        return 0;
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
        Integer badgeTotal = getBadgeTotal(payload);
        if (badgeTotal != null) {
            long timestampMs = getBadgeTimestampMs(payload);
            // Only process if timestamp is newer than the last processed one (or if no timestamp provided).
            // Use compareAndSet loop for thread-safe atomic update.
            if (timestampMs > 0) {
                ensureTimestampInitialized();
                long current = lastBadgeTimestampMs.get();
                if (timestampMs <= current) {
                    Log.d(TAG, "Skipping stale badge update: timestamp_ms=" + timestampMs
                            + " <= lastProcessed=" + current);
                } else {
                    // Attempt atomic update; if another thread updated in the meantime, re-check
                    while (!lastBadgeTimestampMs.compareAndSet(current, timestampMs)) {
                        current = lastBadgeTimestampMs.get();
                        if (timestampMs <= current) {
                            Log.d(TAG, "Skipping stale badge update after CAS retry: timestamp_ms=" + timestampMs
                                    + " <= lastProcessed=" + current);
                            badgeTotal = null;
                            break;
                        }
                    }
                    if (badgeTotal != null) {
                        persistBadgeTimestamp(timestampMs);
                        applyBadge(badgeTotal, type, timestampMs);
                    }
                }
            } else {
                // No timestamp provided — always process (backward compatible)
                applyBadge(badgeTotal, type, 0);
            }
        }

        if ("badge_update".equals(type)) {
            isHandled = true;
        } else if (type.equals("incoming_phone_call") || type.equals("incoming_video_call")) {
            Context ctx = applicationContext;
            if (ctx == null) {
                Log.w(TAG, "Cannot handle call intent: context is null");
                return false;
            }
            isHandled = true;

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

    /** Applies the badge count using the appropriate device-specific mechanism. */
    private static void applyBadge(int badgeTotal, String type, long timestampMs) {
        Context ctx = applicationContext;
        if (ctx != null) {
            FirebasePlugin.persistBadgeNumber(ctx, badgeTotal);
            // Samsung launchers use ShortcutBadger; other launchers (e.g., Pixel)
            // derive badges from active notifications. Use only one mechanism per
            // device to avoid double-counting.
            if (isSamsungDevice()) {
                ShortcutBadger.applyCount(ctx, badgeTotal);
            } else {
                updateBadgeNotification(badgeTotal);
            }
            Log.d(TAG, "Persisted badge total=" + badgeTotal + " for type=" + type
                    + " timestamp_ms=" + timestampMs);
        } else {
            Log.w(TAG, "Cannot apply badge update: context is null");
        }
    }

    private static final String BADGE_CHANNEL_ID = "iotum_badge_channel";
    private static final String BADGE_NOTIFICATION_TAG = "iotum_badge";
    private static final int BADGE_NOTIFICATION_ID = 0;

    /**
     * Returns true if the device is manufactured by Samsung, which uses
     * ShortcutBadger for badge counts rather than notification-derived badges.
     * Shared utility used by both CustomFCMReceiverPlugin and FirebasePluginMessagingService.
     */
    static boolean isSamsungDevice() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /**
     * Resolves a non-zero small icon resource ID for notifications.
     * Tries the custom "notification_icon" drawable first, then falls back
     * to applicationInfo.icon, and finally to the Android default app icon.
     */
    private static int getSmallIconResId(Context ctx) {
        int iconResId = ctx.getResources().getIdentifier(
                "notification_icon", "drawable", ctx.getPackageName());
        if (iconResId != 0) return iconResId;

        iconResId = ctx.getApplicationInfo().icon;
        if (iconResId != 0) return iconResId;

        return android.R.drawable.sym_def_app_icon;
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
                nm.cancel(BADGE_NOTIFICATION_TAG, BADGE_NOTIFICATION_ID);
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
                .setSmallIcon(getSmallIconResId(ctx))
                .setContentTitle("")
                .setContentText("")
                .setNumber(count)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setSilent(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();

        try {
            nm.notify(BADGE_NOTIFICATION_TAG, BADGE_NOTIFICATION_ID, notification);
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
