# Q7vpn Android MVP

## Current behavior
- Native Android application project (Java, minSdk 26).
- Imports a user-selected TXT file through Android's document picker.
- Parses common VLESS URI and a subset of SS URI formats, deduplicates exact URIs.
- Shows node name, protocol, host/port and a basic TCP reachability latency.
- "交给客户端" opens Android's share chooser so a compatible proxy client can receive the URI.

## Critical limitation — not yet a standalone VPN client
This MVP does **not** embed a proxy core, does not implement its own `VpnService` tunnel, and does not itself request Android VPN permission. The share/handoff step is not the same as connecting inside Q7vpn. A compatible client must accept the URI, validate it, start its own tunnel and show Android's VPN permission dialog. This is deliberate; the UI must not falsely report "connected".

## Build
1. Install Android Studio.
2. Open this `android` folder as a project.
3. Let Android Studio install Gradle/Android SDK components.
4. Build > Build APK(s), then install the debug APK on an Android phone.
5. To test, import a TXT file containing valid SS/VLESS URIs you are authorized to use.

No prebuilt APK is included because this environment does not have Android SDK/Gradle build tooling installed.

## Next engineering phase for embedded VPN
Integrate and test a pinned sing-box Android `libbox` version, including its required platform callbacks, lifecycle, foreground service, TUN file descriptor handoff, DNS/routing, config conversion, native ABI packaging, and license notices. Do not simply add a dependency and claim it is integrated: `libbox` integration requires implementing the library's platform contracts. Official project: https://github.com/singbox-android/libbox
