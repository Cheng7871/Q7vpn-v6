# Q7VPN V6.0.0 — 升级包说明

## 本包已做的整理
- Android app 版本号更新为 6.0.0 / versionCode 6。
- 增加 Codemagic 云端 debug APK 构建工作流 `codemagic.yaml`，固定 JDK 17、Gradle 8.9、Android SDK 35。
- 保留原有 Android 节点管理器与 Web 项目文件，未删除旧功能。
- 加入本说明，标出当前能力边界，避免把节点端口检测误认为 VPN 已连接。

## 重要：V6 目前不是完整的独立 VPN
现有 Android 端仍是节点导入/解析、TCP 端口可达性检测和把节点 URI 分享给其他兼容客户端的工具。当前没有集成 sing-box/libbox，也没有自己的 Android `VpnService` 隧道，因此不能宣称已经支持真正的 VPN 流量转发。

要成为完整 VPN，需要进一步集成并测试代理核心、Android VPN 服务、TUN 文件描述符、DNS/路由、前台服务生命周期、ABI 原生库和许可声明；需要真实节点进行协议握手与流量验证。

## 使用 Codemagic 生成 APK
1. 将本 ZIP 解压到一个新目录，保留原仓库不动。
2. 将整个工程推送到 GitHub 的新分支或新仓库。
3. 打开 https://codemagic.io/ ，连接 GitHub，选择该仓库。
4. 选择 `codemagic.yaml` 工作流 `Q7VPN V6 Android debug APK` 并开始构建。
5. 构建成功后，从 Artifacts 下载 `app-debug.apk`。

## 验证状态
此包只经过文件/配置静态整理；尚未在 Codemagic 云端实际构建，也未在真机验证。构建成功与否必须以 Codemagic 日志为准。Debug APK 仅用于测试，不是正式商店签名包。
