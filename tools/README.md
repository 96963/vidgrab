# VidGrab 真机验证工具

## 目录结构

```
tools/
├── device_test.py       # 主验证脚本 (Python)
├── adb_verify.sh        # 快速 ADB 验证命令
└── README.md            # 本文件
```

## 快速开始

### 1. 构建并安装

```bash
# 构建 debug APK
./build.sh assembleDebug

# 安装到设备
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

### 2. 运行完整验证套件

```bash
# 运行所有测试 (需要 Python 3)
python tools/device_test.py

# 仅运行指定测试
python tools/device_test.py --test browser_persistence --test link_download

# 列出所有测试
python tools/device_test.py --list
```

### 3. 快速 ADB 验证命令

```bash
# 给脚本执行权限
chmod +x tools/adb_verify.sh

# 运行快速检查
./tools/adb_verify.sh
```

## 测试项对照表

| 测试 ID | 对应规划阶段 | 说明 |
|---------|-------------|------|
| `install` | Phase 1/5 | 安装与首次启动 |
| `cold_start` | Phase 4 | 冷启动恢复下载任务 |
| `browser_persistence` | **Phase 3.4** | 浏览器页签切换状态保留 |
| `link_download` | Phase 4 | 链接下载 (Bilibili 480p) |
| `sniff_download` | Phase 3/4 | 浏览器嗅探下载 |
| `edge_player` | Phase 3/4 | 边下边播 |
| `rename` | **Phase 4.5** | 重命名文件 (公共目录) |
| `batch_delete` | Phase 4 | 批量删除 (含文件) |
| `adblock` | Phase 3 | 广告拦截开关 |
| `history_bookmark` | Phase 3 | 历史记录与书签 |
| `yt_dlp_update` | Phase 4 | yt-dlp 在线更新 |
| `settings` | Phase 4 | 设置持久化 |
| `public_dir` | **Phase 4.5** | 公共目录权限 |
| `dark_mode` | Phase 2 | 深色模式适配 |
| `abi` | Phase 5 | ABI 支持 |

## 关键验证点

### Phase 3.4: 浏览器页签状态保留
- [ ] 访问 Bilibili 并播放视频
- [ ] 切换到「下载」页签
- [ ] 切回「浏览器」页签
- [ ] ✅ 页面不重载，视频进度保留

### Phase 4.5: 公共目录全流程
- [ ] 下载完成 → 文件出现在 `/sdcard/Download/VidGrab/`
- [ ] App 内重命名 → 文件系统文件名同步变更
- [ ] App 内打开 → 系统播放器/分享正常
- [ ] App 内删除 (含文件) → 文件系统文件被删除

### Phase 5: Release 就绪
- [ ] `./build.sh assembleRelease` 成功
- [ ] x86_64 模拟器运行正常 (如可用)
- [ ] 签名配置正确 (keystore.properties)

## 设备信息

根据 PROGRESS.md，目标测试设备：

| 设备 | 型号 | Android | 备注 |
|------|------|---------|------|
| Xiaomi | 24122RKC7C | 16 | 主力机，arm64-v8a |
| vivo | V2156A | 11 | 备用机，arm64-v8a |

## 常用 ADB 片段

```bash
# 查看应用日志
adb logcat -s "VidGrab:*" "*:E"

# 查看下载目录
adb shell ls -la /sdcard/Download/VidGrab/

# 查看私有目录
adb shell ls -la /data/data/com.vidgrab.app/files/VidGrab/

# 查看 browser_data.json (历史/书签持久化)
adb shell cat /data/data/com.vidgrab.app/files/browser_data.json

# 查看设置持久化
adb shell cat /data/data/com.vidgrab.app/shared_prefs/*.xml

# 强制停止应用
adb shell am force-stop com.vidgrab.app

# 清除应用数据
adb shell pm clear com.vidgrab.app

# 启动应用并打开指定 URL
adb shell am start -n com.vidgrab.app/.MainActivity --es browser_url "https://www.bilibili.com"
```

## 结果文件

验证完成后会生成 `device_test_results.json`，包含所有测试结果汇总。

## 故障排查

| 问题 | 解决 |
|------|------|
| `adb: command not found` | 配置 ANDROID_HOME 到 PATH，或使用完整路径 |
| `Python 编码错误` | 确保终端编码为 UTF-8 (Windows Terminal 推荐) |
| `设备未授权` | 手机上点击 "允许 USB 调试" |
| `安装失败 INSTALL_FAILED_VERSION_DOWNGRADE` | 先卸载旧版本: `adb uninstall com.vidgrab.app` |