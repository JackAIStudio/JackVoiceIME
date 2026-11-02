<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

> 这是上游 **同文输入法 Trime** 的原始 README，原样保留以保留出处与致谢。
> 本项目 JackVoice 的说明请看 [README.md](README.md)。

# Trime

Rime IME for Android

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

English | [简体中文](README_sc.md) | [繁體中文](README_tc.md)

## About

Trime is originally a frontend of open-source [Android Traditional Chinese IME], based on [RIME] input method framework and written in Java/Kotlin with JNI. It is designed to protect the native language of various local dialects of Chinese and is a universal shape-based and phonetic-based input method platform.

[Documentation](https://github.com/osfans/trime/wiki)

## Build

No prebuilt packages are published, and this repository does not use GitHub Actions.
Build it yourself:

```sh
git clone --recursive https://github.com/JackAIStudio/JackVoiceIME.git
cd JackVoiceIME
./gradlew assembleDebug            # output: app/build/outputs/apk/debug/
```

Requires JDK 21, Android SDK and NDK 28. librime / OpenCC / snappy are submodules,
so the `--recursive` flag is required.

Note that a plain build still carries the upstream Trime configuration. What this fork
changes on top of upstream is documented in [JACKVOICE.md](JACKVOICE.md).

## History

TRIME is the abbreviation of _Tongwen RIME_ or _ThaeRv Input Method_.

From the beginning, TRIME was written for TaeRv Pinyin, and named _TaeRv Input Method (泰如输入法)_.

Then, we created an input method platform with some code tables, such as Wu dialect (吴语). We renamed it to _Chinese Character Dialect Input Method (汉字方言输入法)_.

Later, it supports Wubi and Liangbi and other shape-based input method, we branded it [_Tongwen Input Method Platform 2.0 (同文输入法平台 2.0)_](https://github.com/osfans/trime-legacy), which implies that the phonetic-based and shape-based input method on one platform, while dialects and Mandrain share one kind of characters.

Benefit from the [librime](https://github.com/rime/librime) project by JNI, we are now in version 3.0 of TRIME aka _Tongwen Input Method (同文输入法)_.

Your are now welcome to [contribution](CONTRIBUTING.md) ~ !

## Getting Started for developer

### Prepare

#### Requirements:

- Android SDK and Android NDK
  * If you are new to Android development, please install [Android Studio](https://developer.android.com/studio).

- JDK (OpenJDK) 17
- Python 3 (required by OpenCC to generate dictionary text files)

#### Prerequisites for Windows

Symbolic links will be created according to current build configurations, developers need:

- Enable [Developer Mode](https://learn.microsoft.com/en-us/windows/apps/get-started/enable-your-device-for-development) so that symlinks can be created without administrator privilege.

- Enable symlink support for `git`:

  ```powershell
  git config --global core.symlinks true
  ```

If you cannot or wouldn't like to enable anything, it doesn't matter. Copying will be used instead when error on creating symbolic links.

### Build

#### 1. Clone this project and fetch all submodules:

```sh
git clone --recursive git@github.com:JackAIStudio/JackVoiceIME.git
git submodule update --init --recursive
# use partial clone to save time
git submodule update --init --recursive --filter=blob:none
```

#### 2. Debug version without signature:

```sh
# On Linux or macOS
make debug

# On Windows
.\gradlew assembleDebug
```

#### 3. Release version with signture:

Create `keystore.properties` file which contains following contents for [signing information](https://developer.android.com/studio/publish/app-signing.html):

```gradle.properties
storePassword=myStorePassword
keyPassword=mykeyPassword
keyAlias=myKeyAlias
storeFile=myStoreFileLocation
```

Then, you may run:

```sh
# On Linux or macOS
make release

# On Windows
.\gradlew assembleRelease
```

### Troubleshooting

```
Target "boost_log_setup" links to target "Boost::coroutine" but the target was not found.
```

Run `make clean` on Linux or macOS, or run `.\gradlew clean` on Windows.

Other issues:

1. Try `make clean`
2. Make sure your repo is up-to-date. If one or more submodules are modified, also make sure they are compatible with the current version.
3. If the problem still exists(very unlikely), try to make a new clone.
4. Check if this is there is an issue/PR related to your problem. If yes, try their solutions.
5. If none of them works, you may make an issue to ask for help.(optional)

## Acknowledgments

- Developer: [osfans](https://github.com/osfans)
- Contributors: [boboIqiqi](https://github.com/boboIqiqi)、[Bambooin](https://github.com/Bambooin)、[senchi96](https://github.com/senchi96)、[heiher](https://github.com/heiher)、[abay](https://github.com/a342191555)、[iovxw](https://github.com/iovxw)、[huyz-git](https://github.com/huyz-git)、[tumuyan](https://github.com/tumuyan)、[WhiredPlanck](https://github.com/WhiredPlanck)、[nopdan](https://github.com/nopdan)...
- [Wiki Editors](https://github.com/osfans/trime/wiki): [xiaoqun2016](https://github.com/xiaoqun2016)、[boboIqiqi](https://github.com/boboIqiqi)...
- Translators: 天真可爱的满满 (Chinese Traditional), 点解 (English) ...
- Keyboard Designers: 天真可爱的满满、皛筱晓小笨鱼、吴琛 11、熊猫阿 Bo、默默ㄇㄛ ˋ...
- Community: Netizens who feedback in [Issues](https://github.com/osfans/trime/issues)、[QQ Group (811142286)](https://jq.qq.com/?_wv=1027&k=AXdR80HN)、[QQ Group (224230445)](http://qm.qq.com/cgi-bin/qm/qr?_wv=1027&k=pg_q7UVumWYLq1Rk8kIAqkK1xGt64VnX&authKey=04m9l7OBO5H5vgrEL8IbpsmtnptWM60xy%2FUwYCfyvw9VcRhe8zRzAS1ezoemZdFr&noverify=0&group_code=224230445)、[Tieba](http://tieba.baidu.com/f?kw=rime)、[Google Play](https://play.google.com/store/apps/details?id=com.osfans.trime)、[Telegram](https://t.me/trime_dev)...
- Projects: [RIME]、[OpenCC]、[Android Traditional Chinese IME] and so on.

## Third Party Libraries

- [Boost C++ Libraries](https://www.boost.org/) (Boost Software License)
- [darts-clone](https://github.com/s-yata/darts-clone) (New BSD License)
- [LevelDB](https://github.com/google/leveldb) (New BSD License)
- [libiconv](https://www.gnu.org/software/libiconv/) (LGPL License)
- [marisa-trie](https://github.com/s-yata/marisa-trie) (BSD License)
- [glog](https://github.com/google/glog) (New BSD License)
- [OpenCC](https://github.com/BYVoid/OpenCC) (Apache License 2.0)
- [RIME](https://rime.im) (BSD License)
- [snappy](https://github.com/google/snappy)(BSD License)
- [utfcpp](https://github.com/nemtrif/utfcpp) (Boost Software License)
- [yaml-cpp](https://github.com/jbeder/yaml-cpp) (MIT License)
- [Android Traditional Chinese IME](https://code.google.com/p/android-traditional-chinese-ime/) (Apache License 2.0)

[Android Traditional Chinese IME]: https://code.google.com/p/android-traditional-chinese-ime/
[RIME]: http://rime.im
[OpenCC]: https://github.com/BYVoid/OpenCC
