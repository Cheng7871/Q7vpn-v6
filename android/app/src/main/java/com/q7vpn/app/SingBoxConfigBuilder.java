package com.q7vpn.app;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Converts supported share links into a sing-box 1.14.0 configuration. */
public final class SingBoxConfigBuilder {
    private SingBoxConfigBuilder() { }

    public static JSONObject build(String shareUri) throws Exception {
        if (shareUri == null || shareUri.trim().isEmpty()) {
            throw new IllegalArgumentException("节点内容为空");
        }
        String raw = shareUri.trim();
        JSONObject outbound;
        if (raw.startsWith("vmess://")) outbound = vmess(raw);
        else if (raw.startsWith("vless://")) outbound = vless(raw);
        else if (raw.startsWith("trojan://")) outbound = trojan(raw);
        else if (raw.startsWith("ss://")) outbound = shadowsocks(raw);
        else throw new IllegalArgumentException("暂不支持此节点格式");

        // Resolve a proxy node's own domain directly to avoid a DNS/proxy bootstrap loop.
        outbound.put("domain_resolver", "dns-bootstrap");
        JSONArray outbounds = new JSONArray()
                .put(outbound)
                .put(new JSONObject().put("type", "direct").put("tag", "direct"));

        JSONObject bootstrapDns = new JSONObject()
                .put("type", "udp")
                .put("tag", "dns-bootstrap")
                .put("server", "1.1.1.1");
        // sing-box 1.12+ DNS server format; 1.14.0 uses typed DNS servers.
        JSONObject dnsServer = new JSONObject()
                .put("type", "https")
                .put("tag", "dns-remote")
                .put("server", "1.1.1.1")
                .put("server_port", 443)
                .put("path", "/dns-query")
                .put("detour", "proxy");
        JSONObject dns = new JSONObject()
                .put("servers", new JSONArray().put(bootstrapDns).put(dnsServer))
                .put("final", "dns-remote");
        JSONObject route = new JSONObject().put("final", "proxy");
        JSONObject log = new JSONObject().put("level", "warn");
        return new JSONObject().put("log", log)
                .put("dns", dns)
                .put("outbounds", outbounds)
                .put("route", route);
    }

