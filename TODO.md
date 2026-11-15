# VidGrab 未完成清单与问题

记录时间：2026-11-15 16:40（周日，Asia/Shanghai）
当前版本：`1.4.44 / versionCode 51`，debug 包已编译并装机验证；手机上装的是 1.4.44 / 51
当前代码：本地 `main` 已推 Gitee/GitHub；本轮全部改动 `efde7d4`；1.4.39 代码 `9e07da0`、验证记录 `2e03de2`；1.4.3 修复 `3f5436d`、验证记录 `7791f05`、1.4.2 修复 `892a805`；本文档初版 `dba57ba`
工程路径：`E:\claude\REA\VidGrab`

> 明天先看本文档，不需要重新读聊天记录。

---

## 0.1 2026-11-15 已解决（重要结论，勿重复排查）

### 播放卡顿根因：视频编码是 AV1，设备无 AV1 硬解
- 现象：App 内播放明显卡顿、不流畅。
- 取证方式：`PlayerActivity` 注册 `AnalyticsListener`，把 `onVideoInputFormatChanged` / `onDroppedVideoFrames` / `onRenderedFirstFrame` 写进 `files/vglog.txt`（vivo 屏蔽第三方 App 的 java logcat，只能写文件看）。
- 证据（1.4.20 版本、AV1 文件）：
  ```
  Player: format=video/av01 1920x1080 fps=29.468302
  Player: dropped=73 in 1087ms
  Player: dropped=50 in 4911ms
  Player: dropped=128 in 5471ms
  Player: dropped=133 in 4998ms
  ```
  即每约 4.9 秒丢 50~133 帧，周期与 GOP（15 个 I 帧 / 69s ≈ 4.6s）吻合，属软解跟不上。
- 文件本身没问题：moov 前置（faststart）、vide+soun 双轨、无 elst、stts delta 543/542（29.47fps，仅 1 处 944 异常）、音频 stts 单条 1024。
- 修法：`YtDlpEngine.DEFAULT_SELECTOR` 改成优先 H.264：
  `bestvideo[vcodec^=avc1]+bestaudio/bestvideo[vcodec^=h264]+bestaudio/bestvideo*+bestaudio/best`
- 验证（1.4.21）：`Player: format=video/avc`，播放 50 秒 **零 dropped 日志** → 卡顿解决。
- 遗留：已经下载好的 AV1 文件仍会卡，需要重新下载；如需保留 AV1 选项，可在格式卡片里手动选。

### 保存位置：一律公共目录 Download/VidGrab
- 用户明确要求：**不要私有目录**，除非用户自己用 SAF 选择。
- 因此「卸载 App 时自动删除视频」在 Android 上做不到（公共目录文件归用户；MediaStore 的 owner 清理 vivo 未执行；App 无卸载回调）。
- 已试过并放弃：私有目录 `Android/data/...`（用户否决）、`Android/media/<包名>`（更接近但仍非公共 Download）、App 内「清空全部下载」按钮（用户认为没人会去点）。
- 现状：1.4.19 起默认回到 MediaStore `Download/VidGrab`，任务页显示 `Download/VidGrab/<文件名>`。

### P0 推 Gitee + 发行版（2026-11-15 已完成）
- 远端 main 由 `5c413aa`（v1.3.1 P0 分支）前进到 `3b15d95`：合并策略 `-s ours`，代码以 1.4.21 主线为准（主线的通知栏按钮 / 公共目录 / 内置 yt-dlp 均已覆盖并验证）
- 发行版 `v1.4.21`（release id `1310711`），名称「VidGrab 1.4.21 - 播放卡顿修复」
- 附件 `VidGrab-1.4.21-arm64-v8a-debug.apk`，82,716,889 B（arm64 单架构，低于 Gitee 100MB 上限）
  - SHA256 `5f54470bed56a1da99a9928515dbfe0df088895e76db5154d42846c9f6a1698e`
  - 下载页 `https://gitee.com/WSFSA123/vid-grab/releases/tag/v1.4.21`
- 单 ABI 打包：`gradle assembleDebug -Pabi=arm64-v8a`（必须先 `clean`，否则增量构建会保留旧 ABI 的 so，包体仍是 141MB）
- 仍待：正式签名（缺 `keystore.properties`，当前 release 附件是 debug 签名）

### 发行版 v1.4.37（2026-11-15 已完成，双端）
- 推送：Gitee `eef45e5..2e18158`、GitHub `eef45e5..2e18158`，tag `v1.4.37` 两端都已建
- Gitee release id `1311168`，attachment id `3704284`，下载页 `https://gitee.com/WSFSA123/vid-grab/releases/tag/v1.4.37`
- GitHub release id `445867196`，asset id `750118300`，下载页 `https://github.com/96963/vidgrab/releases/tag/v1.4.37`
- 附件同名 `VidGrab-1.4.37-arm64-v8a-debug.apk`，82,841,497 B
  - SHA256 `c062d2372040c0ce1ce7d966f41ac6832363a4de2472cb9849fc7645768dbc51`
  - GitHub API 回读 `digest=sha256:c062d2372040c0ce1ce7d966f41ac6832363a4de2472cb9849fc7645768dbc51`，与本地一致
- 中文乱码治理：正文用 Python `json.dumps(ensure_ascii=False)` 生成 UTF-8 无 BOM JSON，`curl --data-binary` + `Content-Type: application/json; charset=utf-8` 提交；两端回读正文中文均正常（无 `\uXXXX`、无乱码）
- **GitHub 网络坑（下次会再遇到）**：
  - 本机 DNS 把 `github.com` 解析成 `57.155.223.240`（污染地址，连不上）；`api.github.com`、`uploads.github.com` 反而直连正常
  - 改 hosts 需要管理员权限，做不到（`AppendAllText` 报 Access denied）
  - 可用办法：写一个本地 CONNECT 转发代理 `E:\claude\REA\.tools\ghproxy.py`（监听 8899，把 `github.com:443` 转发到可用 IP `140.82.112.4:443`，自动保留 SNI，证书校验通过），然后
    `git -c http.proxy=http://127.0.0.1:8899 -c http.version=HTTP/1.1 push github main`
  - 注意：`git -c http.curloptResolve=...` 在本机 Git（schannel 后端）直接报 `Unsupported SSL backend 'openssl'`，不可用
  - 代理用完要 kill 并确认 hosts 未被改动
  - 备选可用 IP：`140.82.112.4`（1.2s）、`140.82.114.4`（1.5s）、`20.247.166.64`（2.1s）

### 发行版 v1.4.39（2026-11-15 已完成，双端）
- 推送：Gitee `b4a7035..9e07da0`、GitHub `b4a7035..9e07da0`，tag `v1.4.39` 两端都已建
- Gitee release id `1311299`，attachment id `3704735`
  - 下载页 `https://gitee.com/WSFSA123/vid-grab/releases/tag/v1.4.39`
  - `curl -I -L` 验证 302 → 200，`Content-Length: 82,727,601`
- GitHub release id `445885359`，asset id `750217909`
  - 下载页 `https://github.com/96963/vidgrab/releases/tag/v1.4.39`
  - API 回读 `digest=sha256:1b41f467b123543b0f30a42be96cf99319e12013ca6b373885ca58cd37307e6b`，与本地一致
- 附件 `VidGrab-1.4.39-arm64-v8a-debug.apk`，82,727,601 B
  - SHA256 `1b41f467b123543b0f30a42be96cf99319e12013ca6b373885ca58cd37307e6b`
- 中文乱码：沿用 Python `json.dumps(ensure_ascii=False)` 写 UTF-8 无 BOM JSON + `curl --data-binary` + `Content-Type: application/json; charset=utf-8`；两端回读 name/body 中文均正常，无 `\uXXXX`、无乱码
- **新坑**：GitHub `POST /releases` 带 `target_commitish: "9e07da0"`（短 hash）直接 **422 Validation Failed（field: target_commitish）**；tag 已经推上去时**省略该字段**即可（提交到 GitHub 时会按 tag 指向的 commit）

