# VidGrab 未完成清单与问题

记录时间：2026-11-14 22:11（周六，Asia/Shanghai）
当前版本：`1.4.3 / versionCode 10`，debug 包已安装到 vivo V2156A（Android 11，arm64-v8a）
当前代码：本地 `main` 干净，最新提交 `dba57ba`（本文档）；1.4.3 修复提交 `3f5436d`，验证记录 `7791f05`，1.4.2 修复 `892a805`
工程路径：`E:\claude\REA\VidGrab`

> 明天先看本文档，不需要重新读聊天记录。手机当前 ADB 已断开，开工第一步先重新连接手机。

---

## 0. 明天开工前

- [ ] 重新连接手机，确认 `adb devices -l` 能看到 `35059217030026B`
- [ ] 确认已安装版本仍是 `versionCode=10 / versionName=1.4.3`；如果重装过，先核对
- [ ] 需要浏览器/本地测试源时，启动 `E:\claude\REA\.tools\vidgrab-web\server.py`，并执行 `adb reverse tcp:8765 tcp:8765`
- 手机当前默认输入法仍是 `org.fcitx.fcitx5.android/.input.FcitxInputMethodService`；如果键盘再次透明/弹不出来，临时切到 vivo 输入法，不要改 App 代码

---

## 1. 建议的明天顺序

1. P1 通知栏暂停/继续按钮真实点击
2. P1 批量/播放列表真实入队
3. P1 SAF 保存目录
4. 1.4.3 回归：无暂停完整下载 + 失败自动重试/封面幂等
5. P2 剩余四项：亮度值、断点续播、崩溃日志、嗅探复制/分享
6. 决定 1.4.2「切页不断播」是否接受
7. Gitee 推送与发行版（先定分支策略，再传附件）

---

## 2. P1 未完成项

B 站主链路已经通过，以下不是 B 站主链路本身的问题，而是 P1 其他验收项。

- [ ] **通知栏暂停/继续按钮没有真实点击过**
  - 已确认任务通知有 2 个 Action（取消 + 按状态显示暂停/继续），`NotificationActionReceiver` 已注册，`exported=false`
  - 未做：下拉通知栏点暂停，核对任务变 `PAUSED`、`.part` 保留；再点继续，核对变 `DOWNLOADING` 并续传
  - 注意：Android 11 通知 Action 可能需要展开通知才看得到按钮

- [ ] **批量/播放列表没有真实入队验证**
  - 实现：空白拆 URL、`--flat-playlist --dump-json`、`_type=playlist` entries 最多 200、统一 `bestvideo*+bestaudio/best`
  - 阻塞：ADB `input text` 会把多行链接拼成一条，之前只得到“0 个任务，1 个解析失败”
  - 明天可用：先用单条播放列表 URL（如 B 站合集/多 P）验证自动展开；再用剪贴板粘贴两条独立 URL，拆成两个任务
  - 要核对：任务数量、每个任务标题、并发 2 的排队、失败项是否单独显示

- [ ] **SAF 保存目录未完成**
  - vivo DocumentsUI 里根目录提示“无法使用此文件夹”；选 `Download` 后“使用这个文件夹”按钮不亮
  - 未验证：`OpenDocumentTree` 返回 URI、持久化权限、`DownloadSettings.saveTreeUri`、`PublicStorage.publishToTree` 写入和删除私有源文件
  - 可尝试：改选 `Download/VidGrab` 子目录；或换系统文件选择器/第三方文件管理器；仍不行则记录为 vivo 系统限制，保留 MediaStore 默认路径

- [ ] **字幕链路缺可验证源**
  - 本地测试源无字幕轨；B 站 `BV1v4Wr6uEoG` 等候选 `x/player/v2` 返回 `subs=0`，没有 CC 字幕
  - 未验证：`--write-subs --write-auto-subs --sub-langs zh.*,en.* --convert-subs srt` 实际产出 srt/vtt/ass/ssa，以及 `publishExtras` 发布
  - 明天需要：找一个带字幕的公开源（YouTube 自动字幕/有 CC 的站点），或登录 B 站后找有 CC 的视频；勾选“保存字幕”验证公共目录出现 srt

- [ ] **仅 Wi‑Fi 中途断网未实测**
  - 只验证了“仅 Wi‑Fi 开启时移动网络下排队/提示”，没有在下载中途关 Wi‑Fi/切移动数据
  - 要核对：任务进入 `PAUSED` 或排队、不继续偷跑流量；恢复 Wi‑Fi 后继续/重试

- [ ] **并发 3 路真实任务未验证**
  - 设置里 `concurrent=3` 已写入 `download_settings.xml`，但是否真有 3 个任务同时 DOWNLOADING 没观察到
  - 准备 3 个短任务同时入队，看任务页/日志

- [ ] **杀进程重启后的自动续传未实测**
  - 任务 JSON 落盘、未完成任务重启续传已实现；没有做 `force-stop` 后重开对比
  - 要核对：任务还在、状态恢复、`.part` 继续增长、不重复下载已完成文件

- [ ] **限速只实测了 512 KB/s**
  - 1024 / 2048 / 4096 KB/s 未逐档验证；不是阻塞项，时间够再补

---

## 3. P2 未完成项

- [ ] **播放器左侧亮度手势没有读到亮度值**
  - 手势能触发，但没有 UI 显示当前亮度百分比；需要确认是否只是没显示，还是亮度值没有真正变化
  - 要核对：左侧上下滑时系统亮度变化、松手后保留、退出播放器是否恢复

- [ ] **断点续播没有做“退出重进”对比**
  - 需要记录退出前位置（如 `t=35s`），杀掉/退出播放器再进，确认从接近位置继续，而不是从头
  - 分别测直链 MP4 和 yt-dlp 成品

- [ ] **CrashReporter 没有制造真实崩溃**
  - `files/crash_log.txt` 未验证生成；设置页“反馈/导出崩溃日志”分享未在真实崩溃后测试
  - 可临时在 debug 包触发一次异常（测完恢复代码/不提交），核对日志、分享 Intent

- [ ] **嗅探结果的“复制/分享”没有逐项点击**
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
