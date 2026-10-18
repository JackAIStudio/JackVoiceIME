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
- **语音底栏与打字键盘共网格**：底行是地球 / 空格 / 动作键（15% / 70% / 15%），
  右下角动作键与拼音、EN 在同一个像素上；它上面一格是 ⌫，再上面是 @。
  键面颜色、13dp 圆角和 3.5×6dp 内缩都跟 `qwerty` 的 `KeyView` 一致
- 顶栏左侧品牌、右侧 `[🎙|拼|EN]` 切换器（142×36dp，字形 17sp）；打字键盘是重写过的极简四排
- **空格长按 = 触控板**：按住空格后键盘淡出、上方浮出「移动光标」，左右拖动改选区，
  上下拖动只在多行输入框里发方向键；光标到文本首尾就停住，不会把焦点移出输入框。
  没拖动就松手仍然按一次空格，轻点不受影响。语音面板底栏的空格走同一套
  `GestureFrame`/`moveCursorBy`，不是另写一份
- 123 页照 Typeless 抄：数字 / 半角符号 / `#+=`+中文标点 / `拼音`+空格+动作键；
  `#+=` 进第二页（`symbols2`），第二页左下角 `123` 回第一页
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
| 主题 `CommitAction` 键 | 底行动作键。组合中先选高亮候选上屏，commit 到了再执行宿主 `imeOptions` 动作——**直接发 Return 会把原始拼音上屏** |

## 语音链路

```
点大圆 → 麦克风前台服务 → 16kHz 单声道录音 → 豆包流式（59 条热词）
      → 二遍终稿 → 79 条替换规则 → commitText 落到当前输入框
```

录音圆里的波形跟着真实音量走：`VoiceLevel` 用每块 PCM 的 RMS 减去一条会自己跟的
底噪线（预热 3 块，静音时必须是 0），不再用 `sin(t)` 假动画——停嘴之后柱子就停在
最小高度。流式一遍结果会实时显示在面板的预览气泡里（`VoicePanelView.setPreview`），
终稿到了才 `commitText`；预览只留最后 30 个字，两行放不下就丢掉开头。

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

`lexicon/src/*.ts` 是唯一源头，`app/src/main/assets/*.json` 是生成物：

```sh
node lexicon/export-assets.mjs     # 输出与仓库文件字节一致，不会制造垃圾 diff
```

改词库只改 `.ts`，再跑一次脚本。

## 与 JackAndroidVoice 的关系

`JackAIStudio/JackAndroidVoice` 是上一代方案（Xime 输入法插件 + 独立语音 App），
**已归档**。它的识别协议、热词、替换规则是这一版的基础，词库源头已搬进本仓 `lexicon/`。
那里的 `plugin/` 和 `app/` 只在你还想用「曦码输入法 + 语音插件」或「悬浮球 + 无障碍注入」
那两种形态时才有用。