### 镜像：GitHub 同步（2026-11-15 已完成）
- `git push github main` 成功：`218eb4d..187c5e2`（GitHub 仓库是公开的，凭证在 `~/.git-credentials` 的 `store` helper 里）
- GitHub 发行版 `v1.4.21`（release id `445793973`，asset id `749736791`）
  - `https://github.com/96963/vidgrab/releases/tag/v1.4.21`
  - 附件 82,716,889 B；**GitHub API 回传 `digest=sha256:5f54470bed56a1da99a9928515dbfe0df088895e76db5154d42846c9f6a1698e`，与本地一致**
  - 国内直连 GitHub 下载很慢（180s 只到 10MB，超时），但文件本身完整；国内用户优先走 Gitee
- 乱码治理：`git config core.quotepath false`（本仓库已设，中文文件名不再显示成 `\344\270\255` 之类转义）；仓库当前没有被跟踪的中文文件名；`TODO.md`/`PROGRESS.md`/`README.md` 均为 UTF-8 无 BOM；GitHub Release 正文按 UTF-8 字节 POST，回读校验中文正常
- 注意：`core.quotepath` 存在 `.git/config`，不随代码同步，换机器克隆后需重新设置

### 启动卡顿/空白修复（2026-11-15，1.4.22）
- 现象：每次打开（尤其新装后第一次）有 1 秒左右的卡顿/空白
- 实测 1.4.21 冷启动 `am start -W`：`TotalTime 1201~1314ms`
- 根因：`MainActivity` 首帧就把三个页面全部组合，`BrowserScreen` 会创建 WebView（首个 WebView 初始化 0.3~0.6s）；`TasksScreen` 同时读列表
- 修法：`BrowserScreen` / `TasksScreen` 改为首次切到该页签才组合（`browserReady` / `tasksReady`）；`App.onCreate` 原有耗时项保留（实测 `app init 14~76ms`，不是瓶颈）
- 结果（1.4.22）：`TotalTime 529~552ms`（连续 4 次冷启动稳定），`Boot: app init 14ms` / `Boot: first frame 68~77ms`
- 启动窗口：`windowBackground` 改为 `@drawable/splash_background`（浅底 + 整屏居中的靛蓝圆角图标 + 白色下载箭头），不再出现纯白空白
- 仍需知道：**安装/覆盖安装后的第一次启动约 2.4s**（`TotalTime 2417ms`），这是系统对新 APK 做校验与 dex 优化的固有成本，代码层面无法消除；之后每次冷启动稳定在 ~0.5s
- 诊断埋点：`DiagLog` 写 `Boot: app init ... / Boot: first frame ...`，排查启动问题直接 `adb shell run-as com.vidgrab.app cat files/vglog.txt`

### 三项待验证全部通过（2026-11-15 下午，1.4.36 + 1.4.37）

**测试方法**：新建限速源 `E:\claude\REA\.tools\vidgrab-web\slow_server.py`（8766 端口、200 KB/s、全局共享令牌桶、支持 Range/HEAD），`adb reverse tcp:8766 tcp:8766`，样本 `n2.mp4`（12,853,793 B）。任务进度直接读 `files/download_tasks.json` 的 `progress` 字段，不靠截图目测。用 `direct` 选择器可拿到细粒度进度（yt-dlp 走 aria2c 时只在结束时回调一次）。

1. **1.4.29 续传进度单调 —— 通过（1.4.36 才真正修好）**
   - 1.4.35 实测：暂停在 86.2% → 点继续 → `progress` 立刻变 `0`，6 次连续采样全是 0
   - 根因：`DownloadManager.runTask` 进入时无条件 `progress = 0f`；`shownProgress` 只挡住了回调侧的倒退，挡不住这次清零。暂停→继续和自动重试都走这条路径
   - 修法（1.4.36）：删掉 `progress = 0f`，并让 `shownProgress` 以任务当前进度为起点
   - 复测：暂停 86.2% → 继续 → 8 次连续采样恒为 `0.862296`，不回退，之后继续上涨直到 DONE

2. **1.4.30 封面发布幂等 —— 通过**
   - 用 B 站 `BV1GJ411x7h7`（75,234,087 B）下载并勾选「同时保存封面」，得到 `…[BV1GJ411x7h7].jpg`（651,508 B）
   - 删掉公共目录的主视频、把任务改成 `ERROR` 再点「重试」→ 重新下载完成
   - 结果：`Download/VidGrab` 里 jpg 仍只有 **1 张**，没有出现 `… (1).jpg`；`hasFileNamed` 命中后走「同名跳过」分支

3. **1.4.27/1.4.28 网络中断自动重试 —— 通过（1.4.37 修了漏判）**
   - 断网手法：`adb reverse --remove` 不会断开已建立的连接（实测无效），必须 **直接杀掉 `slow_server.py` 进程**（客户端读到 `unexpected end of stream`）
   - 1.4.36 实测：断流后直接 `ERROR`，一个重试都没发生
   - 根因：`RetryPolicy.isRetryableNetworkError` 的信号词里没有 `unexpected end of stream`；OkHttp 的 `EOFException: \n not found` 还会被 `not found` 当成 404 业务错误拦掉
   - 修法（1.4.37）：补 `unexpected end of stream` / `eof` / `socket closed` / `connection closed`，并对 `eofexception` 单独优先放行；`RetryPolicyTest` 加用例（11 个全绿，其中一个用例正是为了抓出 `not found` 误判）
   - 复测：断流 → `state=QUEUED` `warning=网络中断，自动重试 2/3 → 3/3`；杀服务器 6 秒后重启 → 下一次重试自动接上，进度 20.4% → 26.5% → 30.6% → 36.7% 一路涨到 DONE，全程进度不回零
   - 日志证据：`Download: auto retry 1/3 in 3000ms: 多线程下载失败：unexpected end of stream`、`2/3 in 8000ms`、`3/3 in 20000ms`

### 本轮顺手修的 UI bug
- **「开始下载」按钮文字被裁（1.4.35）**：按钮写死 `Modifier.height(48.dp)`，文案「开始下载 · 最佳画质（自动合并，优先 H.264）」换行后下半截被切掉
  - 修法：`heightIn(min = 48.dp)` + `contentPadding(vertical = 12.dp)` + `TextAlign.Center`

### 已修复：direct 模式「暂停 → 继续」卡在某个百分比（1.4.38 / 45）
- 现象（1.4.37 及之前）：`direct`（网页直链）任务暂停在某个进度后点继续，`.part` 被预分配成完整尺寸、状态长期停在 `DOWNLOADING`，进度不再变化，也不进 `PROCESSING`/`DONE`
- 根因有三层，叠在一起才表现成「卡死」：
  1. `multiDownload` 一进来就 `RandomAccessFile(part).setLength(total)`，`.part` **从一开始就是完整长度**。下次 resume 用 `part.length()` 当已下载字节，读到的是「已下完」，Range 请求直接跳过整个文件
  2. 取消标记是 `taskId → Boolean` 的全局开关。`download()` 开头 `cancelled.remove(taskId)`，于是「暂停 → 立刻继续」会把上一轮**还没退出的线程**的取消标记清掉，旧线程复活、和新线程一起写同一个 `.part`
  3. `latch.await()` 没有超时，线程卡在 socket 上时调用方会一直等
- 修法（1.4.38）：
  - 抽出 `RunGenerationTracker`：取消是「按代次」的，`cancel` 只标记当前代，`begin` 刻意不清上一代的标记，旧线程因此仍能认出自己已被取消
  - 多段下载期间留一个 `<名字>.part.multi` 标记；`download()` 开头看到「`.part` + `.multi` 同时存在」就直接删掉重下，不再把预分配的残缺文件当续传起点
  - `latch.await(120s)` 超时，避免永久挂住
  - 失败/取消时 `.part` 与 `.multi` 一起清掉
