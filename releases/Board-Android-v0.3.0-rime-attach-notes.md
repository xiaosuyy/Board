# Board-Android v0.3.0 —— 大型导入词库真正接入 Rime 候选

- 日期：2026-09-30
- APK：`releases/Board-Android-v0.3.0-rime-attach.apk`（23,342,385 B，debug 构建）
- 代码：`android/app/src/main/java/com/board/ime/dictionary/RimeDictionaryBridge.kt`、
  `.../dictionary/PinyinSyllables.kt`（新）、`.../dictionary/DictionaryRepository.kt`、
  `android/app/src/main/cpp/board_rime.cpp`
- 真机：PLZ110 / Android 16 / 1216×2640（`3B162E000FF00000`）

## 一句话

三个导入词库（600000 / 255901 / 543514 条）**已经在真机上真正产生候选**：
输入 `dingzhefusu` → 候选栏第 2 位出现导入词库的 **鼎折覆餗**（默认词库的 盯着复苏 仍在第 1 位）。

---

## 为什么之前「deploy 成功但词库不生效」

先后挖出 **4 个真 bug**，最后一个是关键：

### 1. 词库正文列序与头部声明相反（数据作废）

导入词库正文是 `编码 \t 词条 \t zh-CN \t \t 词频`（`abagaqiweidangxiao ␉ 阿巴嘎旗委党校 ␉ zh-CN ␉ ␉ 0`），
而头部声明 `columns: [text, code, weight]` → Rime 把拉丁拼音当成"词条"、中文当成"编码"，
整份词典作废。修：部署时按内容判列序，归一化成 `词条 \t 编码 \t 词频`。

### 2. 少了「占位方案」文件 —— 真正卡住的一环

`schema/dependencies` 的语义不是"词典"，而是**方案（schema）**。
librime 的 deployer 对每个依赖名去找 `<名字>.schema.yaml`，找到就把它当依赖方案、
预编译它的 `translator.dictionary`（官方 `stroke` 就是这么被编译的），
找不到只打印 `missing input schema; skipped unsatisfied dependency`。

真机 librime INFO 日志（`deployment_tasks.cc`）实录：

```
I schema: luna_pinyin_simp / I preparing dictionary 'luna_pinyin' / I 'luna_pinyin' is ready.
I schema: stroke            / I 'stroke' is ready.
I schema: board_import_327c2506b5aa
W missing input schema; skipped unsatisfied dependency: board_import_327c2506b5aa
I finished updating schemas: 3 success, 0 failure.
```

只给 `.dict.yaml` 永远建不出 table。修：部署时额外写一个极小的占位方案
`<逻辑名>.schema.yaml`（必须把 `schema_id/name/version/dependencies` 放进 **`schema:` 段**，
写在顶层会被判 `invalid schema definition`）。部署完成后连同临时 `.dict.yaml` 一起删除。

### 3. `code` 必须是空格分隔的音节（否则 prism 爆炸 / 候选对不上）

官方 `luna_pinyin.dict.yaml` 的 `code` 是空格分音节（`㕷啦 → pa la`），
而导入词库是连写（`jiuzhuan`、`abagaqiweidangxiao`）。
连写会让 prism 把整串当成"一个音节"——60 万条就是 60 万个"音节"。

修：新增 `PinyinSyllables.kt`，音节表**直接从官方词典的 code 列提取**（420 个，
含 `biang/cei/fiao/lv/nve` 等生僻音节），部署时用 DP 做"音节数最少"的切分。
效果：prism 从"几十 MB 级隐患"变成 **4~5 KB**：

```
board_import_327c2506b5aa.prism.bin      4,412 B
board_import_f37f23da1b26.prism.bin      5,436 B
board_import_e7481b54b17d.prism.bin  4,800,828 B
```

### 4. 提前删掉临时文件 + 候选排序不可见

- 部署结束后立刻删临时 `.dict.yaml`（现在改成轮询等产物出现再删）——防御性修复。
- 额外词源的 `initial_quality` 原本是 `-1`：导入词条词频全是 0，永远排在默认词库的
  整句组合之后，真机上被挤出候选栏可视区（`鼎折覆餗` 就是这样"看不见"的）。

**排序校准**：官方词频来自 `essay.txt`（44 万条，**中位 265、最大 4,822,928**）。
取 `initial_quality = 1000000`：导入词库的**整词精确匹配**压过默认词库的中低频候选，
而最常用的词仍排前面。实测结果正合预期：

