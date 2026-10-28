#!/usr/bin/env bash
#
# build-module.sh —— 打包 HidPager Privileged（priv-app）Magisk/KernelSU 模块
#
# 用法:
#   ./build-module.sh [版本号]
#   版本号默认 1.3，产物为 HidPager-privapp-v<版本号>.zip
#
# 前置条件:
#   先构建 release APK: app/build/outputs/apk/release/app-release.apk
#   （本地无 KEYSTORE_PATH 时回退 debug 签名，可接受；CI 由 secrets 注入正式签名）
#
set -euo pipefail

# 始终基于脚本所在目录（工程根）定位，避免被 cwd 影响
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

# ---------- 参数 ----------
VERSION="${1:-1.3}"

MODULE_DIR="${SCRIPT_DIR}/magisk-module"
APK_SRC="${SCRIPT_DIR}/app/build/outputs/apk/release/app-release.apk"
APK_DST_DIR="${MODULE_DIR}/system/priv-app/HidPager"
APK_DST="${APK_DST_DIR}/HidPager.apk"
ZIP_NAME="HidPager-privapp-v${VERSION}.zip"
ZIP_OUT="${SCRIPT_DIR}/${ZIP_NAME}"

# ---------- 1. 校验 APK 存在 ----------
if [ ! -f "${APK_SRC}" ]; then
    echo "[ERROR] 未找到 APK: ${APK_SRC}"
    echo "        请先 ./gradlew assembleRelease 或等 CI 构建"
    exit 1
fi

# ---------- 2. 拷贝 APK 到模块 ----------
mkdir -p "${APK_DST_DIR}"
cp -f "${APK_SRC}" "${APK_DST}"
echo "[OK] APK 已拷入: ${APK_DST}"

# ---------- 3. 删除旧 zip ----------
rm -f "${ZIP_OUT}"

# ---------- 4. 打包（优先 zip 命令，缺失则 python3 兜底） ----------
cd "${MODULE_DIR}"
if command -v zip >/dev/null 2>&1; then
    zip -rq "${ZIP_OUT}" .
elif command -v python3 >/dev/null 2>&1; then
    python3 -m zipfile -c "${ZIP_OUT}" .
else
    echo "[ERROR] 既无 zip 命令，也无 python3，无法打包"
    exit 1
fi

echo "[OK] 模块打包完成: ${ZIP_OUT}"
echo "产物绝对路径: ${ZIP_OUT}"
