#!/usr/bin/env bash
#
# Reproducibly builds the six arm64-v8a Android executables bundled in the APK.
# Requires: curl, tar, autoconf, automake, libtool, cmake, pkg-config, make, JDK-free.
# The Android NDK (r28c) is downloaded automatically unless ANDROID_NDK_ROOT is set.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_DIR="$ROOT_DIR/app/src/main/assets/bin/arm64-v8a"
NDK_VERSION="r28c"
API=26
HOST="aarch64-linux-android"

REQUIRED=(usbmuxd idevice_id ideviceinfo idevicepair ideviceinstaller zsign)

if [[ "${FORCE_BUILD:-0}" != "1" ]]; then
  missing=0
  for f in "${REQUIRED[@]}"; do
    [[ -x "$ASSET_DIR/$f" ]] || missing=1
  done
  if [[ $missing -eq 0 ]]; then
    echo "[toolchain] all six binaries already present, skipping (FORCE_BUILD=1 to rebuild)"
    exit 0
  fi
fi

log() { printf '\n\033[1;34m[toolchain]\033[0m %s\n' "$*"; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC="$WORK/src"
PREFIX="$WORK/prefix"
mkdir -p "$SRC" "$PREFIX"

# ---------------------------------------------------------------- NDK
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK" || ! -d "$NDK/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
  NDK="$WORK/android-ndk-$NDK_VERSION"
  log "download Android NDK $NDK_VERSION"
  curl -fL --retry 3 --connect-timeout 30 \
    -o "$WORK/ndk.zip" \
    "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
  unzip -q "$WORK/ndk.zip" -d "$WORK"
fi
export ANDROID_NDK_ROOT="$NDK"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$PATH"

export CC="$TOOLCHAIN/bin/${HOST}${API}-clang"
export CXX="$TOOLCHAIN/bin/${HOST}${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export CFLAGS="-O2 -fPIC -I$PREFIX/include"
export CXXFLAGS="-O2 -fPIC -I$PREFIX/include"
export LDFLAGS="-L$PREFIX/lib"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export ac_cv_lib_pthread_pthread_once=yes
export ac_cv_func_malloc_0_nonnull=yes
export ac_cv_func_realloc_0_nonnull=yes

JOBS="${JOBS:-$(nproc)}"
[[ "$JOBS" -gt 4 ]] && JOBS=4

fetch() {
  local url="$1" out="$2"
  [[ -f "$out" ]] || curl -fL --retry 3 --connect-timeout 30 -o "$out" "$url"
}

extract() {
  local archive="$1" name="$2"
  log "extract $name"
  case "$archive" in
    *.tar.gz|*.tgz) tar xzf "$archive" -C "$SRC" ;;
    *.tar.bz2) tar xjf "$archive" -C "$SRC" ;;
    *) echo "unsupported archive $archive" >&2; exit 1 ;;
  esac
  find "$SRC" -maxdepth 1 -type d -name "$name*" | head -n 1
}

# ---------------------------------------------------------------- sources
log "download sources"
fetch "https://github.com/openssl/openssl/releases/download/openssl-3.5.1/openssl-3.5.1.tar.gz" "$WORK/openssl.tar.gz"
fetch "https://github.com/libimobiledevice/libplist/archive/refs/tags/2.8.1.tar.gz" "$WORK/libplist.tar.gz"
fetch "https://github.com/libimobiledevice/libimobiledevice-glue/archive/refs/tags/1.3.0.tar.gz" "$WORK/glue.tar.gz"
fetch "https://github.com/libimobiledevice/libusbmuxd/archive/refs/tags/2.1.1.tar.gz" "$WORK/libusbmuxd.tar.gz"
fetch "https://github.com/libimobiledevice/libimobiledevice/archive/refs/tags/1.3.0.tar.gz" "$WORK/libimobiledevice.tar.gz"
fetch "https://github.com/libimobiledevice/usbmuxd/archive/3ded00c9985a5108cfc7591a309f9a23d57a8cba.tar.gz" "$WORK/usbmuxd.tar.gz"
fetch "https://github.com/libimobiledevice/ideviceinstaller/archive/refs/tags/1.1.1.tar.gz" "$WORK/ideviceinstaller.tar.gz"
fetch "https://github.com/zhlynn/zsign/archive/refs/tags/v1.1.3.tar.gz" "$WORK/zsign.tar.gz"
fetch "https://github.com/libusb/libusb/releases/download/v1.0.28/libusb-1.0.28.tar.bz2" "$WORK/libusb.tar.bz2"
fetch "https://github.com/nih-at/libzip/releases/download/v1.11.4/libzip-1.11.4.tar.gz" "$WORK/libzip.tar.gz"

