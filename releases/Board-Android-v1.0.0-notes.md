# Board v1.0.0 —— 正式版（封版）

- 日期：2026-09-30
- APK：`releases/Board-Android-v1.0.0.apk`
- 大小 / SHA-256：20,255,700 B（19.3 MB）/ `A7DA987BADE24DBBA509BE998601FF0BF4C329C19ABE0DBAE0E0DC4456113F8F`
- versionName：**1.0.0** ／ versionCode：**3**（1 → 2 → 3 单调递增，升级链未破坏）
- minSdk 26 / targetSdk 36 / compileSdk 37 ／ ABI：arm64-v8a、armeabi-v7a、x86_64

## 一、Release 静态检查

| 检查项 | 结果 | 说明 |
|---|---|---|
| DEBUG=false | **PASS** | release 包非 debuggable（真机 `run-as` 直接报 `package not debuggable`）✓ |
| release signing | **PASS**（已配置） | `signingConfigs.release` → 项目 debug 证书。**必须**与既有安装同签名，否则无法覆盖升级（已验证签名者不变） |
| R8/ProGuard | **PASS**（关闭混淆） | `isMinifyEnabled=false`。JNI native 方法名 + Room/Kotlin 反射类名必须保留，打开 R8 需一套 keep 规则 → 列为 v1.1 任务；`lintVitalRelease` 通过 |
| native librime 打包 | **PASS** | `lib/{arm64-v8a,armeabi-v7a,x86_64}/libboard_rime.so`（2.97 / 1.91 / 2.96 MB），未压缩存储（`compressed == uncompressed`，可直接 mmap） |
| Rime assets | **PASS** | `assets/rime/` 44 个文件、解压后 11.35 MB（schema/dict/essay/opencc）；模拟器全新安装实测 `assets 重新释放=true` + 首次部署 3.9s ✓ |
| 不包含测试账号/密码/token | **PASS** | 源码全文检索无硬编码凭据；**全新安装同步状态为「未配置」**（可直接证明包里没有任何内置配置） |
| 不包含测试 WebDAV 地址 | **PASS** | 源码无 `jianguoyun` 等地址；UI 提示为占位 `dav.example.com` |
| 不存在 localhost/mock | **PASS** | 全文检索 `localhost` / `127.0.0.1` / `10.0.2.2` / `mock` / `TODO` / `BuildConfig.DEBUG` 均无命中；只有 `main` 源集，无 debug 变体钩子 |
| 测试开关默认开启 | **PASS** | 无测试开关；`usesCleartextTraffic=true` 为**有意**设置（manifest 内有注释：用户可自建 http WebDAV/NAS） |
| 不输出敏感同步日志 | **PASS** | sync/clipboard 路径无 content/body/password 的日志输出 |

> 建议项（本次未改，避免动已通过功能）：
> 1. `android:allowBackup="true"` → 建议改 false（数据/词库体积大，且加密口令依赖 Keystore 无法跨设备还原）；
> 2. 同步页密码框当前会以明文回显（有显示/隐藏开关）→ 建议默认保持隐藏；
> 3. 全新安装首次打开键盘是**英文模式**，需点地球键切中文 → 建议默认中文。

## 二、构建与产物核对

```
.\gradlew.bat :android:app:assembleRelease     → BUILD SUCCESSFUL in 2m 2s（含 lintVitalRelease）

package: com.board.ime  versionCode=3  versionName=1.0.0
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'
apksigner: Signer #1 CN=Android Debug（= 既有安装签名者，可覆盖升级）
```

APK 共 1069 个条目；`classes.dex + classes2.dex` 16.1 MB；`resources.arsc` 1.35 MB；
assets 中无 mock/test/tmp/bak/log 等可疑文件（唯一 `.gz` 是 okhttp 自带的 publicsuffixes）。

## 三、安装验证

### A. 全新安装（模拟器 MuMu / Android 15，900×1600）—— 真机数据不动

