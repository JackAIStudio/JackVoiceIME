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

## 键盘形态

- **语音面板 = 主键盘**。`InputView.startInput()` 直接挂 `VoiceWindow`，不是挂 `KeyboardWindow`
- 面板三态：空闲（黑胶囊麦克风）/ 录音（黑圆 + 实时波形 + 光晕）/ 处理（Thinking 进度胶囊）
- 尺寸都是从 Typeless 截图里逐像素量出来的（胶囊 145×60dp、录音圆 128dp、面板 320dp…）
- 顶栏左侧品牌、右侧 `[🎙|拼|EN]` 切换器（142×36dp，字形 17sp）；打字键盘是重写过的极简四排
- 123 页照 Typeless 抄：数字 / 半角符号 / `#+=`+中文标点 / `拼音`+空格+动作键；
  `#+=` 进第二页（`symbols2`），第二页左下角 `123` 回第一页
- **英文是独立 Rime 方案**（`assets/shared/en_US.schema.yaml` + `en_US.dict.yaml`，约 9.5k 常用词）：
  点胶囊的 EN 是 `selectSchema("en_US")`，不是只翻 ascii_mode——只翻 ascii 的话中文方案会把字母原样透传、永远没有候选。
  方案的 `ascii_mode` 必须 `reset: 0`（置 1 会让 ascii_composer 直接放行字母）

### 组合中变成两行：拼写一行、候选一行

```
ce shi                     ← ComposingBarView：浮在键盘上沿外面（原来画方案提示的位置）
测试 侧室 侧视  ⌄           ← 原来的「JackVoice + 🎙|拼|EN」那一行，组合时让给候选
q w e r t y u i o p        ← 按键
```

不输入时还是「品牌 + 胶囊」；一输入中文，品牌和胶囊收起、候选占那一行，
拼写单独浮在键盘上方那条。⌄ 展开的整页候选盖在按键区上（`CandidatePanelView`）。

**键盘总高度从头到尾不变**（语音面板和打字键盘都是 320dp = 留白 11 + 顶栏 44 + 缝 9 + 按键 236 + 底部让位 20），
切过去不会「落地」矮一截。

四处连带的约定，改键盘前先看一眼：

| 位置 | 约定 |
|---|---|
| `InputView.applyBarVisibility` | Trime 原来那条独立候选栏（`InputBarDelegate`）**永远是 GONE**，别再打开 |
| `ComposingBarView` | 拼写条在输入法**可触摸区域之外**——它只显示不接收触摸，所以别再往里加可点的东西（要加就得像预编辑那样套一个透明 `PopupWindow` 转发触摸） |
| `Rime.kt` 的 `inlinePreeditMode` | 强制 `COMPOSING_TEXT`：拼音字母直接进输入框；预编辑里的 `‸`(U+2038) 光标标记必须剥掉，不然会被当正文写进输入框 |
| 主题 `CommitAction` 键 | 底行动作键。组合中先选高亮候选上屏，commit 到了再执行宿主 `imeOptions` 动作——**直接发 Return 会把原始拼音上屏** |

## 语音链路

```
点大圆 → 麦克风前台服务 → 16kHz 单声道录音 → 豆包流式（59 条热词）
      → 二遍终稿 → 79 条替换规则 → commitText 落到当前输入框
```

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
