# VidGrab 开发进度

更新：2026-11-15（当前版本 1.4.21 / versionCode 28；P1 真机验证基本完成；播放卡顿已定位并修复（AV1 无硬解 → 默认优先 H.264，实测零丢帧）；保存位置按要求固定为公共 Download/VidGrab；P0 推 Gitee/发行版、P2 四项未开始）

> 明天继续的完整未完成清单与问题见 [`TODO.md`](TODO.md)。


## 目标
逆向 Seal 与 SuperX 两个 Android 下载器，做一个自己的 Android 下载 App。
已确定：Android 原生（Kotlin + Compose），首版功能 = yt-dlp 站点解析下载 + 内置浏览器嗅探。

## 环境
| 组件 | 位置 |
|---|---|
| JDK 21 | 本机已安装，`JAVA_HOME` 指向 |
| Android SDK（platform 35 / build-tools 35.0.0） | `.tools/android-sdk` |
| Gradle 8.9 | `.tools/gradle-8.9` |
| Gradle 用户目录（构建缓存） | `.tools/gradle-home` |
| youtubedl-android 0.18.1 源码 | `.tools/src/youtubedl-android-0.18.1` |
| 反编译结果 | `.tools/re-out/seal`、`.tools/re-out/superx` |
| 工程 | 仓库根目录（包名 `com.vidgrab.app`） |

`.tools/` 是仓库外的工具目录（与仓库同级），不随仓库分发；也可用 `VIDGRAB_TOOLS` 等环境变量改到别处。

下载源优先国内：阿里云 Maven、腾讯 Gradle 镜像、清华 PyPI、ghfast.top 代理 GitHub。

## 已完成
- [x] 逆向分析两个 APK（架构见下）
- [x] 安装 Android SDK / Gradle
- [x] 工程骨架：Gradle 配置、Manifest、基础资源
- [x] 并入 youtubedl-android 0.18.1 源码与 arm64 jniLibs（Python 3.12、内置 yt-dlp 2026.10.26、FFmpeg、QuickJS）
- [x] Kotlin 业务：yt-dlp 封装、下载队列/前台服务/通知、Compose 三页、WebView 嗅探（含 Cookie/Referer/UA 透传）
- [x] 编译 APK：`dist/VidGrab-1.0.0-debug-arm64.apk`（package `com.vidgrab.app`，targetSdk 35，仅 arm64-v8a，debug 签名）
- [x] APK 静态校验：包名/权限/ABI/签名/内置库均正常

## 真机验证（Xiaomi 24122RKC7C，Android 16，arm64）
- [x] 安装与首次启动：Python 3.12 / FFmpeg / yt-dlp 正确解压到 no_backup
- [x] 链接下载：bilibili 视频解析出 480p/360p，下载生成 10.3MB MP4，任务显示「已完成」
- [x] 浏览器嗅探：W3Schools 页面识别出 HLS / MP4 / OGG，FAB 显示检测数量
- [x] 嗅探→解析→下载：mov_bbb.mp4 解析并下载成功（788KB）
- [x] 修复崩溃：`shouldInterceptRequest` 后台线程调用 WebView 方法导致 `A WebView method was called on thread 'ThreadPoolForeg'`，改为 PageState 快照
- [x] 修复体验：切换页签后浏览器回到首页，改为记住最后访问的 URL
- [x] 新增：浏览器后退按钮 + 系统返回键回退历史
- [x] 新增：广告拦截（`assets/ad_hosts.txt` 域名黑名单 + 路径规则 + 可疑标签启发式），工具栏盾牌开关，实测拦截日志正常、正常网页不受影响
- [x] 拦截增强：引入 EasyList / EasyPrivacy / EasyList China，生成约 9.6 万条域名规则 + 5800 条第三方规则 + 573 条例外；匹配改为域名后缀逐级查表，支持第三方判定
- [x] 新增边下边播：Media3 ExoPlayer（HLS/DASH/MP4），嗅探列表每项加「播放」，真机验证 MP4 与 m3u8 均可播放；请求头复用嗅探结果
- [x] 图标修复：改用 Adaptive Icon（背景/前景/monochrome）+ 五档 PNG + roundIcon，通知栏改用单色 `ic_stat_download`，桌面显示正常


## 第 5 批 P0 修复（2026-11-14，v1.3.0 / versionCode 5）

本轮只做“会直接出错/让用户困惑”的 P0 项，编译通过，**尚未真机验证**（当前没有连接设备）。

- [x] 浏览器下载监听：网页里的下载按钮/附件链接不再无反应
  - 媒体文件（video/audio、常见媒体扩展名）转到下载页，按直链下载处理
  - 非媒体（zip/pdf 等）交给系统 DownloadManager，落到 `Download/VidGrab`
- [x] WebChromeClient：`<input type=file>` 文件选择器、JS `alert/confirm/prompt` 对话框恢复
- [x] 多窗口：`window.open` / `target=_blank` 由独立 WebView 弹窗承载（用户点击才弹，广告自动弹被挡），与主页面共享 Cookie，第三方登录可用
- [x] 外部 scheme：`tel:` / `mailto:` / `intent://` 交给系统应用打开，没有应用可处理时给出提示（原先静默拦截）
- [x] 网页加载失败：`onReceivedError` / `onReceivedHttpError` 显示自定义错误页 + 「重新加载」
- [x] 通知：每个任务一条独立通知（原先共用一个 id，只看得到最后一个任务）
  - 点通知回到「任务」页；通知栏直接「取消」任务；进度按 1.2 秒节流刷新（原先只在开始推一次）
  - 完成/失败/取消/删除任务时清理对应通知
