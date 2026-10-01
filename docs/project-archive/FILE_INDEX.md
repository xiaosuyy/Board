# 文件索引（Phase 2）

> 对应提交 `dad502c64ce5595b0441f6a4bfa43e2d8b415122`（19 files changed, 3610 insertions, 69 deletions）。

## A. 新增文件（13）

### A.1 Android 主源码（8）

| 文件 | 用途（一句话） |
|---|---|
| `android/app/src/main/java/com/board/ime/cloudlibrary/CloudLibraryModels.kt` | 资料库数据模型 + 纯规则（路径、净化、10 MiB/24h 常量、来源文案、大小写不敏感的 type 判定） |
| `android/app/src/main/java/com/board/ime/cloudlibrary/CloudLibraryCodec.kt` | `/library/index-*.json` 与 `devices.json` 的编解码、按 hash 合并（`max(uploadedAt)`）、可删除判定、`deviceId→deviceType` 映射 |
| `android/app/src/main/java/com/board/ime/cloudlibrary/CloudLibraryRemote.kt` | `/library/` WebDAV 协议层（读索引、下正文、上传 binary+index、自注册、DELETE；index 失败不回滚 binary） |
| `android/app/src/main/java/com/board/ime/cloudlibrary/CloudLibraryRepository.kt` | 业务编排（refresh 三态 / download / SAF upload / 24h prune / fetchBytes / StateFlow 快照） |
| `android/app/src/main/java/com/board/ime/cloudlibrary/LibraryCacheStore.kt` | 本机资料库缓存（App 私有目录 `library-cache/`：`index.json` + `<hash><ext>` 正文）与 `LibraryMerge`（本机 ∪ 云端） |
| `android/app/src/main/java/com/board/ime/cloudlibrary/LibraryUiModel.kt` | 纯 UI 逻辑（统一混合列表 + `uploadedAt` DESC 稳定排序 + hidden 过滤 + 缩略图增量 key） |
| `android/app/src/main/java/com/board/ime/cloudlibrary/LibraryPanel.kt` | Library 面板 View（缓存优先渲染、行卡片 `[缩略图/图标] 名称/大小/来源 [下载][×]`、预览、60s 自动刷新、逐行 fallback） |
| `android/app/src/main/java/com/board/ime/cloudlibrary/CloudLibraryAndroid.kt` | Android 适配（`LibraryMime`、SAF 有界读取 `AndroidLibraryUploadSource`、MediaStore 落盘 `AndroidLibraryFileSink`） |

### A.2 Android 资源（1）

| 文件 | 用途 |
|---|---|
| `android/app/src/main/res/drawable/ic_board_refresh.xml` | Library 刷新按钮的单色矢量图标（24×24，描边，运行时 `setTint`） |

### A.3 Android 单元测试（4）

| 文件 | 用途 |
|---|---|
| `android/app/src/test/java/com/board/ime/cloudlibrary/CloudLibraryCodecTest.kt` | 编解码 / TTL / union / 路径净化 / type 大小写 / MIME 等 21 项断言 |
| `android/app/src/test/java/com/board/ime/cloudlibrary/CloudLibraryRemoteTest.kt` | 远端层协议语义（含回滚安全、404 幂等、路径穿越、不碰 `/file/`）27 项 |
| `android/app/src/test/java/com/board/ime/cloudlibrary/CloudLibraryRepositoryTest.kt` | Repository 编排（refresh/下载/上传/prune/hidden/deviceId）28 项 |
| `android/app/src/test/java/com/board/ime/cloudlibrary/LibraryUiModelTest.kt` | 统一列表 / 排序 / hidden / 缩略图增量 / **metadata-only 可见** / union 25 项 |

## B. 修改文件（6）

| 文件 | 用途（一句话） |
|---|---|
| `android/app/src/main/java/com/board/ime/ui/panel/ClipboardPanel.kt` | Clipboard UI 改为**只展示文字**：`visibleItems()` 恒为 `isTextLike`，删除 `ClipPage/pageIndex/page/switchPage` 与水平切页接管（**保留全部公开回调**） |
| `android/app/src/main/java/com/board/ime/ui/panel/TopPanelController.kt` | 新增 `onOpenLibrary`，把剪贴板面板的**云朵图标**回调指向「打开云端资料库」 |
| `android/app/src/main/java/com/board/ime/ime/BoardInputMethodService.kt` | 实现云朵 → `SettingsSectionActivity(EXTRA_SECTION=LIBRARY)` 的一跳导航 |
| `android/app/src/main/java/com/board/ime/settings/SettingsSection.kt` | 新增一级分类 `LIBRARY("云端资料库", …)` |
| `android/app/src/main/java/com/board/ime/settings/SettingsSectionActivity.kt` | 新增 `buildLibrary(content)`（Activity 级 `by lazy` 装配 Repository + SAF 上传启动器 + 隐藏回调 + `onDestroy` 释放） |
| `android/app/src/main/java/com/board/ime/settings/BoardPreferences.kt` | 新增 `hiddenLibraryHashes`（`StringSet`，**只存 hash**）与 `hide/unhide/isLibraryHashHidden` |

## C. 明确**未改动**的文件（边界确认）

以下均**未在本次提交中出现**，属刻意保持不动：

- Windows：`windows/BoardCompanion/CloudLibrary.cs`、`CloudLibraryRemote.cs`、`CloudLibraryForm.cs`、`ClipboardSyncEngine.cs`、`ClipboardMonitor.cs`、`ClipboardLog.cs` 等
- Android：`clipboard/ClipboardRepository.kt`、`sync/SyncEngine.kt`、`sync/SyncWorker.kt`、`sync/ClipboardLogRecords.kt`、`clipboard/SyncClipboardMapper.kt`
- 协议与文档：`SyncClipboard.json` 结构、`/file/`、`/clipboard-log/`、`docs/PRIVACY_AND_CN_COMPATIBILITY.md`

（`docs/project-archive/*` 为本次归档新增的文档，属独立提交 `chore: archive project phase 2`。）
