package com.q7vpn.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Q7VPN Android bridge for the sing-box/libbox 1.14.0 API.
 * The implementation follows the official Android client's PlatformInterface,
 * CommandServer and VpnService lifecycle while keeping this project in Java.
 */
public final class Q7VpnService extends VpnService {
    public static final String ACTION_START = "com.q7vpn.app.action.START_VPN";
    public static final String ACTION_STOP = "com.q7vpn.app.action.STOP_VPN";
    public static final String EXTRA_CONFIG = "com.q7vpn.app.extra.SINGBOX_CONFIG";

    private static final String TAG = "Q7VpnService";
    private static final String LIBBOX = "io.nekohasekai.libbox.";
    private static final int NOTIFICATION_ID = 7001;
    private static final String CHANNEL_ID = "q7vpn_vpn";

    private static volatile boolean libboxReady;

    private final Object coreLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "q7vpn-libbox-worker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean stopping;
    private volatile Future<?> startFuture;
    private Object commandServer;
    private ParcelFileDescriptor tunDescriptor;
    private Object defaultInterfaceListener;
    private ConnectivityManager.NetworkCallback defaultNetworkCallback;
    private boolean systemProxyAvailable;
    private boolean systemProxyEnabled;
    private volatile String activeConfig;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopEngineAndService();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String config = intent.getStringExtra(EXTRA_CONFIG);
        if (config == null || config.trim().isEmpty()) {
            Log.e(TAG, "启动失败：没有收到 sing-box 配置");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        stopping = false;
        try {
            // Promote immediately; do not wait for libbox initialization.
            startForegroundCompat("Q7VPN 正在连接");
        } catch (Throwable error) {
            Log.e(TAG, "无法启动前台 VPN 服务", error);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Future<?> previous = startFuture;
        if (previous != null) previous.cancel(true);
        final String finalConfig = config;
        startFuture = worker.submit(() -> {
            try {
                closeCore();
                if (stopping || Thread.currentThread().isInterrupted()) return;
                startCore(finalConfig);
                if (!stopping) updateNotification("Q7VPN 已启动");
            } catch (Throwable error) {
                Log.e(TAG, "sing-box 启动失败", error);
                closeCore();
                if (!stopping) {
                    updateNotification("Q7VPN 启动失败：" + safeMessage(error));
                    mainHandler.post(() -> {
                        stopForegroundCompat();
                        stopSelf(startId);
                    });
                }
            }
        });

        return START_NOT_STICKY;
    }

    private void startCore(String config) throws Exception {
        prepareLibbox();

        Class<?> platformType = Class.forName(LIBBOX + "PlatformInterface");
        Class<?> handlerType = Class.forName(LIBBOX + "CommandServerHandler");
        Object platform = Proxy.newProxyInstance(
                platformType.getClassLoader(), new Class<?>[]{platformType}, new PlatformHandler());
        Object handler = Proxy.newProxyInstance(
                handlerType.getClassLoader(), new Class<?>[]{handlerType}, new CommandHandler());

        Class<?> serverType = Class.forName(LIBBOX + "CommandServer");
        Constructor<?> constructor = serverType.getConstructor(handlerType, platformType);
        Object server = constructor.newInstance(handler, platform);
        synchronized (coreLock) {
            commandServer = server;
        }

        invokeNoArgs(server, "start");
        Class<?> overrideType = Class.forName(LIBBOX + "OverrideOptions");
        Object overrideOptions = overrideType.getConstructor().newInstance();
        invokeTwoArgs(server, "startOrReloadService", config, overrideOptions);
        activeConfig = config;
        Log.i(TAG, "libbox 1.14.0 已启动并接收配置");
    }

    private void prepareLibbox() throws Exception {
        if (libboxReady) return;
        synchronized (Q7VpnService.class) {
            if (libboxReady) return;

            Class<?> setupType = Class.forName(LIBBOX + "SetupOptions");
            Object options = setupType.getConstructor().newInstance();
            File base = new File(getFilesDir(), "q7vpn-libbox");
            File work = new File(base, "work");
            File temp = new File(getCacheDir(), "q7vpn-libbox");
            if (!work.exists() && !work.mkdirs()) throw new IllegalStateException("无法创建 libbox 工作目录");
            if (!temp.exists() && !temp.mkdirs()) throw new IllegalStateException("无法创建 libbox 临时目录");

            setRequiredProperty(options, "basePath", base.getAbsolutePath());
            setRequiredProperty(options, "workingPath", work.getAbsolutePath());
            setRequiredProperty(options, "tempPath", temp.getAbsolutePath());
            setProperty(options, "logMaxLines", 1000);
            setProperty(options, "debug", false);
            setProperty(options, "fixAndroidStack", true);
            setProperty(options, "appVersion", "6");
            setProperty(options, "appMarketingVersion", "6.0.0");

            Class<?> libboxType = Class.forName(LIBBOX + "Libbox");
            invokeStaticOneArg(libboxType, "setup", options);
            try {
                Method setLocale = libboxType.getMethod("setLocale", String.class);
                setLocale.invoke(null, Locale.getDefault().toLanguageTag());
            } catch (NoSuchMethodException ignored) {
                // Optional API; setup itself is required.
            }
            libboxReady = true;
        }
    }

    private final class PlatformHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (isObjectMethod(proxy, method, args)) return objectMethod(proxy, method, args, "Q7VPN PlatformInterface");

            switch (name) {
                case "usePlatformAutoDetectInterfaceControl":
                    return true;
                case "autoDetectInterfaceControl": {
                    int fd = ((Number) args[0]).intValue();
                    if (!protect(fd)) throw new IllegalStateException("无法保护 VPN 核心的底层网络连接");
                    return null;
                }
                case "openTun":
                    return openTun(args[0]);
                case "useProcFS":
                    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
                case "findConnectionOwner":
                    return findConnectionOwner(args, method.getReturnType());
                case "startDefaultInterfaceMonitor":
                    startDefaultInterfaceMonitor(args == null || args.length == 0 ? null : args[0]);
                    return null;
                case "closeDefaultInterfaceMonitor":
                    closeDefaultInterfaceMonitor();
                    return null;
                case "getInterfaces":
                    return buildInterfaceIterator(method.getReturnType());
                case "underNetworkExtension":
                case "includeAllNetworks":
                    return false;
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
                case "lookupUser":
                    return null;
                case "usePlatformShell":
                case "usePlatformBridge":
                    return false;
                case "readSystemSSHHostKey":
                case "lookupSFTPServer":
                    return "";
                case "tailscaleHostname":
                    return Build.MANUFACTURER + " " + Build.MODEL;
                default:
                    Log.d(TAG, "未专门实现 PlatformInterface 回调：" + name);
                    return defaultValue(method.getReturnType());
            }
        }
    }

    private final class CommandHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (isObjectMethod(proxy, method, args)) return objectMethod(proxy, method, args, "Q7VPN CommandServerHandler");

            switch (name) {
                case "serviceStop":
                    mainHandler.post(Q7VpnService.this::stopEngineAndService);
                    return null;
                case "serviceReload":
                    reloadActiveConfig();
                    return null;
                case "getSystemProxyStatus": {
                    Object status = method.getReturnType().getConstructor().newInstance();
                    setProperty(status, "available", systemProxyAvailable);
                    setProperty(status, "enabled", systemProxyEnabled);
                    return status;
                }
                case "setSystemProxyEnabled":
                    systemProxyEnabled = args != null && args.length > 0 && Boolean.TRUE.equals(args[0]);
                    reloadActiveConfig();
                    return null;
                case "triggerNativeCrash":
                    Log.w(TAG, "libbox requested native crash test; ignored in release client");
                    return null;
                case "writeDebugMessage":
                    Log.d(TAG, args == null || args.length == 0 ? "" : String.valueOf(args[0]));
                    return null;
                case "connectSSHAgent":
                    return -1;
                default:
                    return defaultValue(method.getReturnType());
            }
        }
    }

    private void reloadActiveConfig() {
        final String config = activeConfig;
        if (config == null || stopping) return;
        worker.submit(() -> {
            Object server;
            synchronized (coreLock) { server = commandServer; }
            if (server == null || stopping) return;
            try {
                Object override = Class.forName(LIBBOX + "OverrideOptions").getConstructor().newInstance();
                invokeTwoArgs(server, "startOrReloadService", config, override);
                Log.i(TAG, "sing-box 配置已重新加载");
            } catch (Exception error) {
                Log.e(TAG, "重新加载 sing-box 配置失败", error);
                mainHandler.post(() -> updateNotification("Q7VPN 重载失败：" + safeMessage(error)));
            }
        });
    }

    private int openTun(Object options) throws Exception {
        if (VpnService.prepare(this) != null) {
            throw new IllegalStateException("Q7VPN 尚未获得系统 VPN 授权，请返回应用重新连接");
        }

        Builder builder = new Builder()
                .setSession("Q7VPN")
                .setMtu(readInt(options, "mtu", 1400));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);

        int ipv4AddressCount = addAddressIterator(builder, readProperty(options, "inet4Address"));
        int ipv6AddressCount = addAddressIterator(builder, readProperty(options, "inet6Address"));
        if (ipv4AddressCount + ipv6AddressCount == 0) {
            throw new IllegalStateException("sing-box 没有提供 TUN 地址，无法建立隧道");
        }

        boolean autoRoute = readBoolean(options, "autoRoute", true);
        if (autoRoute) {
            Object dnsMode = readProperty(options, "dnsMode");
            int dnsModeValue = dnsMode instanceof Number
                    ? ((Number) dnsMode).intValue() : readInt(dnsMode, "value", -1);
            int dnsDisabled = staticInt(LIBBOX + "Libbox", "DNSModeDisabled", Integer.MIN_VALUE);
            if (dnsModeValue != dnsDisabled) {
                addDnsServers(builder, readProperty(options, "dnsServerAddress"));
            }

            int ipv4Routes = 0;
            int ipv6Routes = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ipv4Routes = addRouteIterator(builder, readProperty(options, "inet4RouteAddress"));
                ipv6Routes = addRouteIterator(builder, readProperty(options, "inet6RouteAddress"));
            } else {
                ipv4Routes = addRouteIterator(builder, readProperty(options, "inet4RouteRange"));
                ipv6Routes = addRouteIterator(builder, readProperty(options, "inet6RouteRange"));
            }

            // Match the official Android client: if a family has no explicit route,
            // route that address family through the Android VPN interface.
            if (ipv4Routes == 0 && ipv4AddressCount > 0) builder.addRoute("0.0.0.0", 0);
            if (ipv6Routes == 0 && ipv6AddressCount > 0) builder.addRoute("::", 0);

            addPackages(builder, readProperty(options, "includePackage"), true);
            addPackages(builder, readProperty(options, "excludePackage"), false);
        }

        systemProxyAvailable = false;
        systemProxyEnabled = false;
        if (readBoolean(options, "isHTTPProxyEnabled", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String host = readString(options, "httpProxyServer", "");
            int port = readInt(options, "httpProxyServerPort", 0);
            if (!host.isEmpty() && port > 0) {
                List<String> bypass = readStringIterator(readProperty(options, "httpProxyBypassDomain"));
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port, bypass));
                systemProxyAvailable = true;
                systemProxyEnabled = true;
            }
        }

        ParcelFileDescriptor pfd = builder.establish();
        if (pfd == null) throw new IllegalStateException("Android 未能建立 VPN TUN 文件描述符");
        synchronized (coreLock) {
            if (tunDescriptor != null) {
                try { tunDescriptor.close(); } catch (Exception ignored) { }
            }
            tunDescriptor = pfd;
        }
        Log.i(TAG, "Android TUN 已建立，IPv4 地址数=" + ipv4AddressCount + "，IPv6 地址数=" + ipv6AddressCount);
        return pfd.getFd();
    }

    private int addAddressIterator(Builder builder, Object iterator) throws Exception {
        int count = 0;
        while (hasNext(iterator)) {
            Object prefix = next(iterator);
            String address = readString(prefix, "address", "");
            int length = readInt(prefix, "prefix", -1);
            if (address.isEmpty() || length < 0) continue;
            builder.addAddress(address, length);
            count++;
        }
        return count;
    }

    private int addRouteIterator(Builder builder, Object iterator) throws Exception {
        int count = 0;
        while (hasNext(iterator)) {
            Object prefixObject = next(iterator);
            String address;
            int length;
            if (prefixObject instanceof String) {
                String[] parts = ((String) prefixObject).split("/", 2);
                address = parts[0];
                length = parts.length == 2 ? Integer.parseInt(parts[1]) : -1;
            } else {
                address = readString(prefixObject, "address", "");
                length = readInt(prefixObject, "prefix", -1);
            }
            if (address.isEmpty() || length < 0) continue;
            builder.addRoute(address, length);
            count++;
        }
        return count;
    }

    private void addDnsServers(Builder builder, Object iterator) throws Exception {
        while (hasNext(iterator)) {
            Object value = next(iterator);
            if (value == null) continue;
            String dns = String.valueOf(value).trim();
            if (!dns.isEmpty()) builder.addDnsServer(dns);
        }
    }

    private void addPackages(Builder builder, Object iterator, boolean allow) throws Exception {
        while (hasNext(iterator)) {
            Object value = next(iterator);
            if (value == null) continue;
            String packageName = String.valueOf(value).trim();
            if (packageName.isEmpty()) continue;
            try {
                if (allow) builder.addAllowedApplication(packageName);
                else builder.addDisallowedApplication(packageName);
            } catch (Exception error) {
                Log.w(TAG, "无法应用应用分流包名：" + packageName, error);
            }
        }
    }

    private Object buildInterfaceIterator(Class<?> iteratorType) throws Exception {
        Class<?> itemType = Class.forName(LIBBOX + "NetworkInterface");
        List<Object> items = new ArrayList<>();
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return makeObjectIterator(iteratorType, items);

        Map<String, Network> networksByInterface = new HashMap<>();
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) continue;
            String interfaceName = properties.getInterfaceName();
            if (interfaceName == null || interfaceName.isEmpty()) continue;
            if ("lo".equals(interfaceName) || interfaceName.startsWith("tun")) continue;
            networksByInterface.put(interfaceName, network);
        }

        for (Map.Entry<String, Network> entry : networksByInterface.entrySet()) {
            String name = entry.getKey();
            Network network = entry.getValue();
            NetworkInterface systemInterface;
            try { systemInterface = NetworkInterface.getByName(name); }
            catch (Exception ignored) { continue; }
            if (systemInterface == null) continue;

            LinkProperties link = manager.getLinkProperties(network);
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (link == null || caps == null) continue;

            Object item = itemType.getConstructor().newInstance();
            setProperty(item, "name", name);
            setProperty(item, "index", systemInterface.getIndex());
            try { setProperty(item, "mtu", systemInterface.getMTU()); } catch (Exception ignored) { }

            List<String> addresses = new ArrayList<>();
            for (InterfaceAddress interfaceAddress : systemInterface.getInterfaceAddresses()) {
                if (interfaceAddress.getAddress() != null) {
                    addresses.add(formatAddress(interfaceAddress.getAddress()) + "/" + interfaceAddress.getNetworkPrefixLength());
                }
            }
            setProperty(item, "addresses", makeStringIterator(addresses));

            List<String> dns = new ArrayList<>();
            for (InetAddress address : link.getDnsServers()) {
                if (address != null) dns.add(formatAddress(address));
            }
            setProperty(item, "dnsServer", makeStringIterator(dns));

            List<String> gateways = new ArrayList<>();
            for (RouteInfo route : link.getRoutes()) {
                if (route.getDestination() != null
                        && route.getDestination().getPrefixLength() == 0
                        && route.getGateway() != null
                        && !route.getGateway().isAnyLocalAddress()) {
                    gateways.add(formatAddress(route.getGateway()));
                }
            }
            setProperty(item, "gateway", makeStringIterator(gateways));

            int type = staticInt(LIBBOX + "Libbox", "InterfaceTypeOther", 0);
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                type = staticInt(LIBBOX + "Libbox", "InterfaceTypeWIFI", 1);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                type = staticInt(LIBBOX + "Libbox", "InterfaceTypeCellular", 2);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                type = staticInt(LIBBOX + "Libbox", "InterfaceTypeEthernet", 3);
            }
            setProperty(item, "type", type);
            setProperty(item, "metered", !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));

            int flags = 0;
            try {
                if (systemInterface.isUp()) flags |= OsConstants.IFF_UP | OsConstants.IFF_RUNNING;
            } catch (Exception ignored) { }
            if (systemInterface.isLoopback()) flags |= OsConstants.IFF_LOOPBACK;
            if (systemInterface.isPointToPoint()) flags |= OsConstants.IFF_POINTOPOINT;
            if (systemInterface.supportsMulticast()) flags |= OsConstants.IFF_MULTICAST;
            setProperty(item, "flags", flags);
            items.add(item);
        }
        return makeObjectIterator(iteratorType, items);
    }

    private void startDefaultInterfaceMonitor(Object listener) {
        closeDefaultInterfaceMonitor();
        if (listener == null) return;
        defaultInterfaceListener = listener;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return;

        defaultNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { publishDefaultInterface(network); }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                publishDefaultInterface(network);
            }
            @Override public void onLost(Network network) {
                Network active = manager.getActiveNetwork();
                publishDefaultInterface(active);
            }
        };
        try {
            manager.registerDefaultNetworkCallback(defaultNetworkCallback);
            publishDefaultInterface(manager.getActiveNetwork());
        } catch (Exception error) {
            Log.w(TAG, "注册默认网络监听失败", error);
            defaultNetworkCallback = null;
        }
    }

    private void publishDefaultInterface(Network network) {
        Object listener = defaultInterfaceListener;
        if (listener == null) return;
        String name = "";
        int index = -1;
        try {
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            LinkProperties link = network == null || manager == null ? null : manager.getLinkProperties(network);
            if (link != null && link.getInterfaceName() != null) {
                name = link.getInterfaceName();
                NetworkInterface ni = NetworkInterface.getByName(name);
                if (ni != null) index = ni.getIndex();
            }
            invokeFourArgs(listener, "updateDefaultInterface", name, index, false, false);
        } catch (Exception error) {
            Log.w(TAG, "更新默认网络接口失败", error);
        }
    }

    private void closeDefaultInterfaceMonitor() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager != null && defaultNetworkCallback != null) {
            try { manager.unregisterNetworkCallback(defaultNetworkCallback); }
            catch (Exception ignored) { }
        }
        defaultNetworkCallback = null;
        defaultInterfaceListener = null;
    }

    private Object findConnectionOwner(Object[] args, Class<?> returnType) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || args == null || args.length < 5) return null;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return null;
        int protocol = ((Number) args[0]).intValue();
        String sourceAddress = String.valueOf(args[1]);
        int sourcePort = ((Number) args[2]).intValue();
        String destinationAddress = String.valueOf(args[3]);
        int destinationPort = ((Number) args[4]).intValue();
        int uid = manager.getConnectionOwnerUid(protocol,
                new InetSocketAddress(InetAddress.getByName(sourceAddress), sourcePort),
                new InetSocketAddress(InetAddress.getByName(destinationAddress), destinationPort));
        if (uid == -1) throw new IllegalStateException("找不到连接所属 UID");
        Object owner = returnType.getConstructor().newInstance();
        setProperty(owner, "userId", uid);
        String[] packages = getPackageManager().getPackagesForUid(uid);
        setProperty(owner, "userName", packages == null || packages.length == 0 ? "" : packages[0]);
        List<String> packageList = new ArrayList<>();
        if (packages != null) Collections.addAll(packageList, packages);
        setProperty(owner, "androidPackageNames", makeStringIterator(packageList));
        return owner;
    }

    private Object makeStringIterator(List<String> values) throws Exception {
        Class<?> type = Class.forName(LIBBOX + "StringIterator");
        final Iterator<String> iterator = values.iterator();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            String name = method.getName();
            if ("hasNext".equals(name)) return iterator.hasNext();
            if ("next".equals(name)) return iterator.next();
            if ("len".equals(name)) return values.size();
            if (isObjectMethod(proxy, method, args)) return objectMethod(proxy, method, args, "Q7VPN StringIterator");
            return defaultValue(method.getReturnType());
        });
    }

    private Object makeObjectIterator(Class<?> type, List<?> values) {
        final Iterator<?> iterator = values.iterator();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            String name = method.getName();
            if ("hasNext".equals(name)) return iterator.hasNext();
            if ("next".equals(name)) return iterator.next();
            if (isObjectMethod(proxy, method, args)) return objectMethod(proxy, method, args, "Q7VPN Iterator");
            return defaultValue(method.getReturnType());
        });
    }

    private static String formatAddress(InetAddress address) {
        if (address instanceof Inet6Address) return address.getHostAddress().split("%", 2)[0];
        return address.getHostAddress();
    }

    private static boolean isObjectMethod(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        return "toString".equals(name) || "hashCode".equals(name) || "equals".equals(name);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args, String label) {
        switch (method.getName()) {
            case "toString": return label;
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == (args == null ? null : args[0]);
            default: return null;
        }
    }

    private static int staticInt(String className, String fieldName, int fallback) {
        try { return Class.forName(className).getField(fieldName).getInt(null); }
        catch (Exception ignored) { return fallback; }
    }

    private static Object readProperty(Object target, String name) throws Exception {
        if (target == null) return null;
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String methodName : new String[]{"get" + cap, "is" + cap, name}) {
            try { return target.getClass().getMethod(methodName).invoke(target); }
            catch (NoSuchMethodException ignored) { }
            catch (InvocationTargetException error) { throw unwrap(error); }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static String readString(Object target, String name, String fallback) {
        try { Object value = readProperty(target, name); return value == null ? fallback : String.valueOf(value); }
        catch (Exception ignored) { return fallback; }
    }

    private static int readInt(Object target, String name, int fallback) {
        try { Object value = readProperty(target, name); return value instanceof Number ? ((Number) value).intValue() : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean readBoolean(Object target, String name, boolean fallback) {
        try { Object value = readProperty(target, name); return value instanceof Boolean ? (Boolean) value : fallback; }
        catch (Exception ignored) { return fallback; }
    }

    private static boolean hasNext(Object iterator) throws Exception {
        if (iterator == null) return false;
        Object value = invokeNoArgs(iterator, "hasNext");
        return Boolean.TRUE.equals(value);
    }

    private static Object next(Object iterator) throws Exception {
        return invokeNoArgs(iterator, "next");
    }

    private static List<String> readStringIterator(Object iterator) throws Exception {
        List<String> values = new ArrayList<>();
        while (hasNext(iterator)) {
            Object value = next(iterator);
            if (value != null) values.add(String.valueOf(value));
        }
        return values;
    }

    private static Object invokeNoArgs(Object target, String name) throws Exception {
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (InvocationTargetException error) { throw unwrap(error); }
    }

    private static Object invokeTwoArgs(Object target, String name, Object first, Object second) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 2
                    && compatible(parameters[0], first) && compatible(parameters[1], second)) {
                try { return method.invoke(target, first, second); }
                catch (InvocationTargetException error) { throw unwrap(error); }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "(2 args)");
    }

    private static void invokeFourArgs(Object target, String name, Object a, Object b, Object c, Object d) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getName().equals(name) && p.length == 4
                    && compatible(p[0], a) && compatible(p[1], b)
                    && compatible(p[2], c) && compatible(p[3], d)) {
                try { method.invoke(target, a, b, c, d); return; }
                catch (InvocationTargetException error) { throw unwrap(error); }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "(4 args)");
    }

    private static void invokeStaticOneArg(Class<?> type, String name, Object argument) throws Exception {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers())
                    && method.getParameterTypes().length == 1
                    && compatible(method.getParameterTypes()[0], argument)) {
                try { method.invoke(null, argument); return; }
                catch (InvocationTargetException error) { throw unwrap(error); }
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static boolean compatible(Class<?> parameter, Object value) {
        if (value == null) return !parameter.isPrimitive();
        if (parameter.isAssignableFrom(value.getClass())) return true;
        if (!parameter.isPrimitive()) return false;
        return (parameter == int.class && value instanceof Integer)
                || (parameter == long.class && value instanceof Long)
                || (parameter == boolean.class && value instanceof Boolean)
                || (parameter == double.class && value instanceof Double)
                || (parameter == float.class && value instanceof Float)
                || (parameter == short.class && value instanceof Short)
                || (parameter == byte.class && value instanceof Byte)
                || (parameter == char.class && value instanceof Character);
    }

    private static void setRequiredProperty(Object target, String name, Object value) throws Exception {
        if (!setProperty(target, name, value)) {
            throw new NoSuchMethodException("缺少 libbox 属性 set" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
        }
    }

    private static boolean setProperty(Object target, String name, Object value) {
        if (target == null || value == null) return false;
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(setter) || method.getParameterTypes().length != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            try {
                if (parameter == int.class && value instanceof Number) method.invoke(target, ((Number) value).intValue());
                else if (parameter == long.class && value instanceof Number) method.invoke(target, ((Number) value).longValue());
                else if (parameter == boolean.class && value instanceof Boolean) method.invoke(target, value);
                else if (parameter == float.class && value instanceof Number) method.invoke(target, ((Number) value).floatValue());
                else if (parameter.isAssignableFrom(value.getClass())) method.invoke(target, value);
                else continue;
                return true;
            } catch (Exception error) {
                Log.w(TAG, "设置 libbox 属性失败：" + name, error);
                return false;
            }
        }
        return false;
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

    private void closeCore() {
        Object server;
        ParcelFileDescriptor pfd;
        synchronized (coreLock) {
            server = commandServer;
            commandServer = null;
            pfd = tunDescriptor;
            tunDescriptor = null;
            activeConfig = null;
        }
        if (server != null) {
            try { invokeNoArgs(server, "closeService"); }
            catch (Exception error) { Log.w(TAG, "关闭 sing-box service 失败", error); }
            try { invokeNoArgs(server, "close"); }
            catch (Exception error) { Log.w(TAG, "关闭 CommandServer 失败", error); }
        }
        if (pfd != null) {
            try { pfd.close(); }
            catch (Exception error) { Log.w(TAG, "关闭 TUN 文件描述符失败", error); }
        }
    }

    private void stopEngineAndService() {
        stopping = true;
        Future<?> task = startFuture;
        if (task != null) task.cancel(true);
        closeDefaultInterfaceMonitor();
        closeCore();
        stopForegroundCompat();
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Q7VPN 连接状态", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("显示 Q7VPN 隧道连接状态");
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String message) {
        createNotificationChannel();
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Q7VPN")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat(String message) {
        Notification notification = buildNotification(message);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    private static Exception unwrap(InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception) return (Exception) cause;
        return new Exception(cause == null ? error : cause);
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName() : message;
    }

    @Override
    public void onRevoke() {
        stopEngineAndService();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        Future<?> task = startFuture;
        if (task != null) task.cancel(true);
        closeDefaultInterfaceMonitor();
        closeCore();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