# ---------------------------------------------------------------- stubs
# Bionic provides pthread/rt symbols in libc; autotools still asks for -lpthread/-lrt.
mkdir -p "$PREFIX/lib"
printf 'void __ibridge_pthread_stub(void){}\n' > "$WORK/stub.c"
"$CC" -c "$WORK/stub.c" -o "$WORK/stub.o"
"$AR" rcs "$PREFIX/lib/libpthread.a" "$WORK/stub.o"
cp "$PREFIX/lib/libpthread.a" "$PREFIX/lib/librt.a"

# ---------------------------------------------------------------- OpenSSL
log "build OpenSSL 3.5.1"
openssl_dir="$(extract "$WORK/openssl.tar.gz" openssl-3.5.1)"
(
  cd "$openssl_dir"
  ./Configure android-arm64 no-shared no-tests no-apps no-engine no-zlib \
    --prefix="$PREFIX" --openssldir="$PREFIX/ssl" >/dev/null
  make -j"$JOBS" build_libs >/dev/null
  make install_dev >/dev/null
)

build_autotools() {
  local dir="$1"; shift
  log "build $(basename "$dir")"
  (
    cd "$dir"
    ./autogen.sh --host="$HOST" --prefix="$PREFIX" --enable-static --disable-shared "$@" >/dev/null
    make -j"$JOBS" >/dev/null
    make install >/dev/null
  )
}

libplist_dir="$(extract "$WORK/libplist.tar.gz" libplist-2.8.1)"
build_autotools "$libplist_dir" --without-cython

glue_dir="$(extract "$WORK/glue.tar.gz" libimobiledevice-glue-1.3.0)"
build_autotools "$glue_dir"

usbmuxdlib_dir="$(extract "$WORK/libusbmuxd.tar.gz" libusbmuxd-2.1.1)"
build_autotools "$usbmuxdlib_dir"

# libusb
log "build libusb 1.0.28"
libusb_dir="$(extract "$WORK/libusb.tar.bz2" libusb-1.0.28)"
(
  cd "$libusb_dir"
  ./configure --host="$HOST" --prefix="$PREFIX" --enable-static --disable-shared \
    --disable-udev >/dev/null
  make -j"$JOBS" >/dev/null
  make install >/dev/null
)

# ---------------------------------------------------------------- libimobiledevice
log "patch + build libimobiledevice 1.3.0"
limd_dir="$(extract "$WORK/libimobiledevice.tar.gz" libimobiledevice-1.3.0)"
python3 - "$limd_dir" <<'PY'
import re, sys
root = sys.argv[1]
p = root + "/common/utils.h"
s = open(p).read()
s = re.sub(r"enum plist_format_t \{.*?\};\s*", "", s, flags=re.S)
s = s.replace("enum plist_format_t format", "plist_format_t format")
open(p, "w").write(s)
p = root + "/common/utils.c"
s = open(p).read().replace("enum plist_format_t format", "plist_format_t format")
open(p, "w").write(s)
p = root + "/common/userpref.c"
s = open(p).read()
needle = "if (__config_dir)\n\t\treturn __config_dir;\n"
insert = needle + """
#ifdef __ANDROID__
	const char *android_dir = getenv("IBRIDGE_LOCKDOWN_DIR");
	if (android_dir && android_dir[0]) {
		__config_dir = strdup(android_dir);
		return __config_dir;
	}
#endif
"""
if "IBRIDGE_LOCKDOWN_DIR" not in s:
    s = s.replace(needle, insert, 1)
