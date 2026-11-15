# VidGrab

一个基于 yt-dlp 的 Android 视频下载器，参考 Seal 与 SuperX 的逆向分析结果实现。

介绍页：https://soft-hall-6e48.gjvgfffgh3.workers.dev/

当前版本：**1.4.30（versionCode 37，debug 包）**。版本演进：1.4.21 默认优先 H.264（修播放卡顿）、1.4.22 启动懒加载 + 启动图（冷启动 1.3s → 0.53s）、1.4.26 菜单新增「检测结果」入口、1.4.27 网络失败自动重试、1.4.28 重试策略单测 + 日志治理、1.4.29 续传进度不再回退、1.4.30 封面发布幂等。

## 功能

- **链接下载**：粘贴任意 yt-dlp 支持的站点链接，解析标题/缩略图/格式后下载
- **yt-dlp 在线更新**：设置面板一键检查更新（官方 zipapp，经国内代理下载，失败自动切换源）；内置 2026.10.26
- **内置浏览器**：WebView 浏览网页，自动嗅探 `.m3u8` / `.mpd` / `.mp4` / 音频等媒体地址，菜单「检测结果」或页面底部入口查看列表，可播放、下载、复制、分享
- **网页下载与上传**：网页触发的下载不再无响应（媒体转 App 下载、其它文件交系统下载器）；支持 `<input type=file>`、JS 对话框和第三方登录弹窗
- **加载失败提示**：网页打不开时显示原因和「重新加载」，不是一片空白
- **前进/后退**：工具栏返回按钮 + 系统返回键，按网页历史回退
- **历史与书签**：访问记录与收藏持久化保存（`filesDir/browser_data.json`），菜单面板中查看、跳转、删除
- **边下边播**：嗅探到的 HLS / DASH / MP4 可直接用 ExoPlayer 播放，缓冲播放、无需先下载；自动携带 Cookie/Referer/UA
- **广告拦截**：EasyList / EasyPrivacy / EasyList China 生成的约 10 万条域名规则（`assets/ad_rules.txt`）+ 人工补充列表 + 路径规则，支持第三方判定与例外放行；工具栏盾牌可一键开关
- **下载控制**：暂停/继续（保留 `.part` 续传）、可配置并发、仅 Wi‑Fi 下载、限速、通知栏暂停/继续/取消
- **失败自动重试**：超时 / 连接重置 / 读流中断 / 5xx 退避 3s→8s→20s 自动重试 3 次并保留 `.part`；403 / 404 / 需要登录 / 解析失败 / 地区限制不重试，直接给中文原因
- **批量与附加文件**：一次粘贴多条链接或播放列表批量入队；可选同时保存字幕（中/英）与封面；支持纯视频轨
- **保存目录**：默认公共 `Download/VidGrab`（Android 10+ 走 MediaStore，无需存储权限）；也可用 SAF 选自定义目录
- **任务页增强**：标题/链接搜索、按时间/状态/名称排序、失败原因展开与复制、标题与磁盘文件名不一致提示
- **播放器增强**：0.5x–2.0x 倍速、字幕轨切换、亮度/音量手势、画中画、断点续播（位置落盘，播完自动清除）
- **提示与反馈**：下载错误映射中文可操作建议；崩溃日志写入 `files/crash_log.txt`，设置页一键分享
- **持久化与续传**：任务列表落盘（应用被杀后可恢复），未完成任务重启后自动续传；HLS 分片并发、自动重试
- **外部分享**：从其他 App 分享文本/链接可直接进入解析

## 落地页与视觉规范

- 线上页面：https://soft-hall-6e48.gjvgfffgh3.workers.dev/
- 落地页源码：`site/index.html`（单文件，无外部依赖，可部署到任意静态托管）
- 视觉规范：`docs/design/vidgrab-visual-spec.md`（色彩、字体、间距、组件、Compose 映射）

## 构建

需要 JDK 21、Android SDK（platform 35 / build-tools 35.0.0）与 Gradle 8.9。工具链默认从仓库同级的 `.tools/` 读取（即 `../.tools`），也可用 `ANDROID_HOME`、`GRADLE_USER_HOME`、`VIDGRAB_TOOLS` 环境变量指定。

