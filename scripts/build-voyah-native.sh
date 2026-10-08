#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK}"
ndk_root="${ANDROID_NDK_HOME:-$ANDROID_SDK_ROOT/ndk/28.2.13676358}"
compiler="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
sysroot="$ndk_root/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
output="$project_root/build/voyah-jni"
mkdir -p "$project_root/build/native-tmp"
export TMPDIR="${TMPDIR:-$project_root/build/native-tmp}"
test -x "$compiler"

for abi in arm64-v8a armeabi-v7a x86_64; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android28 ;;
        armeabi-v7a) target=armv7a-linux-androideabi28 ;;
        x86_64) target=x86_64-linux-android28 ;;
    esac
    mkdir -p "$output/$abi"
    for library in xcertplay_i2c local_hotspot_radio; do
        source=linux_i2c_jni.c
        flags=()
        if [[ "$library" == local_hotspot_radio ]]; then
            source=local_hotspot_radio.c
            flags=(-Wall -Wextra -Werror)
        fi
        "$compiler" "--target=$target" "--sysroot=$sysroot" -shared -fPIC -O2 -g \
            -Wl,--no-undefined -Wl,-z,relro -Wl,-z,now -Wl,--build-id=sha1 \
            -Wl,-z,max-page-size=16384 "-Wl,-soname,lib$library.so" "${flags[@]}" \
            "$project_root/shared/src/main/jni/$source" -o "$output/$abi/lib$library.so"
    done
done
printf 'Built native libraries from this checkout: %s\n' "$output"
