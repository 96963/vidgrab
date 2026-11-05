# VidGrab 开发进度

更新：2026-11-05（当前版本 1.1.0 / versionCode 3）

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
   - 未单独验证：公共目录里文件的重命名、打开、删除

11. **下载之前修改文件名（2026-11-05 已实现）**
   - 点「开始下载 · 格式」后先弹出「下载文件名」对话框，预填解析到的标题，可修改后确认入队
   - 链接下载与浏览器嗅探下载共用该入口（嗅探会先回到首页解析，再走同一对话框）
   - 文件名仍按 UTF-8 字节限长、非法字符清洗；扩展名由所选格式自动决定

12. **已知遗留**
   - 复制到公共目录会增加一次「复制 + 删除」IO，大文件耗时可见（任务显示「处理中」）
   - Android 9 及以下未做运行时存储权限申请，未授权时回退到私有目录
   - 若复制途中进程被杀，MediaStore 里可能留下不可见的 pending 条目（极小概率）

## 待办
- [ ] 可选：更多 ABI（x86_64 模拟器）、代理设置、播放页专用解析规则
- [ ] 可选：yt-dlp 更新改为带版本预检（需找到手机可访问的版本接口）
- [ ] 已知限制：切换页签会重新加载当前页面（不保留播放进度）；仅 arm64；debug 签名
- [x] 许可证：已补 GPL-3.0 全文 `LICENSE`，README 增加指向

## 逆向结论摘要
- **Seal**：youtubedl-android + yt-dlp zipapp + aria2c + FFmpeg；任务状态机、3 并发、进度回调；Room/MMKV/Koin。
- **SuperX**：WebView + 三种检测器（URL 规则 / 拦截请求并主动 GET 探测 Content-Type / M3U8-MPD 检测）；直链、自研 HLS-DASH、yt-dlp 四种下载 Worker；内嵌 Xray、Rust 广告拦截。