- 真机验证（1.4.38）：12.8MB 直链，暂停在 **69.4%**（此时临时目录里的 `.part` 已被正确清掉）→ 点继续 → 进度**保持 69.4% 不回零** → 48s 后 77.5% → 87.7% → **DONE**，成品 12,853,793 B 与源文件同尺寸
- 单测：`RunGenerationTrackerTest` 7 个用例（旧代次仍看到取消、新代次不受影响、跨任务隔离、结束后清理等），加 `RetryPolicyTest` 11 个，共 18 个全绿

### 1.4.39 / 46：用户两项决策（已实现并真机验证）

1. **「模拟崩溃（仅 debug）」按钮删掉**
   - `HomeScreen.kt` 里整块 `if (com.vidgrab.app.BuildConfig.DEBUG) { TextButton(...) { Text("模拟崩溃（仅 debug）") } }` 移除
   - 真机验证：设置页 `uiautomator dump` 全文匹配「模拟崩溃」→ **REMOVED**

2. **「切页不断播」改成「点击继续播放」**
   - 原来切页签时 WebView 保持播放，切回来视频继续放；现在改成：离开浏览器页签时暂停网页里正在播的音视频，切回来用一个显式按钮让用户自己点
   - `BrowserViewModel` 新增 `PAUSE_MEDIA_JS` / `RESUME_MEDIA_JS` 与 `pauseMediaForTabSwitch(onResult)` / `resumePausedMedia()`
     - 暂停只作用于 `!paused && !ended` 的元素，并打 `dataset.vidgrabPaused='1'`；用户自己暂停的视频不动
     - 恢复只处理带该标记的元素，`play().catch` 容错（自动播放策略拒绝时不崩）
   - `BrowserScreen` 新增 `mediaPausedByTabSwitch` 状态 + `LaunchedEffect(isActive)`；在 WebView 的 `Box(weight=1f)` 内、`pageError` 之后加底部居中提示条 `Surface`：「▶ 网页播放已暂停 · 点击继续播放」，点击后置状态并调 `resumePausedMedia()`
   - App 真正退到后台仍走 `pauseWebView()`（`WebView.onPause`），与页签切换是两套逻辑
   - 真机验证（8766 限速源，测试页改 `autoplay muted` 让视频自动播）：
     - 自动播放中 `paused=false t=9.7` → 切任务页 → 切回浏览器 → `paused=true t=9.7`（**进度冻结在暂停点**），底部蓝色提示条出现
     - 点提示条 → 提示消失 → `paused=false t=15.2`（**从 9.7 接着播**，1x 速度推进）
     - 截图：`t1439_j.png`（暂停 + 提示条）、`t1439_l.png`（续播）、`t1439_f.png`
   - 坑：提示条在截图里目测位置不准（目测 y≈1590，实际 **y≈2035**），用 PIL 按行扫蓝色像素才定位到；按错位置点会「点了没反应」

### 1.4.41 / 48：用户三项诉求（已实现并真机验证，双端已发布）

用户原话三条：① 浏览器打开 B 站视频页点「解析」→ 下载文件名不是原本的视频名（给的具体 URL `https://www.bilibili.com/video/BV1njHK6GEpT/`）；② 打开 B 站页直接被踢到 B 站客户端，要加跳转限制，且**「拦截不要只考虑 B 站的，还有许多，可以让用户来确定是否跳转」**；③ **浏览器页面可手机模式和电脑模式自由切换**。1.4.40 为同批修复的中间版本（加了 `onReceivedTitle` 与占位词识别），未单独发布，最终并进 1.4.41。

**① 文件名回退到视频原标题（根因三层，缺一层都修不好）**

1. **页面标题滞后**：嗅探发生在页面加载早期，`pageState.title` 还停在上一个页面（bing 首页）。原代码只在 `onPageStarted` / `onPageFinished` 更新标题，中间没有回调窗口
   - 修法：改在 **`webChromeClient`** 里加 `onReceivedTitle`，`<title>` 一解析出来就同步 `pageState.title` 与 `viewModel.updateTitle`
   - **坑**：`onReceivedTitle` 属于 `WebChromeClient`，**不属于 `WebViewClient`**。第一版加进 `webViewClient` 直接 `Unresolved reference 'onReceivedTitle'` 编译失败
2. **yt-dlp 对直链只给占位标题**：`video` / `audio` 这类占位词被 `looksLikeUrl` 当成「有标题」
   - 修法：`FileNames` 新增 `placeholderTitles`（video/audio/media/download/index/playlist/master/stream/file/untitled/output/temp/tmp/default/unknown），无空格且小写命中即视为无标题
3. **最致命一层：yt-dlp 自己预生成的兜底名被当成合法标题**：`YtDlpEngine.fetchInfo` 内部已调 `FileNames.resolveTitle(info.title, info.webpageUrl)`，yt-dlp 拿不到标题时会先生成「域名 + 时间戳」兜底名（如 `upos-sz-mirrorbd.bilivideo.com 20261115-1613`）；`HomeScreen` 二次调 `resolveTitle(parsed.title, …)` 时，这个兜底名既不是 URL、也不是哈希、含空格所以不命中占位词表 → **被原样保留，正确的页面标题反而被丢弃**
   - 修法：`FileNames` 新增 `private val fallbackLike = Regex("""^[\w.\-]+\s+\d{8}-\d{4}$""")`，在 `resolveTitle` / `safeTitle` / `directFileName` 三处都加 `!fallbackLike.matches(...)` 排除；`resolveTitle` 第二分支由「`"$default ${timestamp()}"`」改为直接 `default`（页面标题优先、不再拼时间戳）

- 日志实证（`Title` 埋点）：
  ```
  Title: media.pageTitle=… pageState.title=… hint=[CarPlay的真正用法，国产车机全是垃圾？_哔哩哔哩_bilibili]
  Title: parsed.title=[cn-zjjh-ct-04-06.bilivideo.com 20261115-1616] hint=[CarPlay的真正用法，国产车机全是垃圾？_哔哩哔哩_bilibili] -> [CarPlay的真正用法，国产车机全是垃圾？]
  ```
- 真机结果：下载页标题卡片 = 「CarPlay的真正用法，国产车机全是垃圾？」；点「开始下载」→「下载文件名」对话框默认值同样为该标题（站点后缀 `_哔哩哔哩_bilibili` 自动清掉）
- 截图：`t1441_l.png`（修复后标题）、`t1441_n.png`（下载对话框默认名）；修复前对照 `t1441_e.png` / `t1441_j.png`（兜底名 `…bilivideo.com 20261115-1608`）

**② 网页跳转外部 App 改为「用户确认」（通用实现，不写死站点）**

- 用户明确否决过第一版方案：加 `DownloadSettings.blockAppLinks` 开关、只处理 B 站 →「不要只考虑 B 站、让用户决定」。该字段与设置页 Switch 已全部回滚删除
- 最终方案（`BrowserScreen.shouldOverrideUrlLoading`）：
  - `tel:` / `mailto:` / `sms:` **直接交系统**，不打扰
  - **其余所有 scheme 一律**设 `pendingAppLink` 并 `return true`（不进 WebView、不 openExternal），弹 `AlertDialog`「网页要打开其他 App」
  - 对话框正文显示 scheme 与完整链接，并提示「选『留在浏览器』可以继续在当前页面嗅探下载」；按钮「跳转」/「留在浏览器」
- 真机结果：加载 `https://www.bilibili.com/video/BV1njHK6GEpT/` 后弹出该对话框，正文为 `bilibili://video/117495642000360?page=0&h5awaken=…`；`dumpsys activity activities | grep mResumedActivity` 始终是 `com.vidgrab.app/.MainActivity`，没有被踢到 `com.bilibili.app.in`
- 截图：`t1441_b.png`（弹窗 + 通知浮窗同屏）、`t1441_c.png`（关闭弹窗后正常停在 www 页）
- **已知限制（本轮未做）**：登录弹窗 `PopupHost` 的 `shouldOverrideUrlLoading` 仍直接走 `openExternal`，没接询问层。`PopupHost` 是普通 class（Dialog 内自建 WebView），确认框要把状态回传到 Composable 层，需要额外改造，留待下一版