- [x] 公共目录落盘：失败不再静默回退
  - `PublishResult` 带失败原因，任务卡片显示在「已完成」下方
  - 复制前检查剩余空间（源文件 + 16MB），不足时保留私有文件并提示
  - 复制过程上报进度，任务显示「处理中 x% · 正在保存到公共目录」
  - 写完用 `ParcelFileDescriptor.statSize` 校验长度，不完整则删掉 pending 条目并报错
- [x] 文件名：新增 `FileNames.publicFileName`，发布到公共目录时先清洗标题再拼扩展名，避免 80 字节截断把 `.mp4` 截掉
- [x] `DirectDownloader`：重命名失败时不再把 `.part` 当成品返回；改为 copy+delete 兜底，仍失败抛 `IOException`
- [x] Android 15 前台服务：`DataSync` 类型改用 `ServiceCompat.startForeground`，实现 `Service.onTimeout`（约 6 小时上限）——停止前台服务、把进行中任务标为可重试错误并保留 `.part`
- [ ] 待真机验证：浏览器下载/上传/弹窗、通知取消、公共目录失败提示、Android 15 超时行为

构建：`assembleDebug` BUILD SUCCESSFUL（38s）；产物 `dist/VidGrab-1.3.0-debug-universal.apk`（141,440,212 B，SHA-256 `5EA69F3D…59307728F`），debug 签名、arm64-v8a + x86_64。


## 第 6 批 内置 yt-dlp 升级（2026-11-14，v1.3.1 / versionCode 6）

- [x] 内置 `res/raw/ytdlp` 从 `2025.11.12` 升到 `2026.10.26`（官方 zipapp，3,073,072 B，经 `ghfast.top` 代理下载并校验 `yt_dlp/version.py`）
- [x] `YtDlpUpdater.installedVersion` 的兜底从 `assets.open("ytdlp")` 改为 `res/raw/ytdlp`（原路径资源不存在，首次启动前会读不到内置版本）
- [x] 新增 `YtDlpUpdater.syncBundled`：`YtDlpEngine.ensureReady` 初始化前比较内置与已解压版本，只有内置更新时才覆盖 `no_backup/youtubedl-android/yt-dlp/yt-dlp`，不会把用户在线更新过的新版换回旧版
- [x] 通知通道改为 `downloads_v2`（IMPORTANCE_DEFAULT + 静音/无震动/无角标，priority DEFAULT）；原 `downloads` 为 LOW，MIUI/HyperOS 会归入“静默通知”并隐藏“取消”按钮
- [x] “下载文件名”确认机制按用户要求保留两次点击（打开弹窗 + 弹窗内确认）

构建：`assembleDebug` BUILD SUCCESSFUL（17s）；产物 `dist/VidGrab-1.3.1-debug-universal.apk`（141,234,992 B，SHA-256 `99ADFCC1E5B60AF41A3D08C8782FDA1998E8F0012E0C07094AE10F758A1BF929`），versionCode 6 / 1.3.1，debug 签名，arm64-v8a + x86_64。

真机（Xiaomi 24122RKC7C / Android 16 / arm64）：
- [x] 覆盖安装 1.3.1 成功，应用数据目录保留；设置页显示「yt-dlp 版本：2026.10.26」
- [x] 升级同步：安装前已解压文件为 2025.11.12（3,170,726 B），启动后变为 2026.10.26（3,073,072 B）
- [x] yt-dlp 路径真实下载：本地 32MB MP4 经 yt-dlp 解析（最佳画质）下载并发布到 `Download/VidGrab/127.0.0.1 20261114-1813.mp4`（33,554,432 B，MediaStore `_id=1000038847`）
- [x] 通知栏进度、完成通知正常，点通知回任务页
- [x] 通知“取消”：`dumpsys notification` 确认 `actions=1 "取消"`；用 shell 广播触发同一个 `NotificationActionReceiver` 后，任务变为 CANCELED（进度 38.3%）、通知消失、`.part` 保留，任务页显示「已取消」
- 限制：MIUI 通知卡片不提供展开/操作区手势，通知栏按钮本身未手动点击；接收器与清理链路已验证。`run-as` 以应用 UID 执行 `am broadcast` 被 `INTERACT_ACROSS_USERS` 拒绝，shell 广播可送达（`result=0` 是结果码，不是送达数）


## 第 7 批 P1（2026-11-14，v1.4.0 / versionCode 7）

目标：登录态复用、下载控制、批量/播放列表、保存目录与附加文件、中文错误提示、直链多连接。

