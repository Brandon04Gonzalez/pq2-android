#!/bin/bash
# build_apk.sh <abi...>   p. ej. build_apk.sh arm64-v8a   o   build_apk.sh x86_64
# Arma C:\pq2port\out\PersonaQ2-<abis>.apk sin Gradle: aapt2, javac, d8,
# zipalign y apksigner del SDK, y la libmain.so que compiló cargo.
set -euo pipefail
ROOT=${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
APP=$ROOT/android
SDK="${ANDROID_HOME:-/c/Users/brand/AppData/Local/Android/Sdk}"
BT="$SDK/build-tools/35.0.1"
JAR="$SDK/platforms/android-35/android.jar"
JDK="${JDK:-/c/Program Files/Android/Android Studio/jbr}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
BUILD=$ROOT/android/build
ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64-v8a)
NAME=$(IFS=-; echo "${ABIS[*]}")

rm -rf "$BUILD" && mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$ROOT/out"

"$BT/aapt2.exe" compile --dir "$APP/res" -o "$BUILD/res.zip"
# VALIDATION=1: APK depurable con la capa de validación de Vulkan dentro,
# que se activa con  settings put global enable_gpu_debug_layers 1  etc.
MANIFEST="$APP/AndroidManifest.xml"
if [ "${VALIDATION:-0}" = 1 ]; then
    sed 's/<application/<application android:debuggable="true"/' "$APP/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
    MANIFEST="$BUILD/AndroidManifest.xml"
fi
"$BT/aapt2.exe" link -o "$BUILD/base.apk" -I "$JAR" --manifest "$MANIFEST" \
    --java "$BUILD/gen" --min-sdk-version 26 --target-sdk-version 35 "$BUILD/res.zip"

find "$BUILD/gen" "$APP/java" -name '*.java' | while read f; do cygpath -m "$f"; done > "$BUILD/sources.txt"
javac --release 11 -encoding UTF-8 -nowarn -classpath "$(cygpath -m "$JAR")" -d "$(cygpath -m "$BUILD/classes")" @"$(cygpath -m "$BUILD/sources.txt")"
find "$BUILD/classes" -name '*.class' | while read f; do cygpath -m "$f"; done > "$BUILD/classes.txt"
"$BT/d8.bat" --release --min-api 26 --lib "$(cygpath -m "$JAR")" --output "$(cygpath -m "$BUILD/dex")" $(cat "$BUILD/classes.txt")

LIBS=()
for abi in "${ABIS[@]}"; do
    case $abi in
        arm64-v8a) target=aarch64-linux-android ;;
        x86_64) target=x86_64-linux-android ;;
        *) echo "ABI desconocida $abi"; exit 1 ;;
    esac
    LIBS+=("lib=$abi:$(cygpath -w "$ROOT/pq2-android/target/$target/release/libmain.so")")
    if [ "${VALIDATION:-0}" = 1 ]; then
        LIBS+=("extra=$abi/libVkLayer_khronos_validation.so:$(cygpath -w "$ROOT/tools/vvl/android-binaries-1.4.363.0/$abi/libVkLayer_khronos_validation.so")")
    fi
done
# the APK holds no game code or data: the game is recompiled on the phone the first time it starts,
# with the C compiler shipped here (Zig 0.17.0 for aarch64-linux, static, compressed in the APK and
# extracted at install), and its library (headers, std, compiler_rt) as an already compressed zip
ZIG=${ZIG:-$ROOT/build/zig-android}
ASSETS=()
for abi in "${ABIS[@]}"; do
    if [ "$abi" = arm64-v8a ]; then
        LIBS+=("exe=arm64-v8a/libzig.so:$(cygpath -w "$ZIG/zig-aarch64-linux-0.17.0/zig")")
        ASSETS=("asset=zig-lib.zip:$(cygpath -w "$ZIG/zig-lib.zip")")
    fi
done
python "$(cygpath -w "$ROOT/tools/pack_apk.py")" "$(cygpath -w "$BUILD/base.apk")" "$(cygpath -w "$BUILD/dex/classes.dex")" "$(cygpath -w "$BUILD/unsigned.apk")" \
    "${LIBS[@]}" "${ASSETS[@]}"

"$BT/zipalign.exe" -P 16 -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

KEYSTORE=$ROOT/android/pq2port.keystore
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$(cygpath -w "$KEYSTORE")" -storepass pq2port -keypass pq2port -alias pq2port \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PQ2 Port, O=pq2port, C=MX"
fi
OUT="$ROOT/out/PersonaQ2-$NAME.apk"
[ "${VALIDATION:-0}" = 1 ] && OUT="$ROOT/out/PersonaQ2-$NAME-validation.apk"
"$BT/apksigner.bat" sign --ks "$(cygpath -w "$KEYSTORE")" --ks-pass pass:pq2port --key-pass pass:pq2port --ks-key-alias pq2port \
    --out "$(cygpath -w "$OUT")" "$(cygpath -w "$BUILD/aligned.apk")"
"$BT/apksigner.bat" verify "$(cygpath -w "$OUT")"
"$BT/zipalign.exe" -c -P 16 4 "$OUT" && echo "alineación OK"
ls -la "$OUT"
