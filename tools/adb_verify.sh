#!/usr/bin/env bash
# VidGrab 快速 ADB 验证脚本
# 无需 Python，直接用 adb 命令验证关键功能

set -euo pipefail

PACKAGE="com.vidgrab.app"
DOWNLOAD_DIR="/sdcard/Download/VidGrab"
PRIVATE_DIR="/data/data/${PACKAGE}/files/VidGrab"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_ok() { echo -e "${GREEN}[OK]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_err() { echo -e "${RED}[ERR]${NC} $*"; }

check_device() {
    log_info "检查设备连接..."
    if ! adb devices | grep -q "device$"; then
        log_err "未检测到设备，请开启 USB 调试"
        exit 1
    fi
    log_ok "设备已连接"
}

check_app_installed() {
    log_info "检查应用安装状态..."
    if adb shell pm list packages | grep -q "^package:${PACKAGE}$"; then
        log_ok "应用已安装: ${PACKAGE}"
        # 显示版本信息
        adb shell dumpsys package ${PACKAGE} | grep -E "versionCode|versionName" | head -2
    else
        log_err "应用未安装"
        exit 1
    fi
}

check_native_libs() {
    log_info "检查 Native 库 (ABI 支持)..."
    local abi
    abi=$(adb shell getprop ro.product.cpu.abi)
    log_info "设备 ABI: ${abi}"

    local libs
    libs=$(adb shell ls /data/app/${PACKAGE}*/lib/ 2>/dev/null || echo "未找到")
    log_info "已安装 native lib: ${libs}"

    if [[ "${abi}" == *"arm64"* ]] || [[ "${abi}" == *"x86_64"* ]]; then
        log_ok "当前 ABI (${abi}) 在支持列表中"
    else
        log_warn "未知 ABI: ${abi}"
    fi
}

check_download_dirs() {
    log_info "检查下载目录..."
    echo "--- 公共目录 (${DOWNLOAD_DIR}) ---"
    adb shell ls -la "${DOWNLOAD_DIR}" 2>/dev/null || log_warn "公共目录不存在或无权限"

    echo "--- 私有目录 (${PRIVATE_DIR}) ---"
    adb shell ls -la "${PRIVATE_DIR}" 2>/dev/null || log_warn "私有目录不存在"
}

check_browser_data() {
    log_info "检查浏览器数据持久化 (browser_data.json)..."
    local data
    data=$(adb shell cat "/data/data/${PACKAGE}/files/browser_data.json" 2>/dev/null || echo "")
    if [[ -n "${data}" ]]; then
        log_ok "browser_data.json 存在 (${#data} 字符)"
        echo "${data}" | head -c 200
        echo "..."
    else
        log_warn "browser_data.json 不存在或为空"
    fi
}

check_shared_prefs() {
    log_info "检查 SharedPreferences (设置持久化)..."
    adb shell ls "/data/data/${PACKAGE}/shared_prefs/" 2>/dev/null | while read -r f; do
        if [[ -n "${f}" ]]; then
            echo "  ${f}:"
            adb shell cat "/data/data/${PACKAGE}/shared_prefs/${f}" 2>/dev/null | sed 's/^/    /'
        fi
    done
}

check_permissions() {
    log_info "检查运行时权限..."
    adb shell dumpsys package ${PACKAGE} | grep -A 20 "runtime permissions:" | head -25
}

run_quick_test() {
    log_info "启动应用进行快速功能测试..."
    adb shell am start -n "${PACKAGE}/.MainActivity" --es browser_url "https://www.w3schools.com/html/mov_bbb.mp4"
    sleep 3
    log_ok "应用已启动，请手动验证嗅探下载和边下边播"
}

main() {
    echo "========================================"
    echo "  VidGrab 快速 ADB 验证"
    echo "========================================"
    echo

    check_device
    echo
    check_app_installed
    echo
    check_native_libs
    echo
    check_download_dirs
    echo
    check_browser_data
    echo
    check_shared_prefs
    echo
    check_permissions
    echo

    # 可选：启动应用测试
    read -p "是否启动应用进行嗅探/播放快速测试？(y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        run_quick_test
    fi

    echo
    echo "========================================"
    log_ok "快速验证完成"
    echo "========================================"
}

main "$@"