    private static JSONObject base(String type, String tag, String host, int port) throws Exception {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("服务器地址或端口无效");
        }
        return new JSONObject().put("type", type).put("tag", tag)
                .put("server", host).put("server_port", port);
    }

    private static JSONObject vless(String raw) throws Exception {
        URI uri = URI.create(raw);
        String uuid = uri.getUserInfo();
        if (uuid == null || uuid.isEmpty()) throw new IllegalArgumentException("VLESS 缺少 UUID");
        JSONObject outbound = base("vless", "proxy", uri.getHost(), uri.getPort()).put("uuid", uuid);
        String flow = query(uri, "flow");
        if (!flow.isEmpty()) outbound.put("flow", flow);
        addTls(outbound, uri);
        addTransport(outbound, uri);
        return outbound;
    }

    private static JSONObject trojan(String raw) throws Exception {
        URI uri = URI.create(raw);
        String password = uri.getUserInfo();
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Trojan 缺少密码");
        JSONObject outbound = base("trojan", "proxy", uri.getHost(), uri.getPort())
                .put("password", password);
        addTls(outbound, uri);
        addTransport(outbound, uri);
        return outbound;
    }

    private static JSONObject vmess(String raw) throws Exception {
        String body = raw.substring("vmess://".length());
        int hash = body.indexOf('#');
        if (hash >= 0) body = body.substring(0, hash);
        JSONObject json = new JSONObject(new String(
                Base64.decode(pad(body), Base64.DEFAULT | Base64.URL_SAFE), StandardCharsets.UTF_8));
        String host = json.optString("add", "");
        int port = parsePort(json.optString("port", "0"));
        String uuid = json.optString("id", "");
        if (uuid.isEmpty()) throw new IllegalArgumentException("VMess 缺少 UUID");
        JSONObject outbound = base("vmess", "proxy", host, port)
                .put("uuid", uuid)
                .put("security", json.optString("scy", "auto"));

        String network = json.optString("net", "tcp");
        if ("ws".equalsIgnoreCase(network)) {
            JSONObject ws = new JSONObject().put("type", "ws");
            String path = json.optString("path", "");
            if (!path.isEmpty()) ws.put("path", path);
            String hostHeader = json.optString("host", "");
            if (!hostHeader.isEmpty()) ws.put("headers", new JSONObject().put("Host", hostHeader));
            outbound.put("transport", ws);
        } else if (!"tcp".equalsIgnoreCase(network) && !network.isEmpty()) {
            throw new IllegalArgumentException("此 VMess 传输方式暂不支持：" + network);
        }

        if ("tls".equalsIgnoreCase(json.optString("tls", ""))) {
            JSONObject tls = new JSONObject().put("enabled", true);
            String sni = json.optString("sni", json.optString("host", ""));
            if (!sni.isEmpty()) tls.put("server_name", sni);
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
        String userInfo = uri.getUserInfo();

        if (userInfo != null && !userInfo.isEmpty()) {
            String decoded = userInfo; // URI.getUserInfo() is already percent-decoded.
            if (decoded.indexOf(':') < 1) {
                // SIP002 commonly uses base64(method:password) before @host:port.
                decoded = decodeBase64Text(userInfo);
            }
            int colon = decoded.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("SS 加密方式或密码格式无效");
            method = decoded.substring(0, colon);
            password = decoded.substring(colon + 1);
        } else {
            // Legacy form: ss://base64(method:password@host:port)
            String body = raw.substring("ss://".length()).split("[?#]", 2)[0];
            String decoded = decodeBase64Text(body);
            int at = decoded.lastIndexOf('@');
            if (at < 1) throw new IllegalArgumentException("无法识别此 SS 分享链接");
            String auth = decoded.substring(0, at);
            int colon = auth.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("SS 密码格式无效");
            method = auth.substring(0, colon);
            password = auth.substring(colon + 1);
            URI serverUri = URI.create("ss://" + decoded);
            host = serverUri.getHost();
            port = serverUri.getPort();
        }

        return base("shadowsocks", "proxy", host, port)
                .put("method", method).put("password", password);
    }

    private static String decodeBase64Text(String value) throws Exception {
        String normalized = value.replace('-', '+').replace('_', '/');
        int remainder = normalized.length() % 4;
        if (remainder != 0) normalized += "====".substring(remainder);
        byte[] bytes = Base64.decode(normalized, Base64.DEFAULT);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void addTls(JSONObject outbound, URI uri) throws Exception {
        String security = query(uri, "security");
        String sni = query(uri, "sni");
        String fingerprint = query(uri, "fp");
        String publicKey = query(uri, "pbk");
        String shortId = query(uri, "sid");
        if ("tls".equalsIgnoreCase(security) || "reality".equalsIgnoreCase(security)) {
            JSONObject tls = new JSONObject().put("enabled", true);
            if (!sni.isEmpty()) tls.put("server_name", sni);
            if (!fingerprint.isEmpty()) {
                tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fingerprint));
            }
            if ("reality".equalsIgnoreCase(security)) {
                JSONObject reality = new JSONObject().put("enabled", true);
                if (!publicKey.isEmpty()) reality.put("public_key", publicKey);
                if (!shortId.isEmpty()) reality.put("short_id", shortId);
                tls.put("reality", reality);
            }
            outbound.put("tls", tls);
        }
    }

    private static void addTransport(JSONObject outbound, URI uri) throws Exception {
        String type = query(uri, "type");
        if (type.isEmpty() || "tcp".equalsIgnoreCase(type)) return;
        if ("ws".equalsIgnoreCase(type)) {
            JSONObject ws = new JSONObject().put("type", "ws");
            String path = query(uri, "path");
            if (!path.isEmpty()) ws.put("path", path);
            String host = query(uri, "host");
            if (!host.isEmpty()) ws.put("headers", new JSONObject().put("Host", host));
            outbound.put("transport", ws);
        } else if ("grpc".equalsIgnoreCase(type)) {
            JSONObject grpc = new JSONObject().put("type", "grpc");
            String service = query(uri, "serviceName");
            if (!service.isEmpty()) grpc.put("service_name", service);
            outbound.put("transport", grpc);
        } else {
            throw new IllegalArgumentException("暂不支持此传输方式：" + type);
        }
    }

    private static String query(URI uri, String key) throws Exception {
        String query = uri.getRawQuery();
        if (query == null) return "";
        for (String item : query.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2 && decode(pair[0]).equals(key)) return decode(pair[1]);
        }
        return "";
    }

    private static String decode(String value) throws Exception {
        return URLDecoder.decode(value, "UTF-8");
    }

    private static String pad(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "====".substring(remainder);
    }

    private static int parsePort(String value) {
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口无效");
        return port;
    }
}
