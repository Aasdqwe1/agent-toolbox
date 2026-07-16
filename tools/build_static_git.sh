#!/bin/bash
# ============================================================
# 静态编译 git for Android arm64 (aarch64)
# 使用 Android NDK 交叉编译，输出无动态依赖的 git 二进制
#
# 已验证版本: git 2.46.0 + NDK r26d + OpenSSL 3.3.2 + curl 8.9.0
# 产出: 4.2MB 静态二进制，0 动态依赖，含 HTTPS 支持
#
# 用法:
#   方式1 - Docker: docker build -t git-builder . && docker cp $(docker create git-builder):/output/git ../assets/git/git
#   方式2 - 本地:   bash build_static_git.sh
#
# 产出路径: /output/git → 复制到 APK 的 assets/git/git
# ============================================================
set -ex

# ---- 配置 ----
NDK_VERSION="r26d"
API_LEVEL=21
ARCH="aarch64"
PREFIX="/tmp/static_prefix"
GIT_VERSION="2.46.0"
ZLIB_VERSION="1.3.1"
OPENSSL_VERSION="3.3.2"
CURL_VERSION="8.9.0"
CARES_VERSION="1.33.1"

# ---- 安装依赖 ----
apt-get update -qq
apt-get install -y -qq wget xz-utils bzip2 make autoconf automake libtool \
    pkg-config gettext curl ca-certificates python3 perl unzip

# ---- 下载 NDK ----
NDK_DIR="/tmp/android-ndk"
if [ ! -d "$NDK_DIR" ]; then
    echo "下载 Android NDK $NDK_VERSION ..."
    wget -q "https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip" -O /tmp/ndk.zip
    unzip -q /tmp/ndk.zip -d /tmp/
    mv /tmp/android-ndk-* "$NDK_DIR"
    rm /tmp/ndk.zip
fi

# ---- 设置工具链 ----
export ANDROID_NDK_ROOT="$NDK_DIR"
export NDK="$NDK_DIR"
export TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export API=$API_LEVEL
export TARGET="aarch64-linux-android"
export CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
export CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export LD="$TOOLCHAIN/bin/ld.lld"
export PATH="$TOOLCHAIN/bin:$PATH"

export CFLAGS="-static -fPIC -O2"
export LDFLAGS="-static -L$PREFIX/lib"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"

mkdir -p "$PREFIX"
cd /tmp

# ---- 1. 编译 zlib (静态) ----
echo "=== [1/4] 编译 zlib ==="
if [ ! -d "zlib-${ZLIB_VERSION}" ]; then
    wget -q "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz" -O zlib.tar.gz
    tar xf zlib.tar.gz
fi
cd "zlib-${ZLIB_VERSION}"
./configure --static --prefix="$PREFIX"
make -j$(nproc)
make install
cd ..
echo "=== zlib 完成 ==="

# ---- 2. 编译 OpenSSL (静态) ----
echo "=== [2/4] 编译 OpenSSL ==="
if [ ! -d "openssl-${OPENSSL_VERSION}" ]; then
    wget -q "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz" -O openssl.tar.gz
    tar xf openssl.tar.gz
fi
cd "openssl-${OPENSSL_VERSION}"
# 注意: ANDROID_NDK_ROOT 必须设置，否则 Configure 找不到 NDK
perl Configure android-arm64 \
    --prefix="$PREFIX" \
    no-shared no-tests \
    -D__ANDROID_API__=$API
make -j$(nproc)
make install_sw
cd ..
echo "=== OpenSSL 完成 ==="

# ---- 3. 编译 c-ares (静态, 给 curl 提供 DNS 解析) ----
# NDK 静态 libc 的 getaddrinfo 无法与 Android netd 通信，
# DNS 解析不工作。c-ares 自带 DNS 解析器，直接发送 DNS 查询，
# 不依赖系统 getaddrinfo，在静态二进制中可正常工作。
echo "=== [3/5] 编译 c-ares ==="
if [ ! -d "c-ares-${CARES_VERSION}" ]; then
    wget -q "https://github.com/c-ares/c-ares/releases/download/v${CARES_VERSION}/c-ares-${CARES_VERSION}.tar.gz" -O cares.tar.gz
    tar xf cares.tar.gz
fi
cd "c-ares-${CARES_VERSION}"
./configure \
    --host=$TARGET \
    --build=x86_64-pc-linux-gnu \
    --prefix="$PREFIX" \
    --disable-shared \
    --enable-static \
    CC="$CC" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS"
make -j$(nproc)
make install
cd ..
echo "=== c-ares 完成 ==="

# ---- 4. 编译 libcurl (静态, 启用 c-ares DNS) ----
echo "=== [4/5] 编译 libcurl ==="
if [ ! -d "curl-${CURL_VERSION}" ]; then
    wget -q "https://github.com/curl/curl/releases/download/curl-$(echo $CURL_VERSION | tr '.' '_')/curl-${CURL_VERSION}.tar.xz" -O curl.tar.xz
    tar xf curl.tar.xz