```bash
./build.sh assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

PowerShell（Windows）：

```powershell
$env:ANDROID_HOME = "$PWD\..\.tools\android-sdk"
$env:GRADLE_USER_HOME = "$PWD\..\.tools\gradle-home"
$env:JAVA_HOME = "B:\jdk-21.0.2"
& "..\.tools\gradle-8.9\bin\gradle.bat" -p . assembleDebug --no-daemon
```

依赖通过阿里云 Maven 镜像下载，Gradle 用户目录（构建缓存）默认放在仓库同级的 `.tools/gradle-home`，可用 `GRADLE_USER_HOME` 覆盖。

### 单 ABI 包（发布用）

默认产物包含 `arm64-v8a` 与 `x86_64` 两套原生库，约 141 MB；发布包只带 arm64，约 79 MB（Gitee 附件上限 100 MB）。

```bash
./build.sh clean assembleDebug -Pabi=arm64-v8a
```

**必须先 `clean`**：增量构建会保留上一次其他 ABI 的 `.so`，包体依旧是 141 MB。

Windows 下同理，在 `gradle.bat` 后面加 `clean assembleDebug -Pabi=arm64-v8a`。

## 测试

```bash
./build.sh testDebugUnitTest
```

目前覆盖下载重试判定（`RetryPolicyTest`，10 个用例）：哪些错误该自动重试、哪些不该、退避序列、重试次数上限。纯 JVM 测试，不需要模拟器或真机。

## 调试

vivo / 部分国产系统会屏蔽第三方 App 的 logcat，诊断信息统一写文件，仅 debug 包可用 `run-as` 读取：

```bash
adb shell run-as com.vidgrab.app cat files/vglog.txt          # Boot / Sniff / Flat / Download / Player 日志
adb shell run-as com.vidgrab.app cat files/crash_log.txt      # 崩溃记录（时间、版本、设备、堆栈）
adb shell run-as com.vidgrab.app cat shared_prefs/player_positions.xml   # 断点续播位置
adb shell run-as com.vidgrab.app cat files/download_tasks.json # 任务队列
```

- 启动耗时：`vglog.txt` 里的 `Boot: app init …ms` 与 `Boot: first frame …ms`
- 播放诊断：`Player: format=…` / `Player: dropped=… in …ms`
- 嗅探诊断：`Sniff: offer raw=… cls=…`（`verbose` 日志，仅 debug 构建落盘）
- 日志文件上限 8 MB，超过自动裁到 2 MB

## 已知限制

- **卸载 App 不会删除已下载的视频**：成品在公共 `Download/VidGrab`，归系统与用户所有，Android 不提供卸载回调。要保住公共目录，就只能接受这一点；需要清理时请在文件管理里手动删。
- **AV1 视频仍可能卡顿**：设备没有 AV1 硬解。1.4.21 起默认优先 H.264，新下载不会再拿到 AV1；已经下好的 AV1 文件需要重新下载。
- **安装/覆盖安装后第一次启动约 2.4 s**：系统对新 APK 做校验与 dex 优化，属系统成本；之后冷启动稳定在 0.5 s 左右。
- **字幕依赖源站**：只有站点本身提供 CC/自动字幕时才产出 `.srt`，没有字幕轨的视频不会凭空生成。

## 架构

```
app/src/main/java/com/vidgrab/app/
├── App.kt                     应用初始化（通知渠道、引擎预热、启动埋点）
├── MainActivity.kt            三个页签（首次切入才创建页面）+ 外部 Intent 处理
├── PlayerActivity.kt          ExoPlayer 播放器（手势、字幕、断点续播、丢帧诊断）
├── core/
│   ├── YtDlpEngine.kt         yt-dlp 封装：初始化 / 解析 / 格式选择 / 执行下载 / 附加文件
│   ├── DirectDownloader.kt    直链 HTTP 下载（Range 续传）
│   ├── DownloadManager.kt     下载队列（StateFlow + 并发闸门 + 自动重试）
│   ├── RetryPolicy.kt         失败重试判定与退避序列（可单测）
│   ├── DownloadService.kt     前台服务，保证后台下载存活
│   ├── Notifications.kt       通知栏进度与暂停/继续/取消
│   ├── DownloadStore.kt       任务队列落盘与恢复
│   ├── DownloadSettings.kt    并发 / 仅 Wi‑Fi / 限速 / aria2c / SAF 目录
│   ├── PublicStorage.kt       成品与附加文件发布到公共 Download（MediaStore / SAF）
│   ├── FileNames.kt           文件名清洗、占位标题识别、按标题重命名
│   ├── MediaSniffer.kt        浏览器媒体嗅探（请求拦截 + JS 注入）
│   ├── AdBlocker.kt           广告拦截规则匹配
│   ├── BrowserStore.kt        历史 / 书签持久化
│   ├── DiagLog.kt             文件诊断日志（debug 才写高频日志、8 MB 上限）
│   ├── ErrorMessages.kt       英文报错 → 中文可操作提示
│   └── Models.kt              数据模型
└── ui/                        Compose 界面（下载 / 浏览器 / 任务）
```

底层使用 [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.1（内置 CPython 3.12、yt-dlp 2026.10.26、FFmpeg、QuickJS），源码与 arm64 预编译库已并入本工程。

## 发布

| 平台 | 仓库 | 发行版 |
|---|---|---|
| Gitee（国内优先） | https://gitee.com/WSFSA123/vid-grab | `v1.4.21` 起 |
| GitHub（镜像） | https://github.com/96963/vidgrab | `v1.4.21` 起 |

发布附件用单 ABI（arm64）包，小于 Gitee 100 MB 上限；Release 正文与仓库文件统一 UTF-8、文件名不用中文，避免各平台转义成乱码。

## 第三方规则

`assets/ad_rules.txt` 由 `tools/gen_adrules.py` 从以下列表生成（重新生成需联网下载规则）：

- [EasyList](https://easylist.to/)（GPLv3 / CC BY-SA 3.0）
- [EasyPrivacy](https://easylist.to/)（GPLv3 / CC BY-SA 3.0）
- [EasyList China](https://easylist.to/)（GPLv3 / CC BY-SA 3.0）

```bash
python tools/gen_adrules.py <规则目录> app/src/main/assets/ad_rules.txt
```

## 许可证

工程内含 GPL-3.0 的 youtubedl-android 代码与 FFmpeg、aria2c 相关组件，因此本应用整体需按 **GPL-3.0** 分发，完整许可证文本见 [LICENSE](LICENSE)。