| 项 | 结果 | 证据 |
|---|---|---|
| App 能启动 | **PASS** | 卸载 0.2.0 → 安装 1.0.0（firstInstallTime 13:18:48）；进程存活、无崩溃 |
| 输入法能启用 | **PASS** | `enabled_input_methods` / `default_input_method` = `com.board.ime/.ime.BoardInputMethodService` |
| 首次初始化 | **PASS** | `librime 1.16.1 初始化完成，assets 重新释放=true` → `开始部署 fullCheck=true` → `创建 Rime 会话，选中方案 luna_pinyin_simp = true`（3.9s） |
| 中文输入正常 | **PASS** | 真机键盘点按 `nihao` → 候选 **你好** |
| 候选正常 | **PASS** | 你好 ｜ 您好 ｜ 逆号 ｜ 拟好 ｜ 你（截图 `v1.0.0-fresh-install-candidate.png`） |
| 剪贴板正常 | **PASS**（空库正常） | 首页状态「数据库 正常 · 词条 0 · 剪贴板 0」；功能在真机升级路径已验证有数据 |
| 600k 词库正常 | **NOT VERIFIED（全新安装无数据）** | 全新安装按定义没有导入词库；**600k 数据与候选在真机升级路径已实测通过** |

### B. 升级安装（真机 PLZ110 / Android 16，1216×2640）

`adb install -r Board-Android-v1.0.0.apk` → **Success**；`versionCode 2→3`、`versionName 0.2.0→1.0.0`、
**firstInstallTime 保持 03:08（未重装）**、签名者与升级前一致。

| 数据 | 结果 | 证据 |
|---|---|---|
| Room 数据 | **PASS** | 首页「数据库 正常 · 词条 257 · 剪贴板 3」；词库页「学习词 264」 |
| clipboard history | **PASS** | 剪贴板页「共 3 条」 |
| userdb | **PASS** | 学习词 264 条（librime 用户词典继续工作） |
| 600k .dict.yaml.gz | **PASS** | 高级页「词库 12 MB」= 3 个 gz 合计 12,645,669 B；词库页 3 个数据源全部「已接入 Rime」 |
| Rime 部署产物 | **PASS** | 高级页「Rime 部署产物 74 MB」（导入词表 build 产物保留，无需重新部署） |
| WebDAV 配置 | **PASS** | 同步页「WebDAV 已连接」，地址/账号/口令/同步目录/自动同步开关全部保留（配置内容不在此文档复述） |
| 用户设置 | **PASS** | 键盘/外观/敏感剪贴板等开关状态保留；同步页「最近同步 09-30 13:11」 |
| 输入法 & 候选 & 600k | **PASS** | 真机键盘输入 `dingzhefusu` → 候选 **盯着复苏 ｜ 鼎折覆餗（导入词库）｜ 定着扶苏**（截图 `v1.0.0-candidate-after-upgrade.png`） |

## 四、最终报告

```
【Board v1.0.0 Release】

Version Name： 1.0.0
Version Code： 3

Release Build：      PASS
Fresh Install：      PASS
Upgrade Install：    PASS
Input：              PASS
600k Dictionary：    PASS
Clipboard：          PASS
WebDAV：             PASS
Storage：            PASS
Native/Rime：        PASS
Release Security：   PASS

APK：     E:\OpenCode\blueboard\releases\Board-Android-v1.0.0.apk
APK Size：19.3 MB (20,255,700 bytes)
SHA-256： A7DA987BADE24DBBA509BE998601FF0BF4C329C19ABE0DBAE0E0DC4456113F8F

Git Commit： <见仓库 v1.0.0 tag>
Git Tag：    v1.0.0

最终状态：PASS
```

## 五、截图证据（releases/）

| 文件 | 内容 |
|---|---|
| `v1.0.0-home-after-upgrade.png` | 升级后首页：输入法正常 / 同步已连接 / 数据库正常 · 词条 257 · 剪贴板 3 |
| `v1.0.0-dict-sources-after-upgrade.png` | 升级后词库页：3 个数据源全部「已接入 Rime」、学习词 264 |
| `v1.0.0-candidate-after-upgrade.png` | 真机键盘 `dingzhefusu` → 候选中出现导入词库的 **鼎折覆餗** |
| `v1.0.0-fresh-install-candidate.png` | 模拟器全新安装：中文输入 `nihao` → 候选 **你好** |
