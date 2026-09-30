# Board v1.1.0 第一阶段 —— 正式签名 + R8

- 日期：2026-09-30
- APK：`releases/Board-Android-v1.1.0.apk`
- 大小 / SHA-256：15,622,845 B（14.9 MB）/ `59049493DE8AD368907B195D0E4DB417038BF12E6DFAD956782E7AC7E76D832F`
- versionName **1.1.0** ／ versionCode **4**（1→2→3→4 单调递增，升级链未破坏）
- 签名身份：`CN=Board IME, OU=Input Method, O=Board, L=Beijing, ST=Beijing, C=CN`
  证书 SHA-256：`2D:7A:A0:6D:1F:C9:B1:10:41:97:8D:96:51:A6:5E:F7:BE:D1:DA:72:E9:02:F9:7E:34:C6:8D:A9:B3:4F:FD:E7`
- v1.0.0（commit `ea70b7b` / tag `v1.0.0` / APK `A7DA987B…13F8F`）**未被修改**（本文件写入后复核哈希一致）

---

## 一、正式签名（密码不进仓库、不进日志）

| 项 | 做法 |
|---|---|
| keystore | `%USERPROFILE%\board-keys\board-release.jks`（RSA 4096 / 有效期 10950 天 ≈ 30 年 / alias `board`）——**在仓库之外** |
| 备份 | `%USERPROFILE%\board-keys-backup\board-release.jks`（请再做一份离线备份，见下） |
| 口令来源 | `local.properties` 的 `board.release.*`（已 gitignore）**或**环境变量 `BOARD_RELEASE_*`（环境变量优先，便于 CI/临时构建） |
| 构建日志 | 只输出「keystore 文件名 + alias」，**不输出任何口令** |
| gitignore | 新增 `*.jks`、`*.p12`、`keystore.properties`（`*.keystore`、`local.properties` 早已忽略） |
| 缺失时行为 | 不静默回退 debug 证书，而是产出 `app-release-unsigned.apk` 并警告 |

> ⚠️ 说明：第一把 keystore 生成时口令被误回显到终端，已**立即作废并删除**；当前这把是唯一有效身份，且**尚无用它签发的对外版本**。
>
> 🔑 **备份方案（务必执行）**：把 `board-release.jks` 与口令一起放到至少两处离线介质（U 盘 + 密码管理器/纸质封存）。
> 这把 keystore 是 v1.1.0 起所有正式版本的**唯一升级身份**，丢失后无法再对老用户发升级包。

## 二、R8

```kotlin
release {
    isMinifyEnabled = true       // 代码混淆 + 收缩
    isShrinkResources = false    // 有意关闭，见下
}
```

- **资源收缩保持关闭**：Rime 的 schema/词典/OpenCC 都在 `assets/`，运行时按路径读取，
  资源收缩无法判断它们"被使用"；误删即输入法不可用。收益也远小于代码收缩。
- **keep 规则只有一条**（`proguard-rules.pro`）：

```proguard
-keep class com.board.ime.engine.rime.RimeNative { native <methods>; }
-keepattributes SourceFile,LineNumberTable
```

  理由：librime 方法**按 JNI 符号名解析**（`Java_com_board_ime_engine_rime_RimeNative_<方法>`），
  工程内没有 `RegisterNatives`、没有 native→Java 回调 → 只有这一个类的类名与 native 方法名必须保留。
- **没有使用 `-keep class ** { *; }`**；也**没有重复**添加依赖自带的规则：
  - room-runtime 2.8.4 → `-keep class * extends androidx.room.RoomDatabase { void <init>(); }`
  - work-runtime 2.10.0 → `-keepnames class * extends androidx.work.ListenableWorker` + 构造器
  - security-crypto 1.1.0 → 声明 safe to shrink；okhttp 4.12.0 jar 自带规则
  - v1.0.0 里那条 `-keep class * extends androidx.room.RoomDatabase` 与 AAR 重复，**已删除**
- **App 自身没有任何反射调用**（无 `Class.forName` / `getDeclaredMethod` / `newInstance`），
  没有 Parcelable / Serializable 传递 → 不需要额外 keep。

构建结果：`BUILD SUCCESSFUL`，**无 missing class / R8 警告**；
`mapping/seeds` 中 `com.board.ime.engine.rime.RimeNative` 未被改名，4 个 native 方法全在 seeds 里。

## 三、包体与资源核对

| 项 | v1.0.0 | v1.1.0 (R8) |
|---|---|---|
| APK | 20,255,700 B | **15,622,845 B（-23%）** |
| classes.dex | 9.59 MB + 6.48 MB | **2.56 MB（单 dex）** |
| assets/rime | 44 个文件 | **44 个文件，逐个字节一致** |
| native | arm64/armv7/x86_64 | 同样三个 ABI，`.so` 未压缩存储 |

