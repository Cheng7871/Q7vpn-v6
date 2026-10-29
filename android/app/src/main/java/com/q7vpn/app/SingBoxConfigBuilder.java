package com.q7vpn.app;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Converts supported share links into a minimal sing-box configuration.
 * This class does not start the VPN engine. */
public final class SingBoxConfigBuilder {
    private SingBoxConfigBuilder() {}

    public static JSONObject build(String shareUri) throws Exception {
        if (shareUri == null) throw new IllegalArgumentException("节点内容为空");
        String raw = shareUri.trim();
        JSONObject outbound;
        if (raw.startsWith("vmess://")) outbound = vmess(raw);
        else if (raw.startsWith("vless://")) outbound = vless(raw);
        else if (raw.startsWith("trojan://")) outbound = trojan(raw);
        else if (raw.startsWith("ss://")) outbound = shadowsocks(raw);
        else throw new IllegalArgumentException("暂不支持此节点格式");

        JSONArray outbounds = new JSONArray();
        outbounds.put(outbound);
        JSONObject direct = new JSONObject().put("type", "direct").put("tag", "direct");
        outbounds.put(direct);
        JSONObject route = new JSONObject().put("final", "proxy");
        JSONObject dnsServer = new JSONObject().put("tag", "dns-remote")
                .put("address", "https://1.1.1.1/dns-query").put("detour", "proxy");
        JSONArray dnsServers = new JSONArray().put(dnsServer);
        JSONObject dns = new JSONObject().put("servers", dnsServers).put("final", "dns-remote");
        JSONObject log = new JSONObject().put("level", "warn");
        return new JSONObject().put("log", log).put("dns", dns)
                .put("outbounds", outbounds).put("route", route);
    }

    private static JSONObject base(String type, String tag, String host, int port) throws Exception {
        if (host == null || host.isEmpty() || port < 1 || port > 65535)
            throw new IllegalArgumentException("服务器地址或端口无效");
        return new JSONObject().put("type", type).put("tag", tag)
                .put("server", host).put("server_port", port);
    }

    private static JSONObject vless(String raw) throws Exception {
        URI u = URI.create(raw);
        String uuid = u.getUserInfo();
        if (uuid == null || uuid.isEmpty()) throw new IllegalArgumentException("VLESS 缺少 UUID");
        JSONObject o = base("vless", "proxy", u.getHost(), u.getPort()).put("uuid", uuid);
        String flow = query(u, "flow"); if (!flow.isEmpty()) o.put("flow", flow);
        addTls(o, u);
        addTransport(o, u);
        return o;
    }

    private static JSONObject trojan(String raw) throws Exception {
        URI u = URI.create(raw);
        String password = u.getUserInfo();
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Trojan 缺少密码");
        JSONObject o = base("trojan", "proxy", u.getHost(), u.getPort()).put("password", decode(password));
        addTls(o, u);
        addTransport(o, u);
        return o;
    }

    private static JSONObject vmess(String raw) throws Exception {
        String body = raw.substring("vmess://".length());
        int hash = body.indexOf('#'); if (hash >= 0) body = body.substring(0, hash);
        JSONObject j = new JSONObject(new String(Base64.decode(pad(body), Base64.DEFAULT | Base64.URL_SAFE), StandardCharsets.UTF_8));
        String host = j.optString("add", "");
        int port = parsePort(j.optString("port", "0"));
        String uuid = j.optString("id", "");
        if (uuid.isEmpty()) throw new IllegalArgumentException("VMess 缺少 UUID");
        JSONObject o = base("vmess", "proxy", host, port).put("uuid", uuid)
                .put("security", j.optString("scy", "auto"));
        String net = j.optString("net", "tcp");
        if ("ws".equalsIgnoreCase(net)) {
            JSONObject ws = new JSONObject().put("type", "ws");
            String path = j.optString("path", ""); if (!path.isEmpty()) ws.put("path", path);
            String hostHeader = j.optString("host", "");
            if (!hostHeader.isEmpty()) ws.put("headers", new JSONObject().put("Host", hostHeader));
            o.put("transport", ws);
        } else if (!"tcp".equalsIgnoreCase(net) && !net.isEmpty()) {
            throw new IllegalArgumentException("此 VMess 传输方式暂不支持：" + net);
        }
        if ("tls".equalsIgnoreCase(j.optString("tls", ""))) {
            JSONObject tls = new JSONObject().put("enabled", true);
            String sni = j.optString("sni", j.optString("host", ""));
            if (!sni.isEmpty()) tls.put("server_name", sni);
            o.put("tls", tls);
        }
        return o;
    }

