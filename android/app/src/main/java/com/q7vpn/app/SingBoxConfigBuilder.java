package com.q7vpn.app;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Builds a complete sing-box 1.14.x client config for the supported share-link formats. */
public final class SingBoxConfigBuilder {
    private static final String PROXY_TAG = "proxy";
    private static final String BOOTSTRAP_DNS_TAG = "dns-bootstrap";
    private static final String REMOTE_DNS_TAG = "dns-remote";

    private SingBoxConfigBuilder() { }

    /** Returns a complete configuration, including the Android TUN inbound. */
    public static JSONObject build(String shareUri) throws Exception {
        if (shareUri == null || shareUri.trim().isEmpty()) {
            throw new IllegalArgumentException("节点内容为空");
        }
        String raw = shareUri.trim();
        JSONObject outbound;
        if (raw.regionMatches(true, 0, "vmess://", 0, 8)) outbound = vmess(raw);
        else if (raw.regionMatches(true, 0, "vless://", 0, 8)) outbound = vless(raw);
        else if (raw.regionMatches(true, 0, "trojan://", 0, 9)) outbound = trojan(raw);
        else if (raw.regionMatches(true, 0, "ss://", 0, 5)) outbound = shadowsocks(raw);
        else throw new IllegalArgumentException("暂不支持此节点格式：目前支持 VLESS、VMess、Trojan、Shadowsocks 分享链接");

        // Resolve a domain-form proxy endpoint before the tunnel starts, avoiding DNS recursion.
        outbound.put("domain_resolver", BOOTSTRAP_DNS_TAG);

        JSONArray outbounds = new JSONArray()
                .put(outbound)
                .put(new JSONObject().put("type", "direct").put("tag", "direct"));

        JSONObject bootstrapDns = new JSONObject()
                .put("type", "udp")
                .put("tag", BOOTSTRAP_DNS_TAG)
                .put("server", "1.1.1.1")
                .put("detour", "direct");
        JSONObject remoteDns = new JSONObject()
                .put("type", "https")
                .put("tag", REMOTE_DNS_TAG)
                .put("server", "1.1.1.1")
                .put("server_port", 443)
                .put("path", "/dns-query")
                .put("detour", PROXY_TAG);
        JSONObject dns = new JSONObject()
                .put("servers", new JSONArray().put(bootstrapDns).put(remoteDns))
                .put("final", REMOTE_DNS_TAG)
                .put("strategy", "prefer_ipv4");

        JSONArray tunAddresses = new JSONArray()
                .put("172.19.0.1/30")
                .put("fdfe:dcba:9876::1/126");
        JSONArray tunDnsAddresses = new JSONArray()
                .put("172.19.0.2")
                .put("fdfe:dcba:9876::2");
        JSONObject tun = new JSONObject()
                .put("type", "tun")
                .put("tag", "tun-in")
                .put("address", tunAddresses)
                .put("dns_mode", "hijack")
                .put("dns_address", tunDnsAddresses)
                .put("mtu", 1400)
                .put("auto_route", true);

        // dns_address is explicitly set above, so sing-box requires a hijack-dns rule.
        JSONArray rules = new JSONArray().put(
                new JSONObject()
                        .put("protocol", "dns")
                        .put("action", "hijack-dns"));
        JSONObject route = new JSONObject()
                .put("rules", rules)
                .put("final", PROXY_TAG);

        return new JSONObject()
                .put("log", new JSONObject().put("level", "warn"))
                .put("dns", dns)
                .put("inbounds", new JSONArray().put(tun))
                .put("outbounds", outbounds)
                .put("route", route);
    }

