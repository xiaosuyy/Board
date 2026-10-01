# Board 项目归档（Phase 2）

> **本项目当前处于阶段性完成并暂时封存状态。**
> 后续重新开发时，应以当前稳定基线为起点，**不直接在历史提交上做破坏性修改**。

---

## 这是什么项目

**Board** 是一个双端剪贴板 / 输入法协作项目：

| 端 | 形态 | 主要能力 |
|---|---|---|
| Android | 输入法（IME，Kotlin + Rime/librime） | 中文输入、剪贴板历史、剪贴板同步、云端资料库、翻译、语音 |
| Windows | 托盘小工具（C# / .NET Framework / WinForms） | 系统剪贴板镜像、剪贴板同步、云端资料库（上传/下载/24h TTL prune） |
| 云端 | 用户自建 WebDAV | `SyncClipboard.json`（当前剪贴板）、`/file/`（大文本/图片/文件）、`/library/`（云端资料库）、`/clipboard-log/`（B1 增量历史） |

---

## 当前状态

- **阶段性完成，暂时封存**（不再继续开发）
- 已完成到 **Phase 2**（云端资料库 Android 侧 + Clipboard 纯文字 UI + 本机 Library 缓存）
- **稳定基线提交**：

```
dad502c64ce5595b0441f6a4bfa43e2d8b415122
feat: complete cloud library phase 2
```

- 工作区**干净**，无未提交改动

---

## 重新启动项目时，先读这些

建议阅读顺序：

1. `docs/project-archive/README.md`（本文件）— 总入口
2. `docs/project-archive/PROJECT_STATUS.md` — 当前最终状态与基线信息
3. `docs/project-archive/ARCHITECTURE.md` — 模块关系（**特别是 Clipboard 与 Library 的边界**）
4. `docs/project-archive/PHASE_HISTORY.md` — 各阶段做了什么
5. `docs/project-archive/VALIDATION.md` — 哪些已自动/人工验证，哪些仍受限
6. `docs/project-archive/KNOWN_ISSUES.md` — 已知限制与**刻意保留的旧代码**
7. `docs/project-archive/NEXT_STEPS.md` — 未来候选项（**本次不实施**）
8. `docs/project-archive/FILE_INDEX.md` — Phase 2 新增/修改文件索引
9. `docs/PRIVACY_AND_CN_COMPATIBILITY.md` — 隐私与国内可用性基线（含 B1 与 Cloud Library 的隐私行为）

---

## 核心模块在哪里

**Android**（`android/app/src/main/java/com/board/ime/`）

| 领域 | 位置 |
|---|---|
| 输入法服务 / 面板调度 | `ime/BoardInputMethodService.kt`、`ui/panel/TopPanelController.kt` |
| 剪贴板 UI（**已改为纯文字**） | `ui/panel/ClipboardPanel.kt`、`ui/panel/ClipboardHeader.kt` |
| 剪贴板数据层 / 同步 | `clipboard/ClipboardRepository.kt`、`sync/SyncEngine.kt`、`sync/webdav/WebDavClient.kt` |
| 增量剪贴板历史（B1） | `sync/ClipboardLogRecords.kt`、`sync/SyncEngine.kt`、`sync/webdav/WebDavConfigStore.kt` |
| **云端资料库（Phase 2）** | `cloudlibrary/`（8 个文件，见 `FILE_INDEX.md`） |
| 设置 | `settings/SettingsSection.kt`、`settings/SettingsSectionActivity.kt`、`settings/BoardPreferences.kt` |

**Windows**（`windows/BoardCompanion/`）：`ClipboardSyncEngine.cs`、`CloudLibrary.cs` / `CloudLibraryRemote.cs` / `CloudLibraryForm.cs`、`WebDavClient.cs`、`ClipboardMonitor.cs`、`ClipboardLog.cs`、`ClipboardNotification.cs`、`SyncCoalescer.cs`

---

## 构建 / 测试方式

**Android**（项目模块为 `:android:app`）

```powershell
cd E:\OpenCode\blueboard
.\gradlew.bat --offline :android:app:testDebugUnitTest
.\gradlew.bat --offline :android:app:assembleDebug
```

**Windows**（只用系统 in-box 编译器，零 NuGet）

```powershell
cd windows\BoardCompanion
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

> 注意：Windows 构建前需确认 `BoardCompanion.exe` 未在运行（否则 `build\` 被占用），且**不要强杀用户进程**。

---

## APK 输出位置

```
android\app\build\outputs\apk\debug\app-debug.apk
大小：24,467,092 B
SHA256：81fe6f77093fd883656dbba2b878958668a1e8aeea444b49a1a2e6719be7d804
```

---

## 工作区中未被版本控制的临时内容（按指示未删除）

项目根目录存在若干中间产物 / 备份目录，**未被 `git status` 跟踪**（本次归档提交不含它们），按指示**未删除**：

```
_probe/  _webdav_remote/  backup-before-dictionary-fix/  releases/
build/  .gradle/  .kotlin/  local.properties
_final.log _p2.log _r8.log _rc.log _rel.log _s2.log _s3.log _s4.log _s5.log _ux.log _ux2.log _ux3.log _ux4.log _v12.log
```

重新启动项目时可自行决定是否清理，但**不要在未确认的情况下删除**（其中 `backup-before-dictionary-fix/`、`releases/` 可能是历史备份）。
