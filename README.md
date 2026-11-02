<!--
SPDX-FileCopyrightText: 2026 Jack

SPDX-License-Identifier: GPL-3.0-or-later
-->

<div align="center">

# JackVoice

**说话即成文。**

安卓语音输入法：点进任何输入框，按住说话，文字自己落进去。

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)](#)

[功能](#功能) · [界面](#界面) · [构建](#从源码构建) · [技术设计](JACKVOICE.md)

</div>

---

## 这是什么

JackVoice 是一个**语音优先的安卓输入法**。它把「语音面板」做成了主键盘——点开任何输入框，
先看到的不是字母键，而是一颗「点击说话」。

说完，文字直接进当前输入框。不用复制，不用粘贴，不用切到别的 App。

> 对标 [Typeless](https://www.typeless.com/) 的交互形态，键盘部分基于
> [同文输入法 Trime](https://github.com/osfans/trime)（RIME 引擎，GPL-3.0）改造。

## 功能

### 语音

| | |
|---|---|
| **点一下就说** | 语音面板就是主键盘，不需要先切模式 |
| **实时预览** | 说话过程中文字实时出现在面板上，终稿到了才上屏 |
| **云端 / 本地双引擎** | 面板左上角一键切换。本地引擎基于 sherpa-onnx，**不联网也能出字**，不需要 API Key |
| **录音永不丢** | 录音一开始就写盘。识别失败、断网、进程被杀，音频都还在，随时重新转写 |
| **识别会断，录音不会** | 网络断了只停识别，麦克风继续录——地铁进隧道不会让你白说一段 |
| **热词与替换词** | 内置词表 + 自定义，专有名词、产品名、人名识别更准 |
| **标点档位** | 全标点 / 去句号 / 无标点，一键切换，数字、时间、网址里的符号不动 |
| **按住别人的声音** | 说话时自动压住正在播放的音乐和直播，说完恢复 |

### 键盘

| | |
|---|---|
| **中文 / 英文** | 顶栏胶囊 `[🎙 \| 拼 \| EN]` 一键切换，模块会记住上次停在哪 |
| **空格长按 = 触控板** | 按住空格滑动移动光标、选中文字，轻点仍然打空格 |
| **表情搜索** | 内置完整 emoji 索引，输入拼音就能找表情，支持肤色变体 |
| **极简布局** | 重写过的四排键盘，语音底栏与打字键盘共用同一套网格，切换时高度不变 |

### 主界面

四个 Tab：**首页**（听写统计）/ **历史记录**（含音频回放与重新转写）/
**词典**（热词、替换词）/ **设置**。

## 界面

<!-- 视频发布后把截图放这里 -->
> 📷 截图待补充

## 从源码构建

本项目**不提供预编译安装包**，需要自行构建。原因很简单：这是一个需要麦克风和输入法权限的
个人工具，代码开源给你，包你自己出，中间没有任何分发环节。

### 环境要求

| 依赖 | 版本 |
|---|---|
| JDK | 21 |
| Android SDK | API 37 |
| Android NDK | 28.0.13004108 |
| CMake | 3.31.6 |

### 步骤

```sh
# submodule 是必须的：librime / OpenCC / snappy / lua 都在里面
git clone --recursive https://github.com/JackAIStudio/JackVoiceIME.git
cd JackVoiceIME

./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/
```

> **Windows 用户**：构建过程会创建符号链接，需要开启
> [开发者模式](https://learn.microsoft.com/en-us/windows/apps/get-started/enable-your-device-for-development)，
> 并执行 `git config --global core.symlinks true`。不开启也能构建，失败时会自动回退到复制。

### 安装到手机

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

装好后在 **系统设置 → 语言和输入法 → 当前输入法** 里选中 JackVoice，
并按提示授予麦克风权限。

> ⚠️ **麦克风权限必须走系统弹窗授权**。用 `adb grant RECORD_AUDIO` 表面上是 granted，
> 但 AppOps 里仍是 ignore，录音会被系统直接拒掉。原因见
> [技术设计 · 录音放行看的是 AppOps](JACKVOICE.md#两个必须知道的坑)。

## 语音引擎

| | 云端 | 本地 |
|---|---|---|
| 引擎 | 豆包流式识别 | sherpa-onnx + X-ASR |
| 联网 | 需要 | **不需要** |
| API Key | 需要 | 不需要 |
| 模型 | — | 首次使用时下载，约 128MB |
| 延迟 | 取决于网络 | 停止后约 150–250ms |
| 热词 | 支持 | 不支持 |

引擎在**开始录音时定下**，中途不切，避免同一个会话里两套结果打架。

## 隐私

- 录音**只存在这台手机上**（应用私有目录），不上传
- 云端识别时音频会发送到你配置的识别服务，这是云端引擎的固有代价
- 本地引擎完全离线，音频不出设备
- 没有统计、没有埋点、没有第三方 SDK

## 项目状态

个人项目，按自己的使用习惯迭代。欢迎 Issue，但不保证响应速度。

上游 Trime 的原始 README 保留在 [README_upstream.md](README_upstream.md)。

## 致谢

- [同文输入法 Trime](https://github.com/osfans/trime) 与 [RIME](https://rime.im/) 社区——键盘与输入法框架的基础
- [librime](https://github.com/rime/librime)、[OpenCC](https://github.com/BYVoid/OpenCC)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- 以及 [AUTHORS](AUTHORS) 中列出的所有 Trime 贡献者

## 许可证

[GPL-3.0-or-later](LICENSE)。作为 Trime 的衍生作品，本项目的分发同样遵循 GPL-3.0。
