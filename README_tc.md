<!--
SPDX-FileCopyrightText: 2026 Jack

SPDX-License-Identifier: GPL-3.0-or-later
-->

<div align="center">

# JackVoice

**說話即成文。**

安卓語音輸入法：點進任何輸入框，按住說話，文字自己落進去。

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#)

[功能](#功能) · [建置](#從原始碼建置) · [技術設計](JACKVOICE.md)

[English](README.md) | [简体中文](README_sc.md) | 繁體中文

</div>

---

## 這是什麼

JackVoice 是一個**語音優先的安卓輸入法**。它把「語音面板」做成了主鍵盤——點開任何輸入框，
先看到的不是字母鍵，而是一顆「點擊說話」。

說完，文字直接進當前輸入框。不用複製，不用貼上，不用切到別的 App。

> 對標 [Typeless](https://www.typeless.com/) 的互動形態，鍵盤部分基於
> [同文輸入法 Trime](https://github.com/osfans/trime)（RIME 引擎，GPL-3.0）改造。

## 功能

### 語音

| | |
|---|---|
| **點一下就說** | 語音面板就是主鍵盤，不需要先切模式 |
| **即時預覽** | 說話過程中文字即時出現在面板上，終稿到了才上屏 |
| **雲端 / 本地雙引擎** | 面板左上角一鍵切換。本地引擎基於 sherpa-onnx，**不連網也能出字**，不需要 API Key |
| **錄音永不丟** | 錄音一開始就寫盤。辨識失敗、斷網、行程被殺，音訊都還在，隨時重新轉寫 |
| **辨識會斷，錄音不會** | 網路斷了只停辨識，麥克風繼續錄——進隧道不會讓你白說一段 |
| **熱詞與替換詞** | 內建詞表 + 自訂，專有名詞、產品名、人名辨識更準 |
| **標點檔位** | 全標點 / 去句號 / 無標點，一鍵切換，數字、時間、網址裡的符號不動 |
| **按住別人的聲音** | 說話時自動壓住正在播放的音樂和直播，說完恢復 |

### 鍵盤

| | |
|---|---|
| **中文 / 英文** | 頂欄膠囊 `[🎙 \| 拼 \| EN]` 一鍵切換，模組會記住上次停在哪 |
| **空格長按 = 觸控板** | 按住空格滑動移動游標、選取文字，輕點仍然打空格 |
| **表情搜尋** | 內建完整 emoji 索引，輸入拼音就能找表情，支援膚色變體 |
| **極簡佈局** | 重寫過的四排鍵盤，語音底欄與打字鍵盤共用同一套網格，切換時高度不變 |

### 主介面

四個 Tab：**首頁**（聽寫統計）/ **歷史記錄**（含音訊回放與重新轉寫）/
**詞典**（熱詞、替換詞）/ **設定**。

## 介面

<!-- 影片發布後把截圖放這裡 -->
> 📷 截圖待補充

## 從原始碼建置

本專案**不提供預先編譯的安裝包**，需要自行建置。這是一個需要麥克風和輸入法權限的
個人工具，程式碼開源給你，包你自己出，中間沒有任何散佈環節。

### 環境需求

| 依賴 | 版本 |
|---|---|
| JDK | 21 |
| Android SDK | API 37 |
| Android NDK | 28.0.13004108 |
| CMake | 3.31.6 |

### 步驟

```sh
# submodule 是必須的：librime / OpenCC / snappy / lua 都在裡面
git clone --recursive https://github.com/JackAIStudio/JackVoiceIME.git
cd JackVoiceIME

./gradlew assembleDebug
# 產物：app/build/outputs/apk/debug/
```

> **Windows 使用者**：建置過程會建立符號連結，需要開啟
> [開發人員模式](https://learn.microsoft.com/en-us/windows/apps/get-started/enable-your-device-for-development)，
> 並執行 `git config --global core.symlinks true`。

### 安裝到手機

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

裝好後在 **系統設定 → 語言和輸入法 → 目前輸入法** 裡選中 JackVoice，
並依提示授予麥克風權限。

> ⚠️ **麥克風權限必須走系統彈窗授權**。用 `adb grant RECORD_AUDIO` 表面上是 granted，
> 但 AppOps 裡仍是 ignore，錄音會被系統直接拒掉。原因見
> [技術設計 · 兩個必須知道的坑](JACKVOICE.md#两个必须知道的坑)。

## 語音引擎

| | 雲端 | 本地 |
|---|---|---|
| 引擎 | 豆包串流辨識 | sherpa-onnx + X-ASR |
| 連網 | 需要 | **不需要** |
| API Key | 需要 | 不需要 |
| 模型 | — | 首次使用時下載，約 128MB |
| 延遲 | 取決於網路 | 停止後約 150–250ms |
| 熱詞 | 支援 | 不支援 |

引擎在**開始錄音時定下**，中途不切，避免同一個工作階段裡兩套結果打架。

## 隱私

- 錄音**只存在這台手機上**（應用程式私有目錄），不上傳
- 雲端辨識時音訊會傳送到你設定的辨識服務，這是雲端引擎的固有代價
- 本地引擎完全離線，音訊不出裝置
- 沒有統計、沒有埋點、沒有第三方 SDK

## 專案狀態

個人專案，按自己的使用習慣迭代。歡迎 Issue，但不保證回應速度。

上游 Trime 的原始 README 保留在 [README_upstream.md](README_upstream.md)。

## 致謝

- [同文輸入法 Trime](https://github.com/osfans/trime) 與 [RIME](https://rime.im/) 社群——鍵盤與輸入法框架的基礎
- [librime](https://github.com/rime/librime)、[OpenCC](https://github.com/BYVoid/OpenCC)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- 以及 [AUTHORS](AUTHORS) 中列出的所有 Trime 貢獻者

## 授權

[GPL-3.0-or-later](LICENSE)。作為 Trime 的衍生作品，本專案的散佈同樣遵循 GPL-3.0。