- [x] Cookie/登录态：新增 `CookieStore`，按 host 持久化 WebView Cookie；嗅探、播放器、yt-dlp、直链都复用
- [x] 暂停/继续：`TaskState.PAUSED`，暂停时取消 job / 直链 / yt-dlp 进程并保留 `.part`，继续走 `--continue`
- [x] 并发数可配置（2/3/4，默认 2），运行中任务不受影响
- [x] 仅 Wi‑Fi：排队前无 Wi‑Fi 置 PAUSED，回 Wi‑Fi 自动恢复；下载途中切流量暂停并保留 `.part`
- [x] 限速：0/512/1024/2048/4096 KB/s；yt-dlp `--limit-rate`，直链单连接与多连接均节流
- [x] 通知栏暂停/继续/取消按钮（`NotificationActionReceiver`）
- [x] 批量/播放列表：按空白拆分多条链接；`--flat-playlist --dump-json` 解析播放列表，最多 200 条
- [x] 保存目录：`OpenDocumentTree` + 持久化 URI 权限，`PublicStorage` 支持 SAF 创建/写入/校验/重命名/删除
- [x] 字幕/封面：下载前勾选“同时保存字幕”“同时保存封面”，srt/vtt/ass/jpg/png/webp 一并发布到公共目录
- [x] 纯视频轨选项：`bestvideo/best`，适合剪辑
- [x] 中文错误提示：登录/403/404/解析器过期/超时/地区/年龄/版权/空间不足，失败卡片可一键更新 yt-dlp 后重试
- [x] 直链 4 连接：≥8MB、HTTP 200 且 `Accept-Ranges: bytes` 时 4 段并发；失败/取消删除 `.part` 重下，不支持断点续传
- [x] 真机验证（vivo V2156A / Android 11 / arm64-v8a，序列号 `35059217030026B`）：
  - [x] 设置：分片并发 4/8/16 可切；同时下载数 2/3/4 切到 3；仅 Wi‑Fi 打开；限速切 512 KB/s；`/data/data/com.vidgrab.app/shared_prefs/download_settings.xml` 实际写入 `speed_limit_kbps=512`、`wifi_only=true`、`concurrent=3`
  - [x] 暂停/继续：任务状态 `DOWNLOADING → PAUSED → DOWNLOADING`，界面进度与按钮同步
  - [x] `.part` 保留：暂停后外部目录 `.part` 18,366,464 B / 22,298,624 B 仍在；取消后 `.part` 也保留
  - [x] Range 续传：测试服务新增 `/range-slow.mp4`（206 + `Content-Range`）；暂停时 `.part` 32,019,456 B，继续后 32,576,512 B，任务最终 `DONE`，确认不是从头重下
  - [x] 仅 Wi‑Fi：关闭 Wi‑Fi 后新任务置 `PAUSED`，`warning=仅 Wi‑Fi 下载已开启，等待 Wi‑Fi`；重开 Wi‑Fi 并回到前台后任务恢复 `DOWNLOADING`
  - [x] 文件名双确认：主按钮 → 弹窗 → “开始下载”，两次点击；弹窗含字幕/封面复选框，可勾选
  - [x] 中文错误：`404` 显示“视频不存在或已被删除（404），请检查链接是否有效”
  - [x] 直链：32 MB `/range.mp4` 走直链通道完成，落到 `Download/VidGrab`，任务 `DONE`；4 连接路径由代码与完成任务验证（未抓包计数）
  - [x] 通知栏：任务通知 actions=2（暂停/取消），服务通知存在；PendingIntent 已确认
  - [x] 通知栏按钮手动点击（2026-11-15 补验，1.4.3）：展开通知点「暂停」→ `PAUSED` + warning `已暂停` + `.part` 停止增长（5,729,092 B），按钮变「继续」；点「继续」→ `DOWNLOADING`；点「取消」→ `CANCELED`、任务通知清除、`.part` 保留
  - [x] 批量多链接真实入队（2026-11-15 补验，1.4.3）：切到 vivo 输入法后 ADB 输入正常；两条本地直链经「批量下载多个链接 / 播放列表」弹窗入队，拆成 2 个任务（`createdAt` 相差 123ms，同时启动），均 `DONE`：69.9 KB + 545.4 KB，同名自动加「 (1)」后缀不互相覆盖；MP4 解析确认（本地源无音轨，仅 vide+meta）
  - [x] SAF 保存目录（2026-11-15 补验，1.4.3）：vivo DocumentsUI 对内部存储根/`Download` 根拦截「无法使用此文件夹」（系统限制）；选「视频」根成功并持久化 `save_tree_uri`；`real.mp4` 经 SAF 写入 `/sdcard/视频/127.0.0.1 20261115-0831.mp4`（71,568 B，`filePath` 为 tree/document URI），任务 DONE；任务页「同时删除文件」（默认勾选）删除任务后文件同步消失；「恢复默认」清空 `save_tree_uri` 回到 `Download/VidGrab`
  - 限制/未完成：播放列表自动展开未验证（可用 B 站合集/多 P 或 `seriesdetail?sid=` 单条 URL）；SAF 位置下任务卡片仍显示「Download/VidGrab」前缀（显示层小问题）；本地源无字幕轨，字幕/封面只验证勾选项与任务字段（B 站封面发布已在 1.4.3 验证）
  - ⚠ 事故：SAF 测试期间 vivo 文件管理将用户目录 `/sdcard/视频/RJ01137048` 移入回收站（当日唯一新条目），已从回收站移回原位并核对完好；回收站界面可能残留幽灵记录。教训：vivo DocumentsUI 内避免快速连续 swipe，SAF 测试后核对 `/sdcard/视频/` 与 `.vivoFileRecycleBin/`
  - 教训：ADB `input text` 在 Fcitx5 下会被拼音组合改写（`aaa%nbbb` → “啊啊啊%”），ADB 输入前先 `ime set com.vivo.ai.ime/.main.IMEService`