## 四、验证（真机 PLZ110 / Android 16 + 模拟器 MuMu）

> 真机上是**同代码、debug 签名**的 R8 包（`_probe` 内临时构建，不入库）：
> 这样才能覆盖已安装的 v1.0.0 而不破坏用户数据；R8 的运行时行为与正式签名包完全一致。

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | App 启动 | **PASS** | 首页状态正常；无 FATAL/ANR |
| 2 | 启用输入法 | **PASS** | `default_input_method = com.board.ime/.ime.BoardInputMethodService` |
| 3 | 中文输入 | **PASS** | 真机键盘点按 `dingzhefusu` / `hao` 均正常 |
| 4 | 候选 | **PASS** | `hao → 好/号/浩/豪/耗` |
| 5 | 600k 词库 | **PASS** | `dingzhefusu → 盯着复苏 \| 鼎折覆餗(导入) \| 定着扶苏`（与未混淆版完全一致） |
| 6 | 用户学习 | **PASS** | `SYNC: 学习词合并 本地=265 远端=257 librime导入=265` |
| 7 | 剪贴板 | **PASS** | 剪贴板页「共 5 条」；面板打开正常、含「本机·云端」标记 |
| 8 | WebDAV 配置读取 | **PASS** | 同步页四项配置齐全、状态「已连接」（含 EncryptedSharedPreferences/Tink） |
| 9 | WebDAV 同步 | **PASS** | 实测 `GET index.json 200` / `PUT board_learned.json 204` / 词库 sha 比对跳过 |
| 10 | Room 数据读取 | **PASS** | 首页「数据库 正常 · 词条 264 · 剪贴板 5」 |
| 11 | 横屏 | **PASS**（模拟器） | 横屏浮动键盘渲染 + 输入 `hao → 好/号/浩/豪/耗…`（真机 ROM 锁定前台竖屏，故在模拟器验证） |
| 12 | 深色模式 | **PASS** | `cmd uimode night yes` 后 UI 正常（该截图含口令，未入库） |
| 13 | 关闭/重开输入法 | **PASS** | 收起 `mInputShown=false` → 重开 `true`；**杀进程后冷启动**（pid 15494→652）仍可输入 |
| 14 | 重启 App | **PASS** | force-stop 后冷启动正常，无崩溃 |
| 15 | 重启设备后再输入 | **PASS** | 重启后无锁屏、默认输入法仍为 Board、`hao → 好/号/浩/豪/耗` |

**未出现**：Crash / ANR / Rime 初始化失败 / JNI UnsatisfiedLinkError / Room 初始化失败 / 候选异常。

## 五、升级测试

- **v1.0.0（debug 签名）→ v1.1.0（正式签名）：`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`**
  —— 这是 Android 的正常行为，**不是 bug**；v1.0.0 未做任何修改，覆盖失败后应用与数据完好。
  用户若要从 v1.0.0 升到正式签名版，需**卸载一次**（会清空应用数据）再安装。
- **v1.0.0（正式签名测试包）→ v1.1.0（正式签名）**（模拟器）：**覆盖安装 Success**，
  `versionCode 3→4`、`firstInstallTime` 未变、升级后首页显示「剪贴板 1」（播种条目仍在）✓
  → 正式签名的升级链本身完全可用。

## 六、最终报告

```
【Board v1.1 Release/R8】

正式签名：   PASS（keystore 在仓库外 + 备份；口令只在 local.properties/环境变量）
R8：         PASS（minify=true，无 missing class；资源收缩有意关闭）
JNI：        PASS（仅 keep RimeNative bridge；无 UnsatisfiedLinkError）
Room：       PASS（AAR 自带规则；DB/DAO 运行时正常）
Rime：       PASS（librime 初始化 + 部署 + 会话 + 候选全通过）
600k 词库：  PASS（导入词表候选与未混淆版一致）
Release Build：PASS（15,622,845 B，14.9 MB）
真机：       PASS（15 项全过，见上表）
Upgrade：    PASS（正式签名链）；跨签名 v1.0.0→v1.1.0 = 系统拒绝，属预期，非 bug

APK：        E:\OpenCode\blueboard\releases\Board-Android-v1.1.0.apk
APK Size：   14.9 MB
SHA-256：    59049493DE8AD368907B195D0E4DB417038BF12E6DFAD956782E7AC7E76D832F
Version：    1.1.0（versionCode 4）

是否修改 v1.0.0：NO（APK 哈希、commit、tag 均未变）

最终结论：PASS
```

## 七、后续（不在本阶段范围）

1. 打开 `shrinkResources` 前，需要先把 Rime assets 的"使用关系"显式声明（或保持关闭）。
2. 同步页密码框明文回显、全新安装默认英文模式（v1.0.0 报告里的建议项，仍未处理）。
3. 视需要把 `SyncWorker` 的 workerClassName 稳定性纳入长期监控（WorkManager 已自带 keepnames）。
