package com.ivanmalison.akuvoxwear.bridge;

import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SmartPlusWearService extends WearableListenerService {
    private static final String TAG = "SmartPlusWear";
    private static final String REQUEST_PATH = "/akuvox/unlock/request";
    private static final String RESULT_PATH = "/akuvox/unlock/result";
    private static final long UNLOCK_TIMEOUT_MS = 12_000;
    private static final long WAKE_LOCK_TIMEOUT_MS = UNLOCK_TIMEOUT_MS + 3_000;

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
            Log.w(TAG, "Rejected malformed request from watch");
            sendResult(event.getSourceNodeId(), requestId, false, "Invalid watch request");
            return;
        }

        String sourceNodeId = event.getSourceNodeId();
        Log.i(TAG, "Received unlock request from watch");
        AtomicBoolean completed = new AtomicBoolean(false);
        PowerManager.WakeLock wakeLock = acquireWakeLock();
        mainHandler.postDelayed(
                () -> complete(
                        completed,
                        wakeLock,
                        sourceNodeId,
                        requestId,
                        false,
                        "SmartPlus timed out"
                ),
                UNLOCK_TIMEOUT_MS
        );
        mainHandler.post(() -> {
            try {
                requestFavoriteDoorUnlock((success, message) ->
                        complete(completed, wakeLock, sourceNodeId, requestId, success, message));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                Log.e(TAG, "Unable to request SmartPlus unlock", exception);
                complete(
                        completed,
                        wakeLock,
                        sourceNodeId,
                        requestId,
                        false,
                        "SmartPlus bridge failed"
                );
            }
        });
    }

    private PowerManager.WakeLock acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    TAG + ":unlock"
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            return wakeLock;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to hold phone awake for unlock", exception);
            return null;
        }
    }

    private void requestFavoriteDoorUnlock(UnlockCallback callback) throws ReflectiveOperationException {
        ClassLoader loader = getClassLoader();
        Class<?> favoriteModelClass = Class.forName(
                "com.akuvox.mobile.libcommon.model.config.FavouriteRelayBeanModel", true, loader);
        Object favoriteModel = favoriteModelClass.getMethod("getInstance").invoke(null);
        Object favoriteData = favoriteModelClass.getMethod("getData").invoke(favoriteModel);
        if (favoriteData instanceof List<?> favorites) {
            FavoriteRelay favorite = findRemoteRelay(favorites);
            if (favorite != null) {
                Log.i(TAG, "Using favorite remote-unlock door");
                requestDoorUnlock(favorite, callback);
                return;
            }
        }

        List<FavoriteRelay> availableRelays = findAvailableRemoteRelays(loader);
        if (availableRelays.isEmpty()) {
            callback.complete(false, "No remote-unlock door available");
            return;
        }
        if (availableRelays.size() > 1) {
            Log.i(TAG, "Found multiple remote-unlock doors without a favorite");
            callback.complete(false, "Multiple doors available; favorite one in SmartPlus");
            return;
        }

        Log.i(TAG, "Using sole available remote-unlock door");
        requestDoorUnlock(availableRelays.get(0), callback);
    }

    private void requestDoorUnlock(FavoriteRelay relay, UnlockCallback callback)
            throws ReflectiveOperationException {
        ClassLoader loader = getClassLoader();
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

    private List<FavoriteRelay> findAvailableRemoteRelays(ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> deviceModelClass = Class.forName(
                "com.akuvox.mobile.libcommon.model.config.DeviceModel", true, loader);
        Object deviceModel = deviceModelClass.getMethod("getInstance").invoke(null);
        Object deviceData = deviceModelClass.getMethod("getData").invoke(deviceModel);
        if (!(deviceData instanceof List<?> devices)) {
            return new ArrayList<>();
        }

        Class<?> deviceDataClass = Class.forName(
                "com.akuvox.mobile.libcommon.bean.DeviceData", true, loader);
        Class<?> unlockParamsClass = Class.forName(
                "com.akuvox.mobile.libcommon.params.UnlockParams", true, loader);
        Class<?> unlockParamsToolsClass = Class.forName(
                "com.akuvox.mobile.libcommon.utils.UnlockParamsTools", true, loader);
        Method buildUnlockParams = unlockParamsToolsClass.getMethod(
                "buildUnlockParams",
                deviceDataClass,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class
        );
        Field accountSipField = unlockParamsClass.getField("accountSip");
        Field deviceMacField = unlockParamsClass.getField("deviceMac");
        Field relayListField = unlockParamsClass.getField("relayList");
        Map<String, FavoriteRelay> relays = new LinkedHashMap<>();

        for (Object device : devices) {
            if (!deviceDataClass.isInstance(device)) {
                continue;
            }
            Object unlockParams = buildUnlockParams.invoke(null, device, false, true, true, false);
            if (unlockParams == null) {
                continue;
            }
            String accountSip = stringValue(accountSipField.get(unlockParams));
            String deviceMac = stringValue(deviceMacField.get(unlockParams));
            Object relayData = relayListField.get(unlockParams);
            if (accountSip == null || !(relayData instanceof List<?> deviceRelays)) {
                continue;
            }

            for (Object relay : deviceRelays) {
                FavoriteRelay available = readAvailableRelay(relay, deviceMac, accountSip);
                if (available != null) {
                    String key = available.deviceId + '\0' + available.accountSip + '\0'
                            + available.relayType + '\0' + available.relayId;
                    relays.putIfAbsent(key, available);
                }
            }
        }
        return new ArrayList<>(relays.values());
    }

    private FavoriteRelay readAvailableRelay(Object relay, String fallbackDeviceId, String accountSip)
            throws ReflectiveOperationException {
        if (relay == null
                || !"com.akuvox.mobile.libcommon.bean.RelayBean".equals(relay.getClass().getName())) {
            return null;
        }
        Class<?> relayClass = relay.getClass();
        String relayType = apiRelayType(stringValue(relayClass.getField("type").get(relay)));
        String deviceId = stringValue(relayClass.getField("deviceMac").get(relay));
        if (deviceId == null) {
            deviceId = fallbackDeviceId;
        }
        if (relayType == null || deviceId == null) {
            return null;
        }
        int relayId = relayClass.getField("relay_id").getInt(relay);
        return new FavoriteRelay(deviceId, accountSip, relayType, relayId);
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
            String relayType = apiRelayType(stringValue(stringMap.get("RelayType")));
            if (relayType == null) {
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
            PowerManager.WakeLock wakeLock,
            String nodeId,
            String requestId,
            boolean success,
            String message
    ) {
        if (completed.compareAndSet(false, true)) {
            Log.i(TAG, "Completing watch request: " + (success ? "success" : "failure"));
            try {
                sendResult(nodeId, requestId, success, message);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
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
        try {
            Wearable.getMessageClient(this).sendMessage(nodeId, RESULT_PATH, payload)
                    .addOnFailureListener(exception -> Log.e(TAG, "Unable to reply to watch", exception));
        } catch (LinkageError | RuntimeException exception) {
            Log.e(TAG, "Unable to create reply to watch", exception);
        }
    }

    private static String apiRelayType(String relayType) {
        if ("Local".equals(relayType) || "relay".equals(relayType)) {
            return "relay";
        }
        return "security_relay".equals(relayType) ? relayType : null;
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
