#!/usr/bin/env bash
# Q7VPN APK 手工构建脚本（不需要 Gradle、不需要联网、不需要 Android Studio）
# 依赖：JDK 17+、Android build-tools（aapt2/d8/zipalign/apksigner）、android.jar
#
# 用法：
#   ./build_apk.sh                     # 用默认路径
#   JDK=/path/to/jdk BT=/path/to/build-tools AJ=/path/to/android.jar ./build_apk.sh
set -euo pipefail

# 三个路径按你机器上的实际情况指定（环境变量或下面的默认值）
#   JDK  留空表示用 PATH 里的 java（需 17+）
#   BT   Android SDK 的 build-tools 目录，例如 ~/Android/Sdk/build-tools/35.0.0
#   AJ   对应平台的 android.jar，例如 ~/Android/Sdk/platforms/android-35/android.jar
JDK="${JDK:-}"
BT="${BT:-$HOME/Android/Sdk/build-tools/35.0.0}"
AJ="${AJ:-$HOME/Android/Sdk/platforms/android-35/android.jar}"
SRC="${SRC:-$(cd "$(dirname "$0")" && pwd)/app/src/main}"
OUT="${OUT:-$(cd "$(dirname "$0")" && pwd)/build_manual}"
NAME="${NAME:-Q7vpn}"
PKG="${PKG:-com.q7vpn.app}"
VER="${VER:-1.0.0}"

if [ -n "$JDK" ]; then export JAVA_HOME="$JDK"; export PATH="$JDK/bin:$PATH"; fi
command -v javac >/dev/null || { echo "找不到 javac，请装 JDK 17+ 或用 JDK=/path/to/jdk 指定"; exit 1; }
[ -n "$BT" ] && [ -n "$AJ" ] || { echo "请用 BT=... AJ=... 指定 build-tools 和 android.jar 路径"; exit 1; }
for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJ"; do
  [ -e "$f" ] || { echo "缺少构建组件: $f"; exit 1; }
done

rm -rf "$OUT"; mkdir -p "$OUT"/{compiled,gen,classes,dex}

echo "[1/7] 编译资源"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled/res.zip"

echo "[2/7] 链接资源与清单"
# AGP 用 namespace 提供包名，aapt2 命令行则要求清单里带 package —— 只改临时副本，不动源码
cp "$SRC/AndroidManifest.xml" "$OUT/AndroidManifest.xml"
grep -q 'package=' "$OUT/AndroidManifest.xml" || \
  sed -i 's|<manifest |<manifest package="'"$PKG"'" |' "$OUT/AndroidManifest.xml"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" \
  --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code 1 --version-name "$VER" \
  "$OUT/compiled/res.zip" --auto-add-overlay

echo "[3/7] 编译 Java"
javac -source 17 -target 17 -encoding UTF-8 -classpath "$AJ" -d "$OUT/classes" \
  $(find "$OUT/gen" -name "*.java") $(find "$SRC/java" -name "*.java")

echo "[4/7] 转 dex"
"$BT/d8" --lib "$AJ" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")

echo "[5/7] 组包"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex)

echo "[6/7] 对齐"
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[7/7] 签名"
if [ ! -f "$OUT/q7-debug.keystore" ]; then
  keytool -genkeypair -keystore "$OUT/q7-debug.keystore" -alias q7vpn -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=Q7VPN Debug,O=Q7,L=NA,ST=NA,C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$OUT/q7-debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/${NAME}_${VER}.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/${NAME}_${VER}.apk"
echo "完成: $OUT/${NAME}_${VER}.apk"
