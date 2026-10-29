package com.q7vpn.app;

import android.content.Intent;
import android.net.VpnService;

/** Small helper for MainActivity to check/request Android VPN consent. */
public final class VpnPermissionHelper {
    private VpnPermissionHelper() {}

    /** Returns null when permission is already granted; otherwise returns the system consent Intent. */
    public static Intent prepare(android.content.Context context) {
        return VpnService.prepare(context);
    }

    /** Creates the service start Intent after the activity receives RESULT_OK. */
    public static Intent startIntent(android.content.Context context) {
        Intent intent = new Intent(context, Q7VpnService.class);
        intent.setAction(Q7VpnService.ACTION_START);
        return intent;
    }

    /** Creates the service stop Intent. */
    public static Intent stopIntent(android.content.Context context) {
        Intent intent = new Intent(context, Q7VpnService.class);
        intent.setAction(Q7VpnService.ACTION_STOP);
        return intent;
    }
}