**③ 手机模式 / 电脑模式自由切换**

- `DownloadSettings`：新增 `KEY_DESKTOP_MODE = "desktop_mode"` 与 `var desktopMode: Boolean`（默认 false，重启后恢复上次选择）
- `BrowserViewModel`：新增 `UA_DESKTOP`（Windows Chrome 122）、`phoneUserAgent` 缓存、`applyUserAgent()`、`setDesktopMode(enabled, reloadNow = true)`（改 UA 后 `reload()` 当前页）
- `BrowserScreen`：菜单在「本站广告拦截设置」之后插入 `MenuItem(if (viewModel.desktopMode) "切换手机模式" else "切换电脑模式")`；`initializeWebView` 之后调 `setDesktopMode(DownloadSettings.desktopMode, reloadNow = false)` 恢复上次状态
- 真机结果（双向都验）：
  - 手机 → 电脑：`m.bilibili.com` 封面流 → `www.bilibili.com` 完整桌面版（导航栏「首页/番剧/直播/游戏中心/会员购/漫画/赛事」+ 播放器 + 右侧推荐栏 + 评论区），截图 `t1441_s.png`
  - 电脑 → 手机：回到 `m.bilibili.com` 封面流，截图 `t1441_t.png`

**附带修掉的一个坑：`browser_url` 传参在 WebView 未创建时被静默丢弃**

- 原 `BrowserViewModel.loadUrl` 直接 `webView?.loadUrl(url)`；`onCreate` 通过 `browser_url` extra 传 URL 时 WebView 还没创建 → URL 丢了，`am start --es browser_url` 无效、页面停在 bing
- 修法：`pendingLoad` 挂起，`initializeWebView` 成功后补执行。修好后所有真机测试都可以用
  `am start -n com.vidgrab.app/.MainActivity --es browser_url "<url>"` 直接进页面，省掉地址栏输入的种种麻烦

**真机操作踩坑（本轮新发现，下次直接照做）**

- **`browser_url` extra 是最可靠的页面加载方式**，别在地址栏输入
- **`adb shell input keyevent 66` 在 vivo 输入法上不能提交地址栏**（`ImeAction.Go` 不触发），必须点软键盘右下角「前往」，键盘弹起时坐标约 `(985,1820)`
- **`input text` 输入 URL 时末尾 `/` 会被吞成 `p`**（截图显示 `...iK6GEpT/p`），导致加载失败；用不带末尾斜杠的 URL 或走 `browser_url`
- **按 BACK 会退回网页而不是收键盘**：键盘已收起时 `keyevent 4` = 网页后退。第一次就是这样把 B 站页面退回了 bing 首页，误判成「页面没加载」
- **`uiautomator dump` 是定位弹窗按钮的最可靠手段**，本轮目测坐标多次全偏：点 `(510,1367)` 完全没命中「解析」；dump 出的真实 bounds 是「解析」`[615,1729][699,1789]`→中心 `(657,1759)`、「开始下载」按钮中心 `(588,1752)`、菜单项「切换电脑模式」中心 `(192,2267)`；工具栏菜单图标实际在 `(988,158)`（不是 843 也不是 915）；嗅探角标图标在 `(660,143)`
- 下载页「开始下载」按钮在 `(540,1930)` 点不动，必须用 dump 得到的实际中心点

**B 站页面的一个额外发现**：`browser_data.json` 历史里 `https://www.bilibili.com/video/BV1njHK6GEpT/` 的标题是 **「出错啦! - bilibili.com」**，而 `https://m.bilibili.com/video/BV1njHK6GEpT` 标题才是真实视频名 —— 说明 B 站对 www 桌面版有反爬，实际会重定向到 m 版。排查文件名问题时不要被 www 的「出错啦」标题带偏。

### 发行版 v1.4.41（2026-11-15 已完成，双端）

- 提交：`efde7d4`（6 files changed, 198 insertions(+), 25 deletions(-)）
- 推送：Gitee `2e03de2..efde7d4 main`、GitHub `2e03de2..efde7d4 main`（走本地代理 `ghproxy.py`）
- Gitee：release id `1311472`，tag `v1.4.41`（tag 已推），附件 `VidGrab-1.4.41-arm64-v8a-debug.apk`
  - 下载页 `https://gitee.com/WSFSA123/vid-grab/releases/tag/v1.4.41`
- GitHub：release id `445914485`，asset id `750357912`
  - 下载页 `https://github.com/96963/vidgrab/releases/tag/v1.4.41`
  - API 回读 `size=82999358` `state=uploaded` `digest=sha256:4f7b6028c6ff091456a2cfd249bccb96ab0337f93d1975e9edff9892b3457c00`，与本地一致
- 附件 `VidGrab-1.4.41-arm64-v8a-debug.apk`，82,999,358 B，SHA256 `4f7b6028c6ff091456a2cfd249bccb96ab0337f93d1975e9edff9892b3457c00`
- 中文乱码：沿用 Python `json.dumps(ensure_ascii=False)` 写 UTF-8 无 BOM JSON + `curl --data-binary` + `Content-Type: application/json; charset=utf-8`；两端回读 name/body 中文均正常

**本轮新增的两个发布坑**

1. **GitHub `git push` tag 推不上去**：`140.82.112.4` / `140.82.114.4` / `140.82.116.4` 前后都出现过 `SSL_read: unexpected eof` 或 `WinError 10060`；`Test-NetConnection` 通也不等于 `socket.create_connection` 通
   - 绕过办法：**不推 tag，直接用 GitHub API 创建 release**（省略 `target_commitish`），API 会按 `tag_name` 自动在 `main` 的 HEAD 建 tag
   - `ghproxy.py` 已加 `CANDIDATE_IPS` + `pick_upstream()`，启动时按顺序探测可用 IP（`140.82.113.4` → `140.82.116.4` → `140.82.114.4` → `140.82.112.4` → `20.247.166.64`，4 秒超时）
   - **关键**：`Start-Process python` 必须显式 `-RedirectStandardOutput / -RedirectStandardError`，否则看不到 `upstream -> x.x.x.x` 这行，会误判成「代理没启动」（本轮就因此白重试了两轮）
2. **Gitee PATCH release 必须带 `tag_name`**：只传 `access_token`+`body`+`name` 会 400 `{"messages":["tag_name is missing"]}`；带上 `tag_name` 后 200

### 1.4.42 / 49 ~ 1.4.44 / 51：整剧分集 + 边下边播（已真机验证）

前序 1.4.42 为中间版本（未单独发布），1.4.43 补齐 UA 回退与播放页兜底，1.4.44 加边下边播。

- **通用分集识别 `SeriesResolver`**：把所有 `<a href>` 按「末段数字替换成 `{n}`」的 URL 模板分组，取编号最多的一组；锚文本需像 第N集/第N话/EPn/纯数字。剧名取 h1 → og:title → `<title>` 并清洗。实测 vidhub《小巷人家》40 集、nkdvd《谷雨》24 集、kele47《走马上任》31 集都能弹选集
- **单集挖流顺序**：页面 m3u8/mp4 直链 → iframe（≤2 层、≤5 个）→ 播放器 JSON 的 `url` → 都没有就把分集页 URL 交给 yt-dlp
- **入口**：首页解析与内置浏览器菜单「整剧 / 分集下载」共用 `SeriesPickerDialog`（默认全不选、可全选、逐集解析入队）
- **UA 回退**：`httpGet` 遇 403/404/406/429/451 自动换移动 UA 再试一次；nkdvd 桌面 UA 404、移动 UA 200 实证
- **`WebStreamProbe`**：HTTP 挖不到且页面含 `player` 时，用不可见 WebView 拦截 `.m3u8/.mp4`，18s 超时、Mutex 串行、用完 destroy。nkdvd 未命中（该站播放地址在自定义播放器里加密计算，且要求登录）
- **命名**：`fileName` 统一「剧名 第NN集」；集数归一（空/纯数字 → 第NN集，第N集/EPn 原样）。按用户要求**不做强制改名**，落盘仍走 yt-dlp 输出 + 完成时清洗重命名
- **1.4.44 边下边播**：任务卡在 排队中/下载中/暂停/处理中 且任务 URL 是 m3u8/mp4 时显示「边下边播」，直接播源流、下载继续跑；实测从 16% 起播，8 秒后下载涨到 34%，互不影响。代价是播放再走一份流量；省流量版（本地分片代理）未做
- **仍未解决**：nkdvd 类站点播放地址拿不到（加密 + 需登录），回落到页面 URL 后 yt-dlp 报「需要登录」；任务偶发入队即 PAUSED（warning「已暂停」、progress 0）触发源仍未定位；1.4.43/1.4.44 改动尚未发 release

