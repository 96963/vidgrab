# VidGrab 视觉规范

适用于落地页与 Android App。落地页实现见 `site/index.html`，本文是同一套 token 在 Compose 里的用法。

## 1. 定位

VidGrab 是一件拿走即用的工具：把网页里正在播放的流，变成手机里能播放、能保存的文件。视觉上要像一台可靠的仪器，不像一个推销产品的网站。

三条原则：

1. 结构即信息。分隔线、编号、状态色都用来表达真实状态，不做装饰。
2. 只保留一个记忆点。首页的嗅探面板与 HLS 分片条是唯一的高光，其余部分安静。
3. 说清楚，不喊口号。按钮和提示用动词，文案说明会发生什么。

## 2. 色彩

冷调纸面打底，广播蓝承担操作，磁带黄只用于「正在检测 / 当前分片」这类瞬时状态。

### 浅色

| token | 值 | 用途 |
|---|---|---|
| paper | `#EEF2F4` | 页面背景 |
| panel | `#F8FAFB` | 面板、列表底 |
| ink | `#10161B` | 主文字、描边 |
| body | `#2A3742` | 正文 |
| muted | `#586874` | 次要文字、说明 |
| line | `#C7D2DA` | 结构线 |
| hair | `#E1E8ED` | 列表分隔线 |
| blue | `#0B5FFF` | 主操作、链接、HLS 标签 |
| blueSoft | `#DCE7FF` | HLS 标签底 |
| yellow | `#F5B700` | 检测中、当前分片 |
| dash | `#6749C9` | DASH 标签 |
| dashSoft | `#E9E3FB` | DASH 标签底 |
| green | `#1C7A57` | MP4 标签、完成态 |
| greenSoft | `#DDF1E8` | MP4 标签底 |
| error | `#C62828` | 失败、危险操作 |

### 深色

| token | 值 |
|---|---|
| background | `#101820` |
| surface | `#182129` |
| surfaceVariant | `#243039` |
| onBackground | `#E7EDF1` |
| onSurfaceVariant | `#A9B7C2` |
| primary | `#7FA6FF` |
| onPrimary | `#0A1A3D` |
| secondary | `#F5B700` |
| onSecondary | `#1A1400` |
| outline | `#3A4853` |
| error | `#FF8A80` |

### 规则

- 蓝色只给可点击的主操作和当前选中的状态，不做大面积装饰。
- 黄色永远表示「此刻正在发生」，静态区块不使用。
- 绿色只表示完成，不表示成功营销。
- 同一屏内彩色标签不超过两种。

## 3. 字体

一套中文无衬线，靠字重与字号区分层级。不引入衬线、不引入等宽字体。

字体栈：`MiSans` → `HarmonyOS Sans SC` → `PingFang SC` → `Noto Sans SC` → 系统无衬线。

| 层级 | 字号 / 行高 | 字重 | 用途 |
|---|---|---|---|
| display | 40 / 44 | 800 | 落地页主标题 |
| headline | 28 / 34 | 700 | 页面标题 |
| title | 20 / 28 | 700 | 卡片、面板标题 |
| body | 16 / 26 | 400 | 正文、列表主文案 |
| label | 14 / 20 | 500 | 按钮、标签、次按钮 |
| caption | 12 / 18 | 400 | 时间、说明、元数据 |

- 中文标题字距 `-0.01em`，不要更紧。
- 所有数字开启 tabular（等宽数字），用于进度、时长、版本号。
- 行宽控制在 35 个汉字左右。

## 4. 间距、圆角与描边

- 间距基数 4：4 / 8 / 12 / 16 / 24 / 32 / 48 / 64。
- 圆角按层级区分，避免全站一个值：
  - 标签 chip：4
  - 按钮、输入框：8
  - 面板、底部面板：12（顶部）
  - 全屏对话框：16
- 描边 1dp，颜色 `line`；不使用投影。层级靠线与底色区分。
- 进度条高 2dp，轨道 `hair`，进度 `blue`；缓冲或未开始用 `line`。

## 5. 组件

### 按钮

- 主按钮：填充 `blue`，白字，高 48，水平内边距 20，文字 label 字重 600。一屏最多一个。
- 次按钮：1dp 描边 `ink`，透明底，文字 `ink`。用于「查看源码」这类并列动作。
- 文字按钮：用于面板内的低频动作，颜色 `blue`，高度 40。

### 状态标签

嗅探结果的时间类型标签：

| 类型 | 底色 | 文字色 |
|---|---|---|
| HLS | blueSoft | blue |
| DASH | dashSoft | dash |
| MP4 | greenSoft | green |
| AUDIO | hair | muted |

