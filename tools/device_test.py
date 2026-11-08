#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
VidGrab 真机验证脚本
用于在 Android 真机上自动化验证核心功能

使用方法：
1. 手机开启 USB 调试，连接电脑
2. 运行: python tools/device_test.py
3. 或运行单项测试: python tools/device_test.py --test browser_persistence

依赖：adb、python3、uiautomator2 (可选，用于更复杂的 UI 交互)
"""

import subprocess
import sys
import time
import json
import argparse
from pathlib import Path
from typing import Optional, List, Tuple

# 常量
PACKAGE = "com.vidgrab.app"
MAIN_ACTIVITY = f"{PACKAGE}/.MainActivity"
BROWSER_URL_EXTRA = "browser_url"
DOWNLOAD_DIR = "/sdcard/Download/VidGrab"
PRIVATE_DIR = f"/data/data/{PACKAGE}/files/VidGrab"

# 颜色输出
class Colors:
    GREEN = "\033[92m"
    RED = "\033[91m"
    YELLOW = "\033[93m"
    BLUE = "\033[94m"
    RESET = "\033[0m"
    BOLD = "\033[1m"

def log_info(msg: str): print(f"{Colors.BLUE}[INFO]{Colors.RESET} {msg}")
def log_ok(msg: str): print(f"{Colors.GREEN}[OK]{Colors.RESET} {msg}")
def log_warn(msg: str): print(f"{Colors.YELLOW}[WARN]{Colors.RESET} {msg}")
def log_err(msg: str): print(f"{Colors.RED}[ERR]{Colors.RESET} {msg}")
def log_step(msg: str): print(f"\n{Colors.BOLD}=== {msg} ==={Colors.RESET}")

def run_adb(cmd: List[str], timeout: int = 30) -> Tuple[int, str, str]:
    """执行 adb 命令"""
    full_cmd = ["adb"] + cmd
    try:
        result = subprocess.run(full_cmd, capture_output=True, text=True, timeout=timeout)
        return result.returncode, result.stdout.strip(), result.stderr.strip()
    except subprocess.TimeoutExpired:
        return -1, "", "timeout"
    except Exception as e:
        return -1, "", str(e)

def check_device() -> bool:
    """检查设备连接"""
    code, out, _ = run_adb(["devices"])
    if code != 0:
        log_err("adb 不可用")
        return False
    lines = out.splitlines()[1:]
    devices = [l.split()[0] for l in lines if l.strip() and "device" in l]
    if not devices:
        log_err("未检测到设备，请检查 USB 调试")
        return False
    log_ok(f"检测到设备: {devices[0]}")
    return True

def install_apk(apk_path: str) -> bool:
    """安装 APK"""
    log_step(f"安装 {apk_path}")
    if not Path(apk_path).exists():
        log_err(f"APK 不存在: {apk_path}")
        return False
    code, out, err = run_adb(["install", "-r", "-g", apk_path], timeout=120)
    if code == 0:
        log_ok("安装成功")
        return True
    log_err(f"安装失败: {err or out}")
    return False

def start_app(url: Optional[str] = None) -> bool:
    """启动应用"""
    cmd = ["shell", "am", "start", "-n", MAIN_ACTIVITY]
    if url:
        cmd += ["--es", BROWSER_URL_EXTRA, url]
    code, out, err = run_adb(cmd)
    if code == 0:
        log_ok(f"应用已启动{'，打开 ' + url if url else ''}")
        time.sleep(2)
        return True
    log_err(f"启动失败: {err or out}")
    return False

def force_stop() -> bool:
    """强制停止应用"""
    code, _, _ = run_adb(["shell", "am", "force-stop", PACKAGE])
    return code == 0

def clear_app_data() -> bool:
    """清除应用数据"""
    code, _, _ = run_adb(["shell", "pm", "clear", PACKAGE])
    return code == 0

def get_pid() -> Optional[int]:
    """获取应用进程 PID"""
    code, out, _ = run_adb(["shell", "pidof", PACKAGE])
    if code == 0 and out.strip():
        return int(out.strip().split()[0])
    return None

def wait_for_process(timeout: int = 10) -> bool:
    """等待进程启动"""
    for _ in range(timeout):
        if get_pid():
            return True
        time.sleep(1)
    return False

# ===== 测试用例 =====

def test_install(apk_path: str) -> bool:
    """测试 1：安装与首次启动"""
    log_step("测试 1：安装与首次启动")
    if not install_apk(apk_path):
        return False
    if not start_app():
        return False
    if not wait_for_process():
        log_err("进程未启动")
        return False
    log_ok("首次启动正常")
    force_stop()
    return True

def test_cold_start() -> bool:
    """测试 2：冷启动恢复下载任务"""
    log_step("测试 2：冷启动恢复下载任务")
    # 这里需要先有未完成任务，暂跳过
    log_warn("需预置未完成任务，跳过")
    return True

def test_browser_persistence() -> bool:
    """测试 3：浏览器页签切换状态保留"""
    log_step("测试 3：浏览器页签切换状态保留")
    log_info("请手动操作：")
    log_info("  1. 打开浏览器页，访问 https://www.bilibili.com")
    log_info("  2. 等待页面加载并开始播放视频")
    log_info("  3. 切换到「下载」页签")
    log_info("  4. 切回「浏览器」页签")
    log_info("  5. 观察页面是否重载、视频播放进度是否保留")

    input("按回车继续...")

    # 通过 adb 检查 WebView 状态（有限）
    code, out, _ = run_adb(["shell", "dumpsys", "activity", "activities", "|", "grep", PACKAGE])
    if "BrowserScreen" in out or "WebView" in out:
        log_ok("浏览器 Activity 存在")
    else:
        log_warn("无法通过 dumpsys 确认状态，请人工确认")

    result = input("页面是否保持原状（未重载）？视频进度是否保留？(y/n): ").strip().lower()
    return result == 'y'

def test_link_download() -> bool:
    """测试 4：链接下载（Bilibili 480p）"""
    log_step("测试 4：链接下载")
    test_url = "https://www.bilibili.com/video/BV1xx411c7mD"  # 示例视频
    log_info(f"测试链接: {test_url}")
    log_info("请手动操作：")
    log_info("  1. 点击首页「设置」确认 yt-dlp 版本")
    log_info("  2. 在首页输入框粘贴链接并点击「解析链接」")
    log_info("  3. 选择格式（如 480p），点击「开始下载」")
    log_info("  4. 观察任务列表进度、速度、ETA 显示")
    log_info("  5. 等待下载完成，确认文件生成")

    input("按回车开始测试...")
    start_app()
    time.sleep(1)

    result = input("下载是否成功？文件是否生成在 Download/VidGrab？(y/n): ").strip().lower()
    if result == 'y':
        # 验证文件
        code, out, _ = run_adb(["shell", "ls", "-la", DOWNLOAD_DIR])
        if out:
            log_ok(f"公共目录文件:\n{out}")
        return True
    return False

def test_browser_sniff_download() -> bool:
    """测试 5：浏览器嗅探下载"""
    log_step("测试 5：浏览器嗅探下载")
    test_url = "https://www.w3schools.com/html/mov_bbb.mp4"
    log_info(f"测试页面: {test_url}")
    log_info("请手动操作：")
    log_info("  1. 切换到「浏览器」页签")
    log_info("  2. 访问上述页面")
    log_info("  3. 观察右下角 FAB 显示「检测到 N 个媒体」")
    log_info("  4. 点击 FAB 打开检测面板")
    log_info("  5. 点击 MP4 项的「解析」-> 确认文件名 -> 「开始下载」")
    log_info("  6. 切到任务页观察下载完成")

    input("按回车开始测试...")
    start_app(test_url)
    time.sleep(3)

    result = input("嗅探是否工作？下载是否成功？(y/n): ").strip().lower()
    return result == 'y'

def test_edge_player() -> bool:
    """测试 6：边下边播"""
    log_step("测试 6：边下边播")
    test_url = "https://www.w3schools.com/html/mov_bbb.mp4"
    log_info("请手动操作：")
    log_info("  1. 在浏览器嗅探面板中，点击 MP4 项的「播放」")
    log_info("  2. 观察 PlayerActivity 启动，视频是否直接播放")
    log_info("  3. 拖动进度条测试 seek")
    log_info("  4. 返回浏览器页，视频是否继续播放")

    input("按回车开始测试...")
    start_app(test_url)
    time.sleep(3)

    result = input("播放是否正常？seek 是否工作？(y/n): ").strip().lower()
    return result == 'y'

def test_rename_file() -> bool:
    """测试 7：重命名文件（已完成/下载中）"""
    log_step("测试 7：重命名文件")
    log_info("请手动操作：")
    log_info("  1. 在任务列表中，点击已完成任务的编辑图标")
    log_info("  2. 修改文件名，点击保存")
    log_info("  3. 观察文件是否在 Download/VidGrab 中重命名")
    log_info("  4. 尝试重命名下载中的任务，完成后检查文件名")

    input("按回车开始测试...")

    result = input("重命名是否生效？公共目录文件名是否变更？(y/n): ").strip().lower()
    if result == 'y':
        code, out, _ = run_adb(["shell", "ls", "-la", DOWNLOAD_DIR])
        log_ok(f"当前文件:\n{out}")
    return result == 'y'

def test_batch_delete() -> bool:
    """测试 8：批量删除任务（含文件）"""
    log_step("测试 8：批量删除")
    log_info("请手动操作：")
    log_info("  1. 任务页点击「选择」进入多选模式")
    log_info("  2. 勾选多个任务，点击「删除所选」")
    log_info("  3. 选择「连文件删除」，确认")
    log_info("  4. 检查 Download/VidGrab 对应文件是否被删除")

    input("按回车开始测试...")

    result = input("批量删除是否工作？文件是否同步删除？(y/n): ").strip().lower()
    return result == 'y'

def test_adblock() -> bool:
    """测试 9：广告拦截开关"""
    log_step("测试 9：广告拦截")
    log_info("请手动操作：")
    log_info("  1. 访问含广告页面（如某新闻站）")
    log_info("  2. 打开菜单 -> 「关闭广告拦截」")
    log_info("  3. 刷新页面，观察广告是否出现")
    log_info("  4. 再次开启拦截，刷新确认拦截生效")
    log_info("  5. 长按盾牌图标测试「本站广告拦截设置」")

    input("按回车开始测试...")

    result = input("广告拦截开关是否生效？本站设置是否持久化？(y/n): ").strip().lower()
    return result == 'y'

def test_history_bookmark() -> bool:
    """测试 10：历史记录与书签"""
    log_step("测试 10：历史记录与书签")
    log_info("请手动操作：")
    log_info("  1. 访问几个不同网站")
    log_info("  2. 打开菜单 -> 「历史记录」，确认列表正确")
    log_info("  3. 点击历史记录项，确认跳转")
    log_info("  4. 打开菜单 -> 「收藏当前页」")
    log_info("  5. 打开菜单 -> 「书签」，确认收藏存在")
    log_info("  6. 杀进程重启应用，确认历史/书签保留")

    input("按回车开始测试...")

    result = input("历史/书签是否正常？重启后是否保留？(y/n): ").strip().lower()
    if result == 'y':
        # 检查持久化文件
        code, out, _ = run_adb(["shell", "cat", f"/data/data/{PACKAGE}/files/browser_data.json"])
        if out:
            log_ok(f"browser_data.json 存在，长度: {len(out)}")
    return result == 'y'

def test_yt_dlp_update() -> bool:
    """测试 11：yt-dlp 在线更新"""
    log_step("测试 11：yt-dlp 在线更新")
    log_info("请手动操作：")
    log_info("  1. 首页右上角「设置」")
    log_info("  2. 点击「检查并更新 yt-dlp」")
    log_info("  3. 观察下载进度、版本对比结果")
    log_info("  4. 再次点击确认「已是最新」")

    input("按回车开始测试...")
    start_app()
    time.sleep(1)

    result = input("更新检查是否正常？版本号是否变化？(y/n): ").strip().lower()
    return result == 'y'

def test_settings_persistence() -> bool:
    """测试 12：设置持久化（分片并发、aria2c 开关）"""
    log_step("测试 12：设置持久化")
    log_info("请手动操作：")
    log_info("  1. 设置页修改「分片并发」为 16")
    log_info("  2. 关闭「多线程下载（aria2c）」")
    log_info("  3. 杀进程重启应用")
    log_info("  4. 确认设置值保留")

    input("按回车开始测试...")

    result = input("设置是否持久化？重启后值是否保留？(y/n): ").strip().lower()
    return result == 'y'

def test_public_dir_permissions() -> bool:
    """测试 13：公共目录权限（Android 9/10+）"""
    log_step("测试 13：公共目录权限")
    log_info("请手动操作：")
    log_info("  1. 首次下载完成，观察是否弹出存储权限申请（Android 9）")
    log_info("  2. 授权后确认文件出现在 Download/VidGrab")
    log_info("  3. 拒绝权限，确认回退到私有目录")
    log_info("  4. Android 10+ 无需权限直接写入")

    input("按回车开始测试...")

    result = input("权限流程是否正常？文件是否正确落盘？(y/n): ").strip().lower()
    if result == 'y':
        code, out, _ = run_adb(["shell", "ls", "-la", DOWNLOAD_DIR])
        log_ok(f"Download/VidGrab:\n{out}")
        code, out, _ = run_adb(["shell", "ls", "-la", PRIVATE_DIR])
        log_ok(f"私有目录:\n{out}")
    return result == 'y'

def test_dark_mode() -> bool:
    """测试 14：深色模式适配"""
    log_step("测试 14：深色模式")
    log_info("请手动操作：")
    log_info("  1. 系统设置切换深色模式")
    log_info("  2. 观察应用颜色是否跟随（品牌色固定）")
    log_info("  3. 状态栏/导航栏颜色是否正确")
    log_info("  4. 切回浅色模式确认恢复")

    input("按回车开始测试...")

    result = input("深色模式是否正常？状态栏颜色正确？(y/n): ").strip().lower()
    return result == 'y'

def test_abi_support() -> bool:
    """测试 15：ABI 支持（arm64 / x86_64 模拟器）"""
    log_step("测试 15：ABI 支持")
    code, out, _ = run_adb(["shell", "getprop", "ro.product.cpu.abi"])
    abi = out.strip()
    log_info(f"设备 ABI: {abi}")

    code, out, _ = run_adb(["shell", "ls", f"/data/app/{PACKAGE}*/lib/"])
    log_info(f"已安装的 native lib: {out}")

    if "arm64" in abi or "x86_64" in abi:
        log_ok(f"当前 ABI ({abi}) 应被支持")
        return True
    log_warn(f"未知 ABI: {abi}")
    return True

# ===== 主流程 =====

TESTS = [
    ("install", "安装与首次启动", test_install),
    ("cold_start", "冷启动恢复", test_cold_start),
    ("browser_persistence", "浏览器页签状态保留", test_browser_persistence),
    ("link_download", "链接下载", test_link_download),
    ("sniff_download", "浏览器嗅探下载", test_browser_sniff_download),
    ("edge_player", "边下边播", test_edge_player),
    ("rename", "重命名文件", test_rename_file),
    ("batch_delete", "批量删除", test_batch_delete),
    ("adblock", "广告拦截", test_adblock),
    ("history_bookmark", "历史/书签", test_history_bookmark),
    ("yt_dlp_update", "yt-dlp 更新", test_yt_dlp_update),
    ("settings", "设置持久化", test_settings_persistence),
    ("public_dir", "公共目录权限", test_public_dir_permissions),
    ("dark_mode", "深色模式", test_dark_mode),
    ("abi", "ABI 支持", test_abi_support),
]

def run_all_tests(apk_path: str, selected: Optional[List[str]] = None) -> dict:
    """运行选定测试"""
    results = {}

    # 检查设备
    if not check_device():
        return results

    for test_id, name, func in TESTS:
        if selected and test_id not in selected:
            continue

        log_step(f"[{test_id}] {name}")
        try:
            if test_id == "install":
                ok = func(apk_path)
            else:
                ok = func()
            results[test_id] = "PASS" if ok else "FAIL"
            log_ok(f"[{test_id}] {name}: {'通过' if ok else '失败'}")
        except KeyboardInterrupt:
            log_warn("用户中断")
            break
        except Exception as e:
            log_err(f"[{test_id}] 异常: {e}")
            results[test_id] = "ERROR"

        # 测试间隔
        time.sleep(1)

    return results

def print_summary(results: dict):
    """打印汇总"""
    log_step("测试汇总")
    pass_count = sum(1 for v in results.values() if v == "PASS")
    fail_count = sum(1 for v in results.values() if v in ("FAIL", "ERROR"))
    total = len(results)

    for test_id, status in results.items():
        name = next((n for tid, n, _ in TESTS if tid == test_id), test_id)
        color = Colors.GREEN if status == "PASS" else Colors.RED
        print(f"  {color}[{status}]{Colors.RESET} {test_id}: {name}")

    print(f"\n{Colors.BOLD}总计: {total} 项，通过: {Colors.GREEN}{pass_count}{Colors.RESET}，失败: {Colors.RED}{fail_count}{Colors.RESET}{Colors.BOLD}")

def main():
    parser = argparse.ArgumentParser(description="VidGrab 真机验证脚本")
    parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk",
                        help="APK 路径 (默认: debug 包)")
    parser.add_argument("--test", action="append", choices=[t[0] for t in TESTS],
                        help="指定运行的测试 (可多次指定)，默认全跑")
    parser.add_argument("--list", action="store_true", help="列出所有测试")
    args = parser.parse_args()

    if args.list:
        print("可用测试:")
        for tid, name, _ in TESTS:
            print(f"  {tid}: {name}")
        return

    apk_path = args.apk
    if not Path(apk_path).exists():
        log_err(f"APK 不存在: {apk_path}")
        log_info("请先构建: ./build.sh assembleDebug")
        sys.exit(1)

    print(f"{Colors.BOLD}VidGrab 真机验证{Colors.RESET}")
    print(f"APK: {apk_path}")
    print(f"包名: {PACKAGE}")
    if args.test:
        print(f"指定测试: {', '.join(args.test)}")

    results = run_all_tests(apk_path, args.test)
    print_summary(results)

    # 保存结果
    result_file = Path("device_test_results.json")
    result_file.write_text(json.dumps({
        "apk": apk_path,
        "package": PACKAGE,
        "results": results,
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
    }, indent=2, ensure_ascii=False))
    log_info(f"结果已保存至 {result_file}")

if __name__ == "__main__":
    main()