### P2 真机验证进展（四项全部通过，2026-11-15）
- **亮度手势（通过）**：播放器内左侧上滑 `dumpsys window` 读到 `sbrt=1.0`，下滑 `sbrt=0.05`，再上滑回 `1.0`，双向可调且有 `coerceIn(0.05f,1f)` 下限保护
- **断点续播（通过）**：退出播放器后 `shared_prefs/player_positions.xml` 写 `<long name="pos_1707754170" value="51350" />`；重进后从 51s 继续，播到结尾再退出时该键被整条清除（`<map />`）
- **崩溃上报（通过）**：debug 包设置页「模拟崩溃（仅 debug）」触发 `RuntimeException`，进程即死；`files/crash_log.txt` 有完整记录（时间戳、`版本：1.4.23 (30)`、`设备：vivo V2156A / Android 11 (30)`、线程、异常与堆栈）；重开 App 点「反馈 / 导出崩溃日志」拉起系统分享面板
- **嗅探复制/分享（通过，1.4.33）**：点工具栏角标图标 → 弹窗 → 复制（Toast「链接已复制」）→ 分享（拉起 `ChooserActivity`，带 `http://127.0.0.1:8765/long_real.mp4`）

### 已修 bug：嗅探结果入口点击无效（根因两条，1.4.31 + 1.4.32）
- 原现象：1.4.24 的 `ExtendedFloatingActionButton`、1.4.25 的整行 `Button` 都点不动，键盘会收起但不弹列表
- **根因 1 — 输入法遮挡**：地址栏保持焦点时键盘一直在。点入口那一下先被「点击外部收键盘」消费，即便 `onClick` 执行了（1.4.30 日志实证 `Sniff: open sheet detected=1`），`ModalBottomSheet` 也被键盘挡住看不见
  - 修法（1.4.31）：入口 `onClick` 与 WebView `onPageStarted` 都执行 `keyboard?.hide()` + `focusManager.clearFocus(force = true)`
- **根因 2 — 底部按钮的触摸被 WebView 抢走**：键盘收起、布局稳定后点按钮，`entry touch` 与 `open sheet` 两条日志都没有（事件没进 Compose 节点）；同一时刻菜单项与工具栏按钮 100% 可点
  - 修法（1.4.32）：删掉底部整行 `Button`，入口改为**工具栏 `BadgedBox` 列表图标 + 红色数量角标**，触摸区域与刷新/菜单同一行
- 复测证据（1.4.33）：`Sniff: open sheet detected=1` → `Sniff: sheet composed` → 弹窗出现，连续两次稳定；`t1433_b.png` / `t1433_copy.png` / `t1433_share.png`
- 备用入口保留：菜单「检测结果（N 个媒体）」同样可用（`t1431_menu3.png`）
- `uiautomator dump` 在该页面持续报 `ERROR: could not get idle state`（`--compressed` 同样），抓不到 bounds，只能靠截图坐标点击

### 1.4.26 / 1.4.27 其他改动
- 删除 `PublicStorage` 死代码：`Entry` / `listPublicFiles` / `createDeleteRequest` / `PurgeOutcome` / `deleteEntries`（已确认全工程无调用方）
- 去掉两处已废弃的 `WebSettings.databaseEnabled`，编译警告清零
- 字幕链路复核：`--write-subs` / `--write-auto-subs` / `--sub-langs zh.*,en.*` / `--convert-subs srt` 均已配置，缺的是带字幕的测试源，不是代码问题
- **1.4.27 网络类失败自动退避重试**：`DownloadManager.runTask` 的 catch 里新增判定，只对超时/连接重置/读流中断/5xx 自动重试，最多 3 次，退避 3s→8s→20s，重试期间任务显示「网络中断，自动重试 n/3」并保留 `.part` 续传；403 / 404 / 需要登录 / 解析失败 / 地区限制一律不重试。手动「重试」、任务完成、删任务都会清空计数
  - 实现要点：`isRetryableNetwork()` 白名单 + 黑名单双重判定；`autoRetryCount` 记每任务次数；`scope.launch { delay(...) }` 到点后仅在任务仍是 QUEUED 时重新 `schedule`
  - 待真机验证：B 站尾段限速那个 5–10 分钟视频不暂停跑一遍，看是否自动跨过 96% 的失败
- **1.4.28 重试策略抽独立对象 + 单元测试**：判定从 `DownloadManager` 抽到 `core/RetryPolicy.kt`（`MAX_AUTO_RETRY` / `BACKOFF_MS` / `backoffFor` / `isRetryableNetworkError`），新增 `app/src/test/.../RetryPolicyTest.kt` 共 10 个用例，`gradle testDebugUnitTest` 全绿（tests=10 failures=0）
  - 用例覆盖：超时 / 连接重置 / IncompleteRead / 5xx 可重试；403 / 404 / 需要登录 / 解析失败 / 地区限制 / 空错误不可重试；致命信号优先于网络信号（`HTTP Error 403 … (connection refused)` 判为不可重试）；退避序列 3s→8s→20s 及越界复用
  - 发现并修掉一个真实缺陷：`IncompleteRead(...)` 转小写后是 `incomplete read(...)`（无空格），原来的 `"incomplete read"` 关键字匹配不到，已改为 `"incomplete"`
- **1.4.28 诊断日志治理**：`DiagLog.w()` 新增 `verbose` 参数，`verbose=true` 只在 debug 构建落盘；`MediaSniffer.offer`、嗅探入口的 `open sheet` / `entry touch` 都改为 verbose；日志文件加 8MB 上限，超过裁到 2MB，避免长期使用刷爆存储
- **1.4.28 编译警告清零（第二次）**：`MainActivity` 的 `Icons.Default.List` 换 `Icons.AutoMirrored.Filled.List`；`YtDlpEngine` 的 `line != null` 恒真条件改成 `else` 分支

