package com.q7vpn.app;

import android.app.Activity;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int PICK_TXT = 77;
    private static final int VPN_PREPARE = 78;
    private static final String PREFS = "q7vpn";
    private static final String KEY_NODES = "saved_nodes";
    private static final String KEY_SELECTED = "selected_uri";
    private static final int MAX_PROBE_THREADS = 16;
    private static final int NODE_PAGE_SIZE = 80;

    private String pendingVpnConfig;
    private final ArrayList<Node> nodes = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private LinearLayout list;
    private TextView summary;
    private TextView status;
    private Node selected;
    private int visibleNodeCount = NODE_PAGE_SIZE;
    private volatile boolean checking = false;
    private boolean vpnReceiverRegistered = false;
    private final BroadcastReceiver vpnStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || status == null) return;
            String message = intent.getStringExtra(Q7VpnService.EXTRA_MESSAGE);
            if (message != null && !message.isEmpty()) status.setText(message);
        }
    };

    static class Node {
        String uri, name, protocol, host, country = "未知地区";
        int port;
        String latency = "未检测";
        Node(String u, String n, String p, String h, int po) {
            uri = u; name = n; protocol = p; host = h; port = po;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        loadNodes();
        buildUi();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(Q7VpnService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vpnStatusReceiver, filter);
        }
        vpnReceiverRegistered = true;
    }

    @Override protected void onStop() {
        if (vpnReceiverRegistered) {
            try { unregisterReceiver(vpnStatusReceiver); } catch (Exception ignored) { }
            vpnReceiverRegistered = false;
        }
        super.onStop();
    }

    private void loadNodes() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_NODES, "");
        String selectedUri = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SELECTED, "");
        for (String line : raw.split("\n")) {
            if (line.trim().isEmpty()) continue;
            java.util.List<NodeParser.ParsedNode> parsed = NodeParser.parseText(line);
            if (parsed.isEmpty()) {
                Node legacy = parse(line);
                if (legacy != null) nodes.add(legacy);
                continue;
            }
            for (NodeParser.ParsedNode item : parsed) {
                Node n = new Node(item.uri, item.name, item.protocol, item.host, item.port);
                nodes.add(n);
                if (!selectedUri.isEmpty() && selectedUri.equals(n.uri)) selected = n;
            }
        }
    }

    private void persistNodes() {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) sb.append(n.uri).append('\n');
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_NODES, sb.toString())
                .putString(KEY_SELECTED, selected == null ? "" : selected.uri)
                .apply();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private void styleButton(Button b, boolean primary) {
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(primary ? Color.rgb(8,18,31) : Color.rgb(224,238,250));
        b.setBackground(rounded(primary ? Color.rgb(133,231,239) : Color.rgb(29,39,59),
                primary ? Color.rgb(172,248,255) : Color.rgb(81,143,173), 14));
        b.setMinHeight(dp(46));
        b.setElevation(0);
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        int fg = Color.rgb(247,247,251);
        int muted = Color.rgb(148,153,173);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(18));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(7,13,25), Color.rgb(13,18,36), Color.rgb(22,19,43)}));

        TextView title = text("Q7VPN", 32, fg);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = text("节点 · 诊断 · 安全连接", 14, muted);
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button importBtn = new Button(this);
        importBtn.setText("导入配置");
        styleButton(importBtn, true);
        importBtn.setOnClickListener(v -> pickTxt());
        Button testBtn = new Button(this);
        testBtn.setText("筛选节点");
        styleButton(testBtn, false);
        testBtn.setOnClickListener(v -> testAll());
        actions.addView(importBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(testBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions);

        LinearLayout vpnActions = new LinearLayout(this);
        vpnActions.setOrientation(LinearLayout.HORIZONTAL);
        Button connectBtn = new Button(this);
        connectBtn.setText("连接所选节点");
        styleButton(connectBtn, true);
        connectBtn.setOnClickListener(v -> startSelectedVpn());
        Button disconnectBtn = new Button(this);
        disconnectBtn.setText("断开 VPN");
        styleButton(disconnectBtn, false);
        disconnectBtn.setOnClickListener(v -> stopSelectedVpn());
        vpnActions.addView(connectBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        vpnActions.addView(disconnectBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(vpnActions);

        summary = text("0 个节点 · 尚未检测", 15, muted);
        summary.setPadding(0, dp(12), 0, dp(8));
        root.addView(summary);

        status = text("导入节点后，选择节点并点击“连接所选节点”启动内置 sing-box VPN。", 13, muted);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(rounded(Color.rgb(25,32,49), Color.rgb(58,91,120), 16));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView foot = text("内置 sing-box 1.14.0；是否可用以连接日志和实际网络测试为准。", 12, muted);
        foot.setPadding(0, dp(12), 0, 0);
        root.addView(foot);

        setContentView(root);
        redraw();
    }

    private void pickTxt() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_TXT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PREPARE) {
            if (resultCode == RESULT_OK && pendingVpnConfig != null) {
                launchVpn(pendingVpnConfig);
            } else if (status != null) {
                status.setText("你没有允许 Q7VPN 使用 VPN，连接没有开始。");
            }
            pendingVpnConfig = null;
            return;
        }
        if (requestCode != PICK_TXT || resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri file = data.getData();
        int added = 0, duplicates = 0, invalid;
        HashSet<String> seenUris = new HashSet<>();
        for (Node existing : nodes) seenUris.add(existing.uri);
        try (InputStream in = getContentResolver().openInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("无法打开文件");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            String content = new String(out.toByteArray(), StandardCharsets.UTF_8);
            java.util.List<NodeParser.ParsedNode> parsed = NodeParser.parseText(content);
            for (NodeParser.ParsedNode item : parsed) {
                if (nodes.size() >= 3000) break;
                if (!seenUris.add(item.uri)) { duplicates++; continue; }
                nodes.add(new Node(item.uri, item.name, item.protocol, item.host, item.port));
                added++;
            }
            int sourceLines = content.split("\r?\n").length;
            invalid = Math.max(0, sourceLines - parsed.size());
            persistNodes();
            visibleNodeCount = NODE_PAGE_SIZE;
            summary.setText(nodes.size() + " 个节点 · 新增 " + added + " · 重复 " + duplicates + " · 未识别 " + invalid);
            status.setText("导入完成。当前可连接的分享链接格式：VLESS、VMess、Trojan、Shadowsocks。基础 Clash YAML 仅识别展示，不能直接连接。");
            redraw();
            if (added > 0) testAll();
        } catch (Exception e) {
            Toast.makeText(this, "读取文件失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Node parse(String raw) {
        try {
            if (raw.startsWith("vless://")) {
                URI u = URI.create(raw);
                if (u.getHost() == null) return null;
                String name = u.getFragment() == null ? u.getHost() : URLDecoder.decode(u.getFragment(), "UTF-8");
                int p = u.getPort() > 0 ? u.getPort() : 443;
                return new Node(raw, name, "VLESS", u.getHost(), p);
            }
            if (raw.startsWith("ss://")) {
                String body = raw.substring(5).split("#", 2)[0];
                String name = raw.contains("#") ? URLDecoder.decode(raw.substring(raw.indexOf('#') + 1), "UTF-8") : "Shadowsocks 节点";
                int at = body.lastIndexOf('@');
                if (at < 0) return new Node(raw, name, "SS", "", 0);
                String auth = body.substring(at + 1);
                int colon = auth.lastIndexOf(':');
                if (colon < 1) return null;
                String host = auth.substring(0, colon);
                int port = Integer.parseInt(auth.substring(colon + 1).replaceAll("[/?].*$", ""));
                return new Node(raw, name, "SS", host, port);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void testAll() {
        if (checking) {
            Toast.makeText(this, "正在筛选，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (nodes.isEmpty()) {
            Toast.makeText(this, "请先导入 TXT", Toast.LENGTH_SHORT).show();
            return;
        }
        checking = true;
        status.setText("正在后台筛选端口不可达的节点…不会阻塞界面。端口可达不等于协议握手成功。");
        final ArrayList<Node> snapshot = new ArrayList<>(nodes);
        final int pool = Math.max(2, Math.min(snapshot.size(), MAX_PROBE_THREADS));
        final ExecutorService workers = Executors.newFixedThreadPool(pool);
        final CountDownLatch done = new CountDownLatch(snapshot.size());
        for (Node n : snapshot) {
            workers.execute(() -> {
                try {
                    if (n.host == null || n.host.isEmpty() || n.port < 1 || n.port > 65535) {
                        n.latency = "无法检测";
                    } else {
                        long start = System.nanoTime();
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(n.host, n.port), 1800);
                            n.latency = ((System.nanoTime() - start) / 1_000_000L) + " ms";
                        } catch (Exception ex) {
                            n.latency = "不可达";
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        new Thread(() -> {
            try { done.await(8, TimeUnit.MINUTES); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            workers.shutdownNow();
            runOnUiThread(() -> {
                persistNodes();
                long reachable = nodes.stream().filter(n -> !"不可达".equals(n.latency) && !"无法检测".equals(n.latency)).count();
                summary.setText(nodes.size() + " 个节点保留 · 端口可达 " + reachable + " 个（未删除节点）");
                status.setText("端口检测完成。端口可达不代表协议一定可用；未删除任何节点，请连接后测试实际网络。");
                checking = false;
                redraw();
            });
        }, "q7vpn-check-complete").start();
    }

    private void redraw() {
        if (list == null) return;
        list.removeAllViews();
        int shown = Math.min(nodes.size(), visibleNodeCount);
        for (int i = 0; i < shown; i++) {
            Node n = nodes.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.setMargins(0, dp(8), 0, dp(4));
            card.setBackground(rounded(Color.rgb(22,29,45), Color.rgb(54,85,113), 18));
            list.addView(card, cp);

            TextView name = text(n.name, 17, Color.WHITE);
            name.setTypeface(null, 1);
            card.addView(name);
            TextView meta = text(n.protocol + "  ·  " + n.host + (n.port > 0 ? ":" + n.port : "") + "  ·  " + n.country + "  ·  " + n.latency,
                    12, Color.rgb(159,164,185));
            meta.setPadding(0, dp(6), 0, dp(8));
            card.addView(meta);

            LinearLayout row = new LinearLayout(this);
            Button select = new Button(this);
            select.setText(selected == n ? "已选择" : "选择");
            styleButton(select, selected == n);
            select.setOnClickListener(v -> {
                selected = n;
                persistNodes();
                status.setText("已选择：" + n.name + "。点击页面上方“连接所选节点”启动内置 VPN。");
                redraw();
            });
            Button connect = new Button(this);
            connect.setText("连接此节点");
            styleButton(connect, false);
            connect.setOnClickListener(v -> {
                selected = n;
                persistNodes();
                redraw();
                startSelectedVpn();
            });
            row.addView(select, new LinearLayout.LayoutParams(0, dp(48), 1));
            row.addView(connect, new LinearLayout.LayoutParams(0, dp(48), 1));
            card.addView(row);
        }
        if (shown < nodes.size()) {
            Button more = new Button(this);
            more.setText("显示更多节点（" + shown + "/" + nodes.size() + "）");
            styleButton(more, false);
            more.setOnClickListener(v -> {
                visibleNodeCount = Math.min(nodes.size(), visibleNodeCount + NODE_PAGE_SIZE);
                redraw();
            });
            list.addView(more);
        }
    }

    private void startSelectedVpn() {
        if (selected == null) {
            Toast.makeText(this, "请先选择一个节点", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            // Builder returns the complete config: proxy outbound, DNS, TUN and route.
            pendingVpnConfig = SingBoxConfigBuilder.build(selected.uri).toString();
            Intent consent = VpnService.prepare(this);
            if (consent != null) startActivityForResult(consent, VPN_PREPARE);
            else launchVpn(pendingVpnConfig);
        } catch (Exception e) {
            Toast.makeText(this, "节点配置无法生成：" + e.getMessage(), Toast.LENGTH_LONG).show();
            if (status != null) status.setText("节点配置无法生成：" + e.getMessage());
        }
    }

    private void launchVpn(String config) {
        Intent intent = new Intent(this, Q7VpnService.class);
        intent.setAction(Q7VpnService.ACTION_START);
        intent.putExtra(Q7VpnService.EXTRA_CONFIG, config);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        status.setText("已请求启动 VPN，正在创建核心和系统隧道…");
    }

    private void stopSelectedVpn() {
        Intent intent = new Intent(this, Q7VpnService.class);
        intent.setAction(Q7VpnService.ACTION_STOP);
        startService(intent);
        status.setText("已发送断开 VPN 命令。");
    }

    @Override protected void onDestroy() {
        persistNodes();
        executor.shutdownNow();
        super.onDestroy();
    }
}
