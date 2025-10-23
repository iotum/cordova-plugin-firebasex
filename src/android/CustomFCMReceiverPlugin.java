package org.apache.cordova.firebase;

import android.os.Bundle;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import android.app.Activity;
import android.Manifest;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.firebase.FirebasePluginMessageReceiver;

import com.dmarc.cordovacall.InComingCallReceiver;
import com.dmarc.cordovacall.MyConnectionService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;
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
            intent.putExtra("from", payload.optString("from", ""));
            intent.putExtra("payload", payloadString);
            Log.d(TAG, "launching startService() intent for MyConnectionService...");
            this.applicationContext.startService(intent); // starts the service (if not running) this always results in a call to service.onStartCommand()
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