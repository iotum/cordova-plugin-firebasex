package org.apache.cordova.firebase;

import android.os.Bundle;
import android.util.Log;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;

import com.dmarc.cordovacall.MyConnectionService; // TODO dereference by switching to implicit intent
import org.apache.cordova.firebase.FirebasePluginMessageReceiver;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

public class CustomFCMReceiverPlugin {
    static final String TAG = "CustomFCMReceiverPlugin";
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

    private boolean inspectAndHandleMessageData(Map<String, String> data) throws JSONException {
        boolean isHandled = false;
        Log.d(TAG, "inspectAndHandleMessageData: " + data);

        String payloadString = data.get("payload");
        if (payloadString == null) {
            return isHandled;
        }

        JSONObject payload = new JSONObject(payloadString);

        String type = payload.optString("type");
        if (type.equals("incoming_phone_call") || type.equals("incoming_video_call")) {
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

            int originalPriority = remoteMessage.getOriginalPriority();
            int currentPriority = remoteMessage.getPriority(); // PRIORITY_HIGH = 1, PRIORITY_NORMAL = 2, PRIORITY_UNKNOWN = 0

            if (originalPriority != currentPriority) {
                Log.e(TAG, "onMessageReceived: MESSAGE DEPRIORITIZED! originalPriority: " + originalPriority + " currentPriority: " + currentPriority);
                // Note: apps running in the background have restrictions imposed on them:
                // See doc: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
                // When the message is deprioritized like this, this may result in issues later related to notifications, foreground services, etc.
            }

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