构建：`assembleDebug` BUILD SUCCESSFUL（31s）；产物 `dist/VidGrab-1.4.0-debug-universal.apk`（141,201,416 B，SHA-256 `13EC57510FE87C9705B4E96408E222AB333736CE7C462B78D70215611C39DE3A`），versionCode 7 / 1.4.0，debug 签名，arm64-v8a + x86_64。


## 第 8 批 P2（2026-11-14，v1.4.1 / versionCode 8）

目标：浏览器页签媒体暂停、嗅探结果操作与筛选、播放器增强、任务页检索/排序/错误复制、崩溃日志与一键反馈。

- [x] 浏览器页签媒体处理：1.4.1 原方案“离开页签暂停、回来自动 play 恢复”在真机复测中恢复失败；1.4.2 改为页签切换不打断网页媒体，视频在后台页签继续缓冲/播放，切回无需重新 play()，避免自动播放策略拒绝恢复；App 退后台仍暂停 WebView
- [x] 嗅探结果新增“全部/HLS/DASH/视频/音频”筛选、复制链接、分享链接
- [x] 检测日志“复制全部”，格式 `HH:mm:ss [来源] 结果 URL`
- [x] 播放器：0.5x–2.0x 倍速、字幕轨开关/切换、左侧上下滑调亮度、右侧上下滑调音量、画中画（按钮 + 返回桌面自动进入）、按 URL 记录断点续播
- [x] 任务页：标题/链接搜索、最新/最早/状态/名称排序、失败原因展开/收起/复制、“磁盘文件名”与标题不一致提示
- [x] `CrashReporter`：未捕获异常写入 `files/crash_log.txt`；设置页“反馈 / 导出崩溃日志”分享日志、版本与设备信息
- [x] 真机验证（vivo V2156A / Android 11，1.4.1 覆盖安装 1.4.0，数据保留）：
  - [!] 浏览器切页：1.4.1 实测切到任务页变 `PAUSED`，切回浏览器未自动恢复，仍需手动点播放；该问题在 1.4.2 改为“切页不断播”后解决（见下节）
  - [x] 嗅探筛选：`全部/HLS/DASH/视频/音频` 正常；选“音频”显示“暂无该类媒体。”
  - [x] 嗅探日志：`日志` 面板有 `清空 / 复制全部`，条目显示来源、URL、`20:09:20` 时间
  - [x] 播放器：有效 `screenrecord` MP4 可正常播放；倍速 `1.0x → 1.25x`；无字幕轨提示“当前视频没有字幕轨”；`画中画` 成功进入 pinned PiP；右侧上下滑后 `STREAM_MUSIC` 由 `Muted: true` 变 `false`
  - [x] 任务页：搜索“range”过滤到 URL 命中的任务；`最新/最早` 排序生效；失败任务红字中文错误、`展开/收起`、`复制错误` 均可用；`磁盘文件名: ...` 不一致提示显示
  - [x] 反馈：设置页 `反馈 / 导出崩溃日志` 拉起分享面板，标题 `VidGrab 反馈`、副标题 `版本：1.4.1 (8)`
  - 限制/未完成：左侧亮度手势未读亮度值；断点续播未做退出重进对比；未制造真实崩溃，`files/crash_log.txt` 生成链路未验证；嗅探复制/分享未逐一点击目标

构建：`assembleDebug` BUILD SUCCESSFUL（32s）；产物 `dist/VidGrab-1.4.1-debug-universal.apk`（141,228,520 B，SHA-256 `EE29C4B4786772E1E0177A46071194697CCF55815B8D24D2792BCA20DFF8D85D`），versionCode 8 / 1.4.1，debug 签名，arm64-v8a + x86_64。


## 第 9 批 P2 修复（2026-11-14，v1.4.2 / versionCode 9）

问题：1.4.1 的浏览器切页方案在真机上切到任务页后媒体变 `PAUSED`，切回浏览器不自动恢复；`play()` 受 WebView 自动播放策略/页面生命周期影响，无法稳定自动恢复。

修复：页签切换不再暂停/静音网页媒体，WebView 保持当前可见性状态，视频在后台页签继续播放/缓冲；切回浏览器时仍是 `PLAYING`，无需重新调用 `play()`，也就不存在“恢复失败”。App 真正退到后台时仍通过 `WebView.onPause()` 暂停，回前台 `onResume()`。

- [x] `BrowserViewModel` 移除页签 `setActive` 的 JS 暂停/恢复逻辑，新增 `pauseWebView()` / `resumeWebView()` 仅供 App 前后台生命周期使用
- [x] `BrowserScreen` 移除 `LaunchedEffect(isActive)` 的切页暂停，仅保留 `LifecycleEventObserver` 的 ON_STOP/ON_START
- [x] `MainActivity` 不再用页签变化驱动浏览器暂停
- [x] 真机验证（vivo V2156A / Android 11）：测试页 30 秒视频播放到 `PLAYING t=2.4 muted=0`，切到任务页 2 秒再切回浏览器，仍为 `PLAYING`、`muted=0`（最终清理构建实测 `PLAYING t=7.7 muted=0`），无 `PAUSED`、无需手动点播放
- 说明：这是“切页不断播”方案，不是“切页暂停再自动恢复”；若后续需要离开页签静音/暂停，需要换成用户手势触发的恢复入口，WebView 自动 `play()` 在真机上不可靠