```
dingzhefusu | 盯着复苏(默认,选中) | 鼎折覆餗(导入) | 定着扶苏
nihao       | 你好(默认,选中)     | 您好 | 逆号 | 拟好 | 你      ← 常用输入无回归
```

想改回"默认词库严格优先"，把 `RimeDictionaryBridge.EXTRA_QUALITY` 改成 `-1` 即可。

---

## 验收（逐项，不含糊）

| 项 | 结果 | 依据 |
|---|---|---|
| Dictionary Storage | **PASS** | `files/rime/user/imported/` 3 个 `<sha256>.dict.yaml.gz`（5,575,617 / 3,604,900 / 3,464,152 B）完整 |
| Schema 引用 | **PASS** | 编译后的 `build/luna_pinyin_simp.schema.yaml` 含 3 个 `script_translator@board_extra_*`、`schema/dependencies` 3 项、各 `board_extra_*: {dictionary: board_import_*}` |
| Deployment 产物 | **PASS** | `ready=3/3 耗时=5585ms`；`build/board_import_*.table.bin` = 18,221,524 / 16,390,868 / 11,641,924 B |
| **Candidate 特征词进候选** | **PASS** | 真机键盘输入 `dingzhefusu` → **鼎折覆餗** 出现在候选栏第 2 位（截图 `Board-imported-candidate-dingzhefusu.png`）；数据侧核对 `f37f` gz 里确有 `dingzhefusu → 鼎折覆餗` |
| 引擎侧加载 | **PASS** | librime 日志：`loading dictionary 'board_import_*'` + table + prism，无报错 |
| App 状态 | **PASS** | 词库页显示 3 个数据源「已接入 Rime」（截图 `Board-imported-3-sources-attached.png`） |
| 常用输入无回归 | **PASS** | `nihao → 你好` 仍第 1 位（截图 `Board-common-word-nihao-noregres.png`） |
| 原有资产未被破坏 | **PASS** | `luna_pinyin.table.bin` 13,011,364 B / `stroke.table.bin` 4,548,444 B **时间戳仍是 03:08**；`luna_pinyin.userdb` 完好；`custom_phrase.txt` 未动；用户原始 schema 未改 |
| Failure Safety | **PASS** | 部署后用户目录无残留（临时 `.dict.yaml` + 占位方案都已删）、无大 `.bak`、`.gz` 保留 |
| 性能 | **PASS** | 首次部署 5.6s（2.29M 条）；增量部署只重编译变化的 schema（约 0.2s），源未变不重建 table；输入热路径未改动（等待只发生在设置页 IO 线程） |
| 持久化（重启后） | **PASS** | 重启 App 后 librime 仍正常加载 3 本词典（日志重复出现 `loading dictionary 'board_import_*'`） |

## 已知不足 / 下一步

1. **候选栏靠后的候选要横向拖动才能看到**（`CandidateBar` 是 `HorizontalScrollView`，
   `MAX_CANDIDATES = 12`）；提示用户可用拖动翻看，后续可加"翻页"按键。
2. 真机 `min_log_level` 暂设为 `0`(INFO) 便于排查部署问题（`cache/rime/log/`，Rime 会自动清理），
   发正式包可改回 `2`(WARNING)。
3. 部署依赖 librime 的部署日志（glog）—— 这次就是靠它定位的，建议保留可开启的开关。
4. 未做：每个数据源单独启停（目前是全量挂接）；候选使用量统计。

## 复现步骤（真机）

```
# 1) 打包 + 安装
.\gradlew.bat :android:app:assembleDebug
adb -s 3B162E000FF00000 install -r android\app\build\outputs\apk\debug\app-debug.apk

# 2) 设置 → 词库 → 部署到 Rime（按钮坐标 608,2294）
#    期望日志：已解压待部署 ×3 → 开始部署 → 部署成功 ×3 → 手动部署完成：ready=3/3

# 3) 候选验证（必须用真机键盘，不能用 input text）
#    _probe\typetest.ps1 -Code dingzhefusu   → 候选栏应出现 鼎折覆餗
```

> `_probe/keys.ps1` 用截图像素分析自动定位按键中心（真机 1216×2640：
> 首行 y=2023 x=71..1144、次行 y=2196 x=131..1084、三行 y=2369 x=250..965），
> `_probe/typetest.ps1` 用它模拟真实点按输入。