    private static JSONObject base(String type, String host, int port) throws Exception {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("服务器地址或端口无效");
        }
        return new JSONObject().put("type", type).put("tag", PROXY_TAG)
                .put("server", host).put("server_port", port);
    }

    private static JSONObject vless(String raw) throws Exception {
        URI uri = URI.create(raw);
        String uuid = uri.getUserInfo();
        if (uuid == null || uuid.trim().isEmpty()) throw new IllegalArgumentException("VLESS 缺少 UUID");
        JSONObject outbound = base("vless", uri.getHost(), uri.getPort()).put("uuid", uuid);
        String encryption = query(uri, "encryption");
        if (!encryption.isEmpty() && !"none".equalsIgnoreCase(encryption)) {
            throw new IllegalArgumentException("当前 VLESS 加密参数不受支持：" + encryption);
        }
        String flow = query(uri, "flow");
        if (!flow.isEmpty()) outbound.put("flow", flow);
        String packetEncoding = firstNonEmpty(query(uri, "packetEncoding"), query(uri, "packet_encoding"));
        if (!packetEncoding.isEmpty()) outbound.put("packet_encoding", packetEncoding);
        addTls(outbound, uri, false, uri.getHost());
        addTransport(outbound, uri);
        return outbound;
    }

    private static JSONObject trojan(String raw) throws Exception {
        URI uri = URI.create(raw);
        String password = uri.getUserInfo();
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Trojan 缺少密码");
        JSONObject outbound = base("trojan", uri.getHost(), uri.getPort()).put("password", password);
        // Trojan is TLS-based; use SNI when supplied and otherwise fall back to the node host.
        addTls(outbound, uri, true, uri.getHost());
        addTransport(outbound, uri);
        return outbound;
    }

    private static JSONObject vmess(String raw) throws Exception {
        String body = raw.substring("vmess://".length());
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash);
        int query = body.indexOf('?');
        if (query >= 0) body = body.substring(0, query);
        JSONObject json;
        try {
            byte[] decoded = Base64.decode(padBase64(body), Base64.DEFAULT | Base64.URL_SAFE);
            json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException("VMess 分享链接不是有效的 Base64 JSON", error);
        }
        String host = json.optString("add", "").trim();
        int port = parsePort(json.optString("port", "0"));
        String uuid = json.optString("id", "").trim();
        if (uuid.isEmpty()) throw new IllegalArgumentException("VMess 缺少 UUID");
        JSONObject outbound = base("vmess", host, port)
                .put("uuid", uuid)
                .put("security", json.optString("scy", "auto"))
                .put("alter_id", Math.max(0, json.optInt("aid", 0)));
        String packetEncoding = json.optString("packetEncoding", "");
        if (!packetEncoding.isEmpty()) outbound.put("packet_encoding", packetEncoding);

        String network = json.optString("net", "tcp").toLowerCase(Locale.ROOT);
        if ("ws".equals(network)) {
            JSONObject ws = new JSONObject().put("type", "ws");
            String path = json.optString("path", "");
            if (!path.isEmpty()) ws.put("path", path);
            String hostHeader = json.optString("host", "");
            if (!hostHeader.isEmpty()) ws.put("headers", new JSONObject().put("Host", hostHeader));
            outbound.put("transport", ws);
        } else if ("grpc".equals(network)) {
            JSONObject grpc = new JSONObject().put("type", "grpc");
            String serviceName = json.optString("path", json.optString("serviceName", ""));
            if (!serviceName.isEmpty()) grpc.put("service_name", serviceName);
            outbound.put("transport", grpc);
        } else if ("tcp".equals(network) || network.isEmpty()) {
            // Native TCP transport; no transport block needed.
        } else {
            throw new IllegalArgumentException("暂不支持此 VMess 传输方式：" + network);
        }

        String tlsFlag = json.optString("tls", "");
        if (!tlsFlag.isEmpty() && !"none".equalsIgnoreCase(tlsFlag)) {
            JSONObject tls = new JSONObject().put("enabled", true);
            String sni = json.optString("sni", json.optString("host", host));
            if (!sni.isEmpty()) tls.put("server_name", sni);
            String fp = json.optString("fp", "");
            if (!fp.isEmpty()) tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fp));
            outbound.put("tls", tls);
        }
        return outbound;
    }

    private static JSONObject shadowsocks(String raw) throws Exception {
        URI uri = URI.create(raw);
        String host = uri.getHost();
        int port = uri.getPort();
        String method = "";
        String password = "";
        String userInfo = uri.getRawUserInfo();

        if (userInfo != null && !userInfo.isEmpty()) {
            String decodedUserInfo = URLDecoder.decode(userInfo, "UTF-8");
            if (decodedUserInfo.indexOf(':') < 1) decodedUserInfo = decodeBase64Text(decodedUserInfo);
            int colon = decodedUserInfo.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("Shadowsocks 加密方式或密码格式无效");
            method = decodedUserInfo.substring(0, colon);
            password = decodedUserInfo.substring(colon + 1);
        } else {
            // Legacy form: ss://BASE64(method:password@host:port)#name
            String body = raw.substring("ss://".length()).split("[?#]", 2)[0];
            String decoded = decodeBase64Text(body);
            int at = decoded.lastIndexOf('@');
            if (at < 1) throw new IllegalArgumentException("无法识别此 Shadowsocks 分享链接");
            String auth = decoded.substring(0, at);
            int colon = auth.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("Shadowsocks 密码格式无效");
            method = auth.substring(0, colon);
            password = auth.substring(colon + 1);
            URI endpoint = URI.create("ss://" + decoded);
            host = endpoint.getHost();
            port = endpoint.getPort();
        }
        JSONObject outbound = base("shadowsocks", host, port)
                .put("method", method).put("password", password);
        String plugin = query(uri, "plugin");
        if (!plugin.isEmpty()) {
            String[] parts = plugin.split(";", 2);
            outbound.put("plugin", parts[0]);
            if (parts.length > 1) outbound.put("plugin_opts", parts[1]);
        }
        return outbound;
    }

    private static void addTls(JSONObject outbound, URI uri, boolean forceTls, String defaultSni) throws Exception {
        String security = query(uri, "security");
        boolean reality = "reality".equalsIgnoreCase(security);
        boolean tlsEnabled = forceTls || "tls".equalsIgnoreCase(security) || reality;
        if (!tlsEnabled) return;

        JSONObject tls = new JSONObject().put("enabled", true);
        String sni = firstNonEmpty(query(uri, "sni"), defaultSni);
        if (!sni.isEmpty()) tls.put("server_name", sni);
        String insecure = firstNonEmpty(query(uri, "allowInsecure"), query(uri, "insecure"));
        if ("1".equals(insecure) || "true".equalsIgnoreCase(insecure)) tls.put("insecure", true);
        String fingerprint = query(uri, "fp");
        if (!fingerprint.isEmpty()) {
            tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fingerprint));
        }
        String alpn = query(uri, "alpn");
        if (!alpn.isEmpty()) {
            JSONArray values = new JSONArray();
            for (String item : alpn.split(",")) if (!item.trim().isEmpty()) values.put(item.trim());
            if (values.length() > 0) tls.put("alpn", values);
        }
        if (reality) {
            String publicKey = query(uri, "pbk");
            if (publicKey.isEmpty()) throw new IllegalArgumentException("Reality 节点缺少 pbk 公钥参数");
            JSONObject realityConfig = new JSONObject().put("enabled", true).put("public_key", publicKey);
            String shortId = query(uri, "sid");
            if (!shortId.isEmpty()) realityConfig.put("short_id", shortId);
            tls.put("reality", realityConfig);
        }
        outbound.put("tls", tls);
    }

    private static void addTransport(JSONObject outbound, URI uri) throws Exception {
        String type = query(uri, "type").toLowerCase(Locale.ROOT);
        if (type.isEmpty() || "tcp".equals(type)) return;
        if ("ws".equals(type)) {
            JSONObject ws = new JSONObject().put("type", "ws");
            String path = query(uri, "path");
            if (!path.isEmpty()) ws.put("path", path);
            String host = query(uri, "host");
            if (!host.isEmpty()) ws.put("headers", new JSONObject().put("Host", host));
            outbound.put("transport", ws);
        } else if ("grpc".equals(type)) {
            JSONObject grpc = new JSONObject().put("type", "grpc");
            String serviceName = firstNonEmpty(query(uri, "serviceName"), query(uri, "service_name"));
            if (!serviceName.isEmpty()) grpc.put("service_name", serviceName);
            outbound.put("transport", grpc);
        } else if ("httpupgrade".equals(type)) {
            JSONObject upgrade = new JSONObject().put("type", "httpupgrade");
            String path = query(uri, "path");
            if (!path.isEmpty()) upgrade.put("path", path);
            String host = query(uri, "host");
            if (!host.isEmpty()) upgrade.put("host", host);
            outbound.put("transport", upgrade);
        } else {
            throw new IllegalArgumentException("暂不支持此传输方式：" + type);
        }
    }

    private static String query(URI uri, String key) throws Exception {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        for (String item : rawQuery.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2 && URLDecoder.decode(pair[0], "UTF-8").equals(key)) {
                return URLDecoder.decode(pair[1].replace("+", "%2B"), "UTF-8");
            }
        }
        return "";
    }

    private static String decodeBase64Text(String value) {
        try {
            String normalized = value.trim().replace('-', '+').replace('_', '/');
            normalized = normalized.replaceAll("\\s+", "");
            int remainder = normalized.length() % 4;
            if (remainder != 0) normalized += "====".substring(remainder);
            return new String(Base64.decode(normalized, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalArgumentException("Shadowsocks Base64 数据无效", error);
        }
    }

    private static String padBase64(String value) {
        String normalized = value.replace('-', '+').replace('_', '/');
        int remainder = normalized.length() % 4;
        return remainder == 0 ? normalized : normalized + "====".substring(remainder);
    }

    private static String firstNonEmpty(String first, String second) {
        return first == null || first.isEmpty() ? (second == null ? "" : second) : first;
    }

    private static int parsePort(String value) {
        int port;
        try { port = Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("节点端口不是有效数字", e); }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
        return port;
    }
}
