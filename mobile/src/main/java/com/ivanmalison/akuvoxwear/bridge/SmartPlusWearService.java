package com.ivanmalison.akuvoxwear.bridge;

import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class SmartPlusWearService extends WearableListenerService {
    private static final String TAG = "SmartPlusWear";
    private static final String REQUEST_PATH = "/akuvox/unlock/request";
    private static final String RESULT_PATH = "/akuvox/unlock/result";

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!REQUEST_PATH.equals(event.getPath())) {
            return;
        }

        String[] parts = new String(event.getData(), StandardCharsets.UTF_8).split("\0", -1);
        String requestId = parts[0];
        String sourceNodeId = event.getSourceNodeId();
        if (!isValidRequest(parts)) {
            Log.w(TAG, "Rejected malformed request from watch");
            sendResult(sourceNodeId, requestId, false, DoorUnlocker.PIN_NOT_REQUESTED, "Invalid watch request");
            return;
        }
        boolean armPin = Boolean.parseBoolean(parts[1]);

        Log.i(TAG, "Received unlock request from watch" + (armPin ? " with pin" : ""));
        DoorUnlocker.unlock(
                this,
                armPin,
                (success, pin, message) -> sendResult(sourceNodeId, requestId, success, pin, message)
        );
    }

    private static boolean isValidRequest(String[] parts) {
        if (parts.length != 2 || !("true".equals(parts[1]) || "false".equals(parts[1]))) {
            return false;
        }
        try {
            UUID.fromString(parts[0]);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void sendResult(String nodeId, String requestId, boolean success, String pin, String message) {
        String safeMessage = message == null || message.isBlank()
                ? (success ? "Door opened" : "Unlock failed")
                : message.replace('\0', ' ');
        if (safeMessage.length() > 240) {
            safeMessage = safeMessage.substring(0, 240);
        }
        byte[] payload = (requestId + '\0' + success + '\0' + pin + '\0' + safeMessage)
                .getBytes(StandardCharsets.UTF_8);
        try {
            Wearable.getMessageClient(this).sendMessage(nodeId, RESULT_PATH, payload)
                    .addOnFailureListener(exception -> Log.e(TAG, "Unable to reply to watch", exception));
        } catch (LinkageError | RuntimeException exception) {
            Log.e(TAG, "Unable to create reply to watch", exception);
        }
    }
}