### 版本演进（2026-11-14 晚 ~ 2026-11-15）
`1.4.6/13` → `1.4.7/14` → `1.4.8/15`（启动优化+诊断日志）→ `1.4.9/16`（DiagLog 写文件）→ `1.4.10/17`（播放列表宽容解析）→ `1.4.11/18`（下载 err 日志）→ `1.4.12/19`（标题回填+展开上限 30）→ `1.4.13/20`（私有目录，已回退）→ `1.4.14/21`（回公有+时间戳修正）→ `1.4.15/22`（播放器解码回退+缓冲）→ `1.4.16/23`（清理按钮，已删）→ `1.4.17/24`（读权限版，已回退）→ `1.4.18/25`（Android/media，已回退）→ `1.4.19/26`（回 Download/VidGrab）→ `1.4.20/27`（播放器丢帧诊断）→ `1.4.21/28`（默认优先 AVC）→ `1.4.22/29`（启动懒加载 + 启动图，冷启动 1.3s → 0.53s）→ `1.4.23/30`（debug 模拟崩溃入口）→ `1.4.24/31`（`MediaSniffer.offer` 诊断日志）→ `1.4.25/32`（嗅探入口改整行按钮）→ `1.4.26/33`（菜单加「检测结果」入口、删 `PublicStorage` 死代码、清编译警告）→ `1.4.27/34`（网络类失败自动退避重试 3 次）→ `1.4.28/35`（重试策略抽 `RetryPolicy` + 10 个单测、诊断日志 verbose/8MB 上限、清最后两处编译警告）→ `1.4.29/36`（续传进度单调显示，不再被回调的 0% 打回原点）→ `1.4.30/37`（封面/字幕附加文件发布幂等；README 重写）→ `1.4.31/38`（入口与页面加载时收键盘，修「点了没反应」）→ `1.4.32/39`（嗅探入口移到工具栏角标，底部按钮被 WebView 抢触摸）→ `1.4.33/40`（sheet 组合诊断）→ `1.4.34/41`（诊断降级 verbose + P2 结论回写）→ `1.4.35/42`（「开始下载」按钮文字不再被裁）→ `1.4.36/43`（续传/自动重试的进度不再清零）→ `1.4.37/44`（自动重试补 `unexpected end of stream` / `EOFException` 漏判，单测 11 个）→ `1.4.38/45`（直链多段下载「暂停→继续」不再卡死，取消改按代次 + `.multi` 标记，单测 +7）→ `1.4.39/46`（删「模拟崩溃（仅 debug）」按钮；「切页不断播」改为「切页暂停 + 点击继续播放」，真机验证通过）→ `1.4.40/47`（中间版本：`onReceivedTitle` 补页面标题早更新、识别 `video`/`audio` 占位标题、`pendingLoad` 修复 `browser_url` 丢参，未单独发布）→ `1.4.41/48`（网页跳转外部 App 改为用户确认（通用，不写死站点）；嗅探直链文件名回退到页面标题（新增 `fallbackLike` 识别「域名+时间戳」兜底名）；手机/电脑模式自由切换，三项均真机验证通过，双端已发布）

---

## 0. 明天开工前

- [ ] 重新连接手机，确认 `adb devices -l` 能看到 `35059217030026B`
- [ ] 确认已安装版本仍是 `versionCode=28 / versionName=1.4.21`；如果重装过，先核对
  - 注意：本项目 `adb uninstall` 有时报 `Failure [DELETE_FAILED_INTERNAL_ERROR]`（vivo 保护），需先 `am force-stop` 再卸；若卸载失败会退化成覆盖安装、旧数据不清
- [ ] 需要浏览器/本地测试源时，启动 `E:\claude\REA\.tools\vidgrab-web\server.py`，并执行 `adb reverse tcp:8765 tcp:8765`
- 手机输入法已切到 vivo（`com.vivo.ai.ime/.main.IMEService`，2026-11-15 上午）；Fcitx5 仍在设备上但不再默认。ADB `input text` 在 Fcitx5 下会被拼音组合改写，若键盘异常先确认默认输入法是 vivo

---

## 1. 建议的明天顺序

1. P1 通知栏暂停/继续按钮真实点击（已验）
2. P1 批量/播放列表真实入队（批量已验，播放列表待验）
3. P1 SAF 保存目录（已验）
4. 1.4.3 回归：无暂停完整下载 + 失败自动重试/封面幂等
5. P2 剩余四项：亮度值、断点续播、崩溃日志、嗅探复制/分享
6. 决定 1.4.2「切页不断播」是否接受
7. Gitee 推送与发行版（先定分支策略，再传附件）

---

## 2. P1 未完成项

B 站主链路已经通过，以下不是 B 站主链路本身的问题，而是 P1 其他验收项。

- [x] **通知栏暂停/继续/取消按钮已真实点击（2026-11-15 上午补验）**
  - 测试任务：`http://127.0.0.1:8765/slow2.mp4`（本地 32MB 慢速源，任务 id `d709fa91`），通知 id `33041`，channel `downloads_v2`，`actions=2`
  - 需先展开通知才看到按钮（vivo Android 11）：暂停/继续按钮 bounds `[90,1323][234,1449]`，取消 `[234,1323][378,1449]`
  - 点「暂停」→ 任务 `PAUSED`、warning `已暂停`、`.part` 停在 `5,729,092 B` 不再增长，通知文案变「已暂停」、按钮变「继续」
  - 点「继续」→ 任务 `DOWNLOADING`，通知恢复「暂停/取消」；本测试源不支持 Range，续传从 0 重下（源端限制，非 App bug；昨天 B 站 Range 续传已验证）
  - 点「取消」→ 任务 `CANCELED`，任务通知被清除；`.part`（3,403,240 B）仍保留在临时目录
  - 遗留小点：取消后 `.part` 未删除，是否按设计保留待确认；重复取消同一任务无副作用

- [x] **批量/播放列表真实入队验证（2026-11-15 真机通过）**
  - 批量多链接（2026-11-15 真机通过）：切到 vivo 输入法后 ADB 输入正常；两条本地直链（`/real.mp4`+`/long_real.mp4`）经「批量下载多个链接 / 播放列表」弹窗入队，拆成 2 个任务，`createdAt` 相差 123ms（同时启动），均 DONE：69.9 KB + 545.4 KB，同名自动加「 (1)」后缀不互相覆盖，MediaStore `821448`/`821449`，MP4 解析确认（本地源本身无音轨，只 vide+meta）
  - 遗留教训：ADB `input text` 在 Fcitx5 下会被拼音组合改写（`aaa%nbbb` → “啊啊啊%”），今后 ADB 输入前先 `ime set com.vivo.ai.ime/.main.IMEService`
  - **播放列表自动展开（1.4.10 真机通过，5/5 入队）**：根因是 `--dump-json` 末尾有空行，Jackson `readTree("")` 返回 MissingNode 而非 null，旧的 `roots.all { playlist_id非空 || playlist_index.isNumber || n_entries.isNumber }` 被这个空节点判 false，整体丢弃后回退只入队第 1 条。1.4.10 改为宽容解析（能映射成视频 URL 就收，不再要求每行带播放列表元信息），并保留「单行交给 fetchInfo」以维持单视频的封面/格式不变
  - 诊断证据（`files/vglog.txt`，1.4.9 起）：`attempt=1 cost=5241ms len=5175 lines=6` → `parse roots=6 mapped=5 lens=[954,954,954,954,954,0]` → `UI entries=5`
  - 排查过程记录：1.4.4 重写 fetchPlaylistEntries 支持容器式+流式；1.4.5 加 3 次重试 + `--extractor-retries 10`；PC 同版 yt-dlp 输出 5 行正常；youubedl-android 0.18.1 `execute()` 无超时参数；曾误判为手机网络问题（旧 PC 热点确有 25-50% 丢包），换家庭 WiFi 后仍 1 条才转向 App 侧判定
  - 遗留小问题：展开后任务标题显示「视频 1」~「视频 5」（`entryToTarget` 未取条目 title，用索引兜底），待小修
  - 测试合集：`space.bilibili.com/31984294/channel/seriesdetail?sid=446778`（5 条，BV1uB4y1H7ps / BV1uv42127gS / BV19D4y117Qf / BV1v6421G7C4 / BV19g411e7ia）
  - 批量弹窗内按钮文案：开始下载（解析中变“处理中…”），空输入时按钮置灰

