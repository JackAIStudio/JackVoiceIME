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
- 顶栏左侧品牌、右侧 `[🎙|拼|EN]` 切换器；打字键盘是重写过的极简四排

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

### ⚠️ 项目目录不能在 iCloud 里

`~/Documents` 被「桌面与文档」同步托管时（`com.apple.file-provider-domain-id:
com.apple.CloudDocs.iCloudDriveFileProvider/…`），iCloud 会为它跟不上的文件生成
`xxx 2.kt` 这类**冲突副本**。Gradle 扫到就报：

```
Cannot access output property 'destinationDirectory' … Failed to create MD5 hash for file: …/InputView 2.kt
remote unpack failed: index-pack failed          # 推送也会挂
```

构建过程中会持续产生新副本，所以「清理 → 构建」循环也救不回来。**根治办法是把仓库
移出 `~/Documents`**（比如 `~/Projects/`）。临时办法：把构建输出目录符号链接到 iCloud 之外

```sh
rm -rf app/build build && mkdir -p /tmp/trime-out/app-build /tmp/trime-out/root-build
ln -s /tmp/trime-out/app-build app/build && ln -s /tmp/trime-out/root-build build
```

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
