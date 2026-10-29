# VidGrab

一个基于 yt-dlp 的 Android 视频下载器，参考 Seal 与 SuperX 的逆向分析结果实现。

## 功能

- **链接下载**：粘贴任意 yt-dlp 支持的站点链接，解析标题/缩略图/格式后下载
- **yt-dlp 在线更新**：设置面板一键检查更新（官方 zipapp，经国内代理下载，失败自动切换源）
- **内置浏览器**：WebView 浏览网页，自动嗅探 `.m3u8` / `.mpd` / `.mp4` / 音频等媒体地址，一键解析下载
- **前进/后退**：工具栏返回按钮 + 系统返回键，按网页历史回退
- **历史与书签**：访问记录与收藏持久化保存（`filesDir/browser_data.json`），菜单面板中查看、跳转、删除
- **边下边播**：嗅探到的 HLS / DASH / MP4 可直接用 ExoPlayer 播放，缓冲播放、无需先下载；自动携带 Cookie/Referer/UA
- **广告拦截**：EasyList / EasyPrivacy / EasyList China 生成的约 10 万条域名规则（`assets/ad_rules.txt`）+ 人工补充列表 + 路径规则，支持第三方判定与例外放行；工具栏盾牌可一键开关
- **下载任务**：2 路并发队列、实时进度/速度/剩余时间、取消、打开文件、通知栏进度
- **持久化与续传**：任务列表落盘（应用被杀后可恢复），未完成任务重启后自动续传；失败/取消可一键重试；HLS 分片 4 路并发、自动重试 10 次
- **外部分享**：从其他 App 分享文本/链接可直接进入解析


## 落地页与视觉规范

- 落地页：`site/index.html`（单文件，无外部依赖；可直接部署到任意静态托管，或在 Gitee Pages 中把发布目录设为 `site`）
- 视觉规范：`docs/design/vidgrab-visual-spec.md`（色彩、字体、间距、组件、Compose 映射）

落地页面向中文 Android 用户，首屏用一张 CSS 绘制的嗅探面板示意 App 的核心交互：检测 HLS/DASH/MP4，按分片播放或下载。

## 构建

本仓库依赖本机工具链（默认放在 `..\.tools`）：

```bash
# JDK 21、Android SDK (platform 35 / build-tools 35.0.0)、Gradle 8.9
cd VidGrab
./build.sh assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

依赖通过阿里云 Maven 镜像下载，Gradle 用户目录指向 `..\.tools\gradle-home`，不会占用 C 盘。

## 架构

```
app/src/main/java/com/vidgrab/app/
├── App.kt                  应用初始化（通知渠道、引擎预热）
├── MainActivity.kt         三个页签 + 外部 Intent 处理
├── core/
│   ├── YtDlpEngine.kt      yt-dlp 封装：初始化 / 解析信息 / 执行下载
│   ├── DownloadManager.kt  下载队列（StateFlow + 2 路并发）
│   ├── DownloadService.kt  前台服务，保证后台下载存活
│   ├── Notifications.kt    通知栏进度
│   ├── MediaSniffer.kt     浏览器媒体嗅探与 JS 注入
│   └── Models.kt           数据模型
└── ui/                     Compose 界面（下载 / 浏览器 / 任务）
```

底层使用 [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.1（内置 CPython 3.12、yt-dlp 2025.11.12、FFmpeg、QuickJS），源码与 arm64 预编译库已并入本工程。

## 第三方规则

`assets/ad_rules.txt` 由 `tools/gen_adrules.py` 从以下列表生成（重新生成需联网下载规则）：

- [EasyList](https://easylist.to/)（GPLv3 / CC BY-SA 3.0）
- [EasyPrivacy](https://easylist.to/)（GPLv3 / CC BY-SA 3.0）
- [EasyList China](https://easylist.to/)（GPLv3 / CC BY-SA 3.0）

```bash
python tools/gen_adrules.py <规则目录> app/src/main/assets/ad_rules.txt
```

## 许可证

工程内含 GPL-3.0 的 youtubedl-android 代码与 FFmpeg 相关组件，因此本应用整体需按 **GPL-3.0** 分发。若需闭源，应改为自研 Python 进程封装并替换相应组件。
