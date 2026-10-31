# VidGrab 开发进度

更新：2026-10-29

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
- [x] 并入 youtubedl-android 0.18.1 源码与 arm64 jniLibs（Python 3.12、yt-dlp 2025.11.12、FFmpeg、QuickJS）
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

## 用户反馈待办（2026-10-30，暂不动代码）

1. **下载速度慢**
   - 现状：yt-dlp 渐进式下载默认单连接；HLS 分片并发固定为 4；aria2c 库已内置但未启用
   - 可考虑：分片并发做成可调（4 / 8 / 16）；启用 aria2c（`--downloader libaria2c.so`）；直链下载增加多连接
   - 边界：源站限速时收益有限，适合放进设置页让用户自选

2. **应用内删除任务后，文件仍留在手机里**
   - 现状：`DownloadManager.remove` 和 `clearFinished` 只删除任务记录，不删除文件；文件位于 `Android/data/com.vidgrab.app/files/VidGrab`
   - 可考虑：删除任务时询问「同时删除文件」；「清除已完成」提供是否连文件一起清的选项；任务行显示文件大小与路径
   - 注意：已下载的 `.part` 续传文件也应纳入清理逻辑

3. **下载文件名经常不对**
   - 现状：输出模板为 `%(title).180B [%(id)s].%(ext)s`；聚合站/播放页常把网页标题当视频标题；直链兜底使用 URL 最后一段
   - 可考虑：优先取 yt-dlp 的 track / alt title，并清理站点后缀；嗅探到的媒体用「页面标题 + 分辨率 + 时间」命名；提供手动重命名
   - 待办：需要具体站点样本，确认是元数据本身错误还是模板问题

## 待办
- [ ] 可选：更多 ABI（x86_64 模拟器）、代理设置、播放页专用解析规则
- [ ] 可选：yt-dlp 更新改为带版本预检（需找到手机可访问的版本接口）
- [ ] 已知限制：切换页签会重新加载当前页面（不保留播放进度）；仅 arm64；debug 签名
- [ ] 许可证：youtubedl-android 为 GPL-3.0，发布需按 GPL 开源

## 逆向结论摘要
- **Seal**：youtubedl-android + yt-dlp zipapp + aria2c + FFmpeg；任务状态机、3 并发、进度回调；Room/MMKV/Koin。
- **SuperX**：WebView + 三种检测器（URL 规则 / 拦截请求并主动 GET 探测 Content-Type / M3U8-MPD 检测）；直链、自研 HLS-DASH、yt-dlp 四种下载 Worker；内嵌 Xray、Rust 广告拦截。
