# 架构说明（Phase 2 现状）

> 本文只**描述现有代码**，不做重新设计。所有结论均来自当前工作区源码。

## 0. 最重要的一条边界

> **Library 是新增的“资料库展示 / 缓存”能力，但不能把它理解为替代原有 Clipboard 图片 / 文件同步能力。**
>
> **Phase 2 没有删除底层 clipboard image/file sync。**

具体地：Clipboard 的图片/文件**捕获、上传、下载、历史入库**仍在 `ClipboardRepository` + `SyncEngine` 里；Phase 2 只是让 **Clipboard 面板 UI 不再展示**图片/文件行。

## 1. Android 端

### 1.1 输入法与面板调度

| 模块 | 职责 |
|---|---|
| `ime/BoardInputMethodService.kt` | IME 服务；创建 `ClipboardRepository`（**唯一实例**，经 `SyncEngineHolder.clipboardRepository()`）、注册剪贴板监听、驱动面板、承载设置页入口。Phase 2 新增：把剪贴板面板的**云朵图标**接到「打开云端资料库」 |
| `ui/panel/TopPanelController.kt` | 顶部面板状态机（`TopPanelState`）；把各 Header/Panel 的回调接到宿主。Phase 2 新增 `onOpenLibrary`，并把 `clipboardHeader.onToggleRemoteFilter` 指向它 |
| `ui/panel/ClipboardHeader.kt` | Clipboard 顶部栏（返回 / 编辑 / 搜索 / 清空 / **云朵**）；`Slot.CLOUD` → `onToggleRemoteFilter`（现由宿主解释为“打开 Library”） |

### 1.2 Clipboard（**已改为纯文字 UI**）

| 模块 | 职责 |
|---|---|
| `ui/panel/ClipboardPanel.kt` | 剪贴板列表 UI。Phase 2：`visibleItems()` 恒为 `items.filter { it.contentType.isTextLike }`；**删除** `ClipPage` / `pageIndex` / `page` / `switchPage` 与水平切页接管 ⇒ 无左右滑、无图片缩略图行、无文件卡片行。**公开回调全部保留**（`onLoadThumbnail` / `onSaveImage` / `onLoadContent` / `onInsert` / `onDelete` …） |
| `clipboard/ClipboardRepository.kt` | **未改**。图片/文件捕获（`captureImage*`）、远端应用（`applyRemote` / `applyRemoteImage`）、内容落盘（`contentStore`）、hash 去重、生命周期 |
| `sync/SyncEngine.kt` | **未改**。`pushImageLocal` / `pullImage` / `PullFile` / `pushLocal` / `pullRemote` / `syncClipboardOnly`；B1（`clipboard-log`）生产者与消费者 |
| `sync/SyncWorker.kt` | **未改**。30 分钟周期兜底（`expireHistory` + `syncNow`） |

### 1.3 云端资料库（Phase 2 新增，`cloudlibrary/`）

```
LibraryPanel (UI, Android View)
   ├─ 依赖 CloudLibraryRepository（业务编排）
   ├─ 依赖 LibraryCacheStore（本机缓存：metadata + 正文 bytes）
   └─ 依赖 LibraryUiModel / LibraryMerge（纯逻辑：列表、排序、合并、过滤）

CloudLibraryRepository
   ├─ CloudLibraryRemote （WebDAV /library/ 读写；不含 UI）
   │     └─ CloudLibraryCodec（index/devices 编解码、merge、deletable）
   │           └─ CloudLibraryModels（模型 + 纯规则：路径、净化、来源文案、大小写不敏感 type）
   ├─ LibraryFileSink  ← AndroidLibraryFileSink（MediaStore：Pictures/Board、Download/Board）
   └─ LibraryUploadSource ← AndroidLibraryUploadSource（SAF ContentResolver 有界读取）
```

| 模块 | 职责 |
|---|---|
| `cloudlibrary/CloudLibraryModels.kt` | `CloudLibraryItem` / `CloudLibraryIndex` + 纯规则：`FOLDER/INDEX_PREFIX`、10 MiB 上限、24h TTL、`indexPathFor` / `binaryPathFor`、`sanitizeToken/sanitizeFileName/normalizeExtension`、`sanitizeHashOrNull`、`originText`、**大小写不敏感** `isImage/hasPreview`、`typeFor` |
| `cloudlibrary/CloudLibraryCodec.kt` | `index-<deviceId>.json` / `devices.json` 编解码；`mergeIndexes`（按 hash 去重 + `max(uploadedAt)` + 倒序）；`deletableItems`（**全部设备都过期**才可删）；`deviceTypes(indexJsons)` 只读映射 |
| `cloudlibrary/CloudLibraryRemote.kt` | `/library/` 协议层：读注册表/索引、`GET library/<hash><ext>`、上传（binary → own index → 自注册）、`DELETE binary`（404=成功）、`putOwnIndex`。**index 失败不回滚 binary**（安全优先） |
| `cloudlibrary/CloudLibraryRepository.kt` | 业务编排：`refresh()`（三态：成功/partial/全失败保留旧列表）、`download()`、`upload()`（SAF 单文件）、`prune()`（24h，partial 直接 skip）、`fetchBytes()`（缩略图/预览用）、`state: StateFlow<LibrarySnapshot>` |
| `cloudlibrary/LibraryCacheStore.kt` | 本机缓存（App 私有 `filesDir/library-cache/`）：`index.json`（metadata）+ `<hash><ext>`（原始正文）；以及 `LibraryMerge`（**本机缓存 ∪ 云端**，云端 TTL 消失不删本机） |
| `cloudlibrary/LibraryUiModel.kt` | 纯 UI 逻辑：统一混合列表 `rows()`（过滤 hidden → `uploadedAt` DESC → 同值按 hash 稳定）、`sizeLabel`、`sourceLabel`、`canPreview`（仅图片）、`liveHashes` / `staleThumbnailKeys`（缩略图增量） |
| `cloudlibrary/LibraryPanel.kt` | Library 面板 View：**缓存优先**（`load cache → render` 先于网络）→ 订阅 `StateFlow` → 后台 `prune+refresh` → 只对**本机没有正文**的 hash `fetchBytes`；每行 `[缩略图/图标] 名称/大小/来源 [下载][×]`；60s RESUMED 自动刷新；逐行 `runCatching` + fallback 行 |
| `cloudlibrary/CloudLibraryAndroid.kt` | Android 适配：`LibraryMime`（纯）、`AndroidLibraryUploadSource`（SAF 有界读取）、`AndroidLibraryFileSink`（MediaStore + `IS_PENDING`，失败删半成品） |
| `res/drawable/ic_board_refresh.xml` | Library 刷新按钮用的单色矢量图标 |