- [x] **SAF 保存目录（2026-11-15 真机通过）**
  - vivo DocumentsUI 限制：内部存储根目录和 `Download` 根被 vivo 拦截，显示“无法使用此文件夹”（系统限制，非 App 问题）；选「视频」根（`primary:视频`，物理映射 `/sdcard/视频`）成功，返回 tree URI 并持久化 `save_tree_uri`
  - SAF 落盘：`real.mp4` 下载 DONE，`filePath=content://com.android.externalstorage.documents/tree/primary:视频/document/primary:视频/127.0.0.1 20261115-0831.mp4`，物理文件 71,568 B 出现在 `/sdcard/视频/`
  - SAF 删除：任务页删除（“同时删除文件”默认勾选）→ `DocumentsContract.deleteDocument` 生效，文件从 `/sdcard/视频/` 消失
  - 「恢复默认」→ `save_tree_uri` 清空，回到 `Download/VidGrab`
  - 小问题：SAF 位置下任务卡片仍显示「Download/VidGrab/文件名」前缀（显示层硬编码），不影响功能，1.4.4 可改成显示实际目录名
  - ⚠ 事故记录：测试期间（08:25）vivo 文件管理把 `/sdcard/视频/RJ01137048`（用户数据目录，含 `LOI-01`）移入回收站（`fm_recycle.db` 当日唯一新条目）；已从回收站移回原位并核对内容完好。回收站界面可能残留一条幽灵记录。原因未完全定位，疑似 DocumentsUI 期间连续滑动手势误触发；教训：vivo DocumentsUI 里不要快速连续 swipe，SAF 测试后核对 `/sdcard/视频/` 与 `.vivoFileRecycleBin/`

- [ ] **字幕链路缺可验证源**
  - 本地测试源无字幕轨；B 站 `BV1v4Wr6uEoG` 等候选 `x/player/v2` 返回 `subs=0`，没有 CC 字幕
  - 未验证：`--write-subs --write-auto-subs --sub-langs zh.*,en.* --convert-subs srt` 实际产出 srt/vtt/ass/ssa，以及 `publishExtras` 发布
  - 明天需要：找一个带字幕的公开源（YouTube 自动字幕/有 CC 的站点），或登录 B 站后找有 CC 的视频；勾选“保存字幕”验证公共目录出现 srt

- [ ] **仅 Wi‑Fi 中途断网未实测**
  - 只验证了“仅 Wi‑Fi 开启时移动网络下排队/提示”，没有在下载中途关 Wi‑Fi/切移动数据
  - 要核对：任务进入 `PAUSED` 或排队、不继续偷跑流量；恢复 Wi‑Fi 后继续/重试

- [x] **并发 3 路真实任务（2026-11-15 真机通过）**
  - 3 个本地慢源（`/slow.mp4`+`/slow2.mp4`+`/range-slow.mp4`）经批量弹窗入队，任务页同时 3 个「下载中」，24%/10%/51% 各自不同 speed，确认并发上限 3 生效

- [x] **杀进程重启后的自动续传（2026-11-15 真机通过）**
  - `/range-slow.mp4` 下到 50.5% 时 `am force-stop`，`.part`（18,224,941 B）保留、JSON 里任务仍在；`am start` 重启后自动续传（57.4%→完成，未归零），成品落 `Download/VidGrab/127.0.0.1 20261115-0848.mp4`（MediaStore 821457）

- [x] **限速三档（2026-11-15 真机通过）**
  - `/range.mp4`（32MB 无节流）实测：1 MB/s→1021.18 KiB/s、2 MB/s→1.99 MiB/s、4 MB/s→3.96 MiB/s，均贴合 `--limit-rate` 上限；测后已还原 512 KB/s

---

## 3. P2 未完成项

- [x] **播放器左侧亮度手势（2026-11-15 真机通过）**
  - 播放器内左侧上滑后 `dumpsys window windows | grep -o 'sbrt=[0-9.]*'` 读到 `sbrt=1.0`，下滑读到 `sbrt=0.05`，再上滑回到 `1.0`
  - 结论：左半屏上下滑能双向调窗口亮度，`coerceIn(0.05f,1f)` 下限生效
  - 遗留（可选）：没有 UI 显示当前亮度百分比，只是没显示，不影响功能

- [x] **断点续播退出重进（2026-11-15 真机通过）**
  - 退出播放器后 `shared_prefs/player_positions.xml` 出现 `<long name="pos_1707754170" value="51350" />`
  - 重进后继续播放约 20 秒（51s + 20s 超过 69s 总时长），再退出时该键被整条清除（`<map />`）
  - 结论：确实从 51s 续播，且播到结尾才清除记录

- [x] **CrashReporter 真实崩溃（2026-11-15 真机通过）**
  - debug 包设置页「模拟崩溃（仅 debug）」触发 `RuntimeException("VidGrab 测试崩溃（debug）")`，进程立即死亡（`pidof` 为空）
  - `run-as cat files/crash_log.txt` 记录：`=== 2026-11-15 12:12:30 ===`、`版本：1.4.23 (30)`、`设备：vivo V2156A / Android 11 (30)`、`线程：main`、异常类型与堆栈
  - 重开 App 点「反馈 / 导出崩溃日志」成功拉起系统分享面板（「分享方式 · 1 个项目」）

- [x] **嗅探结果的“复制/分享”逐项验证（1.4.33 通过）**
  - 工具栏角标入口 → 弹窗 → 复制：Toast「链接已复制」；分享：拉起系统 ChooserActivity 并带出完整媒体 URL
  - 多条目去重与「复制的是当前条目」逻辑未单独构造多条目页面复测

- [ ] **1.4.2「切页不断播」用户尚未确认接受**
  - 现状：切到任务/下载页，网页视频继续播放且可能有声音
  - 备选：改成用户手势恢复入口（回到浏览器显示“点击继续播放”），不再依赖 WebView 自动 `play()`
  - 这是产品取舍，需要用户拍板，不要擅自改回自动恢复

---

## 3.5 1.4.41 遗留

- [ ] **登录弹窗（PopupHost）未接入「跳转询问」**
  - 现状：主 WebView 的所有非 `tel:/mailto:/sms:` scheme 都会弹「网页要打开其他 App」让用户选；但第三方登录弹窗 `PopupHost`（`BrowserScreen.kt` 约 1349 行，`private class PopupHost`，Dialog 内自建 WebView）的 `shouldOverrideUrlLoading` 仍是直接 `onExternal(request.url)`
  - 影响场景：登录流程里若站点用 scheme 唤起 App（如「用哔哩哔哩客户端登录」），在弹窗里会直接跳出，不会询问
  - 改法方向：给 `PopupHost.show()` 多传一个 `onMaybeExternal: (Uri) -> Boolean` 回调，把 uri 回传给 Composable 层走同一个 `pendingAppLink` 状态；或把确认框改为可复用的 Composable，在 Dialog 内叠一层
  - 发布正文已如实写成「已知限制」，不要写成已修

## 4. 1.4.3 已知问题 / 待回归

- [ ] **无暂停的干净完整下载未跑**
  - 长视频 `BV1v4Wr6uEoG` 下载过程中暂停过两次，第二次尾部遇到 CDN 限速/超时；虽然重试后完成，但不能完全排除暂停/续传对尾部超时的影响
  - 明天用同一视频（或另一个 5–10 分钟视频）从头到尾不暂停跑一遍，记录耗时和是否有网络错误

- [x] **B 站 CDN 尾部限速/断连自动重试（1.4.27/1.4.28 已实现，待真机验证）**
  - 现象：96% 时降到约 3–6 KiB/s，随后 `网络连接失败或超时`；手动点重试后完成
  - 现状：已加自动重试——超时/连接重置/读流中断/5xx 退避 3s→8s→20s，最多 3 次，保留 `.part` 续传；403/404/需要登录/解析失败/地区限制不重试
  - 判定逻辑抽到 `core/RetryPolicy.kt`，`RetryPolicyTest` 10 个用例全绿
  - 待真机：用之前那个 5–10 分钟 B 站视频不暂停跑一遍，确认尾部限速能自动跨过去

