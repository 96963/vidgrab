# VidGrab 未完成清单与问题

记录时间：2026-11-15 12:40（周日，Asia/Shanghai）
当前版本：`1.4.26 / versionCode 33`，debug 包已编译；手机上装的是 1.4.25 / 32（1.4.26 尚未安装）
当前代码：本地 `main` 干净；1.4.3 修复 `3f5436d`、验证记录 `7791f05`、1.4.2 修复 `892a805`；本文档初版 `dba57ba`
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

### P2 真机验证进展（1.4.23 / 30、1.4.24 / 31）
四项里前三项通过，第四项卡在入口点击 bug：
- **亮度手势（通过）**：播放器内左侧上滑 `dumpsys window` 读到 `sbrt=1.0`，下滑 `sbrt=0.05`，再上滑回 `1.0`，双向可调且有 `coerceIn(0.05f,1f)` 下限保护
- **断点续播（通过）**：退出播放器后 `shared_prefs/player_positions.xml` 写 `<long name="pos_1707754170" value="51350" />`；重进后从 51s 继续，播到结尾再退出时该键被整条清除（`<map />`）
- **崩溃上报（通过）**：debug 包设置页「模拟崩溃（仅 debug）」触发 `RuntimeException`，进程即死；`files/crash_log.txt` 有完整记录（时间戳、`版本：1.4.23 (30)`、`设备：vivo V2156A / Android 11 (30)`、线程、异常与堆栈）；重开 App 点「反馈 / 导出崩溃日志」拉起系统分享面板
- **嗅探复制/分享（未完成）**：见下面「已知 bug」

### 已知 bug：嗅探结果入口点击无效（待定位）
- 现象：嗅探记录正常（`Sniff: offer raw=http://127.0.0.1:8765/long_real.mp4 cls=VIDEO` 连续十余条），入口显示「检测到 1 个媒体」，但点击入口不弹底部列表。1.4.24 的 `ExtendedFloatingActionButton` 与 1.4.25 改成的整行 `Button` 都一样点不动
- 证据：点击后 `vglog.txt` 里**没有** `Sniff: open sheet detected=...` 这行 → `onClick` 根本没执行；同时输入法键盘收起，说明触摸落到了 WebView 或页面别处
- 已排除：`showSheet` 状态作用域（全文件仅 180 行一处声明，`if (showSheet)` 在同一函数内）；`detected` 为空（入口存在、日志有记录）；`ModalBottomSheet` 参数（与「检测日志」弹窗同款实现）
- 待查：WebView 的 `AndroidView` 触摸区是否向下溢出、盖住 `Column` 里的入口；下一步用菜单「检测结果」入口做对照，并给入口加 `pointerInput` 原始事件日志
- 1.4.26 已加备用入口：菜单里「检测结果（N 个媒体）」，走同一个 `showSheet = true`
- `uiautomator dump` 在该页面持续报 `ERROR: could not get idle state`（`--compressed` 同样），抓不到 bounds，只能靠截图坐标点击

### 1.4.26 其他改动
- 删除 `PublicStorage` 死代码：`Entry` / `listPublicFiles` / `createDeleteRequest` / `PurgeOutcome` / `deleteEntries`（已确认全工程无调用方）
- 去掉两处已废弃的 `WebSettings.databaseEnabled`，编译警告清零
- 字幕链路复核：`--write-subs` / `--write-auto-subs` / `--sub-langs zh.*,en.*` / `--convert-subs srt` 均已配置，缺的是带字幕的测试源，不是代码问题

### 版本演进（2026-11-14 晚 ~ 2026-11-15）
`1.4.6/13` → `1.4.7/14` → `1.4.8/15`（启动优化+诊断日志）→ `1.4.9/16`（DiagLog 写文件）→ `1.4.10/17`（播放列表宽容解析）→ `1.4.11/18`（下载 err 日志）→ `1.4.12/19`（标题回填+展开上限 30）→ `1.4.13/20`（私有目录，已回退）→ `1.4.14/21`（回公有+时间戳修正）→ `1.4.15/22`（播放器解码回退+缓冲）→ `1.4.16/23`（清理按钮，已删）→ `1.4.17/24`（读权限版，已回退）→ `1.4.18/25`（Android/media，已回退）→ `1.4.19/26`（回 Download/VidGrab）→ `1.4.20/27`（播放器丢帧诊断）→ `1.4.21/28`（默认优先 AVC）→ `1.4.22/29`（启动懒加载 + 启动图，冷启动 1.3s → 0.53s）→ `1.4.23/30`（debug 模拟崩溃入口）→ `1.4.24/31`（`MediaSniffer.offer` 诊断日志）→ `1.4.25/32`（嗅探入口改整行按钮）→ `1.4.26/33`（菜单加「检测结果」入口、删 `PublicStorage` 死代码、清编译警告）

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

- [ ] **嗅探结果的“复制/分享”没有逐项点击**（阻塞：入口点击无效，见 0.1「已知 bug」）
  - 需要点复制，粘贴核对完整 URL；点分享，选一个目标应用核对标题/链接
  - 多条目时确认复制的是当前条目而不是第一条

