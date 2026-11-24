#!/usr/bin/env bash
# Builds the native libraries aasdk needs, for Android arm64, into native/deps/.
# Run once after cloning (takes ~10 minutes). Needs: Android NDK, the SDK's CMake, perl, make, curl, git.
set -euo pipefail

ABI=arm64-v8a
API=29
BOOST_VER=1.86.0
OPENSSL_VER=3.0.15
PROTOBUF_VER=29.3   # must match the protoc version below

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/deps"
SRC="$HERE/deps-src"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_ROOT:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}"
CMAKE_BIN="$(ls -d "$SDK"/cmake/* | sort -V | tail -1)/bin"
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"   # NDK ships x86_64 host tools on macOS too
export PATH="$CMAKE_BIN:$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
mkdir -p "$OUT/$ABI" "$SRC"

# Boost: header-only use (asio, system), so we just unpack the headers.
if [ ! -f "$OUT/boost/boost/asio.hpp" ]; then
  echo ">> boost $BOOST_VER headers"
  u=${BOOST_VER//./_}
  curl -fL "https://archives.boost.io/release/$BOOST_VER/source/boost_$u.tar.gz" -o "$SRC/boost.tar.gz"
  mkdir -p "$OUT/boost" && tar xzf "$SRC/boost.tar.gz" -C "$OUT/boost" --strip-components=1 "boost_$u/boost"
fi

# OpenSSL: aasdk's TLS handshake with the phone.
if [ ! -f "$OUT/$ABI/lib/libssl.a" ]; then
  echo ">> openssl $OPENSSL_VER"
  curl -fL "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VER/openssl-$OPENSSL_VER.tar.gz" -o "$SRC/openssl.tar.gz"
  rm -rf "$SRC/openssl" && mkdir "$SRC/openssl" && tar xzf "$SRC/openssl.tar.gz" -C "$SRC/openssl" --strip-components=1
  (cd "$SRC/openssl" && ANDROID_NDK_ROOT="$NDK" ./Configure android-arm64 -D__ANDROID_API__=$API \
      no-shared no-tests --prefix="$OUT/$ABI" --libdir=lib \
    && make -j"$JOBS" build_libs && make install_dev)
fi

# Protobuf (+ abseil): runtime for the generated Android Auto messages.
if [ ! -f "$OUT/$ABI/lib/libprotobuf.a" ]; then
  echo ">> protobuf $PROTOBUF_VER"
  [ -d "$SRC/protobuf" ] || git clone -q --depth 1 -b "v$PROTOBUF_VER" --recurse-submodules --shallow-submodules \
      https://github.com/protocolbuffers/protobuf.git "$SRC/protobuf"
  cmake -S "$SRC/protobuf" -B "$SRC/protobuf-build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$ABI -DANDROID_PLATFORM=$API \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$OUT/$ABI" -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_PROTOC_BINARIES=OFF -Dprotobuf_BUILD_LIBUPB=OFF \
    -Dprotobuf_ABSL_PROVIDER=module -Dprotobuf_INSTALL=ON -DABSL_ENABLE_INSTALL=ON
  cmake --build "$SRC/protobuf-build" -j"$JOBS" && cmake --install "$SRC/protobuf-build"
fi

# Host protoc of the same version, from Maven Central.
if [ ! -x "$OUT/protoc" ]; then
  echo ">> protoc $PROTOBUF_VER"
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) c=osx-aarch_64 ;; Darwin-x86_64) c=osx-x86_64 ;;
    Linux-x86_64) c=linux-x86_64 ;; Linux-aarch64) c=linux-aarch_64 ;;
    *) echo "unsupported host"; exit 1 ;;
  esac
  curl -fL "https://repo1.maven.org/maven2/com/google/protobuf/protoc/4.$PROTOBUF_VER/protoc-4.$PROTOBUF_VER-$c.exe" -o "$OUT/protoc"
  chmod +x "$OUT/protoc"
fi

echo ">> done: $OUT"
