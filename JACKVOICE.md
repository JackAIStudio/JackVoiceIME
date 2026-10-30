# JackVoice —— 这个 fork 加了什么

基于[同文输入法 Trime](https://github.com/osfans/trime)（GPL-3.0）改造的语音输入法。
目标形态对标 [Typeless](https://www.typeless.com/)：**语音面板就是主键盘**，点进任何
输入框先看到「点击说话」，文字自己落进去，不用粘贴。

上游 README 保留在 [README.md](README.md)，这份只讲我们改了什么。

---

## 三个部分

| 位置 | 内容 |
|---|---|
| `app/…/ime/voice/` | 语音：面板、录音、识别、词典、历史 |
| `app/…/ui/jackvoice/` | 主界面四个 Tab（首页 / 历史记录 / 词典 / 设置） |
| `lexicon/` | **词库唯一源头**（`.ts`）+ 导出到 assets 的脚本 |

## 系统里显示的名字

- 应用名、输入法服务名、启动器名都走 `app_name_release` / `app_name_debug`，
  现在统一是 **JackVoice**；中文系统里不再出现「同文输入法（调试）」
- 键盘子类型的副标题是 `trime_app_slogan`，中文现在显示「说话即成文」
- `applicationId` 仍是 `com.osfans.trime`（debug 为 `.debug`）。改包名会换数据目录、
  丢掉已装的 Rime 配置，还要重新在系统里选输入法，所以品牌名和包名分开处理

## 键盘形态

- **语音面板 = 主键盘**。`InputView.startInput()` 直接挂 `VoiceWindow`，不是挂 `KeyboardWindow`
- 面板三态：空闲（黑胶囊麦克风）/ 录音（黑圆 + 实时波形 + 光晕）/ 处理（Thinking 进度胶囊）
- 尺寸都是从 Typeless 截图里逐像素量出来的（胶囊 145×60dp、录音圆 128dp、面板 320dp…）
- **语音底栏与打字键盘共网格**：底行是标点 / 空格 / 动作键（15% / 70% / 15%），
  右下角动作键与拼音、EN 在同一个像素上；它上面一格是 ⌫。
  键面颜色、13dp 圆角和 3.5×6dp 内缩都跟 `qwerty` 的 `KeyView` 一致
- **左下角是标点档位**：点按在「全标点 → 去句号 → 无标点」之间循环，长按弹出三选一菜单直接跳。
  三档都向服务端要标点再本地处理：去句号只剪末尾一个「。」；无标点把标点换成空格，
  数字 / 时间 / 邮箱 / 网址里的符号不动。默认全标点，档位记在本机；地球键已去掉，
  切别的输入法走系统设置或通知栏
- 顶栏左侧品牌、右侧 `[🎙|拼|EN]` 切换器（142×36dp，字形 17sp）；打字键盘是重写过的极简四排
- **空格长按 = 触控板**：按住空格后键盘淡出、上方浮出「移动光标」，左右拖动改选区，
  上下拖动只在多行输入框里发方向键；光标到文本首尾就停住，不会把焦点移出输入框。
  没拖动就松手仍然按一次空格，轻点不受影响。语音面板底栏的空格走同一套
  `GestureFrame`/`moveCursorBy`，不是另写一份
- 123 页照 Typeless 抄：数字 / 半角符号 / `#+=`+中文标点 / `字母`+空格+动作键；
  `#+=` 进第二页（`symbols2`），第二页左下角 `123` 回第一页
- **换布局不改语言**：`symbols` / `symbols2` 都**不写** `ascii_mode`，也不写
  `reset_ascii_mode`。中英只由顶栏胶囊 `[🎙|拼|EN]` 和输入框类型（密码 / 邮箱 /
  数字强制 ASCII）决定。以前这两页写了 `ascii_mode: 1`，`Keyboard.resetAsciiMode`
  又默认跟着布局强制生效，于是点一下 123 就把中文切成英文、组合中的拼写还被顺手
  确认上屏；左下角那颗键回字母时再强制切回中文，胶囊来回跳。
  现在 `resetAsciiMode` 默认 **false**，只有 YAML 显式写 `reset_ascii_mode: true`
  的布局（英文方案的 `letter`）才允许切语言，判定收在 `asciiModeOnAttach()`
  （`KeyboardResolve.kt`）。数字页左下角那颗键是**回字母键盘**，所以标签叫「字母」，
  别改回「拼音」——它不做语言切换
- **英文不留组合下划线**：英文不需要「确认」，所以 EN 只是 `ascii_mode`，字母直接上屏；
  候选由 `EnglishCompleter`（`assets/en_words.txt`，9.5k 常用词，词频序）按前缀给，
  点中就把刚打的词干换成整词。走 Rime 方案的话必然会带组合下划线，做不到这个观感
- **英文候选点中即收起**：提交后直接回到「品牌 + 胶囊」，并且用一个短抑制窗口挡住
  紧随其后的 `onUpdateSelection`——否则刚上屏的整词会被当成它自己的补全候选反复弹回来，
  点一次 testing 又回不到原位
- **模块会记住**：`VoicePrefs.lastTab` 记下用户上次停在 🎙 / 拼 / EN，下次展开键盘
  直接回到那一屏；密码、邮箱、数字这类强制 ASCII 的输入框只临时改语言，不覆盖选择

### 组合中变成两行：拼写一行、候选一行

```
┌────────┐
│ ce shi │                  ← ComposingBarView：贴在键盘上沿左上角的小气泡，只占文字那么大
├────────┴──────────────  ← 键盘顶边（固定不动的内容区顶边）
测试 侧室 侧视  ⌄           ← 原来的「JackVoice + 🎙|拼|EN」那一行，组合时让给候选
q w e r t y u i o p        ← 按键
```

不输入时还是「品牌 + 胶囊」；一输入中文，品牌和胶囊收起、候选占那一行，
拼写以**半透明小气泡**浮在键盘左上方（`ComposingBarView`）。⌄ 展开的整页候选盖在按键区上（`CandidatePanelView`）。

候选行右侧（展开箭头左边）留了一个 **「拼/EN」小键**：候选一出现就把整行顶栏占满，英文候选又几乎常驻，
没有这一格就切不回中文。它显示的是**目标语言**——中文候选时显示「EN」，英文候选时显示「拼」。

**键盘总高度从头到尾不变**（语音面板和打字键盘都是 320dp = 留白 11 + 顶栏 44 + 缝 9 + 按键 236 + 底部让位 20），
切过去不会「落地」矮一截。拼写气泡**不算键盘高度**：`InputView.imeContentTop()` 固定报键盘顶边，
不随气泡出现/消失改变 `contentTopInsets`——否则每打一个字 App 可见区就变高变矮，整屏会抖。
气泡只覆盖左上角一小块，右边和上方都露给 App。

四处连带的约定，改键盘前先看一眼：

| 位置 | 约定 |
|---|---|
| `InputView.applyBarVisibility` | Trime 原来那条独立候选栏（`InputBarDelegate`）**永远是 GONE**，别再打开 |
| `ComposingBarView` | 气泡在输入法**可触摸区域之外**——它只显示不接收触摸，所以别再往里加可点的东西（要加就得像预编辑那样套一个透明 `PopupWindow` 转发触摸）；宽度随文字、最多 60% 屏宽，别改回整行 |
| 英文候选（`EnglishCompleter`） | 不经过 Rime，**没人替它刷新**：切键盘时由 `KeyboardWindow.onAttached`、`ascii_mode` 变化时由 `onRimeOptionUpdated` 主动补候选；切语言前先 `clearCandidates()`，别让上一种语言的候选留在顶栏上 |
| `commitEnglishSuggestion` | 删词干 + 提交整词要用 `beginBatchEdit/endBatchEdit` 包起来，并且**不要再 `clearEnglishSuggestions()`**——先清会把顶栏闪回「品牌 + 胶囊」，再点候选还可能吞字 |
| `Rime.kt` 的 `inlinePreeditMode` | 强制 `COMPOSING_TEXT`：拼音字母直接进输入框；预编辑里的 `‸`(U+2038) 光标标记必须剥掉，不然会被当正文写进输入框 |
| 主题 `CommitAction` 键 | 底行动作键。组合中第一下只做「确认」：把下划线里的原始拼写原样上屏（`nihao` → `nihao`），不选中文候选、也不触发搜索/前往；组合结束后再按才执行宿主 `imeOptions` 动作 |
| 展开候选面板（`CandidatePanelView`） | Rime 一次只交 16 条（JNI `getBulkCandidates` 的 bulk 上限），滑到底要靠 `CandidatePages` 按**全量下标**用 `getCandidates(offset, limit)` 一批批要；点候选传的也是全量下标，别改成面板里的序号 |
| `Keyboard.resetAsciiMode` | 默认 **false**：换布局不碰 `ascii_mode`。只有显式写 `reset_ascii_mode: true` 的布局（英文方案的 `letter`）才切语言，判定在 `asciiModeOnAttach()`。新增键盘布局时**别**顺手加 `ascii_mode`，数字/符号页尤其不能加——那会让点 123 变成切英文 |

## 外观与深色模式

设置页第一个分组是「外观」：**自动 / 浅色 / 深色**，默认自动跟随系统。
它只写一个偏好 `ui_mode`（`AppPrefs.Advanced.UiMode`），由
`AppearanceManager` 统一分发，别在别处再读系统 `uiMode` 自己判断：

| 层 | 怎么跟 | 切换时 |
|---|---|---|
| 主界面 / 原生设置 | `AppCompatDelegate.setDefaultNightMode`（DayNight 主题 + `values-night`） | Activity 自己重建；JackVoice 用 `onSaveInstanceState` 保住当前 Tab |
| 键盘主题 | `ColorManager` 用生效的深浅色做 `ColorSchemeResolver`；`trime.yaml` 的 `default` 声明 `dark_scheme: jv_dark` | `ColorManager` 发变更，`InputView.refreshColors()` 原地重绘 |
| 语音面板 / 顶栏 / 候选 / 拼写气泡 | `JvColors` 按生效模式返回两套色值 | `AppearanceManager` 通知输入法服务，调 `refreshColors()`，不重建视图 |

- 不再有独立的「跟随系统夜间模式」主题开关：外观模式是唯一开关。
- 系统深浅色变化只在「自动」时生效；显式浅色/深色不受系统影响。
- `JvColors` 的深色键面刻意对齐 `trime.yaml` 的 `jv_dark`：
  键盘底 `#1C1C1E`、键面 `#2C2C2E`、主文字 `#F5F5F7`，语音面板和拼音键盘切过去不会一深一浅。

## 语音链路

```
点大圆 → 麦克风前台服务 → 16kHz 单声道录音 → 云端豆包流式（59 条热词）
      → 二遍终稿 → 76 条替换规则 → 标点档位处理 → commitText 落到当前输入框

                           或

点大圆 → 麦克风前台服务 → 16kHz 单声道录音 → 本地 X-ASR 480ms int8（2 线程）
      → 流式终稿 → 替换规则 → 标点档位处理 → commitText
```

### 云端 / 本地切换

语音面板左上角有一颗 56dp 的 `云端 / 本地` 方块，点一下切换；设置页「语音识别 → 识别引擎」
是同一个偏好（`VoicePrefs.engine`）。**引擎在开始录音时定下，中途不切**，避免同一个
会话里两套结果打架。

- 云端：现有豆包链路，需要 API Key；没网时仍会先录音，停下后走重新识别。
- 本地：sherpa-onnx + X-ASR，不需要 API Key，没网也能出字。模型首次点「本地」时下载
  （约 128MB，解压后约 175MB），之后从 `filesDir/local_asr/x-asr-480ms-int8-…` 读取。
- 模型没装好时点「本地」会先下载，下载完成自动切过去；点回来就回云端。
- 本地模型加载约 2–3 秒（首次），之后同进程复用；2 线程 CPU 推理，实测计算
  RTF ≈ 0.1（Turbo 5），停止后终稿约 150–250ms。手机上没有可用的联发科 NPU 路径，
  不要指望 QNN。
- 本地不做热词，也不适合 APP 名/数字/网址：这些仍走云端或后续的词典后处理。
- 模型 tar 是 bzip2，Android 解压慢（首次约几分钟），所以不要把它塞进 APK；
  AAR 在 `app/libs/sherpa-onnx-1.13.8.aar`，只保留 JNI + onnxruntime 两个 .so。

`VoiceEngine` 里云端与本地各一个客户端，共用 `handlePartial/handleFinal`；
`VoiceTranscriber`（历史里的重新识别）会看当前引擎：云端走火山，本地走本地模型。

录音圆里的波形跟着真实音量走：`VoiceLevel` 用每块 PCM 的 RMS 减去一条会自己跟的
底噪线（预热 3 块，静音时必须是 0），不再用 `sin(t)` 假动画——停嘴之后柱子就停在
最小高度。流式一遍结果会实时显示在面板的预览气泡里（`VoicePanelView.setPreview`），
终稿到了才 `commitText`；预览只留最后 30 个字，两行放不下就丢掉开头。

## 说话时按住别人的声音

手机里同时放着歌、视频、直播，那些声音会一起进麦克风。设置页「语音识别 →
说话时按住其他应用的声音」有三档（`MuteMode`）：

| 档位 | 做了什么 | 直播按得住吗 |
|---|---|---|
| 不处理 | 什么都不做 | — |
| 焦点协商 | 申请 `GAIN_TRANSIENT_EXCLUSIVE` 音频焦点 | ❌ |
| **音量归零**（默认） | 焦点 + 把 `STREAM_MUSIC` 压到 0，说完恢复 | ✅ |

**为什么焦点不够。** 音频焦点是**协商**，不是强制：守规矩的播放器收到
`AUDIOFOCUS_LOSS` 会自己暂停，但没申请焦点的应用根本收不到通知。直播类应用
正是重灾区——华为测过 100 款应用，直播类未申请焦点率达 39%
（[华为开发者联盟](https://developer.huawei.com/consumer/cn/forum/topic/41597864)）。
这就解释了「B 站看视频能暂停、看直播照响」：视频走 `MediaPlayer`/ExoPlayer，
本来就有暂停概念；直播是自家推流内核，不申请焦点，而且**没有暂停这个概念**
（让它暂停等于让观众漏看一段），所以宁可硬播。对这类应用只有动系统音量管用。

**音量归零必须能恢复。** 这是唯一会真的改用户设置的一档，代价是三条硬要求，
改这块之前先看 `DictationAudioMute`：

| 约定 | 为什么 |
|---|---|
| 只有 `VoiceSession.finishCapture` 一处调 `release()` | 那里是所有结束路径（停止 / 取消 / 出错 / 输入法销毁）的**唯一**出口，漏一条用户的手机就一直静音 |
| 归零**之前**先把原值写进 `voice_audio_mute` | 顺序反过来，中间被进程杀掉就丢了这个数 |
| `recoverIfStuck` 在 `VoiceSession.bind` 里跑一次 | 输入法被系统回收 / 强停时会绕过 `release`。自愈只在「落盘有记录 **且** 当前音量是 0」时动手，用户自己调的音量不会被误伤 |

只压 `STREAM_MUSIC`：来电、闹钟、通知音都还在。不做整机静音——那要
`WRITE_SECURE_SETTINGS`，会动响铃和来电，副作用太大。

录音期间键盘按键音也一起闭掉（`InputFeedbackManager.keyPressSound`）：
那点声音会被自己的麦克风录进去，外放时还会被当成回声消掉一部分人声。

## 万物留痕：录音永远先落盘

**只要说了话，这段声音就一定在本地。** 录音一开始就写 WAV，识别失败只改状态、
不丢音频，用户随时能重新识别。这是与桌面版对齐的那一层兜底。

```
点大圆 → VoiceHistory.begin（先建记录）→ VoiceAudio.Writer（边录边写 .wav.part）
      → 同一块 PCM 同时喂豆包流式 → 停止时回填 WAV 头并改名 → 终稿/失败都落进这条记录
```

| 位置 | 做什么 |
|---|---|
| `VoiceAudio` | 16k/16bit/单声道 WAV 写入器。44 字节头先占位，每块 PCM 直接 `RandomAccessFile` 写进去（不经用户态缓冲，进程被杀也留得住），收尾回填长度。录到一半中断留下 `.part`，下次启动 `recoverInterrupted` 修好并交给 `VoiceHistory.adoptOrphans` 补一条失败记录 |
| `VoiceHistory` | 记录状态机：`recording → transcribing → completed / failed / no_network / empty`。**先建记录再谈识别**——失败的那条也留在历史里，带音频路径 |
| `VoiceEngine` | 同一块 PCM 走两条路：写盘 + 喂识别。写盘失败不拖累识别；`cancel()`（用户明确不要）是**唯一**会删音频的路径 |
| `VoiceTranscriber` | 把已存的 PCM 重新送进识别。云端仍是 3200 字节一块、10ms 间隔的流式回放；本地则直接走 `LocalAsrEngine.transcribe`，不建 WebSocket |

**不做自动重试。** 手机上只在用户点的时候跑：语音面板上那颗小圆刷新按钮
（`ic_baseline_refresh_reversed_24`，40dp）、历史页里的「重新转写」。
服务端明确说这段没人说话（`empty`）之后按钮就收起来，别让人反复点同一个空结果。

**但重识别必须有兜底。** 它可能卡在等一个永远不来的 WebSocket 终稿，面板不能
只挂一句「正在重新识别…」：

- 进度界面现在会亮出一颗「取消」，点一下立刻回到空闲，任务在后台收；
- 30 秒 UI 看门狗（`VoiceSession.RETRY_UI_TIMEOUT_MS`）到点强制恢复，提示
  「重新识别超时，已停止，可重试」；
- 重识别期间禁止切云端/本地，避免「界面显示本地、任务却在云端」；
- 本地引擎的重识别走本地模型，不再因为断网去连火山。

### 「没听到人声」只能由服务端下结论

这是踩过的坑，值得单独记一笔：重新识别失败时，两处调用点（`VoiceSession.retry`、
`JackVoiceActivity.retryEntry`）**不管什么原因都写 `Status.Empty`**。而 `Empty`
在 `canRetry` 里是被排除的——于是断一次网，这条录音就被永久判死：

- 历史页写着「这段里没听到人声」，可音频里明明有人声
  （实测 01:29 那条 57% 的块是明显人声，重新识别后正确转出 25 个字）；
- 「重新转写」按钮再也不出现，用户连补救的入口都没有。

现在判据收在 `VoiceHistory.statusAfterRetryFailure(message, retryable)` 一个地方，
语音面板和历史页共用，别再各写一份 `if`：

| 情况 | 状态 | 按钮 |
|---|---|---|
| 服务端说这段没人说话 | `Empty` | 收起——再点也是白点 |
| 网络类 / 连接被掐 / 超时 | `NoNetwork` | 留着 |
| 其余（服务端报错等） | `Failed` | 留着 |

`retryable` 这个布尔**不能**再决定要不要写 `Empty`：它同时被用来表达
「网络问题」和「这段没人说话」，两件事混成一件正是病根。

读取时还有一层修复（`ensureLoaded`）：老数据里 `Empty` 且错误串是网络类的，
自动改回 `NoNetwork`，用户升级后那些被误杀的录音自己就活过来了。

### 重新识别的结果：先预览，再插入

**不自动上屏。** 重新转写是一条补救路径（当时没网 / 没等到终稿），不是日常热路径：
它可能要等好几秒，结果也未必是用户想要的。直接塞进输入框会打断已经打的内容，
而且没法反悔。所以：

```
点刷新 → 面板「正在重新识别 01:26 那段…」
      → 出结果 → 面板上给一张卡片：只读文字 + 「丢弃」/「插入」
      → 点「插入」才走 deliver() 落进输入框（正在拼写时照样排队）
```

卡片是**只读**的，不在上面编辑：改字该在输入框里改，在面板上做一套编辑
就要和 Rime 的组合输入抢状态，收益远小于成本。

| 位置 | 做什么 |
|---|---|
| `VoiceSession.preview` | `VoicePreview(entryId, text)`。只在 Idle 有意义；`start()` / `cancel()` 会主动收掉 |
| `VoiceSession.commitPreview()` / `discardPreview()` | 两个出口。插入走 `deliver()`，与正常终稿同一条路径 |
| `VoicePanelView.setPreviewText` / `setRetrying` | 卡片与进度的显隐。卡片开着时**麦克风让位**，两者都在面板中间那块，同时出现会点错 |
| `JackVoiceActivity`（历史页） | **只把文字补进记录，不往输入框塞**：那里没有「用户正在哪个输入框」这个上下文，贸然 `commitText` 会落到他完全没在看的 App 里 |

卡片尺寸有硬约束：面板 320dp，底栏 `KEY_ROW_DP*3 = 177dp` 加 `BOTTOM_SAFE_DP 20dp`，
**键区上沿在 123dp**。卡片从 51dp（顶栏下沿）铺到 119dp，高度**封死**，
文字多了在卡片内部滚。以前按 84dp 起算只剩 35dp 高，长文字把卡片撑到键区上，
压住了 ⌫（那颗键点不动）。

### 刷新键不能只活在内存里

`retryEntryId` 以前是普通字段：`unbind()` 里清空、只在 `bind()` 时补一次。
输入法服务被回收/重建的时机稍有偏差，刷新键就再也不出现
（实测：历史里有 11 条可重试，面板上却没有那颗键）。

现在它是**计算属性**：内存里没有就回退到 `VoiceHistory.retryable()` 取第一条，
读到就缓存。只要磁盘上有可重试的录音，键就在。

### 重新识别的回调必须切回主线程

`VoiceTranscriber` 的回调来自 OkHttp 线程，而 `select()` / `deliver()` 直接增删 View。
在后台线程碰 View 要么抛 `CalledFromWrongThreadException`，要么在「刚好没触发检查」
的时候**悄悄不刷新**——实测就是后者：文字已经写进历史（`status=completed`、
正文完整），卡片却还停在「正在重新识别…」。现在两处回调都先过一遍
`onMain` / `post`。

### 「正在重新识别…」不能一直挂着

面板上那句话是 `VoiceSession.retryingEntryId` 驱动的。它以前有三条路会漏：

1. **`pump()` 空转**：每 10ms 一拍，等「发送队列空出来」；队列要是一直不空，
   `sent` 永不前进，就这么拍到 60 秒总超时。用户盯着转一分钟。
   现在连续 200 拍（2 秒）推不动就认输，立刻报错收尾。
2. **建连没有看门狗**：`onOpen` 不来时 `pump` 根本不会启动，只剩 60 秒兜底。
   现在另挂一个 15 秒的 `openTimeout`，连不上就说连不上。
3. **`unbind()` 漏清**：输入法服务重建时没清 `retryingEntryId` / `preview`，
   重建后界面继续显示「正在重新识别…」，而实际上压根没有任务在跑。
   现在一起清掉，并且顺手 `VoiceTranscriber.cancel()`。

最后还有一道兜底（`VoiceWindow.syncRetrying`）：显示前拿
`VoiceTranscriber.busyEntryId` 跟标记对一次——**标记是界面状态，任务是事实**。
标记残留而事实没有任务，就直接 `clearStaleRetrying()` 收掉那句话。

实测：断网后点刷新，5 秒内就回到「点击说话」并重新亮出刷新键，
不再是干等一分钟。

### 那颗刷新键的规格与位置

它是键区 **第 0 行第 2 列**——和中间那颗大胶囊同一水平高度、在它右边，
也就是底栏本来就空着的那一格。规格跟 ⌫ 完全一致：同一套键面、13dp 圆角、
`addKey` 统一的内缩。**图标用正文色，不上蓝色**：整块面板只有黑白灰，
凭空多一个彩色按钮会很跳。

**但键面不能只用 `JvColors.keyBg`。** 这是最坑的一处：浅色模式下 `keyBg` 是纯白
`#FFFFFF`，面板背景是 `#F1F1F1`，对比度只有 6%——那颗键**一直在、也一直能点**
（实测点它确实会触发重新识别、日志里能看到火山连接建立），但肉眼几乎看不见，
用户于是认为「根本没有重新转写按钮」。现在补一圈 1dp 描边
（`retryKeyBackground()`，浅色用 `divider` 灰、深色用亮一点的灰）把它托出来。
**改这颗键的外观时，务必在浅色模式下真机看一眼**，别只信代码里「设了背景」。

同理，它的显隐也别只靠外部在「状态变化」时推一次：面板一直开着、状态没变
（重新识别失败后又回到 Idle），就没人再推，键该出现时不出现。现在
`VoicePanelView` 自己持有 `retryAvailable`，`setState` 和 `setRetryAvailable`
都会重算（`applyRetryVisibility()`），并且面板每次 `onAttachedToWindow`
都回来同步一次。

不能压在麦克风那块画布上。`VoiceOrbView` 的画布是 160dp 见方（录音圆 + 光晕要那么大），
而空闲时只画一颗 60dp 高的胶囊；按钮叠进画布范围，点它触发的是**录音**
（实测踩过两次，历史里多了两条空录音）。放进键区就天然避开这块画布，
并且录音 / 处理中底栏整体收起时它也一起消失。

## 识别会断，录音不会

**麦克风与网络解耦。** 这是 `VoiceEngine` 最要紧的一条约定，也是踩过的坑：

以前识别连接一失败就走 `finishWithError`，顺手把录音也收了。用户正说着话，
地铁进隧道、VPN 抖一下，屏幕直接弹「识别失败：connection closed」，
后面说的话一个字都没录下来，连重试的音频都没有。这完全说不过去：
麦克风是本地的，网络是外部的，外部的坏了不该把本地的也停掉。

现在连接断掉只做一件事：把 `asr` 置空、记下 `asrError`，**录音继续**。

```
连接断了 / 一开始就没网 → 录音照常 → 停下 → 音频落盘 → 历史里留一条可重试的记录
                                             → 用户点刷新键 / 历史页「重新转写」
```

| 位置 | 做什么 |
|---|---|
| `VoiceNetwork` | 开录前问一句「现在有网吗」。没网就不建连，省掉十秒注定失败的连接超时——那十秒里用户说的话会一句不剩。它**不是**安全闸门：判为有网、实际连不上（VPN 半死、DNS 挂了）照样会发生，由下面的降级路径接住 |
| `VoiceEngine.onAsrBroken` | 识别线断了：只停识别，`asrError` 记下原因，录音不动。回调 `onAsrDegraded` 让界面把「正在聆听」换成「先录着」 |
| `VoiceEngine.stopRecording` | 停下时如果根本没有识别连接（`asr == null && asrError != null`），不必空等三秒终稿，直接收尾把音频安置成可重试 |
| `VoiceFailure` | 纯字符串判定：网络类 / 鉴权类 / 其余。`connection closed` 这种原始异常不再甩到用户脸上。`describe` 用于当场提示，`describeForHistory` 用于事后回看（「当时网络断了」） |
| `VoiceSession.asrDegraded` | 状态机仍然只有 Idle / Recording / Thinking 三态——**网络好坏是它的注脚，不是第四个状态**。面板和通知栏读它换提示语 |

**只有麦克风自己坏了才真的结束这一轮**（`finishWithError`），那种情况下也没有音频可以留。

## 前台服务的通知只能由它自己撤

用户截图里那条「明明没在听了，下拉还写着正在聆听」是这么来的：

1. 引擎自己走完（终稿 / 出错 / 超时兜底）时，`onFinal` / `onError` / `onRetryable`
   **都没停前台服务**——只有 `stop()` / `cancel()` 会停，而那两条路径不走。
2. 更要命的是 `NotificationManager.cancel()` **撤不掉前台服务自己的那条通知**。
   只要服务还是前台状态，通知就由 AMS 托着，只有
   `stopForeground(STOP_FOREGROUND_REMOVE)` 才真的把它从通知栏拿走。
   于是那条「正在聆听」永远不会走。

现在两条都补上了：三个收尾回调统一走 `finishCapture()`；`VoiceCaptureService`
的 `onDestroy` 和 `stop()` 都显式 `stopForeground`。实测录音中通知在
（标题「正在聆听…」），停止后通知记录与 ServiceRecord 双双归零。

## 听写会话与键盘模块解耦

听写不是语音面板的私有功能，而是一份常驻会话（`VoiceSession`，挂在输入法服务上）。
面板只是它的一个观察者：

```
VoiceCaptureService（前台，只为拿麦克风）
       └── VoiceEngine（录音 + 豆包流式 + 终稿）
             └── VoiceSession（状态 + 上屏 + 待插入队列）  ← 常驻
                   ├── VoiceWindow（面板 UI）
                   └── KeyboardWindow 顶栏（🎙 变色）
```

- 切到「拼 / EN」时面板只是 detach，**不 cancel 录音**。顶栏 🎙 录音中变红、
  等终稿变橙，点它回面板就能停。
- **候选行里也有入口**：录音中打拼音时，「品牌 + 胶囊」整行让给候选，那颗 🎙
  就不见了；候选行右侧（「EN」左边）会多一格红色波形，点它直接回语音面板。
  通知栏则常驻「正在聆听」，点通知卡片也算说完了。
- 切回面板按会话状态恢复（录音中 / 处理中 / 空闲），不会重置成「点击说话」，
  最后一段流式预览也会补回来。
- **手动拼写与听写并行**：语音终稿到达时如果用户正在组合中，先排进待插入队列，
  等这一轮组合结束（选完候选 / 清空）再插到光标处，不打断手动输入。
  键盘收起期间到达的结果同样排队，下次展开键盘时补插。
- 用户明确回到语音面板 / 开始新一轮录音时，待插入结果会强制落盘：
  先 `finishComposingText` 把已打的组合收尾（保留手动输入的字），再插入语音文本。
- `VoiceSession.unbind()` 只在输入法服务销毁时调用；面板、键盘窗口的 detach
  与它无关。`VoiceEngine.isWaitingFinal` 让服务重建后还能恢复「处理中」。

## 通知栏常驻入口：不展开键盘也能开录

以前只有录音时才有通知，而它的存在前提是「已经在录了」——键盘收着的时候
**完全没有入口**，想说话必须先点开某个输入框。现在通知栏里常驻一条
「点一下就开始说话」：

```
VoiceResidentService（specialUse 前台服务，只在空闲时挂着）
       └── 通知：「点一下就开始说话」
             └── 点卡片 → VoiceQuickStartActivity（透明跳板）
                   └── VoiceResidentService.toggleDictation()
                         ├── 空闲 → VoiceSession.start() → 麦克风前台服务
                         └── 录音中 → VoiceSession.stop(Notification) → 剪贴板
```

**任何时刻通知栏里只有一条 JackVoice**：开始录音时常驻那条让位给「正在聆听」，
录完自动挂回来（`VoiceSession.finishCapture` 里 `ensure`）。

### 三个坑，都实测过

1. **点击目标不能用 `startService`**。`contentIntent` 指向服务时，点卡片只会把
   服务重新提前台，`onStartCommand` 收到的还是上一次的 intent，`ACTION_START`
   根本送不到——点了完全没反应。
2. **也不能用 `getBroadcast`**。改成广播后，`dumpsys activity broadcasts` 里
   连记录都没有（只有 adb 手工发的那几条能到）。MIUI 只把「点通知 = 开个界面」
   当正经的点击意图。
3. **跳板 Activity 必须 `exported=true`**。系统发通知的 PendingIntent 以 SystemUI
   身份落地，`exported=false` 会被 `Permission Denial` 直接拒掉。它自己验
   `ACTION_QUICK_START` + 调用方来防冒用。

### 开录必须等前台状态到手

`startForegroundService` 是**异步**的。原来的写法是「起服务 → 紧接着
`startRecording()`」，键盘展开时因为输入法本来就在前台，看不出问题；
从通知栏开录时进程在后台，那一刻拿不到麦克风，`AudioRecord` 直接被拒。

现在拆成两步：`VoiceSession.start()` 只负责拉起服务并记下 `pendingStart`，
真正的 `VoiceEngine.startRecording()` 在 `VoiceCaptureService` 的
`startForeground()` 之后由 `onForegroundReady()` 触发。1.5 秒没等到就放弃
（`startWatchdog`），免得 `pendingStart` 永远挂着把常驻通知压死。

### 会话状态要能自愈

进程被重建、上一轮中途失败之后，`state` 可能停在 `Recording` 而引擎早就空了。
表现是「点通知第一下没反应，第二下才开始录」——第一下走了「停止」分支。
`VoiceSession.reconcileIfStale()` 按引擎为准把状态摆正，`toggleDictation`
每次点击前先调一次。

## 两个必须知道的坑

### ① 录音放行看的是 AppOps，不是权限清单

`pm grant RECORD_AUDIO` 之后权限显示 `granted=true`，但 AppOps 里的
`Uid mode: RECORD_AUDIO` 仍是 `ignore`，录音会被 audio policy 拒掉：

```
getInputForAttr: EX_SECURITY ... missing perms for source 1
```

**只有走系统授权弹窗（选「仅在使用中允许」）才会被置成 `foreground`。**
所以 `MainActivity` 启动时会弹权限引导，那一步不能省，也不能用 adb 代劳。

另外 `VOICE_RECOGNITION`（source 6）在 HyperOS 上同样被拒，音源用的是 `MIC`。

### ② 读输入法列表不能用 Settings.Secure

targetSdk 34 起读 `Settings.Secure.ENABLED_INPUT_METHODS` 会抛 `SecurityException`，
要用 `InputMethodManager.enabledInputMethodList`。

## 构建

```sh
# 需要 JDK 21、NDK 28.0.13004108、CMake 3.31.6
git clone --recursive <this-repo>          # librime / OpenCC / lua / snappy 是 submodule
./gradlew assembleDebug
```

本机适配过两处（都已在仓库里）：`minSdk = 24`（NDK 28 下 lua 的 `fseeko` 需要 API 24）、
`settings.gradle.kts` 加了阿里云镜像。

### ⚠️ 项目目录不能在 iCloud 里（**本仓已搬走**）

本仓原本放在 `~/Documents/Playground/Trime`，而 `~/Documents` 被「桌面与文档」
iCloud 同步托管（`com.apple.file-provider-domain-id:
com.apple.CloudDocs.iCloudDriveFileProvider/…`）。iCloud 会为它跟不上的文件生成
`xxx 2.kt` 这类**冲突副本**，连 `.git/index` 都被生成过 4 份。后果：

```
Cannot access output property 'destinationDirectory' … Failed to create MD5 hash for file: …/InputView 2.kt
remote unpack failed: index-pack failed          # 推送也会挂
Resource deadlock avoided                        # 连 mv / rm 都会被锁住
```

构建过程中会持续产生新副本，所以「清理 → 构建」循环也救不回来（实测连试 10 次全败）。

**本仓现已移到 `~/Projects/JackVoiceIME`**，不在 iCloud 里，构建连做多次零失败、零副本。
`~/Documents/Playground/` 下其它仓同样受影响，要动它们建议一并搬出。

## 词库

`lexicon/src/` 下的 `.ts` 是唯一源头，`app/src/main/assets/*.json` 是生成物。
想从桌面版重新抄一遍词库：

```sh
node lexicon/import-desktop.mjs    # 读桌面版共享数据目录，重写 .ts
node lexicon/export-assets.mjs     # 输出与仓库文件字节一致，不会制造垃圾 diff
```

改词库只改 `.ts`，再跑一次脚本。

热词表和桌面版共用：设置里填桌面版 `settings.json` 里的 `volcBoostingTableId`，
识别请求就直接用云端表；在词典页增删热词会整表覆盖同步上去。替换词仍在终稿后
本地执行，但热词和替换词会通过火山 TOS 的 `glossary.json` 在手机和 Mac 之间自动
双向同步：主界面或语音面板打开时拉取，本机改动后自动推送，冲突按修改时间新者胜。

## 与 JackAndroidVoice 的关系

`JackAIStudio/JackAndroidVoice` 是上一代方案（Xime 输入法插件 + 独立语音 App），
**已归档**。它的识别协议、热词、替换规则是这一版的基础，词库源头已搬进本仓 `lexicon/`。
那里的 `plugin/` 和 `app/` 只在你还想用「曦码输入法 + 语音插件」或「悬浮球 + 无障碍注入」
那两种形态时才有用。
