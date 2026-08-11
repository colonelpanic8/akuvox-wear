package com.ivanmalison.akuvoxwear.bridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SmartPlusWearService extends WearableListenerService {
    private static final String TAG = "SmartPlusWear";
    private static final String REQUEST_PATH = "/akuvox/unlock/request";
    private static final String RESULT_PATH = "/akuvox/unlock/result";
    private static final long UNLOCK_TIMEOUT_MS = 12_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!REQUEST_PATH.equals(event.getPath())) {
            return;
        }

        String requestId = new String(event.getData(), StandardCharsets.UTF_8);
        try {
            UUID.fromString(requestId);
        } catch (IllegalArgumentException exception) {
            sendResult(event.getSourceNodeId(), requestId, false, "Invalid watch request");
            return;
        }

        String sourceNodeId = event.getSourceNodeId();
        AtomicBoolean completed = new AtomicBoolean(false);
        mainHandler.postDelayed(
                () -> complete(completed, sourceNodeId, requestId, false, "SmartPlus timed out"),
                UNLOCK_TIMEOUT_MS
        );
        mainHandler.post(() -> {
            try {
                requestFavoriteDoorUnlock((success, message) ->
                        complete(completed, sourceNodeId, requestId, success, message));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                Log.e(TAG, "Unable to request SmartPlus unlock", exception);
                complete(completed, sourceNodeId, requestId, false, "SmartPlus bridge failed");
            }
        });
    }

    private void requestFavoriteDoorUnlock(UnlockCallback callback) throws ReflectiveOperationException {
        ClassLoader loader = getClassLoader();
        Class<?> favoriteModelClass = Class.forName(
                "com.akuvox.mobile.libcommon.model.config.FavouriteRelayBeanModel", true, loader);
        Object favoriteModel = favoriteModelClass.getMethod("getInstance").invoke(null);
        Object favoriteData = favoriteModelClass.getMethod("getData").invoke(favoriteModel);
        if (!(favoriteData instanceof List<?> favorites) || favorites.isEmpty()) {
            callback.complete(false, "Favorite a door in SmartPlus first");
            return;
        }

        FavoriteRelay relay = findRemoteRelay(favorites);
        if (relay == null) {
            callback.complete(false, "No favorite remote-unlock door");
            return;
        }

        Map<String, String> params = new HashMap<>();
        params.put("mac", relay.deviceId);
        params.put("site", relay.accountSip);
        params.put(relay.relayType, Integer.toString(relay.relayId));

        Class<?> systemToolsClass = Class.forName(
                "com.akuvox.mobile.libcommon.utils.SystemTools", true, loader);
        String traceId = (String) systemToolsClass.getMethod("getUnlockTraceId").invoke(null);
        params.put("trace_id", traceId);

        Class<?> resultClass = Class.forName(
                "com.akuvox.mobile.libcommon.data.response.DataResult$Result", true, loader);
        Object resultCallback = Proxy.newProxyInstance(
                loader,
                new Class<?>[]{resultClass},
                (proxy, method, args) -> handleApiCallback(proxy, method, args, callback)
        );

        Class<?> apiModelClass = Class.forName(
                "com.akuvox.mobile.libcommon.model.ApiDataModel", true, loader);
        Object apiModel = apiModelClass.getMethod("getInstance").invoke(null);
        Method requestOpenDoor = apiModelClass.getMethod("requestOpenDoor", Map.class, resultClass);
        requestOpenDoor.invoke(apiModel, params, resultCallback);
    }

    private FavoriteRelay findRemoteRelay(List<?> favorites) throws ReflectiveOperationException {
        for (Object favorite : favorites) {
            if (favorite == null) {
                continue;
            }
            Class<?> favoriteClass = favorite.getClass();
            int type = favoriteClass.getField("type").getInt(favorite);
            if (type != 1001) {
                continue;
            }

            Field mapField = favoriteClass.getField("stringMap");
            Object mapValue = mapField.get(favorite);
            if (!(mapValue instanceof Map<?, ?> stringMap)) {
                continue;
            }
            String relayType = stringValue(stringMap.get("RelayType"));
            if (!"relay".equals(relayType) && !"security_relay".equals(relayType)) {
                continue;
            }
            String deviceId = stringValue(stringMap.get("DeviceId"));
            String accountSip = stringValue(stringMap.get("AccountSip"));
            if (deviceId == null || accountSip == null) {
                continue;
            }
            int relayId = favoriteClass.getField("relayId").getInt(favorite);
            return new FavoriteRelay(deviceId, accountSip, relayType, relayId);
        }
        return null;
    }

    private Object handleApiCallback(
            Object proxy,
            Method method,
            Object[] args,
            UnlockCallback callback
    ) throws ReflectiveOperationException {
        switch (method.getName()) {
            case "onSuccess":
            case "onSuccessObject":
                Object result = args == null || args.length == 0 ? null : args[0];
                if (result == null) {
                    callback.complete(false, "SmartPlus returned no result");
                    return null;
                }
                String code = stringValue(result.getClass().getMethod("getCode").invoke(result));
                String message = stringValue(result.getClass().getMethod("getMessage").invoke(result));
                boolean success = "0".equals(code);
                callback.complete(success, success ? "Door opened" : fallback(message, "SmartPlus rejected unlock"));
                return null;
            case "onFailure":
                String failure = args != null && args.length > 1 ? stringValue(args[1]) : null;
                callback.complete(false, fallback(failure, "SmartPlus unlock failed"));
                return null;
            case "toString":
                return TAG + "Callback";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            default:
                return null;
        }
    }

    private void complete(
            AtomicBoolean completed,
            String nodeId,
            String requestId,
            boolean success,
            String message
    ) {
        if (completed.compareAndSet(false, true)) {
            sendResult(nodeId, requestId, success, message);
        }
    }

    private void sendResult(String nodeId, String requestId, boolean success, String message) {
        String safeMessage = fallback(message, success ? "Door opened" : "Unlock failed")
                .replace('\0', ' ');
        if (safeMessage.length() > 240) {
            safeMessage = safeMessage.substring(0, 240);
        }
        byte[] payload = (requestId + '\0' + success + '\0' + safeMessage)
                .getBytes(StandardCharsets.UTF_8);
        Wearable.getMessageClient(this).sendMessage(nodeId, RESULT_PATH, payload)
                .addOnFailureListener(exception -> Log.e(TAG, "Unable to reply to watch", exception));
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String string = value.toString();
        return string.isBlank() ? null : string;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private interface UnlockCallback {
        void complete(boolean success, String message);
    }

    private record FavoriteRelay(String deviceId, String accountSip, String relayType, int relayId) {}
}
