# Q7VPN Android status (read before release)

This merged project incorporates the repaired V5.2 web code and Android MVP. The four-character CAPTCHA is a legacy web-page fix; the native Android MVP currently has no account/login server integration.

## Implemented in this merge
- CAPTCHA is four characters; the image and input sizing are reduced and centered for narrow screens.
- Mobile page animations and backdrop blur are reduced to avoid unnecessary GPU work.
- Android node list uses a lightweight glass-like dark UI.
- TXT import persists nodes and starts a background TCP reachability filter automatically after successful imports.
- Duplicate detection uses a hash set during import to avoid quadratic scans on large TXT files.
- The Android node list renders in pages of 80 items to reduce UI jank for large imports.
- GitHub Actions pins Android command-line tools and explicitly installs platform-tools, Android 35 platform, and build-tools 35.0.0; the workflow emits stack traces if Gradle fails.

## Important: not yet a standalone VPN
The current Android code does **not** implement an Android `VpnService` tunnel and does **not** include a proxy protocol core. TCP port reachability is not proof that VLESS/SS authentication or traffic routing works. This merge must not be advertised as a working standalone VPN yet.

To become a real VPN client, a follow-up engineering phase must pin and integrate a compatible sing-box/libbox Android core, convert imported VLESS/SS links into validated core configuration, implement `VpnService` foreground lifecycle/TUN descriptor handoff, request Android VPN consent, handle DNS/routing and stop/reconnect states, and test on a physical device. Do not ship a fake connected state.

## Build APK from GitHub
1. Upload this project to a GitHub repository.
2. Open **Actions** and run **Build Q7VPN Android APK** (or push changes to `main`/`master`).
3. Open the completed workflow run and download the `Q7VPN-debug-apk` artifact.
4. This is a debug-signed APK for testing, not a Play Store release.

The Android VPN tunnel is still not implemented in this revision. No `VpnService`/libbox integration was added because the repository does not yet contain a pinned, integrated proxy-core API implementation; adding a service declaration alone would not make it a working VPN. The next phase must implement and test the actual core integration before claiming standalone VPN capability.

Huawei Mate 50 Pro compatibility still needs physical-device testing. `minSdk 26` is compatible with the Android API baseline, but OEM battery/background policies and VPN behavior must be verified on-device.