构建：`assembleDebug` BUILD SUCCESSFUL（22s）；产物 `dist/VidGrab-1.4.2-debug-universal.apk`（141,229,104 B，SHA-256 `D4ED27B0080D633C607D7FC049651718FC58FCBC10D3B04879B5F8C1DC342E6B`），versionCode 9 / 1.4.2，debug 签名，arm64-v8a + x86_64；已安装到 vivo 验证。


## 第 10 批 P1 修复（2026-11-14，v1.4.3 / versionCode 10）

问题：B 站页面地址下载报 `ERROR: The extracted extension ('html') is unusual...`。原因是 App 给 yt-dlp 强制注入了 WebView 的移动端 UA，且 B 站页面被规范成 `m.bilibili.com`；移动 UA 下 m 站返回移动页，yt-dlp 退回 generic，只识别出一个 html 格式。PC 用 yt-dlp 默认桌面 UA 解析同一地址正常。

修复：

- [x] `CookieStore.headersFor(url, includeDefaultUserAgent)` 增加开关；yt-dlp 调用时不再注入 App 的移动 UA，直链下载/播放器仍保留移动 UA
- [x] `YtDlpEngine.normalizeUrl()`：把 `m.bilibili.com/video/...`、`bilibili.com/video/...` 统一成 `www.bilibili.com/video/...`；解析、播放列表展开、下载和写入任务的 `webpageUrl` 都走这个规范地址
- [x] 下载任务仍保留用户输入的原始 URL，失败重试时由引擎内部规范，不用迁移旧任务数据

真机验证（vivo V2156A / Android 11）：

- [x] `BV1v4Wr6uEoG` 解析成功：真实标题、UP 主「大圆镜科普」、时长 `06:27`、封面，以及 `1920p 74.4 MB / 1280p 35.6 MB / 852p 20.1 MB / 640p 13.4 MB` 四档格式；任务 URL 已是 `https://www.bilibili.com/video/BV1v4Wr6uEoG`
- [x] 最佳画质（自动合并）下载完成：视频轨 `30,894,926 B` + 音频轨 `4,917,315 B`，合并后 `36,757,053 B`，发布为 `Download/VidGrab/怪诞虫：一只看起来活不过三天的动物，真的存在过？.mp4`；拉回文件解析 MP4 box，确认同时含 `vide` 与 `soun` 两条轨道
- [x] 61 秒短测试 `BV1BjWq68Ee2` 合并完成：`4,170,044 B`，同样确认 `vide + soun` 双轨，说明不是单个视频的偶然
- [x] 暂停/继续：长任务 87% 时暂停，任务变 `PAUSED`；继续后从 `.part` 继续，最终完成。B 站 CDN 在 96% 尾部出现限速（约 3–6 KiB/s），随后一次 `网络连接失败或超时`，点重试后完成；属于 CDN 侧限速/断连，下载器已保留 `.part`
- [x] 失败重试/封面发布：`BV12oWi6VEz7` 第一次在音频阶段网络超时，任务 `ERROR`；清掉旧封面后点重试，任务 `DONE`，合并成品 `12,853,793 B`，MP4 解析同样为 `vide + soun` 双轨；新生成封面 `1,974,215 B` 成功发布到 `Download/VidGrab/根本没有这样的夹子 [BV12oWi6VEz7].jpg`
- [x] 浏览器路径回归：在 1.4.2 上从 B 站播放器嗅探到 360P CDN 直链并跳到下载页，完成 `17,497,778 B` 的 video-only MP4；1.4.3 的页面地址下载则会自动合并音轨
- [x] 字幕：B 站这些源没有 CC 字幕轨，勾选字幕后不会产出 srt，和预期一致；封面、合并、任务状态均不受影响
- 已知边角：若重试前同一视频的封面 jpg 已存在于临时目录，`publishExtras` 的 `before` 过滤会把它当成旧文件跳过，公共目录拿不到封面；本次通过清掉旧封面复测已确认新封面能正常发布，后续可单独修这个幂等问题
- 已知展示问题：暂停后继续，yt-dlp 回调会先把进度重置到 0 再继续上涨；`.part` 没丢，最终成品正常

构建：`assembleDebug` BUILD SUCCESSFUL（30s）；产物 `dist/VidGrab-1.4.3-debug-universal.apk`（141,228,060 B，SHA-256 `C19C2D1DCC02E55D730743FDC695A03F62297A12F7675C4185817A8C10856429`），versionCode 10 / 1.4.3，debug 签名，arm64-v8a + x86_64；已安装到 vivo 验证。


## 第 4 批「切页签不丢状态 + 公共目录全流程」真机验证（2026-11-08，vivo V2156A / Android 11 / arm64-v8a）

本次用 adb + 截图驱动真机做端到端验证，结论如下：

完成后并验证：
- [x] 安装与启动：`app-debug.apk` 安装成功，native 库齐全（libpython/libffmpeg/libaria2c/libqjs 等）
- [x] 浏览器嗅探：w3schools `html5_video.asp` 识别出 VIDEO(mp4) + AUDIO(ogg) 两项，FAB 显示「检测到 2 个媒体」
- [x] 嗅探→解析→下载：`mov_bbb.mp4` 解析成功，下载 770KB，落盘 `/sdcard/Download/VidGrab/`（`w3schools.com 20261108-1130.mp4`）
- [x] 公共目录重命名：任务卡片改名后，文件系统同步变为 `vidgrab-rename-test.mp4`，大小不变
- [x] 公共目录打开：通过 `content://` URI 交给系统播放器正常播放
- [x] 公共目录删除（含文件）：确认删除后 `Download/VidGrab` 目录清空
- [x] 浏览器→下载联动：嗅探面板「解析」跳回下载页并预填 URL 自动解析
- [x] 无崩溃/异常：logcat 中无 `AndroidRuntime`/FATAL（`vivocameraserver`/`AuthPII` 为系统无关记录）