标签高度 20，圆角 4，字号 11，字重 700。

### 列表行

- 主标题 body 400，次标题 caption `muted`，右侧操作图标 24dp。
- 行高不小于 56，触控区域 48dp。
- 嗅探列表中的「当前命中行」用 `#FFF6D6` 底，操作按钮由隐藏变为显示。

### 下载进度

- 进度条 + 一行状态文本：`62% · 3.1 MB/s · 剩余 00:48`，数字 tabular。
- 完成态用绿色文字「已完成」，不用对勾大图标。
- 失败态用 `error` 色，写清楚原因与下一步，例如「HTTP 403，服务器拒绝访问。请用浏览器打开原页面后重试。」

### 底部面板与对话框

- 底部面板顶部圆角 12，标题 title，右上角放最高频的次要动作。
- 对话框只用于需要确认的破坏性操作；普通设置直接放面板，不弹窗。

### 空状态

一句话说明这里会出现什么，加一个动作。例如：

> 还没有任务。去「下载」粘贴链接，或到「浏览器」里打开播放页。

> 暂无书签。打开页面后，在菜单里点「收藏当前页」。

### 错误

不道歉、不模糊。三段式：发生了什么、为什么、可以怎么做。

## 6. 动效

- 标准时长 180ms，面板进出 220ms，缓动 `ease`。
- 页面与面板不做逐段淡入。
- 唯一的编排动效保留给嗅探：结果行依次出现，分片条一次性填满。仅首屏触发一次。
- 用户操作触发的动效必须展示变化，例如进度增长、面板展开。
- 尊重系统「减少动态效果」：关闭全部动画与过渡。

## 7. 无障碍

- 正文对比度不低于 4.5:1，大字不低于 3:1。
- 触控目标不小于 48×48dp。
- 所有图标按钮提供中文 contentDescription。
- 状态不能只靠颜色表达：在颜色之外补充文字或形状。
- 键盘与读屏焦点顺序与视觉顺序一致。

## 8. Compose 落地

建议关闭动态取色，固定品牌色；动态取色会让同一版本在不同手机上失去一致性。

### Color.kt

```kotlin
val Paper = Color(0xFFEEF2F4)
val Panel = Color(0xFFF8FAFB)
val Ink = Color(0xFF10161B)
val Body = Color(0xFF2A3742)
val Muted = Color(0xFF586874)
val Line = Color(0xFFC7D2DA)
val Hair = Color(0xFFE1E8ED)
val Blue = Color(0xFF0B5FFF)
val BlueSoft = Color(0xFFDCE7FF)
val Yellow = Color(0xFFF5B700)
val Dash = Color(0xFF6749C9)
val DashSoft = Color(0xFFE9E3FB)
val Green = Color(0xFF1C7A57)
val GreenSoft = Color(0xFFDDF1E8)

val LightScheme = lightColorScheme(
    background = Paper,
    surface = Panel,
    surfaceVariant = Hair,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Muted,
    primary = Blue,
    onPrimary = Color.White,
    secondary = Yellow,
    onSecondary = Ink,
    outline = Line,
    error = Color(0xFFC62828)
)

val DarkScheme = darkColorScheme(
    background = Color(0xFF101820),
    surface = Color(0xFF182129),
    surfaceVariant = Color(0xFF243039),
    onBackground = Color(0xFFE7EDF1),
    onSurface = Color(0xFFE7EDF1),
    onSurfaceVariant = Color(0xFFA9B7C2),
    primary = Color(0xFF7FA6FF),
    onPrimary = Color(0xFF0A1A3D),
    secondary = Color(0xFFF5B700),
    onSecondary = Color(0xFF1A1400),
    outline = Color(0xFF3A4853),
    error = Color(0xFFFF8A80)
)
```

### Type.kt

```kotlin
val VidGrabTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
)
```

### Theme.kt

```kotlin
@Composable
fun VidGrabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = VidGrabTypography,
        content = content
    )
}
```

### 现状差异

当前 `ui/Theme.kt` 在 Android 12 及以上使用动态取色，与本文规范不一致。若采用本规范，需要保留一套固定的 `LightScheme / DarkScheme`，并把动态取色作为可选项关闭。

## 9. 文案

- 动词开头，说清结果：「下载 APK」「收藏当前页」「清空检测」。
- 同一个动作在整条链路里用同一个词：按钮写「下载」，任务状态就叫「下载中」，完成叫「已完成」。
- 不使用夸张词。工具的价值由它做了什么说明，不由形容词说明。
- 数字与单位之间留空格：`74 MB`、`3.1 MB/s`、`00:48`。