    private static JSONObject shadowsocks(String raw) throws Exception {
        URI u = URI.create(raw);
        String host = u.getHost(); int port = u.getPort();
        String user = u.getUserInfo();
        String method = "", password = "";
        if (user != null) {
            String decoded = decode(user);
            int colon = decoded.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("SS 加密方式或密码格式无效");
            method = decoded.substring(0, colon); password = decoded.substring(colon + 1);
        } else {
            String body = raw.substring("ss://".length()).split("[?#]", 2)[0];
            String decoded = new String(Base64.decode(pad(body), Base64.DEFAULT | Base64.URL_SAFE), StandardCharsets.UTF_8);
            int at = decoded.lastIndexOf('@');
            if (at < 1) throw new IllegalArgumentException("无法识别此 SS 分享链接");
            String auth = decoded.substring(0, at); int colon = auth.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("SS 密码格式无效");
            method = auth.substring(0, colon); password = auth.substring(colon + 1);
            String[] hp = decoded.substring(at + 1).split(":", 2);
            if (hp.length != 2) throw new IllegalArgumentException("SS 服务器地址无效");
            host = hp[0]; port = parsePort(hp[1]);
        }
        return base("shadowsocks", "proxy", host, port)
                .put("method", method).put("password", password);
    }

    private static void addTls(JSONObject o, URI u) throws Exception {
        String security = query(u, "security");
        String sni = query(u, "sni");
        String fp = query(u, "fp");
        String pbk = query(u, "pbk");
        String sid = query(u, "sid");
        if ("tls".equalsIgnoreCase(security) || "reality".equalsIgnoreCase(security)) {
            JSONObject tls = new JSONObject().put("enabled", true);
            if (!sni.isEmpty()) tls.put("server_name", sni);
            if (!fp.isEmpty()) tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fp));
            if ("reality".equalsIgnoreCase(security)) {
                JSONObject reality = new JSONObject().put("enabled", true);
                if (!pbk.isEmpty()) reality.put("public_key", pbk);
                if (!sid.isEmpty()) reality.put("short_id", sid);
                tls.put("reality", reality);
            }
            o.put("tls", tls);
        }
    }

    private static void addTransport(JSONObject o, URI u) throws Exception {
        String type = query(u, "type");
        if (type.isEmpty() || "tcp".equalsIgnoreCase(type)) return;
        if ("ws".equalsIgnoreCase(type)) {
            JSONObject ws = new JSONObject().put("type", "ws");
            String path = query(u, "path"); if (!path.isEmpty()) ws.put("path", path);
            String host = query(u, "host");
            if (!host.isEmpty()) ws.put("headers", new JSONObject().put("Host", host));
            o.put("transport", ws);
        } else if ("grpc".equalsIgnoreCase(type)) {
            String service = query(u, "serviceName");
            JSONObject grpc = new JSONObject().put("type", "grpc");
            if (!service.isEmpty()) grpc.put("service_name", service);
            o.put("transport", grpc);
        } else throw new IllegalArgumentException("暂不支持此传输方式：" + type);
    }

    private static String query(URI u, String key) throws Exception {
        String q = u.getRawQuery(); if (q == null) return "";
        for (String item : q.split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2 && decode(pair[0]).equals(key)) return decode(pair[1]);
        }
        return "";
    }
    private static String decode(String s) throws Exception { return URLDecoder.decode(s, "UTF-8"); }
    private static String pad(String s) { int n = s.length() % 4; return n == 0 ? s : s + "====".substring(n); }
    private static int parsePort(String s) { int p = Integer.parseInt(s); if (p < 1 || p > 65535) throw new IllegalArgumentException("端口无效"); return p; }
}
