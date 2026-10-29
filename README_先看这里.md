# Q7VPN V6.0.0（先看这里）

这是 V6 云端构建整理包。它保留现有项目并增加 Codemagic Android debug APK 工作流。建议上传到新分支/新仓库进行构建，先不要覆盖旧版。

**能力边界：** Android 端当前是节点管理/导入工具，尚未集成完整代理内核和自己的 VPN 隧道。生成 APK 不等于完整 VPN 已可用。详细说明见 `README_V6_升级说明.md` 和 `android/README.md`。

打包入口：根目录 `codemagic.yaml`。