- [x] **封面/字幕等附加文件发布幂等（1.4.30 已修）**
  - 原问题：`publishExtras` 只用 `before` 快照过滤，重试前已在临时目录的封面被当旧文件跳过，公共目录拿不到（长视频封面 `139,205 B` 就因此漏发）
  - 修法一（归属）：除「本次新增」外，再按「与主视频同 basename」和「与任务标题同 basename」认领，覆盖上次已下好但没发布成功的情况
  - 修法二（幂等）：新增 `PublicStorage.hasFileNamed()`，公共目录已有同名文件时不再 `insert`，改为删掉私有副本，避免堆出「xxx.jpg / xxx (1).jpg」
  - 待真机：勾选「保存封面」下载一次 → 再次重试同一任务 → 公共目录应始终只有一张同名封面

- [x] **暂停后继续，进度显示先归零（1.4.29 已修）**
  - 原因：yt-dlp 续传会先回调一个 0%，`runTask` 又把进度重置为 0，界面看起来像从头开始（`.part` 其实没丢）
  - 修法：`runTask` 内维护本次运行的最大进度 `shownProgress`，回调低于它 2% 以上时忽略，进度只增不减
  - 待真机：暂停→继续，看一眼进度条是否从原位置接着走

- [ ] **B 站其他 URL 形态未测**
  - 已规范 `m.bilibili.com/video/...`、`bilibili.com/video/...` 到 `www.bilibili.com/video/...`
  - 未测：`b23.tv` 短链、番剧 `bangumi/play/...`、带 `?spm_id_from=` 等 query、多 P 地址 `?p=2`
  - 明天至少补 `b23.tv` 和带 query 的地址

- [ ] **B 站登录态/高码率未验证**
  - `CookieStore` 按 host 持久化 WebView Cookie，yt-dlp 会带 Cookie；但没有登录后下载 1080P+/大会员画质的对比
  - 若用户需要更高画质，先登录 B 站再解析，核对格式列表是否增加

- [x] **构建警告已清零（2026-11-15，1.4.26 + 1.4.28）**
  - `YtDlpEngine` 里 `line != null` 恒真（原 “Condition is always 'true'”）已改为 `else` 分支
  - `MainActivity` 的 `Icons.Default.List` 换成 `Icons.AutoMirrored.Filled.List`
  - 两处废弃的 `WebSettings.databaseEnabled` 已删
  - 现状：`assembleDebug` 与 `testDebugUnitTest` 均 0 warning / 0 error

---

## 5. 构建 / 发版 / Gitee

- [ ] **release 签名仍缺 `keystore.properties`**
  - 当前只有 debug 签名；`assembleRelease` 不能作为正式包
  - 需要用户提供 keystore 信息，或明确继续只发 debug 包

- [ ] **Gitee 发行版只适合 arm64 包**
  - Gitee release 附件上限 100 MB；通用包 141,228,060 B 会被拒
  - 1.3.1 的 arm64 包 82,630,488 B 可上传；1.4.3 需要另建/另打 arm64-only 包
  - 如果必须发通用包，需要分卷或网盘外链，Gitee 直接附件不行

- [x] **1.4.x 源码与发行版已推 Gitee（2026-11-15 完成）**
  - 远端 `main` 已从 `5c413aa`（v1.3.1 P0）前进到 `3b15d95`，再经 `187c5e2` / `ea4c54c` / `eef45e5` 同步；合并用 `git merge 5c413aa -s ours`，代码以 1.4.21 主线为准
  - 发行版 `v1.4.21`（release id `1310711`）+ arm64 附件 82,716,889 B 已上传；GitHub 镜像 `v1.4.21`（release id `445793973`）asset id `749736791`，API 回传 SHA256 与本地一致
  - 遗留：`1.4.22`~`1.4.27` 都还没发新 release；Gitee 口令只在对话里给过，不要写进仓库/文档

- [ ] **`dist/` 和 `*.apk` 在 `.gitignore`，不会随普通 push 上传**
  - 发行版附件需要单独通过 Gitee API 上传，不能指望 git push

- [ ] **P0 的 v1.3.1 已发布，1.4.x 没有对应 release**
  - 现有 release 只有 `v1.3.1`（release id `1308557`，附件 arm64 包）
  - 1.4.3 若要发 release，需要新 tag、release 和 arm64 附件

---

## 6. 测试资产 / 手机上的文件

最后一次确认的公共目录 `Download/VidGrab/`（均为测试产物，不是用户原有文件）：

| 文件 | 大小 |
|---|---:|
| `upos-sz-estgoss.bilivideo.com 20261114-2130.mp4` | 17,497,778 B |
| `宿舍手机壁纸从夯到拉！.mp4` | 4,170,044 B |
| `怪诞虫：一只看起来活不过三天的动物，真的存在过？.mp4` | 36,757,053 B |
| `根本没有这样的夹子 [BV12oWi6VEz7].jpg` | 1,974,215 B |
| `根本没有这样的夹子.mp4` | 12,853,793 B |

- 这些是测试文件，用户确认不要后可清理；不要删用户自己的 Download 文件
- 临时目录 `/sdcard/Android/data/com.vidgrab.app/files/VidGrab/` 可能还有 `.m4a` 或旧封面残留；手机重连后先 `ls -l` 再决定
- 本地调试资产：`E:\claude\REA\.tools\vidgrab-web\bili_merged.mp4`、`bili_short.mp4`、`bili_cover.mp4`、`inspect_mp4.py`、各 `shot_143_*.png`、`ui_143_*.xml`

---

## 7. 1.4.3 已确认通过的部分（避免明天重复测）

- 解析：`BV1v4Wr6uEoG` 真实标题/UP 主/`06:27`/`1920p 74.4 MB` 等格式；`BV1BjWq68Ee2`、`BV12oWi6VEz7` 也能解析
- 合并：三条 B 站成品都确认 MP4 同时含 `vide` 与 `soun` 轨道
  - `36,757,053 B`、`4,170,044 B`、`12,853,793 B`
- 文件名：使用真实视频标题，长视频为 `怪诞虫：一只看起来活不过三天的动物，真的存在过？.mp4`
- 暂停/继续：87% 暂停变 `PAUSED`，继续后 `.part` 继续增长并最终完成
- 失败重试：`BV12oWi6VEz7` 音频阶段超时后重试成功，封面发布成功
- 浏览器路径：360P 直链下载完成 `17,497,778 B`（video-only，属直链本身无音轨）
- 字幕：B 站这些源无 CC，未产出 srt，属源限制

---

## 8. 快速恢复命令

```powershell
$adb = "E:\claude\REA\.tools\android-sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb shell "dumpsys package com.vidgrab.app | grep -E 'versionCode|versionName'"
& $adb shell run-as com.vidgrab.app cat files/download_tasks.json
& $adb shell "ls -l /sdcard/Android/data/com.vidgrab.app/files/VidGrab/"
& $adb shell "ls -l /sdcard/Download/VidGrab/"
```

构建：

```powershell
$env:ANDROID_HOME = "E:\claude\REA\.tools\android-sdk"
$env:GRADLE_USER_HOME = "E:\claude\REA\.tools\gradle-home"
$env:JAVA_HOME = "B:\jdk-21.0.2"
& "E:\claude\REA\.tools\gradle-8.9\bin\gradle.bat" -p "E:\claude\REA\VidGrab" assembleDebug --no-daemon --console=plain
```

Git：

```powershell
& "C:\Program Files\Git\cmd\git.exe" -C "E:\claude\REA\VidGrab" status --short
& "C:\Program Files\Git\cmd\git.exe" -C "E:\claude\REA\VidGrab" log --oneline -5
```

---

## 9. 需要用户拍板的问题

1. ~~1.4.2「切页不断播」是否接受？~~ **已拍板并落地（1.4.39）**：改为「切页暂停 + 底部提示条点击继续播放」，真机验证通过。
2. B 站无 CC 字幕时，P1 字幕验收是否接受“源无字幕、不产出 srt”，还是必须找一个有字幕的源/登录态来验？
3. 封面幂等问题是现在修进 1.4.4，还是先记录、等 P2 收尾一起做？
4. B 站尾部 CDN 超时是否要加自动重试，还是保持手动重试？
5. Gitee 1.4.3 发行版是否只发 arm64 包，通用包走分卷/网盘？