### 1.4 设置与偏好

| 模块 | 职责 |
|---|---|
| `settings/SettingsSection.kt` | 一级分类枚举；Phase 2 新增 `LIBRARY("云端资料库", …")` |
| `settings/SettingsSectionActivity.kt` | 各分类页面；Phase 2 新增 `buildLibrary(content)`（Activity 级 `by lazy` 装配 `CloudLibraryRepository`）、SAF `OpenDocument` 启动器、`onDestroy` 释放面板 |
| `settings/BoardPreferences.kt` | SharedPreferences 封装；Phase 2 新增 `hiddenLibraryHashes`（`StringSet`，**只存 hash**）及 `hide/unhide/isHidden` |
| `settings/BoardSettingsActivity.kt` | 一级列表（`SettingsSection.entries` 自动渲染）——**无需修改** |

## 2. Windows 端（`windows/BoardCompanion/`）

| 模块 | 职责 |
|---|---|
| `ClipboardSyncEngine.cs` | 系统剪贴板镜像 + 去抖 + hash 去重 + 防回环 + B1 生产者；`syncCoalescer` 合并补跑 |
| `ClipboardMonitor.cs` | 专用 STA 监听线程 + 单次 `GetDataObject()` 快照 |
| `ClipboardNotification.cs` | 通知级去重（sequence + 120ms 内容窗口） |
| `SyncCoalescer.cs` | 单执行者 + 合并补跑 |
| `ClipboardLog.cs` | B1 事件模型 / 编解码 / seq 分配 / 崩溃窗口重分配 |
| `CloudLibrary.cs` | 云端资料库纯规则：24h TTL、`MergeIndexes`、`DeletableItems`、路径/净化/来源文案 |
| `CloudLibraryRemote.cs` | `/library/` WebDAV 实现 + `devices.json` 注册表 |
| `CloudLibraryForm.cs` | 非阻塞资料库窗口（`Task.Run` + `BeginInvokeSafe` + 页面内缩略图缓存） |

## 3. 云端与协议

| 资源 | 说明 |
|---|---|
| `SyncClipboard.json` | 「当前剪贴板」latest-state（**Phase 2 未改其字段/语义**） |
| `/file/<dataName>` | 大文本 / 图片 PNG / 文件二进制（**Phase 2 未改**） |
| `/library/<hash><ext>` | 云端资料库正文（原始 bytes、原始扩展名） |
| `/library/index-<deviceId>.json` | 每设备自己的索引（`hash/type/name/size/uploadedAt/deviceId`） |
| `/library/devices.json` | 共享设备注册表（`{"schema":1,"devices":[…]}`；**不新增第二套 registry**） |
| `/clipboard-log/<deviceId>.json` | B1 增量剪贴板历史（每设备只写自己） |

**协议隔离**：`/file/` ≠ `/library/` ≠ `/clipboard-log/`；`1h`（Clipboard 文件缓存）≠ `24h`（Library TTL）≠ `7d`（B1 retention）。

## 4. 线程模型（Android）

| 位置 | 线程 |
|---|---|
| `LibraryPanel` 建 View / `render` / 回填 | 主线程（`Dispatchers.Main.immediate`） |
| `CloudLibraryRepository` 全部 `suspend` | **内部不切线程**，由调用方在 `Dispatchers.IO` 上执行 |
| `AndroidLibraryUploadSource` / `AndroidLibraryFileSink` | 内部 `withContext(Dispatchers.IO)` |
| WebDAV / SHA-256 / 图片下载与解码 | `Dispatchers.IO` |

**UI 线程不做网络 / 解码 / 大批量文件 IO。**

## 5. 隐私边界

- 本机 Library 缓存位于 App 私有目录，**不上传**、不进 `SyncClipboard.json`、不进 WebDAV、不进 `ClipboardRepository` 同步链
- `hiddenLibraryHashes` **只存 hash**
- 日志不含剪贴板正文 / 二进制 / Authorization
- 详见 `docs/PRIVACY_AND_CN_COMPATIBILITY.md`
