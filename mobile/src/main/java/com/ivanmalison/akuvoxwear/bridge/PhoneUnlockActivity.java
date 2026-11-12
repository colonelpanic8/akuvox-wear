package com.ivanmalison.akuvoxwear.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

/** Invisible target of the launcher shortcuts; it stays open only until SmartPlus answers. */
public final class PhoneUnlockActivity extends Activity {
    static final String ACTION_UNLOCK_WITH_PIN = "com.ivanmalison.akuvoxwear.action.UNLOCK_WITH_PIN";

    private static final String TAG = "SmartPlusWear";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        if (savedInstanceState != null) {
            // The original instance's request is still running and reports through a toast.
            finish();
            return;
        }
        boolean armPin = ACTION_UNLOCK_WITH_PIN.equals(getIntent().getAction());
        Log.i(TAG, "Received unlock request from phone" + (armPin ? " with pin" : ""));
        Toast.makeText(this, armPin ? "Unlocking with reminder…" : "Unlocking…", Toast.LENGTH_SHORT).show();
        DoorUnlocker.unlock(this, armPin, (success, pin, message) -> runOnUiThread(() -> {
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
            finish();
        }));
    }
}
