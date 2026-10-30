package com.q7vpn.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Centralized native-app strings. Language choice is stored locally and applied on rebuild. */
public final class Q7Strings {
    private static final String PREFS = "q7vpn";
    private static final String KEY_LANGUAGE = "q7_language";

    private Q7Strings() { }

    public static boolean isEnglish(Context context) {
        return "en".equals(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, "zh"));
    }

    public static void setEnglish(Context context, boolean english) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, english ? "en" : "zh").apply();
    }

    public static String t(Context context, String key) {
        boolean en = isEnglish(context);
        switch (key) {
            case "brand_subtitle": return en ? "PRIVATE NETWORK" : "专属安全网络";
            case "language": return en ? "中文" : "English";
            case "account_center": return en ? "Account" : "账户";
            case "portal_error_title": return en ? "Account service is unavailable" : "账户服务暂时无法连接";
            case "portal_error_detail": return en ? "Check your connection, then retry. Your account portal and registration data have not been changed." : "请检查网络后重试。账户登录和注册数据没有被修改。";
            case "portal_retry": return en ? "Retry" : "重新连接";
            case "theme_dark": return en ? "Dark" : "深色";
            case "theme_light": return en ? "Light" : "浅色";
            case "eyebrow": return en ? "PRIVATE NETWORK" : "专属网络";
            case "hero_title": return en ? "Your network. Your control." : "你的网络，由你掌控。";
            case "selected_node": return en ? "Selected node" : "当前节点";
            case "no_selected_node": return en ? "No node selected" : "尚未选择节点";
            case "connect": return en ? "Connect" : "连接";
            case "connecting": return en ? "Connecting…" : "正在连接…";
            case "disconnect": return en ? "Disconnect" : "断开连接";
            case "import_txt": return en ? "Import TXT" : "导入 TXT";
            case "check_nodes": return en ? "Check nodes" : "检测节点";
            case "node_list": return en ? "Your nodes" : "我的节点";
            case "empty_nodes": return en ? "No nodes yet. Import a TXT subscription or share-link file." : "还没有节点。请导入 TXT 节点文件。";
            case "not_checked": return en ? "Not checked" : "尚未检测";
            case "port_reachable": return en ? "Port reachable" : "端口可达";
            case "port_unreachable": return en ? "Port unreachable" : "端口暂不可达";
            case "unknown_region": return en ? "Unknown region" : "未知地区";
            case "node_usable": return en ? "Proxy traffic verified" : "代理流量验证通过";
            case "node_unusable": return en ? "Proxy traffic test failed" : "代理流量验证失败";
            case "selected": return en ? "Selected" : "已选择";
            case "select": return en ? "Select" : "选择";
            case "remove": return en ? "Remove" : "移除";
            case "more": return en ? "Show more" : "显示更多";
            case "nodes_summary": return en ? "%1$d nodes · %2$d verified usable" : "%1$d 个节点 · %2$d 个已验证可用";
            case "status_ready": return en ? "Ready. Import a node to get started." : "已就绪。导入节点后即可开始。";
            case "status_imported": return en ? "Imported %1$d nodes; skipped %2$d unsupported or invalid entries." : "新增 %1$d 个节点；跳过 %2$d 个不支持或无效条目。";
            case "status_no_valid_nodes": return en ? "No supported nodes found in this file. Existing nodes were kept." : "文件中没有可用的受支持节点；原有节点已保留。";
            case "status_checking": return en ? "Checking proxy traffic through the VPN tunnel…" : "正在通过 VPN 隧道检测真实代理流量…";
            case "status_check_done": return en ? "Proxy traffic checks completed." : "代理流量检测已完成。";
            case "status_preflight": return en ? "Checking this phone's internet before filtering nodes…" : "正在先检查手机自身网络，避免误删节点…";
            case "status_no_internet": return en ? "This phone has no verified internet connection. All saved nodes were kept unchanged." : "手机当前无法验证互联网连接，所有节点均已保留，没有删除。";
            case "status_validation_zero": return en ? "No node passed this run. All candidates were kept because the result may be inconclusive; check network access and try again." : "本次没有节点通过检测。由于结果可能受网络或检测环境影响，已保留全部节点，请检查网络后重试。";
            case "status_validation_start": return en ? "Preparing a real VPN-tunnel test for each node…" : "正在准备逐个节点进行真实 VPN 隧道检测…";
            case "status_validation_progress": return en ? "Testing node %1$d of %2$d through sing-box…" : "正在通过 sing-box 检测第 %1$d / %2$d 个节点…";
            case "status_validation_done": return en ? "Validation complete: %1$d usable, %2$d removed. Only nodes that passed an HTTPS request through the VPN were kept." : "检测完成：保留 %1$d 个可用节点，自动删除 %2$d 个未通过 VPN 实际 HTTPS 测试的节点。";
            case "status_disconnect_before_check": return en ? "Disconnect the current VPN before running the full node test." : "请先断开当前 VPN，再执行完整节点筛选。";
            case "status_starting": return en ? "Starting the VPN core and system tunnel…" : "正在启动 VPN 核心并建立系统隧道…";
            case "status_probe": return en ? "Tunnel started. Verifying real HTTPS traffic…" : "隧道已启动，正在验证真实 HTTPS 流量…";
            case "status_connected": return en ? "Connected · HTTPS traffic test passed" : "已连接 · HTTPS 实际流量测试通过";
            case "status_tunnel_unverified": return en ? "Tunnel is running, but internet traffic could not be verified. Try another node." : "隧道已运行，但未能验证外网流量。建议尝试其他节点。";
            case "status_stopped": return en ? "VPN disconnected." : "VPN 已断开。";
            case "status_disconnecting": return en ? "Disconnecting VPN…" : "正在断开 VPN…";
            case "status_error": return en ? "VPN failed to start. Check the node format and try another node." : "VPN 启动失败。请检查节点格式或尝试其他节点。";
            case "status_permission": return en ? "VPN permission was not granted. No connection was started." : "未获得系统 VPN 授权，连接没有开始。";
            case "status_select_first": return en ? "Select a node first." : "请先选择一个节点。";
            case "status_busy": return en ? "A node check is already running." : "节点检测正在进行，请稍候。";
            case "status_import_error": return en ? "Could not read this file. Choose a plain-text TXT file." : "读取文件失败，请选择普通文本 TXT 文件。";
            case "status_file_too_large": return en ? "File is too large. The limit is 2 MB." : "文件过大，当前限制为 2 MB。";
            case "status_storage_error": return en ? "Secure local storage is unavailable. Your saved data was not overwritten." : "本机安全存储暂不可用，原有数据没有被覆盖。";
            case "status_config_error": return en ? "This node format or configuration is not supported." : "此节点格式或配置暂不受支持。";
            case "storage_note": return en ? "Node credentials are encrypted with Android Keystore on this device." : "节点凭据使用 Android Keystore 在本机加密保存。";
            case "privacy_note": return en ? "Node links contain credentials. Do not share screenshots or exported files publicly." : "节点链接包含认证信息，请勿公开分享截图或导出的文件。";
            case "precheck_note": return en ? "Each node is started in sing-box and must pass a real HTTPS 204 request through the VPN tunnel to be kept." : "逐个启动 sing-box 节点，并通过 VPN 隧道完成真实 HTTPS 204 请求，成功的节点才会保留。";
            case "confirm_remove_title": return en ? "Remove this node?" : "移除此节点？";
            case "confirm_remove": return en ? "Remove" : "移除";
            case "cancel": return en ? "Cancel" : "取消";
            case "notification_connecting": return en ? "Connecting" : "正在连接";
            case "notification_core_started": return en ? "VPN core started · checking traffic" : "VPN 核心已启动 · 正在检测流量";
            case "notification_start_failed": return en ? "VPN could not start" : "VPN 启动失败";
            case "notification_reload_failed": return en ? "VPN configuration reload failed" : "VPN 配置重载失败";
            case "account_security_unavailable": return en ? "Account security requires a trusted authentication API; it is not simulated locally." : "账户安全功能需要可信的认证 API，不能用本地假登录代替。";
            default: return key;
        }
    }
}