**关键缺陷与修复：切换页签后浏览器页面重载、滚动与视频进度丢失**
- 现象：在视频页播放后切到「任务」再切回，页面回到顶部、进度归零。
- 根因：`MainActivity` 用 `when(tab)` 渲染，切页签时 `BrowserScreen` 离开组合，`AndroidView` 被销毁；而 `BrowserScreen` 的 `factory` 每次都 `WebView(ctx)` 新建，并未复用 `BrowserViewModel.webView`，导致重新 `loadUrl` 丢失状态。
- 修复：`MainActivity` 改为三个页面**始终常驻组合**，仅把非活动页用 `graphicsLayer{ translationX = ±100000f }` 移出屏幕并用 `zIndex` 控制命中顺序；`BrowserScreen` 增加 `isActive` 参数，`BackHandler` 仅在浏览器页激活时生效，避免其它页签误拦截返回键。
- 复验：同一视频页播放后切「任务」再切回，滚动位置保留、视频继续播放至 `0:10/0:10`、嗅探结果仍在——状态保留通过。


## 第 1 批「抓得到、不被墙」进度（2026-10-29）
已完成并真机验证：
- [x] 检测日志（来源/结果/时间），入口在嗅探列表标题栏
- [x] 站点级广告拦截开关（长按盾牌，按主域持久化，禁用/恢复均验证）
- [x] 查询参数 m3u8 误报收窄（HLS.js 演示页不再被当成媒体）
已实现并验证（vivo V2156A / Android 11）：
- [x] XHR/fetch 响应内容嗅探：本地页 `stream.txt` 返回 #EXTM3U，early（页面加载阶段）和 late（3 秒后）都被识别为 HLS
- [x] 关键修复：document-start 注入（WebViewCompat.addDocumentStartJavaScript）+ bridge 惰性获取，解决加载阶段请求漏抓
- [x] 请求拦截路径回归：本地 `local.m3u8` 正确识别
- [x] 工具栏常驻「检测日志」按钮（无检测结果时也能打开）
- [x] 调试入口：`am start --es browser_url <url>` 可直接让浏览器打开指定页面（自动化测试用）

已实现未单独验证：
- [ ] Origin 请求头透传
- [ ] 非 http/https 主框架跳转拦截

已知限制：
- document-start 需要 WebView 支持 DOCUMENT_START_SCRIPT（现代 WebView 均支持）；不支持时退回 onPageFinished 注入，加载阶段请求会漏抓
- vivo 会过滤 Log.d/w，调试日志改用应用内检测日志


## 第 2 批「下得稳」进度（2026-10-29，vivo V2156A / Android 11）
已完成并真机验证：
- [x] 任务持久化：`filesDir/download_tasks.json`，保存状态/进度/文件路径；已完成的下载历史重启后仍在
- [x] 断点续传：yt-dlp `--continue`；直链下载改为 `.part` + HTTP Range 续传
- [x] 自动恢复：应用被杀后重启，未完成任务自动重新入队（实测 `.part` 2.09MB → 自动续传 → 40MB 完成）
- [x] 手动重试：失败/取消任务显示「重试」，从 `.part` 继续（实测 2.09MB → 40MB）
- [x] 并发与重试：`--concurrent-fragments 4`、`--retries 10`、`--fragment-retries 10`
- [x] 修复：恢复下载时引擎未初始化（instance not initialized），下载前统一 ensureReady

未单独验证：
- 直链 Range 续传（代码已实现，测试文件走的是 yt-dlp 路径）
- 服务器不支持 Range 时会从头重下（符合预期）

已知行为：
- 系统「强行停止」后不会继续下载，需重新打开 App 才会自动恢复
- 部分第三方接口（如 HLS 分片）不支持续传时会整段重下


## 浏览器历史/书签（2026-10-29，vivo V2156A / Android 11）
- [x] 历史记录：访问 http/https 页面自动记录（标题/URL/时间），去重、上限 500
- [x] 书签：收藏/取消当前页，列表删除
- [x] 持久化：`filesDir/browser_data.json`，重启后仍在
- [x] 工具栏改为「⋮ 菜单」：历史记录 / 书签 / 收藏当前页 / 检测日志 / 清空检测
- [x] 点历史或书签条目直接在当前浏览器跳转
- 验证：记录→重启→仍在；收藏→重启→仍在；删除书签、历史跳转均正常


## yt-dlp 在线更新（2026-10-29，vivo V2156A / Android 11）
- [x] 首页右上角「设置」→ 显示当前 yt-dlp 版本，一键「检查并更新」
- [x] 下载源：ghfast.top 代理 GitHub 官方 zipapp → ghproxy.net → GitHub 直连，自动回退
- [x] 版本判定：解析 zipapp 内 `yt_dlp/version.py`；zipapp 带 shebang，需先定位 PK 头
- [x] 覆盖 `noBackupFilesDir/youtubedl-android/yt-dlp/yt-dlp`，新任务立即生效
- [x] 实测：2025.11.12 → 2026.10.26 更新成功；再次检查返回「已是最新」；更新后解析功能正常
- 说明：手机网络无法访问清华 PyPI 索引，因此不做单独的版本预检，检查更新会先下载包再比对（约 3.3MB）
- 注意：卸载应用会回退到安装包内置版本