open(p, "w").write(s)
PY
build_autotools "$limd_dir" --without-cython --with-openssl

# ---------------------------------------------------------------- usbmuxd
log "build usbmuxd"
usbmuxd_dir="$(extract "$WORK/usbmuxd.tar.gz" usbmuxd-3ded00c)"
build_autotools "$usbmuxd_dir" --without-preflight --without-udev --without-systemd

# ---------------------------------------------------------------- libzip
log "build libzip 1.11.4"
libzip_dir="$(extract "$WORK/libzip.tar.gz" libzip-1.11.4)"
cmake -S "$libzip_dir" -B "$WORK/libzip-build" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_TOOLS=OFF -DBUILD_REGRESS=OFF -DBUILD_EXAMPLES=OFF \
  -DBUILD_DOC=OFF -DENABLE_BZIP2=OFF -DENABLE_LZMA=OFF -DENABLE_ZSTD=OFF \
  -DENABLE_GNUTLS=OFF -DENABLE_OPENSSL=OFF \
  -DCMAKE_DISABLE_FIND_PACKAGE_BZip2=ON -DCMAKE_DISABLE_FIND_PACKAGE_LibLZMA=ON \
  -DCMAKE_DISABLE_FIND_PACKAGE_Zstd=ON -DCMAKE_DISABLE_FIND_PACKAGE_GNUTLS=ON \
  -DCMAKE_DISABLE_FIND_PACKAGE_OpenSSL=ON >/dev/null
cmake --build "$WORK/libzip-build" -j"$JOBS" >/dev/null
cmake --install "$WORK/libzip-build" >/dev/null

# ---------------------------------------------------------------- ideviceinstaller
log "build ideviceinstaller 1.1.1"
idi_dir="$(extract "$WORK/ideviceinstaller.tar.gz" ideviceinstaller-1.1.1)"
(
  cd "$idi_dir"
  ./autogen.sh --host="$HOST" --prefix="$PREFIX" --enable-static --disable-shared \
    CFLAGS="$CFLAGS -Wno-error" LDFLAGS="$LDFLAGS" LIBS="-lz" >/dev/null
  make -j"$JOBS" CFLAGS="$CFLAGS -Wno-error" LIBS="-lz" >/dev/null
  make install >/dev/null
)

# ---------------------------------------------------------------- zsign
log "build zsign v1.1.3"
zsign_dir="$(extract "$WORK/zsign.tar.gz" zsign-1.1.3)"
(
  cd "$zsign_dir/build/linux"
  make -j"$JOBS" \
    CXX="$CXX" CC="$CC" \
    CXXFLAGS="-std=c++11 -O2 -Wno-unused-result -fPIC -I$PREFIX/include -static-libstdc++ -Wl,-z,max-page-size=16384" \
    LIBS="-L$PREFIX/lib -lssl -lcrypto -ldl -llog -static-libstdc++ -Wl,-z,max-page-size=16384" >/dev/null
)

# ---------------------------------------------------------------- stage
log "stage + strip binaries"
mkdir -p "$ASSET_DIR"
install -m 0755 "$PREFIX/sbin/usbmuxd" "$ASSET_DIR/usbmuxd"
install -m 0755 "$PREFIX/bin/idevice_id" "$ASSET_DIR/idevice_id"
install -m 0755 "$PREFIX/bin/ideviceinfo" "$ASSET_DIR/ideviceinfo"
install -m 0755 "$PREFIX/bin/idevicepair" "$ASSET_DIR/idevicepair"
install -m 0755 "$PREFIX/bin/ideviceinstaller" "$ASSET_DIR/ideviceinstaller"
install -m 0755 "$zsign_dir/bin/zsign" "$ASSET_DIR/zsign"

for f in "${REQUIRED[@]}"; do
  "$STRIP" --strip-all "$ASSET_DIR/$f"
done

ls -lh "$ASSET_DIR"/{usbmuxd,idevice_id,ideviceinfo,idevicepair,ideviceinstaller,zsign}
log "done"
