package com.q7vpn.app;

import android.util.Base64;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Q7VPN node text parser. Supports common share links and basic Clash proxy entries. */
public final class NodeParser {
    private NodeParser() {}

    public static final class ParsedNode {
        public final String uri, name, protocol, host;
        public final int port;
        ParsedNode(String uri, String name, String protocol, String host, int port) {
            this.uri = uri; this.name = name; this.protocol = protocol; this.host = host; this.port = port;
        }
    }

    public static List<ParsedNode> parseText(String input) {
        Map<String, ParsedNode> result = new LinkedHashMap<>();
        if (input == null || input.trim().isEmpty()) return new ArrayList<>();
        String decoded = decodeSubscription(input.trim());
        Matcher links = Pattern.compile("(?im)(?:^|\\s)((?:vless|vmess|trojan|ss)://[^\\s\\\"']+)").matcher(decoded);
        while (links.find()) {
            String link = links.group(1).trim().replaceAll("[,;]+$", "");
            ParsedNode node = parseUri(link);
            if (node != null) result.put(node.uri, node);
        }
        parseBasicClash(decoded, result);
        return new ArrayList<>(result.values());
    }

    private static void parseBasicClash(String text, Map<String, ParsedNode> out) {
        // Basic YAML proxy list extraction. This does not pretend to convert every Clash feature.
        Matcher list = Pattern.compile("(?ms)^\\s*proxies\\s*:\\s*\\n(.*?)(?=^\\S|\\z)").matcher(text);
        if (!list.find()) return;
        Matcher blocks = Pattern.compile("(?ms)^\\s*-\\s*([^\\r\\n]+)\\r?\\n(.*?)(?=^\\s*-\\s*[^\\r\\n]+\\r?\\n|\\z)").matcher(list.group(1));
        while (blocks.find()) {
            String body = blocks.group(2);
            String name = yamlField(body, "name");
            String type = yamlField(body, "type");
            String host = yamlField(body, "server");
            String portText = yamlField(body, "port");
            try {
                int port = Integer.parseInt(portText);
                if (host.isEmpty() || type.isEmpty() || port < 1 || port > 65535) continue;
                // Keep a source marker only for display; this is not a runnable share URI.
                String marker = "clash-entry://" + type.toLowerCase(Locale.ROOT) + "@" + host + ":" + port + "#" + name;
                out.put(marker, new ParsedNode(marker, name.isEmpty() ? host : name,
                        "CLASH-" + type.toUpperCase(Locale.ROOT), host, port));
            } catch (Exception ignored) { }
        }
    }

    private static String yamlField(String block, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*([^\\r\\n#]+)").matcher(block);
        if (!m.find()) return "";
        String value = m.group(1).trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
            value = value.substring(1, value.length() - 1);
        return value;
    }

    private static String decodeSubscription(String input) {
        String compact = input.replaceAll("\\s+", "");
        if (compact.length() < 16 || !compact.matches("[A-Za-z0-9_+/=-]+")) return input;
        try {
            byte[] bytes;
            try { bytes = Base64.decode(compact, Base64.URL_SAFE | Base64.NO_WRAP); }
            catch (Exception e) { bytes = Base64.decode(compact, Base64.DEFAULT); }
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            if (decoded.contains("://") || decoded.contains("proxies:")) return decoded;
        } catch (Exception ignored) { }
        return input;
    }

    public static ParsedNode parseUri(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        try {
            if (s.regionMatches(true, 0, "vmess://", 0, 8)) return parseVmess(s);
            if (s.regionMatches(true, 0, "ss://", 0, 5)) return parseShadowsocks(s);
            URI u = URI.create(s);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("vless") || scheme.equals("trojan"))) return null;
            String host = u.getHost(); int port = u.getPort();
            if (host == null || port < 1 || port > 65535) return null;
            String name = u.getFragment() == null ? host : safeDecode(u.getFragment());
            return new ParsedNode(s, name, scheme.toUpperCase(Locale.ROOT), host, port);
        } catch (Exception ignored) { return null; }
    }

    private static ParsedNode parseVmess(String raw) {
        try {
            String payload = raw.substring(8);
            int hash = payload.indexOf('#'); if (hash >= 0) payload = payload.substring(0, hash);
            int query = payload.indexOf('?'); if (query >= 0) payload = payload.substring(0, query);
            JSONObject json = new JSONObject(new String(Base64.decode(payload, Base64.DEFAULT | Base64.URL_SAFE), StandardCharsets.UTF_8));
            String host = json.optString("add", "").trim();
            int port = Integer.parseInt(json.optString("port", "0"));
            String name = json.optString("ps", host);
            if (host.isEmpty() || port < 1 || port > 65535) return null;
            return new ParsedNode(raw, name, "VMESS", host, port);
        } catch (Exception ignored) { return null; }
    }

    private static ParsedNode parseShadowsocks(String raw) {
        try {
            String value = raw.substring(5);
            String name = "Shadowsocks";
            int hash = value.indexOf('#');
            if (hash >= 0) { name = safeDecode(value.substring(hash + 1)); value = value.substring(0, hash); }
            int query = value.indexOf('?'); if (query >= 0) value = value.substring(0, query);
            String authority = value;
            int at = authority.lastIndexOf('@');
            if (at < 0) {
                byte[] decoded = Base64.decode(padBase64(authority), Base64.DEFAULT | Base64.URL_SAFE);
                authority = new String(decoded, StandardCharsets.UTF_8);
                at = authority.lastIndexOf('@');
            }
            if (at < 0) return null;
            String endpoint = authority.substring(at + 1);
            URI endpointUri = URI.create("ss://x@" + endpoint);
            String host = endpointUri.getHost(); int port = endpointUri.getPort();
            if (host == null || port < 1 || port > 65535) return null;
            return new ParsedNode(raw, name, "SS", host, port);
        } catch (Exception ignored) { return null; }
    }

    private static String padBase64(String s) {
        String v = s.replace('-', '+').replace('_', '/');
        while (v.length() % 4 != 0) v += "=";
        return v;
    }
    private static String safeDecode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