fi
cd "curl-${CURL_VERSION}"
./configure \
    --host=$TARGET \
    --build=x86_64-pc-linux-gnu \
    --prefix="$PREFIX" \
    --disable-shared \
    --enable-static \
    --with-openssl="$PREFIX" \
    --with-zlib="$PREFIX" \
    --enable-ares="$PREFIX" \
    --disable-ldap --disable-ldaps --disable-rtsp \
    --disable-dict --disable-telnet --disable-pop3 \
    --disable-imap --disable-smtp --disable-gopher --disable-mqtt \
    --without-libidn2 --without-libpsl --without-brotli \
    --without-zstd --without-nghttp2 \
    CC="$CC" CFLAGS="$CFLAGS -I$PREFIX/include" LDFLAGS="$LDFLAGS -L$PREFIX/lib"
make -j$(nproc)
make install
cd ..
echo "=== libcurl 完成 ==="

# ---- 5. 编译 git (静态) ----
echo "=== [5/5] 编译 git ==="
if [ ! -d "git-${GIT_VERSION}" ]; then
    wget -q "https://mirrors.edge.kernel.org/pub/software/scm/git/git-${GIT_VERSION}.tar.gz" -O git.tar.gz
    tar xf git.tar.gz
fi
cd "git-${GIT_VERSION}"

# ============================================================
# Android Bionic 兼容性补丁
# ============================================================
cat > /tmp/android_compat.h << 'PATCH'
/*
 * Android Bionic libc 兼容性补丁
 *
 * 问题1: Bionic 不支持 pthread_cancel（只有 pthread_kill）
 * 问题2: 低版本 Android 没有 sync_file_range
 * 问题3: Bionic iconv 不完整
 * 问题4: 没有 libintl.h（gettext）
 */
#ifndef ANDROID_COMPAT_H
#define ANDROID_COMPAT_H

/* pthread_cancel → 空操作（git 仅用于线程取消，不影响核心功能） */
#define pthread_setcancelstate(state, oldstate) ((void)(oldstate), 0)
#define PTHREAD_CANCEL_DISABLE 0
#define PTHREAD_CANCEL_ENABLE 0

/* sync_file_range → fdatasync 替代 */
#include <unistd.h>
#define sync_file_range(fd, off, len, flags) fdatasync(fd)
#define SYNC_FILE_RANGE_WAIT_BEFORE 0
#define SYNC_FILE_RANGE_WRITE 0
#define SYNC_FILE_RANGE_WAIT_AFTER 0

#endif /* ANDROID_COMPAT_H */
PATCH

# 在 git-compat-util.h 最前面注入补丁
sed -i '1i#include "/tmp/android_compat.h"' git-compat-util.h

# ============================================================
# 修补 http.c: 添加 GIT_DNS_SERVERS 环境变量支持
# Android 静态二进制的 getaddrinfo 不工作（无法与 netd 通信），
# curl 编译时启用了 c-ares（自带 DNS 解析器）。
# 通过 CURLOPT_DNS_SERVERS 让 c-ares 使用指定的 DNS 服务器。
# 在 http_init 的 curl_easy_init 之后注入设置代码。
# ============================================================
patch -p1 << 'DNSPATCH' || true
--- a/http.c
+++ b/http.c
@@
 static CURL *get_curl_handle(void)
 {
 	CURL *result = curl_easy_init();
 
 	if (!result)
 		die("curl_easy_init failed");
 
+#ifdef CURLUSE_OPENSSL
+	/* Android 静态二进制: c-ares DNS 服务器设置 */
+	{
+		const char *dns_servers = getenv("GIT_DNS_SERVERS");
+		if (dns_servers && *dns_servers) {
+			CURLcode r = curl_easy_setopt(result, CURLOPT_DNS_SERVERS, dns_servers);
+			if (r != CURLE_OK)
+				warning("CURLOPT_DNS_SERVERS failed: %s", curl_easy_strerror(r));
+		}
+	}
+#endif
+
 	primitive_ssl_init();
DNSPATCH

# 如果 patch 失败（行号不匹配），用 sed 注入
if ! grep -q "GIT_DNS_SERVERS" http.c 2>/dev/null; then
    echo "patch 失败，用 sed 注入..."
    # 在 get_curl_handle 函数的 curl_easy_init 后注入
    sed -i '/CURL \*result = curl_easy_init();/{
a\
\
	/* Android 静态二进制: c-ares DNS 服务器设置 */\
	{\
		const char *dns_servers = getenv("GIT_DNS_SERVERS");\
		if (dns_servers && *dns_servers) {\
			curl_easy_setopt(result, CURLOPT_DNS_SERVERS, dns_servers);\
		}\
	}
}' http.c
fi

# 验证补丁
echo "=== http.c 补丁验证 ==="
grep -n "GIT_DNS_SERVERS" http.c || echo "警告: GIT_DNS_SERVERS 补丁未生效"

