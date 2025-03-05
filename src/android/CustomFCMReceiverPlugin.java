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
import com.dmarc.cordovacall.MyConnectionService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;

public class CustomFCMReceiverPlugin {
    static final String TAG = "CustomFCMReceiverPlugin";
    private CustomFCMReceiver customFCMReceiver;
    private TelecomManager tm;
    private PhoneAccount phoneAccount;
    private PhoneAccountHandle handle;
    private String appName;
    private String from;
    private Context applicationContext;

    public void initialize(Context initialApplicationContext) {
        Log.d(TAG, "initialize");
        try {
            Log.d(TAG, "initialApplicationContext: " + initialApplicationContext.toString());
            applicationContext = initialApplicationContext;
            customFCMReceiver = new CustomFCMReceiver();
            appName = getApplicationName();
            tm = (TelecomManager) applicationContext.getSystemService(Context.TELECOM_SERVICE);
            handle = getExistingPhoneAccountHandle();
        } catch (Exception e) {
            handleException("Initializing plugin", e);
        }
    }

    private String getApplicationName() {
        ApplicationInfo applicationInfo = applicationContext.getApplicationInfo();
        int stringId = applicationInfo.labelRes;
        return stringId == 0 ? applicationInfo.nonLocalizedLabel.toString() : applicationContext.getString(stringId);
    }

    private PhoneAccountHandle getExistingPhoneAccountHandle() {
        for (PhoneAccountHandle accountHandle : tm.getCallCapablePhoneAccounts()) {
            PhoneAccount phoneAccount = tm.getPhoneAccount(accountHandle);
            if (phoneAccount != null && phoneAccount.getLabel().toString().equals(appName)) {
                return accountHandle;
            }
        }
        return null;
    }

    protected static void handleError(String errorMsg) {
        Log.e(TAG, errorMsg);
    }

    protected static void handleException(String description, Exception exception) {
        handleError(description + ": " + exception.toString());
    }

    private Map<String, String> bundleToMap(Bundle extras) {
        Map<String, String> map = new HashMap<String, String>();

        Set<String> ks = extras.keySet();
        Iterator<String> iterator = ks.iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            map.put(key, extras.getString(key));
        }
        return map;
    }

    private boolean inspectAndHandleMessageData(Map<String, String> data) {
        boolean isHandled = false;
        Log.d(TAG, "Inspecting message.");
        Log.d(TAG, data.toString());

        if (data.containsKey("callType")) {
            isHandled = true;
            Log.d(TAG, "Calling receiveCallFrom");

            Bundle callInfo = new Bundle();
            callInfo.putString("from", data.get("callerName"));
            tm.addNewIncomingCall(handle, callInfo);

            // TODO: Fix the app opening while ringing, verify it works on the background
            // Make sure the app only opens up when in the background
            openFacetalkApp();
            tm.showInCallScreen(true);
        }
        return isHandled;
    }

    protected void requestRuntimePermission() {
        Intent intent = new Intent(applicationContext, PermissionActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_FROM_BACKGROUND);
        applicationContext.startActivity(intent);
    }

    private void openFacetalkApp() {
        if (applicationContext != null) {
            PackageManager packageManager = applicationContext.getPackageManager();
            Intent intent = packageManager.getLaunchIntentForPackage(applicationContext.getPackageName());
            // Intent.FLAG_ACTIVITY_REORDER_TO_FRONT Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_FROM_BACKGROUND);
            applicationContext.startActivity(intent);
        }
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

            try {
                Map<String, String> data = bundleToMap(bundle);
                isHandled = inspectAndHandleMessageData(data);
            } catch (Exception e) {
                handleException("sendMessage", e);
            }

            return isHandled;
        }
    }

    private class PermissionActivity extends Activity {
        static final String TAG = "PermissionActivity";

        private static final int REQUEST_PHONE_PERMISSION = 1;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permission not granted");
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                            Manifest.permission.READ_PHONE_NUMBERS)) {
                        Log.d(TAG, "Permission denied by user previously");
                    } else {
                        Log.d(TAG, "Requesting permission");
                        ActivityCompat.requestPermissions(this,
                                new String[] { Manifest.permission.READ_PHONE_NUMBERS }, REQUEST_PHONE_PERMISSION);
                    }
                }
            }
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();
            finish(); // Close the activity once permission is handled
        }
    }
}