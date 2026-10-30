package com.q7vpn.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.net.ProxyInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.system.OsConstants;

import java.net.Inet6Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Q7VPN service bridge for the libbox 1.14.0 runtime already declared by Gradle. */
public final class Q7VpnService extends VpnService {
    public static final String ACTION_START = "com.q7vpn.app.action.START_VPN";
    public static final String ACTION_STOP = "com.q7vpn.app.action.STOP_VPN";
    public static final String EXTRA_CONFIG = "com.q7vpn.app.extra.SINGBOX_CONFIG";
    private static final String TAG = "Q7VpnService";
    private static final String LIBBOX = "io.nekohasekai.libbox.";
    private static final int NOTIFICATION_ID = 7001;
    private static final String CHANNEL_ID = "q7vpn_vpn";

    private static volatile boolean libboxReady;
    private final Object lock = new Object();
    private volatile boolean stopping;
    private Object commandServer;
    private ParcelFileDescriptor tunDescriptor;
    private Thread startThread;

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
            Log.e(TAG, "启动失败：没有收到节点配置");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        stopping = false;
        startForegroundCompat("Q7VPN 正在连接");
        if (startThread != null) startThread.interrupt();
        startThread = new Thread(() -> {
            try {
                closeCore();
                startCore(config);
                if (!stopping) updateNotification("Q7VPN 已启动");
            } catch (Throwable error) {
                Log.e(TAG, "sing-box 启动失败", error);
                updateNotification("Q7VPN 启动失败：" + safeMessage(error));
                closeCore();
                stopForegroundCompat();
                stopSelf();
            }
        }, "q7vpn-libbox-start");
        startThread.start();
        return START_NOT_STICKY;
    }

    private void startCore(String config) throws Exception {
        prepareLibbox();
        Class<?> platformType = Class.forName(LIBBOX + "PlatformInterface");
        Class<?> handlerType = Class.forName(LIBBOX + "CommandServerHandler");
        Object platform = Proxy.newProxyInstance(platformType.getClassLoader(), new Class<?>[]{platformType},
                new PlatformHandler());
        Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType},
                new CommandHandler());

        Class<?> serverType = Class.forName(LIBBOX + "CommandServer");
        Constructor<?> constructor = null;
        for (Constructor<?> item : serverType.getConstructors()) {
            Class<?>[] p = item.getParameterTypes();
            if (p.length == 2 && p[0].isAssignableFrom(handlerType) && p[1].isAssignableFrom(platformType)) {
                constructor = item;
                break;
            }
        }
        if (constructor == null) {
            // Gomobile's generated interfaces can be exposed as the exact interface types.
            constructor = serverType.getConstructor(handlerType, platformType);
        }
        Object server = constructor.newInstance(handler, platform);
        synchronized (lock) { commandServer = server; }
        invoke(server, "start");
        Object override = Class.forName(LIBBOX + "OverrideOptions").getConstructor().newInstance();
        invokeTwoArg(server, "startOrReloadService", config, override);
        Log.i(TAG, "libbox 1.14.0 已接收配置并启动服务");
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
            if (!work.exists() && !work.mkdirs()) throw new IllegalStateException("无法创建 VPN 工作目录");
            if (!temp.exists() && !temp.mkdirs()) throw new IllegalStateException("无法创建 VPN 临时目录");
            setProperty(options, "basePath", base.getAbsolutePath());
            setProperty(options, "workingPath", work.getAbsolutePath());
            setProperty(options, "tempPath", temp.getAbsolutePath());
            setProperty(options, "logMaxLines", 1000);
            setProperty(options, "debug", false);
            Class<?> libboxType = Class.forName(LIBBOX + "Libbox");
            invokeStaticWithOneArg(libboxType, "setup", options);
            libboxReady = true;
        }
    }

    private final class PlatformHandler implements InvocationHandler {
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("toString".equals(name)) return "Q7VPN PlatformInterface";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            if ("usePlatformAutoDetectInterfaceControl".equals(name)) return true;
            if ("autoDetectInterfaceControl".equals(name)) {
                int fd = ((Number) args[0]).intValue();
                if (!protect(fd)) throw new IllegalStateException("无法保护 VPN 核心网络连接");
                return null;
            }
            if ("openTun".equals(name)) return openTun(args[0]);
            if ("usePlatformShell".equals(name) || "usePlatformBridge".equals(name)
                    || "usePlatformAutoRedirect".equals(name) || "underNetworkExtension".equals(name)
                    || "includeAllNetworks".equals(name)) return false;
            if ("tailscaleHostname".equals(name)) return Build.MANUFACTURER + " " + Build.MODEL;
            if ("getInterfaces".equals(name)) return buildInterfaceIterator(method.getReturnType());
            if ("sendNotification".equals(name) || "cancelNotification".equals(name)
                    || "clearDNSCache".equals(name) || "registerMyInterface".equals(name)
                    || "startDefaultInterfaceMonitor".equals(name) || "closeDefaultInterfaceMonitor".equals(name)
                    || "startNeighborMonitor".equals(name) || "closeNeighborMonitor".equals(name)) return null;
            if ("readWIFIState".equals(name) || "localDNSTransport".equals(name)
                    || "lookupUser".equals(name) || "openShellSession".equals(name)
                    || "createBridge".equals(name) || "createAutoRedirect".equals(name)) return null;
            if ("readSystemSSHHostKey".equals(name) || "lookupSFTPServer".equals(name)) return "";
            return defaultValue(method.getReturnType());
        }
    }

    private final class CommandHandler implements InvocationHandler {
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("toString".equals(name)) return "Q7VPN CommandServerHandler";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
            if ("serviceStop".equals(name)) { stopEngineAndService(); return null; }
            if ("serviceReload".equals(name)) return null;
            if ("writeDebugMessage".equals(name)) {
                Log.d(TAG, args == null || args.length == 0 ? "" : String.valueOf(args[0]));
                return null;
            }
            if ("connectSSHAgent".equals(name)) return -1;
            if ("triggerNativeCrash".equals(name)) return null;
            if ("getSystemProxyStatus".equals(name)) {
                Object status = method.getReturnType().getConstructor().newInstance();
                setProperty(status, "available", false);
                setProperty(status, "enabled", false);
                return status;
            }
            return defaultValue(method.getReturnType());
        }
    }

    private int openTun(Object options) throws Exception {
        if (VpnService.prepare(this) != null) throw new IllegalStateException("还没有允许 Q7VPN 使用 VPN，请回到应用重新点连接");
        Builder builder = new Builder().setSession("Q7VPN").setMtu(readInt(options, "mtu", 1500));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false);

        int ipv4AddressCount = addAddressIterator(builder, readProperty(options, "inet4Address"));
        int ipv6AddressCount = addAddressIterator(builder, readProperty(options, "inet6Address"));
        int addressCount = ipv4AddressCount + ipv6AddressCount;

        if (readBoolean(options, "autoRoute", true)) {
            addDnsServers(builder, readProperty(options, "dnsServerAddress"));
            int routes = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                routes += addRouteIterator(builder, readProperty(options, "inet4RouteAddress"));
                routes += addRouteIterator(builder, readProperty(options, "inet6RouteAddress"));
            }
            if (routes == 0) {
                routes += addRouteIterator(builder, readProperty(options, "inet4RouteRange"));
                routes += addRouteIterator(builder, readProperty(options, "inet6RouteRange"));
            }
            if (routes == 0 && addressCount > 0) {
                if (ipv4AddressCount > 0) builder.addRoute("0.0.0.0", 0);
                if (ipv6AddressCount > 0) builder.addRoute("::", 0);
            }
            addPackages(builder, readProperty(options, "includePackage"), true);
            addPackages(builder, readProperty(options, "excludePackage"), false);
        }

        if (readBoolean(options, "isHTTPProxyEnabled", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String host = readString(options, "httpProxyServer", "");
            int port = readInt(options, "httpProxyServerPort", 0);
            if (!host.isEmpty() && port > 0) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(host, port, Collections.emptyList()));
            }
        }
        ParcelFileDescriptor pfd = builder.establish();
        if (pfd == null) throw new IllegalStateException("Android 没有建立 VPN 通道，请检查系统 VPN 授权");
        synchronized (lock) {
            if (tunDescriptor != null) try { tunDescriptor.close(); } catch (Exception ignored) { }
            tunDescriptor = pfd;
        }
        return pfd.getFd();
    }

    private boolean hasAddressFamily(Object options, String property) {
        try { Object it = readProperty(options, property); return hasNext(it); }
        catch (Exception e) { return false; }
    }

    private int addAddressIterator(Builder builder, Object iterator) throws Exception {
        int count = 0;
        while (hasNext(iterator)) {
            Object item = next(iterator);
            String address = readString(item, "address", "");
            int prefix = readInt(item, "prefix", -1);
            if (!address.isEmpty() && prefix >= 0) { builder.addAddress(address, prefix); count++; }
        }
        return count;
    }

    private int addRouteIterator(Builder builder, Object iterator) throws Exception {
        int count = 0;
        while (hasNext(iterator)) {
            Object item = next(iterator);
            String address;
            int prefix;
            if (item instanceof String) {
                String[] pair = ((String) item).split("/", 2);
                address = pair[0]; prefix = pair.length == 2 ? Integer.parseInt(pair[1]) : -1;
            } else {
                address = readString(item, "address", "");
                prefix = readInt(item, "prefix", -1);
            }
            if (!address.isEmpty() && prefix >= 0) { builder.addRoute(address, prefix); count++; }
        }
        return count;
    }

    private void addDnsServers(Builder builder, Object iterator) throws Exception {
        while (hasNext(iterator)) {
            Object item = next(iterator);
            if (item != null && !String.valueOf(item).trim().isEmpty()) builder.addDnsServer(String.valueOf(item));
        }
    }

    private void addPackages(Builder builder, Object iterator, boolean allow) throws Exception {
        while (hasNext(iterator)) {
            String packageName = String.valueOf(next(iterator));
            if (packageName.trim().isEmpty()) continue;
            try {
                if (allow) builder.addAllowedApplication(packageName);
                else builder.addDisallowedApplication(packageName);
            } catch (Exception e) { Log.w(TAG, "忽略无效应用包名：" + packageName, e); }
        }
    }

    /** Supplies sing-box with the phone's real interfaces (Wi-Fi/mobile/etc.). */
    private Object buildInterfaceIterator(Class<?> iteratorType) throws Exception {
        Class<?> itemType = Class.forName(LIBBOX + "NetworkInterface");
        List<Object> items = new ArrayList<>();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        java.util.Map<String, Network> networksByInterface = new java.util.HashMap<>();
        if (cm != null) {
            for (Network network : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(network);
                if (lp != null && lp.getInterfaceName() != null) networksByInterface.put(lp.getInterfaceName(), network);
            }
        }
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        while (all != null && all.hasMoreElements()) {
            NetworkInterface ni = all.nextElement();
            Object item = itemType.getConstructor().newInstance();
            String name = ni.getName();
            setProperty(item, "name", name);
            setProperty(item, "index", ni.getIndex());
            try { setProperty(item, "mtu", ni.getMTU()); } catch (Exception ignored) { }
            List<String> addresses = new ArrayList<>();
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress() != null) addresses.add(ia.getAddress().getHostAddress() + "/" + ia.getNetworkPrefixLength());
            }
            setProperty(item, "addresses", makeStringIterator(addresses));
            int flags = 0;
            try { if (ni.isUp()) flags |= OsConstants.IFF_UP | OsConstants.IFF_RUNNING; } catch (SocketException ignored) { }
            if (ni.isLoopback()) flags |= OsConstants.IFF_LOOPBACK;
            if (ni.isPointToPoint()) flags |= OsConstants.IFF_POINTOPOINT;
            if (ni.supportsMulticast()) flags |= OsConstants.IFF_MULTICAST;
            setProperty(item, "flags", flags);

            List<String> dns = new ArrayList<>(), gateways = new ArrayList<>(), search = new ArrayList<>();
            boolean metered = false;
            Network network = networksByInterface.get(name);
            if (network != null && cm != null) {
                LinkProperties lp = cm.getLinkProperties(network);
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (lp != null) {
                    for (java.net.InetAddress address : lp.getDnsServers()) dns.add(address.getHostAddress());
                    if (lp.getDomains() != null) {
                        for (String domain : lp.getDomains().trim().split("\\s+")) if (!domain.isEmpty()) search.add(domain);
                    }
                    for (RouteInfo route : lp.getRoutes()) {
                        if (route.getDestination() != null && route.getDestination().getPrefixLength() == 0
                                && route.getGateway() != null && !route.getGateway().isAnyLocalAddress()) {
                            gateways.add(route.getGateway().getHostAddress());
                        }
                    }
                }
                if (caps != null) metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }
            setProperty(item, "dnsServer", makeStringIterator(dns));
            setProperty(item, "gateway", makeStringIterator(gateways));
            setProperty(item, "dnsSearchDomain", makeStringIterator(search));
            setProperty(item, "metered", metered);
            int type = 0;
            if (network != null && cm != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = staticInt(LIBBOX + "Libbox", "InterfaceTypeWIFI", 1);
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = staticInt(LIBBOX + "Libbox", "InterfaceTypeCellular", 2);
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = staticInt(LIBBOX + "Libbox", "InterfaceTypeEthernet", 3);
                }
            }
            setProperty(item, "type", type);
            items.add(item);
        }
        return makeObjectIterator(iteratorType, items);
    }

    private Object makeStringIterator(List<String> values) throws Exception {
        Class<?> type = Class.forName(LIBBOX + "StringIterator");
        final java.util.Iterator<String> iterator = values.iterator();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            switch (m.getName()) {
                case "len": return values.size();
                case "hasNext": return iterator.hasNext();
                case "next": return iterator.next();
                case "toString": return "Q7VPN StringIterator";
                default: return defaultValue(m.getReturnType());
            }
        });
    }

    private Object makeObjectIterator(Class<?> type, List<Object> values) {
        final java.util.Iterator<Object> iterator = values.iterator();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            switch (m.getName()) {
                case "hasNext": return iterator.hasNext();
                case "next": return iterator.next();
                case "toString": return "Q7VPN NetworkInterfaceIterator";
                default: return defaultValue(m.getReturnType());
            }
        });
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
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static String readString(Object target, String name, String fallback) {
        try { Object v = readProperty(target, name); return v == null ? fallback : String.valueOf(v); }
        catch (Exception ignored) { return fallback; }
    }
    private static int readInt(Object target, String name, int fallback) {
        try { Object v = readProperty(target, name); return ((Number) v).intValue(); }
        catch (Exception ignored) { return fallback; }
    }
    private static boolean readBoolean(Object target, String name, boolean fallback) {
        try { Object v = readProperty(target, name); return v instanceof Boolean ? (Boolean) v : fallback; }
        catch (Exception ignored) { return fallback; }
    }
    private static boolean hasNext(Object iterator) throws Exception {
        if (iterator == null) return false;
        Object v = invoke(iterator, "hasNext"); return Boolean.TRUE.equals(v);
    }
    private static Object next(Object iterator) throws Exception { return invoke(iterator, "next"); }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }
    private static Object invokeTwoArg(Object target, String name, Object first, Object second) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 2
                    && m.getParameterTypes()[0].isAssignableFrom(first.getClass())
                    && m.getParameterTypes()[1].isAssignableFrom(second.getClass())) return m.invoke(target, first, second);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "(2 args)");
    }
    private static void invokeStaticWithOneArg(Class<?> type, String name, Object arg) throws Exception {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isAssignableFrom(arg.getClass())) {
                m.invoke(null, arg); return;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static void setProperty(Object target, String name, Object value) {
        if (target == null) return;
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(setter) || m.getParameterTypes().length != 1) continue;
            try {
                Class<?> p = m.getParameterTypes()[0];
                if (p == int.class && value instanceof Number) m.invoke(target, ((Number) value).intValue());
                else if (p == long.class && value instanceof Number) m.invoke(target, ((Number) value).longValue());
                else if (p == boolean.class && value instanceof Boolean) m.invoke(target, value);
                else if (p.isAssignableFrom(value.getClass())) m.invoke(target, value);
                return;
            } catch (Exception ignored) { }
        }
    }
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
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

    private void stopEngineAndService() {
        stopping = true;
        closeCore();
        stopForegroundCompat();
        stopSelf();
    }

    private void closeCore() {
        Object server;
        synchronized (lock) { server = commandServer; commandServer = null; }
        if (server != null) {
            try { invoke(server, "closeService"); } catch (Exception e) { Log.w(TAG, "closeService", e); }
            try { invoke(server, "close"); } catch (Exception e) { Log.w(TAG, "close", e); }
        }
        synchronized (lock) {
            if (tunDescriptor != null) {
                try { tunDescriptor.close(); } catch (Exception ignored) { }
                tunDescriptor = null;
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Q7VPN 连接状态", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示 Q7VPN 是否正在连接或运行");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {
        createChannel();
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }
    private void updateNotification(String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(message));
    }
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }
    private static String safeMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    @Override public void onRevoke() {
        stopEngineAndService();
        super.onRevoke();
    }
    @Override public void onDestroy() {
        stopping = true;
        closeCore();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
