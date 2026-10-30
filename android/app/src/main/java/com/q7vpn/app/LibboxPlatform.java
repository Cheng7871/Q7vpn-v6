package com.q7vpn.app;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Adapts libbox PlatformInterface callbacks to Android's VpnService implementation.
 * The actual TUN descriptor is created by Q7VpnService.openTun(); libbox owns the
 * packet-processing engine. Optional desktop/root-only callbacks are deliberately
 * disabled for this unprivileged Android client.
 */
public final class LibboxPlatform implements InvocationHandler {
    private static final String TAG = "Q7VPN-LibboxPlatform";
    private final Q7VpnService service;

    private LibboxPlatform(Q7VpnService service) {
        this.service = service;
    }

    public static Object createProxy(Q7VpnService service, Class<?> platformInterface) {
        if (service == null || platformInterface == null || !platformInterface.isInterface()) {
            throw new IllegalArgumentException("libbox PlatformInterface API 无效");
        }
        return Proxy.newProxyInstance(
                platformInterface.getClassLoader(),
                new Class<?>[]{platformInterface},
                new LibboxPlatform(service));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("toString".equals(name)) return "Q7VPN Android PlatformInterface";
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);

        switch (name) {
            case "usePlatformAutoDetectInterfaceControl":
                return true;
            case "autoDetectInterfaceControl": {
                int fd = ((Number) args[0]).intValue();
                if (!service.protectSocketFd(fd)) {
                    throw new IllegalStateException("Android 无法保护 sing-box 底层网络 socket");
                }
                return null;
            }
            case "openTun":
                return service.openTun(args[0]);
            case "useProcFS":
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
            case "findConnectionOwner":
                return service.findConnectionOwner(args, method.getReturnType());
            case "startDefaultInterfaceMonitor":
                service.startDefaultInterfaceMonitor(args == null || args.length == 0 ? null : args[0]);
                return null;
            case "closeDefaultInterfaceMonitor":
                service.closeDefaultInterfaceMonitor();
                return null;
            case "getInterfaces":
                return service.buildInterfaceIterator(method.getReturnType());

            // Features that require root, shell access, or optional platform integration.
            case "underNetworkExtension":
            case "includeAllNetworks":
            case "usePlatformShell":
            case "usePlatformBridge":
            case "usePlatformAutoRedirect":
                return false;
            case "tailscaleHostname":
                return Build.MANUFACTURER + " " + Build.MODEL;

            // Optional callbacks not used by this client's generated configuration.
            case "clearDNSCache":
            case "startNeighborMonitor":
            case "closeNeighborMonitor":
            case "sendNotification":
            case "cancelNotification":
            case "registerMyInterface":
            case "checkPlatformShell":
                return null;
            case "readWIFIState":
            case "localDNSTransport":
            case "openShellSession":
            case "createBridge":
            case "createAutoRedirect":
            case "lookupUser":
                return null;
            case "readSystemSSHHostKey":
            case "lookupSFTPServer":
                return "";
            default:
                Log.d(TAG, "未启用的可选 libbox 平台回调：" + name);
                return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }
}