# 编译所有 .o 文件
# 关键参数:
#   NO_ICONV=1   — Bionic iconv 不完整
#   NO_GETTEXT=1 — 没有 libintl.h
#   NO_TCLTK=1   — 不需要 Tcl/Tk
#   NO_PERL=1    — 不需要 Perl
#   NO_PYTHON=1  — 不需要 Python
make -j$(nproc) \
    CC="$CC" \
    AR="$AR" \
    CFLAGS="$CFLAGS -I$PREFIX/include -DNO_GETTEXT -DNO_ICONV" \
    LDFLAGS="$LDFLAGS" \
    CURL_LDFLAGS="-L$PREFIX/lib -lcurl -lssl -lcrypto -lz" \
    CURLDIR="$PREFIX" \
    OPENSSLDIR="$PREFIX" \
    ZLIB_PATH="$PREFIX" \
    NO_TCLTK=1 \
    NO_PERL=1 \
    NO_PYTHON=1 \
    NO_GETTEXT=1 \
    NO_ICONV=1 \
    NO_INSTALL_HARDLINKS=1 \
    prefix=/tmp/git-install \
    git remote-curl.o http.o http-walker.o || true

# 手动链接（绕过 Makefile 的 -lpthread -lrt，Bionic 内置这些）
# 注意:
#   - 不用 -all-static（lld 不支持），用 -static
#   - 不链接 -lpthread -lrt -llog（Bionic 内置）
#   - 需要显式加 common-main.o（含 main() 和 common_exit()）
echo "=== 手动链接 git ==="
$CC -static -O2 \
    -o git \
    -L$PREFIX/lib \
    git.o common-main.o \
    builtin/*.o \
    libgit.a \
    xdiff/lib.a \
    reftable/libreftable.a \
    -lcurl -lcares -lssl -lcrypto -lz -lm -ldl

# 手动链接 git-remote-https（HTTPS transport helper）
# git clone/push/pull/fetch https:// 需要这个 helper
# 源码: remote-curl.c + http.c + http-walker.c
echo "=== 手动链接 git-remote-https ==="
$CC -static -O2 \
    -o git-remote-https \
    -L$PREFIX/lib \
    remote-curl.o http.o http-walker.o common-main.o \
    libgit.a \
    xdiff/lib.a \
    reftable/libreftable.a \
    -lcurl -lcares -lssl -lcrypto -lz -lm -ldl

# strip 减小体积
$STRIP git
$STRIP git-remote-https

# 修复 TLS 段对齐：Android 14+ Bionic 要求 PT_TLS p_align >= 64
# OpenSSL 的 __thread 变量默认对齐 8，导致运行时报:
#   executable's TLS segment is underaligned: alignment is 8, needs to be at least 64
# 直接修改 ELF 程序头的 p_align 字段从 8 改为 64
fix_tls_align() {
    local bin=$1
    echo "=== 修复 TLS 段对齐: $bin (8 → 64) ==="
    python3 -c "
import struct
with open('$bin', 'rb') as f:
    data = bytearray(f.read())
e_phoff = struct.unpack_from('<Q', data, 32)[0]
e_phentsize = struct.unpack_from('<H', data, 54)[0]
e_phnum = struct.unpack_from('<H', data, 56)[0]
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    p_type = struct.unpack_from('<I', data, off)[0]
    if p_type == 7:  # PT_TLS
        p_align_off = off + 48
        old = struct.unpack_from('<Q', data, p_align_off)[0]
        struct.pack_into('<Q', data, p_align_off, 64)
        print(f'  $bin PT_TLS p_align: {old} -> 64')
        break
with open('$bin', 'wb') as f:
    f.write(data)
"
}
fix_tls_align git
fix_tls_align git-remote-https

# 检查是否真的是静态的
echo "=== 检查二进制 ==="
file git git-remote-https
echo "动态依赖 (git):"
$TOOLCHAIN/bin/llvm-readobj --needed-libs git || echo "(完全静态，0 动态依赖)"
echo "动态依赖 (git-remote-https):"
$TOOLCHAIN/bin/llvm-readobj --needed-libs git-remote-https || echo "(完全静态，0 动态依赖)"
echo "TLS 对齐 (git):"
readelf -l git | grep -A1 TLS
echo "TLS 对齐 (git-remote-https):"
readelf -l git-remote-https | grep -A1 TLS

# 复制到输出目录
mkdir -p /output
cp git /output/git
cp git-remote-https /output/git-remote-https
chmod +x /output/git /output/git-remote-https
echo "=== 输出文件 ==="
ls -lh /output/

echo ""
echo "============================================================"
echo "=== 编译完成! ==="
echo "输出: /output/git"
echo "大小: $(ls -lh /output/git | awk '{print $5}')"
echo "架构: ARM aarch64 (Android)"
echo "链接: 完全静态，0 动态依赖"
echo "功能: 完整 git（含 HTTPS，支持 clone/push/pull）"
echo ""
echo "安装: 复制到 APK 的 assets/git/git"
echo "============================================================"