## 视觉规范落地（2026-10-29，vivo V2156A / Android 11）
- [x] 关闭动态取色，固定品牌色（Paper/Ink/Blue/Yellow）
- [x] 替换字阶：28/24/20/16/15/14/12，正文 16/26
- [x] 按钮、输入框 8dp 圆角；卡片描边无阴影，12dp 圆角；底部面板顶部 12dp
- [x] 导航选中态改为蓝色（不再使用黄色）；完成态使用绿色
- [x] 嗅探列表增加 HLS/DASH/VIDEO 彩色标签
- [x] 首页改为整页滚动，修复无缩略图留白与格式列表固定高度空白
- 真机检查：首页、解析卡片、任务列表、浏览器工具栏与嗅探面板；深色模式状态栏/导航栏已按主题切换

## 用户反馈（2026-10-30）

1. **下载速度慢**（2026-11-05 已处理）
   - 已实施：
     - 设置页新增「分片并发（HLS/DASH）」：4 / 8 / 16 可选，默认 8，新任务生效
     - 内置 aria2c（`libaria2c.so` + `libaria2c.zip.so` 打进 jniLibs，启动时解压到 no_backup），设置页「多线程下载（aria2c）」默认开启
     - 普通单文件下载走 `--downloader libaria2c.so`；URL 是 `.m3u8` / `.mpd` 时自动跳过，仍用内置下载器
     - aria2c 解压/初始化失败或下载时报错时，自动退回内置下载器重试一次
     - 进度显示兼容 aria2c 的 `[#gid …% DL:…]` 汇总行（并发数、速度、ETA）
   - 未处理：直链兜底 `DirectDownloader` 仍为单连接（没有做 Range 分块并发）
   - 边界：源站限速时收益有限

2. **应用内删除任务后，文件仍留在手机里**（2026-10-31 已修复）
   - 删除任务时弹窗询问是否「同时删除文件」；「清除已完成」可选「连文件删除 / 仅删记录」
   - 下载过程中解析 yt-dlp 输出（`[download] Destination:` / `[Merger]` 等）记录实际写入路径，取消/失败任务也能清掉 `.part`
   - 任务行显示「VidGrab/文件名 · 大小」；只能删除应用目录 `files/VidGrab` 内的文件

3. **下载文件名经常不对**（2026-10-31 已修复）
   - 新增 `FileNames.clean`：去掉「- 哔哩哔哩」「_抖音」「高清」「在线观看」等站点/清晰度后缀
   - 下载完成后按清洗过的标题重命名（重名自动加序号）；直链兜底优先用标题而不是 URL 最后一段
   - 嗅探到的媒体解析不出标题时，用「页面标题 + 时间」命名（浏览器把页面标题传给下载页）
   - 任务卡片增加「重命名」按钮，可手动修正
   - 待验证：需要具体站点样本；若元数据本身错误，仍需手动重命名

4. **任务多了只能逐个删**（2026-10-31 已支持）
   - 任务页新增「选择」模式：勾选任意几个任务，支持「全选 / 全不选」，删除所选不影响其余任务
   - 批量删除同样可选是否连带删除文件

5. **下载中/下载后想改名**（2026-10-31 已支持）
   - 已完成任务改名立即重命名文件；下载中/排队中改名，完成后按新名字落盘
   - 重名自动加 `(2)`、`(3)` 序号

6. **浏览器地址栏不好用**（2026-10-31 已优化）
   - 后退按钮移到地址栏左侧，字段明显加宽；聚焦时自动全选，一键清空
   - 广告拦截开关从工具栏移入菜单（含「本站广告拦截设置」），工具栏更简洁
   - 回车/Go 后自动收起键盘

## 用户反馈（2026-11-01）

7. **应用内文件名正确，手机系统目录里是乱码**（2026-11-05 已解决）
   - 现象：任务卡片显示的名字正常，进手机文件管理器看到的实际文件名是乱码
   - 相关代码：下载完成后 `YtDlpEngine.renameToTitle` / `DirectDownloader.finalizeFile` 用 `FileNames.clean(title)` 重命名；任务卡片显示的是持久化的 `task.title`，**不等于磁盘上的实际文件名**
   - 可疑点：`file.renameTo(target)` 失败时静默保留原文件（返回 false 不报错）；直链路径的 `baseName` 来源也可能是 URL 编码或错误解码的标题
   - 下一步：取一个具体乱码文件名（截图或 `ls | hexdump`），确认是 URL 编码（`%E4%B8%AD`）、GBK/UTF-8 混淆还是文件管理器显示问题；重命名失败处加日志与「复制 + 删除」兜底
   - 2026-11-05：用户确认公共下载目录 `Download/VidGrab` 里的文件名未再出现乱码，问题关闭

