# 第三方依赖与许可证

Board 本身是原创实现（键盘布局、绘制、配色、输入控制器、数据层）。
下面列出**实际打进 APK** 的第三方组件及其许可证。

## 1. 原生库（`libboard_rime.so`，静态链接）

| 组件 | 版本 / 来源 | 许可证 | 说明 |
|------|-------------|--------|------|
| **librime** | 1.16.1 · [rime/librime](https://github.com/rime/librime) | **BSD-3-Clause** | 中文输入引擎本体（本项目的核心依赖） |
| **OpenCC** | [BYVoid/OpenCC](https://github.com/BYVoid/OpenCC) | **Apache-2.0** | 繁体 → 简体转换（`t2s.json`） |
| **leveldb** | [google/leveldb](https://github.com/google/leveldb) | **BSD-3-Clause** | 用户词典 / 词频持久化 |
| **marisa-trie** | [s-yata/marisa-trie](https://github.com/s-yata/marisa-trie) | **BSD-2-Clause OR LGPL-2.1-or-later**（双许可，本项目按 BSD-2-Clause 使用） | 词典 Trie 索引 |
| **yaml-cpp** | [jbeder/yaml-cpp](https://github.com/jbeder/yaml-cpp) | **MIT** | 解析 Rime 的 YAML 配置 |
| **glog** | [google/glog](https://github.com/google/glog) | **BSD-3-Clause** | librime 的日志后端 |
| libc++ / liblog | Android NDK | Apache-2.0 / BSD | 系统运行库 |

静态库二进制来自 [fcitx5-android/prebuilt](https://github.com/fcitx5-android/prebuilt)
（该仓库只存放 **上游项目源码的编译产物**，不含修改过的上游代码；
librime 等库本身仍按各自的许可证分发）。Board 通过 `tools/fetch_rime.ps1` 下载这些产物，
不修改二进制；构建时由 `android/app/src/main/cpp/CMakeLists.txt` 静态链接。

> 注：librime 的 boost 依赖是可选的（`find_package(Boost 1.77.0)` 非 REQUIRED），
> 本项目的构建**没有**链接 boost；glog 用于日志。

## 2. Rime 数据（`assets/rime/`，运行时释放到应用私有目录）

| 数据集 | 来源 | 许可证 |
|--------|------|--------|
| 朙月拼音方案与词库 | [rime/rime-luna-pinyin](https://github.com/rime/rime-luna-pinyin) | **LGPL-3.0** |
| 预置配置（default / symbols / punctuation / key_bindings） | [rime/rime-prelude](https://github.com/rime/rime-prelude) | **LGPL-3.0** |
| 语言模型语料 `essay.txt` | [rime/rime-essay](https://github.com/rime/rime-essay) | **LGPL-3.0** |
| 笔画反查方案（luna_pinyin 声明的依赖） | [rime/rime-stroke](https://github.com/rime/rime-stroke) | **LGPL-3.0** |
| OpenCC 字典数据（`*.ocd2` / `*.json`） | [BYVoid/OpenCC](https://github.com/BYVoid/OpenCC) | **Apache-2.0** |

上述数据集**没有做任何修改**，按 Rime 标准格式（YAML）原样使用。

### ⚠️ 分发前需要注意

Rime 的**数据文件**是 **LGPL-3.0**（不是 librime 的 BSD）：

- 本仓库与 APK 里都附带 LGPL-3.0 全文：`android/app/src/main/assets/rime/LICENSE`；
- 数据集中放在 `assets/rime/`，运行时原样释放到 `filesDir/rime/shared/`，**未做修改、未加密、未与代码混合**，
  因此用户可以用自己的数据集替换（覆盖该目录即可生效），符合 LGPL「可替换」的要求；
- 如果你要把 Board 商用或闭源分发，建议二选一：
  1. 继续保持数据集可替换 + 附带许可证（当前做法）；
  2. 换成自己拥有版权或宽松许可的词典（Rime 的 YAML 格式是公开的，替换 `luna_pinyin.dict.yaml` 即可）。

## 3. Android / Kotlin 依赖

| 库 | 许可证 |
|----|--------|
| AndroidX（core-ktx / appcompat / activity / constraintlayout / lifecycle） | Apache-2.0 |
| Material Components for Android | Apache-2.0 |
| Kotlin 标准库 / kotlinx-coroutines | Apache-2.0 |
| Room | Apache-2.0 |
| Android Gradle Plugin / KSP | Apache-2.0 |

## 4. Emoji 数据（`assets/emoji/`）

| 文件 | 来源 | 许可证 |
|------|------|--------|
| `cldr.tsv` | Unicode CLDR 注释（中 / 英文关键词），取自 [Unicode emoji-test / CLDR](https://cldr.unicode.org/) | **Unicode License v3** |
| `zh-extra.tsv` | 聊天常用词表（手工整理），参考 [Gloridust/WowType](https://github.com/Gloridust/WowType)（MIT OR Apache-2.0） | MIT（本仓库按 MIT 使用） |
| `search.tsv` | 由上面两份用 `tools/gen_emoji_search.py`（依赖 pypinyin，MIT）生成的拼音 / 首字母索引 | 同源数据许可证 |

emoji 数据随 APK 分发、只在设备本地解析，不联网、不上传输入内容。
