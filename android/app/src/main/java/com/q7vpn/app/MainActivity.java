package com.q7vpn.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight native Q7VPN screen. No remote website or WebView is required at runtime. */
public class MainActivity extends Activity {
    private static final int PICK_TXT = 77;
    private static final int VPN_PREPARE = 78;
    private static final int NODE_CHECK_PERMISSION = 79;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_NODES = 3000;
    private static final int PAGE_SIZE = 80;
    private static final String PREFS = "q7vpn";
    private static final String KEY_THEME = "q7_theme";

    private final ArrayList<Node> nodes = new ArrayList<>();
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(8);
    private final AtomicBoolean tunnelProbeRunning = new AtomicBoolean(false);
    private final Handler batchHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<Node> validationQueue = new ArrayList<>();
    private final ArrayList<Node> validatedNodes = new ArrayList<>();
    private int validationIndex;
    private int validationPassed;
    private Node validatingNode;
    private boolean batchValidation;
    private boolean ignoreBatchStopStatus;
    private final Runnable validationTimeout = () -> finishNodeValidation(false);
    private LinearLayout nodeList;
    private TextView nodeCountView;
    private TextView statusView;
    private Button connectButton;
    private Button disconnectButton;
    private Node selected;
    private String pendingVpnConfig;
    private int visibleNodeCount = PAGE_SIZE;
    private volatile boolean checkingNodes;
    private boolean receiverRegistered;
    private boolean tunnelStarted;
    private boolean lightTheme;
    private String currentStatusKey = "status_ready";
    private int[] currentStatusArgs;

    private static final class Node {
        final String uri;
        final String name;
        final String protocol;
        final String host;
        final int port;
        final String country = "未知地区";
        volatile String latency;
        volatile String portState;

        Node(String uri, String name, String protocol, String host, int port) {
            this.uri = uri;
            this.name = (name == null || name.trim().isEmpty()) ? host : name.trim();
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            this.latency = "";
            this.portState = "not_checked";
        }
    }

    private final BroadcastReceiver vpnStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String state = intent.getStringExtra(Q7VpnService.EXTRA_STATUS);

            // During batch filtering each candidate is actually started in the TUN and
            // tested with an HTTPS 204 request. A TCP handshake alone is not considered usable.
            if (batchValidation) {
                if ("starting".equals(state)) {
                    showStatus("status_validation_start");
                } else if ("core_started".equals(state)) {
                    tunnelStarted = true;
                    verifyCurrentNodeTraffic();
                } else if ("error".equals(state)) {
                    tunnelStarted = false;
                    finishNodeValidation(false);
                }
                return;
            }