- [ ] **1.4.2「切页不断播」用户尚未确认接受**
  - 现状：切到任务/下载页，网页视频继续播放且可能有声音
  - 备选：改成用户手势恢复入口（回到浏览器显示“点击继续播放”），不再依赖 WebView 自动 `play()`
  - 这是产品取舍，需要用户拍板，不要擅自改回自动恢复

---

## 4. 1.4.3 已知问题 / 待回归

- [ ] **无暂停的干净完整下载未跑**
  - 长视频 `BV1v4Wr6uEoG` 下载过程中暂停过两次，第二次尾部遇到 CDN 限速/超时；虽然重试后完成，但不能完全排除暂停/续传对尾部超时的影响
  - 明天用同一视频（或另一个 5–10 分钟视频）从头到尾不暂停跑一遍，记录耗时和是否有网络错误

- [ ] **B 站 CDN 尾部限速/断连没有自动重试**
  - 现象：96% 时降到约 3–6 KiB/s，随后 `网络连接失败或超时`；手动点重试后完成
  - 现状：`.part` 保留，重试可续传；yt-dlp 自身有重试但这次仍抛错
  - 可选修法：下载器捕获网络类错误后自动退避重试 1–3 次；或提供“换连接/重新解析 CDN”按钮
  - 注意不要吞掉 404/403 等不可重试错误

- [ ] **封面发布对“重试前已存在封面”不幂等**
  - `YtDlpEngine.publishExtras` 用 `before` 集合过滤：如果重试前同一视频的 `...jpg` 已在临时目录，会被当成旧文件跳过，公共目录拿不到封面
  - 本次 `BV12oWi6VEz7` 是清掉旧封面后重试，新封面 `1,974,215 B` 成功发布；长视频封面 `139,205 B` 就因已存在而未发布
  - 可选修法：按任务标题/BV 号匹配该任务的附加文件，或发布前检查公共目录同名文件，存在则跳过、不存在则发布；不要无条件发布导致重复文件

- [ ] **暂停后继续，进度显示先归零**
  - yt-dlp 续传回调会先报 0%，再继续上涨；`.part` 没丢，最终成品正常
  - 可选修法：续传时保持上次进度作为下限，或 UI 对回退做平滑

- [ ] **B 站其他 URL 形态未测**
  - 已规范 `m.bilibili.com/video/...`、`bilibili.com/video/...` 到 `www.bilibili.com/video/...`
  - 未测：`b23.tv` 短链、番剧 `bangumi/play/...`、带 `?spm_id_from=` 等 query、多 P 地址 `?p=2`
  - 明天至少补 `b23.tv` 和带 query 的地址

- [ ] **B 站登录态/高码率未验证**
  - `CookieStore` 按 host 持久化 WebView Cookie，yt-dlp 会带 Cookie；但没有登录后下载 1080P+/大会员画质的对比
  - 若用户需要更高画质，先登录 B 站再解析，核对格式列表是否增加

- [ ] **构建警告未处理**
  - `YtDlpEngine.kt:332` 报 “Condition is always 'true'”，不影响构建；明天顺手清理或确认是误报

---

## 5. 构建 / 发版 / Gitee

- [ ] **release 签名仍缺 `keystore.properties`**
  - 当前只有 debug 签名；`assembleRelease` 不能作为正式包
  - 需要用户提供 keystore 信息，或明确继续只发 debug 包

- [ ] **Gitee 发行版只适合 arm64 包**
  - Gitee release 附件上限 100 MB；通用包 141,228,060 B 会被拒
  - 1.3.1 的 arm64 包 82,630,488 B 可上传；1.4.3 需要另建/另打 arm64-only 包
  - 如果必须发通用包，需要分卷或网盘外链，Gitee 直接附件不行

- [ ] **1.4.x 源码和发行版都还没推 Gitee**
  - 远端 `main` 仍是 `5c413aa`（v1.3.1 P0）
  - 本地 `main` 已有 P1/P2/1.4.2/1.4.3：`fc4660d`、`2f14e9c`、`892a805`、`3f5436d`、`7791f05`
  - 两边从 `218eb4d` 分叉；之前 `git merge 5c413aa` 在 12 个文件冲突后已 `--abort`，不要直接硬 merge
  - 明天先定策略：推荐从当前本地 `main` 新建分支 `release/1.4.3` 推送，再在 Gitee 发起合并；不要 `push --force` 到远端 main
  - Gitee 口令只在对话里给过，不要写进仓库/文档；推送时临时使用

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

1. 1.4.2「切页不断播」是否接受？如果不接受，改用“回到浏览器点一下继续播放”的手势恢复入口。
2. B 站无 CC 字幕时，P1 字幕验收是否接受“源无字幕、不产出 srt”，还是必须找一个有字幕的源/登录态来验？
3. 封面幂等问题是现在修进 1.4.4，还是先记录、等 P2 收尾一起做？
4. B 站尾部 CDN 超时是否要加自动重试，还是保持手动重试？
5. Gitee 1.4.3 发行版是否只发 arm64 包，通用包走分卷/网盘？