8. **希望文件存到公共下载目录**（待处理）
   - 现状：保存到应用专属外部目录 `/sdcard/Android/data/com.vidgrab.app/files/VidGrab`，Android 11+ 文件管理器要逐层点进去，卸载应用还会连带删除
   - 需求：存到公共 `Download/VidGrab`（或公共 `Download`），系统「文件」/下载管理器能直接看到
   - 方向：Android 10+ 用 MediaStore（`MediaStore.Downloads`，写自己的文件无需存储权限）落盘；或 SAF `ACTION_OPEN_DOCUMENT_TREE` 让用户选目录。公共目录下续传/重命名/删除要改为基于 Uri，`download_tasks.json` 需记录 Uri 而不是纯路径；Android 9 及以下仍需 `WRITE_EXTERNAL_STORAGE`

## 用户反馈（2026-11-05）

9. **下载直接失败：`File name too long`（2026-11-05 已实现，待真机验证）**
   - 报错样本：`ERROR: Unable to download video: [Errno 36] File name too long: '.../files/VidGrab/1ba0545f…a42f2f5eaa2].mp4.ytdl'`
   - 现象：任务标题就是一长串哈希（约 150 字符），yt-dlp 用它当文件名，超过 255 字节上限
   - 根因：
     1. `FileNames.resolveTitle` / `clean` 只识别「像网址」的标题，不识别哈希/随机 ID，哈希被原样当任务标题
     2. `YtDlpEngine.download` 的 `-o "%(title).180B [%(id)s].%(ext)s"` 只截了 title，`%(id)s` 仍是长哈希，合计超限
     3. `FileNames.clean` 的 `take(120)` 按字符不按 UTF-8 字节，中文标题仍可能超限；各处 `renameTo` 失败静默保留原名
   - 已实施：
     - 模板改为 `%(title).80B [%(id).24B].%(ext)s`
     - `clean()` 按 UTF-8 字节截断（80 字节）
     - 新增 `FileNames.looksLikeHash`（纯十六进制 ≥16 位，或 ≥32 位无空格随机串）与 `safeTitle`，命中时退回「域名 + 时间」
     - `resolveTitle` / `directFileName` / 下载完成重命名统一走该兜底
     - `renameTo` 失败改为复制+删除兜底
   - 自定义文件名模板：用户确认**暂时不需要**，先做自动清洗
   - 真机验证：已安装新包，哈希链接下载不再报 `File name too long`
   - 遗留：该站页面标题与视频地址都是哈希，拿不到视频真实名称，现退为「域名 + 时间」；用户选择用任务卡片的「重命名」手动改

10. **希望保存到公共下载目录（承接第 8 条，2026-11-05 已实现，待真机验证）**
   - 已实施：新增 `PublicStorage`；下载过程仍写私有目录（保留续传），完成后复制到公共 `Download/VidGrab`
     - Android 10+ 走 `MediaStore.Downloads`（`IS_PENDING`，无需存储权限），成功后删除私有成品
     - Android 9 及以下直接写公共目录（Manifest 增加 `WRITE_EXTERNAL_STORAGE`，maxSdkVersion 28；未申请运行时权限时自动回退私有目录）
     - 任务 `filePath` 保存 `content://`；重命名/删除/打开改走 `ContentResolver`
     - 任务卡片显示 `Download/VidGrab/文件名 · 大小`
   - 恢复：复制期间被杀会停在「处理中」，启动时重新入队；若引用已存在则直接标记完成
   - 真机验证：新包已安装；下载后公共目录 `Download/VidGrab` 已生成，导出链路正常
   - 2026-11-08 补验：公共目录文件的重命名、打开、删除均已通过（见第 4 批）

11. **下载之前修改文件名（2026-11-05 已实现）**
   - 点「开始下载 · 格式」后先弹出「下载文件名」对话框，预填解析到的标题，可修改后确认入队
   - 链接下载与浏览器嗅探下载共用该入口（嗅探会先回到首页解析，再走同一对话框）
   - 文件名仍按 UTF-8 字节限长、非法字符清洗；扩展名由所选格式自动决定

12. **已知遗留**
   - 复制到公共目录会增加一次「复制 + 删除」IO，大文件耗时可见（任务显示「处理中」）
   - Android 9 及以下未做运行时存储权限申请，未授权时回退到私有目录
   - 若复制途中进程被杀，MediaStore 里可能留下不可见的 pending 条目（极小概率）

## 待办
- [ ] 配置 release 签名并分发（`keystore.properties` 仍未提供，当前只能用 debug 包验证）
- [x] Android 15 dataSync 前台服务 6 小时上限（2026-11-14 已处理，待真机验证）
- [ ] 可选：代理设置、播放页专用解析规则
- [ ] 可选：yt-dlp 更新改为带版本预检（需找到手机可访问的版本接口）
- [x] 内置 yt-dlp 升至 2026.10.26（2026-11-14，v1.3.1，真机验证）
- [x] 切换页签保留页面/滚动/视频进度（2026-11-08 已修复并真机验证）
- [x] 许可证：已补 GPL-3.0 全文 `LICENSE`，README 增加指向

## 逆向结论摘要
- **Seal**：youtubedl-android + yt-dlp zipapp + aria2c + FFmpeg；任务状态机、3 并发、进度回调；Room/MMKV/Koin。
- **SuperX**：WebView + 三种检测器（URL 规则 / 拦截请求并主动 GET 探测 Content-Type / M3U8-MPD 检测）；直链、自研 HLS-DASH、yt-dlp 四种下载 Worker；内嵌 Xray、Rust 广告拦截。