            if ("starting".equals(state)) {
                showStatus("status_starting");
            } else if ("core_started".equals(state) || "tun_established".equals(state)) {
                tunnelStarted = true;
                verifyActualTunnelTraffic();
            } else if ("stopped".equals(state)) {
                tunnelStarted = false;
                tunnelProbeRunning.set(false);
                if (!ignoreBatchStopStatus) showStatus("status_stopped");
                ignoreBatchStopStatus = false;
                updateConnectionButtons();
            } else if ("error".equals(state)) {
                tunnelStarted = false;
                tunnelProbeRunning.set(false);
                showStatus("status_error");
                updateConnectionButtons();
            }
        }
    };

    private String s(String key) { return Q7Strings.t(this, key); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        lightTheme = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_THEME, false);
        loadNodes();
        buildUi();
        if (nodes.isEmpty() && "status_ready".equals(currentStatusKey)) showStatus("status_ready");
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(Q7VpnService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(vpnStatusReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(vpnStatusReceiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void loadNodes() {
        try {
            String raw = Q7SecureStore.read(this, "saved_nodes");
            String selectedUri = Q7SecureStore.read(this, "selected_uri");
            HashSet<String> seen = new HashSet<>();
            for (String line : raw.split("\\r?\\n")) {
                String value = line.trim();
                if (value.isEmpty() || !seen.add(value)) continue;
                NodeParser.ParsedNode parsed = NodeParser.parseUri(value);
                if (parsed == null || !isRunnable(parsed.uri)) continue;
                Node node = new Node(parsed.uri, parsed.name, parsed.protocol, parsed.host, parsed.port);
                nodes.add(node);
                if (value.equals(selectedUri)) selected = node;
            }
            if (selected == null && !nodes.isEmpty()) selected = nodes.get(0);
            // Persist only validated supported profiles; this also removes old Clash display-only placeholders.
            persistNodes();
        } catch (Exception error) {
            showStatus("status_storage_error");
        }
    }

    private boolean isRunnable(String uri) {
        try {
            JSONObject config = SingBoxConfigBuilder.build(uri);
            return config != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void persistNodes() {
        StringBuilder raw = new StringBuilder();
        for (Node node : nodes) raw.append(node.uri).append('\n');
        try {
            Q7SecureStore.write(this, "saved_nodes", raw.toString());
            Q7SecureStore.write(this, "selected_uri", selected == null ? "" : selected.uri);
        } catch (Exception error) {
            if (statusView != null) showStatus("status_storage_error");
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int bg() { return lightTheme ? Color.rgb(244, 247, 251) : Color.rgb(8, 12, 21); }
    private int panel() { return lightTheme ? Color.WHITE : Color.rgb(17, 25, 39); }
    private int fg() { return lightTheme ? Color.rgb(25, 33, 48) : Color.rgb(243, 247, 255); }
    private int muted() { return lightTheme ? Color.rgb(105, 117, 137) : Color.rgb(150, 164, 185); }
    private int line() { return lightTheme ? Color.rgb(222, 229, 239) : Color.rgb(52, 68, 91); }
    private int accent() { return lightTheme ? Color.rgb(42, 117, 139) : Color.rgb(111, 222, 231); }
    private int accentText() { return lightTheme ? Color.WHITE : Color.rgb(7, 20, 31); }

    private GradientDrawable shape(int color, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button actionButton(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? accentText() : fg());
        button.setBackground(shape(primary ? accent() : panel(), primary ? accent() : line(), 15));
        button.setMinHeight(dp(48));
        button.setElevation(0);
        return button;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = vertical();
        layout.setPadding(dp(17), dp(16), dp(17), dp(16));
        layout.setBackground(shape(panel(), line(), 20));
        return layout;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(bg());
        getWindow().setNavigationBarColor(bg());
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(lightTheme
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : 0);
        }

        LinearLayout page = vertical();
        page.setPadding(dp(18), dp(18), dp(18), dp(22));
        page.setBackgroundColor(bg());

        LinearLayout top = horizontal();
        LinearLayout brandBlock = vertical();
        TextView brand = label("Q7VPN", 27, fg(), true);
        brand.setLetterSpacing(0.02f);
        brandBlock.addView(brand);
        TextView subtitle = label(s("brand_subtitle"), 9, muted(), true);
        subtitle.setLetterSpacing(0.18f);
        subtitle.setPadding(0, dp(2), 0, 0);
        brandBlock.addView(subtitle);
        top.addView(brandBlock, new LinearLayout.LayoutParams(0, -2, 1));
        Button language = actionButton(s("language"), false);
        language.setTextSize(12);
        language.setMinHeight(dp(38));
        language.setOnClickListener(v -> {
            Q7Strings.setEnglish(this, !Q7Strings.isEnglish(this));
            buildUi();
        });
        top.addView(language, new LinearLayout.LayoutParams(-2, dp(40)));
        Button theme = actionButton(lightTheme ? "◐" : "◑", false);
        theme.setTextSize(17);
        theme.setMinHeight(dp(38));
        LinearLayout.LayoutParams themeParams = new LinearLayout.LayoutParams(dp(44), dp(40));
        themeParams.setMargins(dp(7), 0, 0, 0);
        theme.setOnClickListener(v -> {
            lightTheme = !lightTheme;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_THEME, lightTheme).apply();
            buildUi();
        });
        top.addView(theme, themeParams);
        page.addView(top);
        addSpace(page, 22);

        LinearLayout hero = card();
        hero.setBackground(shape(lightTheme ? Color.rgb(234, 243, 248) : Color.rgb(17, 31, 48), accent(), 24));
        TextView eyebrow = label(s("eyebrow"), 10, accent(), true);
        eyebrow.setLetterSpacing(0.16f);
        hero.addView(eyebrow);
        TextView headline = label(s("hero_title"), 22, fg(), true);
        headline.setPadding(0, dp(10), 0, dp(18));
        hero.addView(headline);
        hero.addView(label(s("selected_node"), 11, muted(), true));
        TextView selectedName = label(selected == null ? s("no_selected_node") : selected.name,
                17, fg(), true);
        selectedName.setPadding(0, dp(5), 0, dp(6));
        hero.addView(selectedName);
        TextView selectedMeta = label(selected == null ? "—" : selected.protocol + " · " + selected.host + ":" + selected.port,
                11, muted(), false);
        hero.addView(selectedMeta);
        addSpace(hero, 16);
        connectButton = actionButton(s("connect"), true);
        connectButton.setTextSize(16);
        connectButton.setMinHeight(dp(54));
        connectButton.setOnClickListener(v -> startSelectedVpn());
        hero.addView(connectButton, new LinearLayout.LayoutParams(-1, dp(54)));
        disconnectButton = actionButton(s("disconnect"), false);
        disconnectButton.setOnClickListener(v -> stopSelectedVpn());
        LinearLayout.LayoutParams disconnectParams = new LinearLayout.LayoutParams(-1, dp(44));
        disconnectParams.setMargins(0, dp(8), 0, 0);
        hero.addView(disconnectButton, disconnectParams);
        page.addView(hero);
        addSpace(page, 12);

        statusView = label(s(currentStatusKey), 12, muted(), false);
        statusView.setPadding(dp(14), dp(13), dp(14), dp(13));
        statusView.setBackground(shape(panel(), line(), 14));
        page.addView(statusView, new LinearLayout.LayoutParams(-1, -2));
        refreshStatusText();
        addSpace(page, 17);

        LinearLayout actions = horizontal();
        Button importButton = actionButton(s("import_txt"), true);
        importButton.setOnClickListener(v -> pickTxt());
        Button checkButton = actionButton(s("check_nodes"), false);
        checkButton.setOnClickListener(v -> testAll());
        actions.addView(importButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        checkParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(checkButton, checkParams);
        page.addView(actions);
        addSpace(page, 21);

        LinearLayout heading = horizontal();
        TextView nodesTitle = label(s("node_list"), 19, fg(), true);
        heading.addView(nodesTitle, new LinearLayout.LayoutParams(0, -2, 1));
        nodeCountView = label(nodeSummary(), 11, muted(), true);
        heading.addView(nodeCountView);
        page.addView(heading);
        addSpace(page, 9);

        nodeList = vertical();
        page.addView(nodeList);
        renderNodeList();
        addSpace(page, 20);
        TextView storageNote = label(s("storage_note"), 11, muted(), false);
        page.addView(storageNote);
        TextView privacyNote = label(s("privacy_note"), 11, muted(), false);
        privacyNote.setPadding(0, dp(5), 0, 0);
        page.addView(privacyNote);
        TextView precheckNote = label(s("precheck_note"), 11, muted(), false);
        precheckNote.setPadding(0, dp(5), 0, 0);
        page.addView(precheckNote);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(page);
        setContentView(scroll);
        updateConnectionButtons();
    }

    private void addSpace(LinearLayout parent, int dpValue) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(dpValue)));
    }

    private String nodeSummary() {
        int reachable = 0;
        for (Node node : nodes) if ("usable".equals(node.portState)) reachable++;
        return String.format(Locale.ROOT, s("nodes_summary"), nodes.size(), reachable);
    }

    private void renderNodeList() {
        if (nodeList == null) return;
        nodeList.removeAllViews();
        if (nodeCountView != null) nodeCountView.setText(nodeSummary());
        if (nodes.isEmpty()) {
            TextView empty = label(s("empty_nodes"), 12, muted(), false);
            empty.setPadding(dp(15), dp(20), dp(15), dp(20));
            empty.setBackground(shape(panel(), line(), 16));
            nodeList.addView(empty);
            return;
        }

        int shown = Math.min(nodes.size(), visibleNodeCount);
        for (int i = 0; i < shown; i++) {
            final Node node = nodes.get(i);
            LinearLayout item = card();
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, -2);
            itemParams.setMargins(0, 0, 0, dp(9));
            if (selected == node) item.setBackground(shape(panel(), accent(), 18));

            LinearLayout titleRow = horizontal();
            TextView name = label(node.name, 15, fg(), true);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView status = label(portStateText(node), 10,
                    "usable".equals(node.portState) ? Color.rgb(55, 177, 139)
                            : ("unusable".equals(node.portState) ? Color.rgb(224, 101, 116) : muted()), true);
            titleRow.addView(status);
            item.addView(titleRow);

            TextView meta = label(node.protocol + "  ·  " + node.host + ":" + node.port + "  ·  " + s("unknown_region"),
                    11, muted(), false);
            meta.setPadding(0, dp(7), 0, dp(11));
            item.addView(meta);

            LinearLayout row = horizontal();
            Button select = actionButton(selected == node ? s("selected") : s("select"), selected == node);
            select.setTextSize(11);
            select.setMinHeight(dp(40));
            select.setOnClickListener(v -> {
                selected = node;
                persistNodes();
                buildUi();
            });
            Button connect = actionButton(s("connect"), false);
            connect.setTextSize(11);
            connect.setMinHeight(dp(40));
            connect.setOnClickListener(v -> {
                selected = node;
                persistNodes();
                buildUi();
                startSelectedVpn();
            });
            Button remove = actionButton(s("remove"), false);
            remove.setTextSize(11);
            remove.setMinHeight(dp(40));
            remove.setOnClickListener(v -> removeNode(node));
            row.addView(select, new LinearLayout.LayoutParams(0, dp(40), 1));
            LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(0, dp(40), 1);
            connectParams.setMargins(dp(6), 0, 0, 0);
            row.addView(connect, connectParams);
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(40), 1);
            removeParams.setMargins(dp(6), 0, 0, 0);
            row.addView(remove, removeParams);
            item.addView(row);
            nodeList.addView(item, itemParams);
        }

        if (shown < nodes.size()) {
            Button more = actionButton(s("more") + "  (" + shown + "/" + nodes.size() + ")", false);
            more.setOnClickListener(v -> {
                visibleNodeCount = Math.min(nodes.size(), visibleNodeCount + PAGE_SIZE);
                renderNodeList();
            });
            nodeList.addView(more);
        }
    }

    private String portStateText(Node node) {
        if ("usable".equals(node.portState)) return s("node_usable") + " · " + node.latency;
        if ("unusable".equals(node.portState)) return s("node_unusable");
        return s("not_checked");
    }

    private void removeNode(Node node) {
        nodes.remove(node);
        if (selected == node) selected = nodes.isEmpty() ? null : nodes.get(0);
        persistNodes();
        renderNodeList();
        buildUi();
    }

    private void showStatus(String key) {
        currentStatusKey = key;
        currentStatusArgs = null;
        refreshStatusText();
    }

    private void showImportStatus(int added, int skipped) {
        currentStatusKey = "status_imported";
        currentStatusArgs = new int[]{added, skipped};
        refreshStatusText();
    }

    private void refreshStatusText() {
        if (statusView == null) return;
        String value = s(currentStatusKey);
        if (currentStatusArgs != null && ("status_imported".equals(currentStatusKey)
                || "status_validation_done".equals(currentStatusKey))) {
            value = String.format(Locale.ROOT, value, currentStatusArgs[0], currentStatusArgs[1]);
        } else if (currentStatusArgs != null && "status_validation_progress".equals(currentStatusKey)) {
            value = String.format(Locale.ROOT, value, currentStatusArgs[0], currentStatusArgs[1]);
        }
        statusView.setText(value);
    }

    private void updateConnectionButtons() {
        if (connectButton != null) connectButton.setEnabled(selected != null && !tunnelStarted);
        if (disconnectButton != null) disconnectButton.setEnabled(tunnelStarted);
    }

    private void pickTxt() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        startActivityForResult(intent, PICK_TXT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == NODE_CHECK_PERMISSION) {
            if (resultCode == RESULT_OK) beginBatchNodeValidation();
            else showStatus("status_permission");
            return;
        }
        if (requestCode == VPN_PREPARE) {
            if (resultCode == RESULT_OK && pendingVpnConfig != null) {
                launchVpn(pendingVpnConfig);
            } else {
                showStatus("status_permission");
            }
            pendingVpnConfig = null;
            return;
        }
        if (requestCode != PICK_TXT || resultCode != RESULT_OK || data == null || data.getData() == null) return;

        try {
            Uri uri = data.getData();
            String content;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("empty stream");
                content = readTextLimited(input, MAX_FILE_BYTES);
            }

            List<NodeParser.ParsedNode> parsed = NodeParser.parseText(content);
            HashSet<String> seen = new HashSet<>();
            for (Node node : nodes) seen.add(node.uri);
            int added = 0;
            int skipped = 0;
            for (NodeParser.ParsedNode item : parsed) {
                if (nodes.size() >= MAX_NODES) break;
                if (!seen.add(item.uri)) continue;
                // Reject unsupported formats here; Clash YAML display-only placeholders cannot enter the VPN list.
                if (!isRunnable(item.uri)) {
                    skipped++;
                    continue;
                }
                nodes.add(new Node(item.uri, item.name, item.protocol, item.host, item.port));
                added++;
            }
            if (selected == null && !nodes.isEmpty()) selected = nodes.get(0);
            visibleNodeCount = PAGE_SIZE;
            persistNodes();
            buildUi();
            if (added == 0) {
                showStatus("status_no_valid_nodes");
            } else {
                showImportStatus(added, skipped);
                testAll();
            }
        } catch (FileTooLargeException tooLarge) {
            showStatus("status_file_too_large");
        } catch (Exception error) {
            showStatus("status_import_error");
        }
    }

    private String readTextLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new FileTooLargeException();
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class FileTooLargeException extends Exception { }

    /** Validate every candidate through the real sing-box tunnel, then remove failures. */
    private void testAll() {
        if (checkingNodes || batchValidation) {
            showStatus("status_busy");
            return;
        }
        if (nodes.isEmpty()) return;
        if (tunnelStarted) {
            showStatus("status_disconnect_before_check");
            return;
        }
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, NODE_CHECK_PERMISSION);
        } else {
            beginBatchNodeValidation();
        }
    }

    private void beginBatchNodeValidation() {
        if (nodes.isEmpty()) return;
        validationQueue.clear();
        validationQueue.addAll(nodes);
        validatedNodes.clear();
        validationIndex = 0;
        validationPassed = 0;
        validatingNode = null;
        batchValidation = true;
        checkingNodes = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showStatus("status_preflight");

        // Confirm the phone itself has working internet before testing candidates.
        // Without this guard, an offline phone could incorrectly delete every saved node.
        probeExecutor.execute(() -> {
            boolean internet = probeHttps("https://cp.cloudflare.com/generate_204")
                    || probeHttps("https://www.gstatic.com/generate_204")
                    || probeHttps("https://www.google.com/generate_204");
            runOnUiThread(() -> {
                if (!batchValidation) return;
                if (!internet) {
                    abortBatchWithoutDeleting("status_no_internet");
                } else {
                    showStatus("status_validation_start");
                    startNextNodeValidation();
                }
            });
        });
    }

    private void abortBatchWithoutDeleting(String statusKey) {
        batchValidation = false;
        checkingNodes = false;
        tunnelProbeRunning.set(false);
        batchHandler.removeCallbacks(validationTimeout);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        validationQueue.clear();
        validatedNodes.clear();
        validatingNode = null;
        // Keep all imported entries untouched when the test environment is unavailable.
        showStatus(statusKey);
        renderNodeList();
        updateConnectionButtons();
    }

    private void stopValidationTunnel() {
        try {
            Intent stop = new Intent(this, Q7VpnService.class);
            stop.setAction(Q7VpnService.ACTION_STOP);
            startService(stop);
        } catch (Exception ignored) { }
    }

    private void startNextNodeValidation() {
        if (!batchValidation) return;
        batchHandler.removeCallbacks(validationTimeout);
        tunnelProbeRunning.set(false);
        tunnelStarted = false;

        if (validationIndex >= validationQueue.size()) {
            finishBatchNodeValidation();
            return;
        }

        validatingNode = validationQueue.get(validationIndex++);
        currentStatusKey = "status_validation_progress";
        currentStatusArgs = new int[]{validationIndex, validationQueue.size()};
        refreshStatusText();

        try {
            String config = SingBoxConfigBuilder.build(validatingNode.uri).toString();
            Intent intent = new Intent(this, Q7VpnService.class);
            intent.setAction(Q7VpnService.ACTION_START);
            intent.putExtra(Q7VpnService.EXTRA_CONFIG, config);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            // A broken node can fail before the service emits its error callback.
            batchHandler.postDelayed(validationTimeout, 30000L);
        } catch (Exception error) {
            finishNodeValidation(false);
        }
    }

    private void verifyCurrentNodeTraffic() {
        if (!batchValidation || validatingNode == null || !tunnelProbeRunning.compareAndSet(false, true)) return;
        batchHandler.removeCallbacks(validationTimeout);
        probeExecutor.execute(() -> {
            long start = System.nanoTime();
            boolean passed = probeHttps("https://cp.cloudflare.com/generate_204")
                    || probeHttps("https://www.gstatic.com/generate_204")
                    || probeHttps("https://www.google.com/generate_204");
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            runOnUiThread(() -> {
                tunnelProbeRunning.set(false);
                if (!batchValidation) return;
                if (passed && validatingNode != null) validatingNode.latency = elapsed + " ms";
                finishNodeValidation(passed);
            });
        });
    }

    private void finishNodeValidation(boolean passed) {
        if (!batchValidation || validatingNode == null) return;
        batchHandler.removeCallbacks(validationTimeout);
        tunnelProbeRunning.set(false);
        Node completed = validatingNode;
        validatingNode = null;
        if (passed) {
            completed.portState = "usable";
            validatedNodes.add(completed);
            validationPassed++;
        } else {
            completed.portState = "unusable";
        }
        // Tear down this candidate's TUN/core before the next one starts.
        stopValidationTunnel();
        batchHandler.postDelayed(this::startNextNodeValidation, 900L);
        renderNodeList();
    }

    private void finishBatchNodeValidation() {
        batchValidation = false;
        checkingNodes = false;
        tunnelProbeRunning.set(false);
        batchHandler.removeCallbacks(validationTimeout);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        int removed = 0;
        boolean anyVerified = !validatedNodes.isEmpty();
        if (anyVerified) {
            removed = Math.max(0, nodes.size() - validatedNodes.size());
            nodes.clear();
            nodes.addAll(validatedNodes);
            if (selected == null || !nodes.contains(selected)) selected = nodes.isEmpty() ? null : nodes.get(0);
            visibleNodeCount = PAGE_SIZE;
            persistNodes();
        } else {
            // Zero passes can indicate a blocked probe endpoint or runtime issue. Do not
            // convert an inconclusive run into destructive deletion of the whole library.
            for (Node node : nodes) {
                node.portState = "not_checked";
                node.latency = "";
            }
        }

        ignoreBatchStopStatus = true;
        stopValidationTunnel();
        batchHandler.postDelayed(() -> ignoreBatchStopStatus = false, 3000L);

        buildUi();
        if (anyVerified) {
            currentStatusKey = "status_validation_done";
            currentStatusArgs = new int[]{validationPassed, removed};
        } else {
            currentStatusKey = "status_validation_zero";
            currentStatusArgs = null;
        }
        refreshStatusText();
        updateConnectionButtons();
        validationQueue.clear();
        validatedNodes.clear();
    }

    private void startSelectedVpn() {
        if (selected == null) {
            showStatus("status_select_first");
            return;
        }
        try {
            pendingVpnConfig = SingBoxConfigBuilder.build(selected.uri).toString();
            Intent consent = VpnService.prepare(this);
            if (consent != null) startActivityForResult(consent, VPN_PREPARE);
            else launchVpn(pendingVpnConfig);
        } catch (Exception error) {
            showStatus("status_config_error");
        }
    }

    private void launchVpn(String config) {
        tunnelProbeRunning.set(false);
        tunnelStarted = false;
        Intent intent = new Intent(this, Q7VpnService.class);
        intent.setAction(Q7VpnService.ACTION_START);
        intent.putExtra(Q7VpnService.EXTRA_CONFIG, config);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        showStatus("status_starting");
        updateConnectionButtons();
    }

    private void stopSelectedVpn() {
        Intent intent = new Intent(this, Q7VpnService.class);
        intent.setAction(Q7VpnService.ACTION_STOP);
        startService(intent);
        showStatus("status_disconnecting");
    }

    private void verifyActualTunnelTraffic() {
        if (!tunnelProbeRunning.compareAndSet(false, true)) return;
        showStatus("status_probe");
        probeExecutor.execute(() -> {
            boolean passed = probeHttps("https://cp.cloudflare.com/generate_204")
                    || probeHttps("https://www.google.com/generate_204");
            runOnUiThread(() -> {
                tunnelProbeRunning.set(false);
                if (passed && tunnelStarted) {
                    showStatus("status_connected");
                } else if (tunnelStarted) {
                    showStatus("status_tunnel_unverified");
                }
                updateConnectionButtons();
            });
        });
    }

    /** Requests a 204 endpoint through the Android default route, which is the active VPN TUN. */
    private boolean probeHttps(String endpoint) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(6500);
            connection.setReadTimeout(6500);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Pragma", "no-cache");
            return connection.getResponseCode() == 204;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override protected void onDestroy() {
        if (batchValidation) {
            batchValidation = false;
            batchHandler.removeCallbacks(validationTimeout);
            Intent stop = new Intent(this, Q7VpnService.class);
            stop.setAction(Q7VpnService.ACTION_STOP);
            try { startService(stop); } catch (Exception ignored) { }
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        persistNodes();
        probeExecutor.shutdownNow();
        super.onDestroy();
    }
}
