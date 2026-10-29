package com.q7vpn.app;

import android.content.Intent;
import android.net.VpnService;
import android.util.Log;

/**
 * Q7VPN Android VPN service entry point.
 *
 * This is intentionally a safe integration scaffold: it registers the Android
 * VPN service and handles lifecycle events, but DOES NOT establish a TUN
 * interface or claim a VPN connection until the matching libbox.aar API and
 * native libraries are added and wired in.
 */
public final class Q7VpnService extends VpnService {
    public static final String ACTION_START = "com.q7vpn.app.action.START_VPN";
    public static final String ACTION_STOP = "com.q7vpn.app.action.STOP_VPN";
    private static final String TAG = "Q7VpnService";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            // Do not call Builder.establish() here yet. Without libbox packet
            // processing, doing so would blackhole the device's network traffic.
            Log.w(TAG, "VPN core is not connected; refusing to create a TUN tunnel.");
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "System VPN permission revoked.");
        super.onRevoke();
        stopSelf();
    }
}
