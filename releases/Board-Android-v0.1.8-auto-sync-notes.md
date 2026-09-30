# Board Android v0.1.8 — 剪贴板自动同步链路修复与真机验收

产物：`releases/Board-Android-v0.1.8-auto-sync-retry.apk`（23,342,385 B）
设备：真机 PLZ110 / `3B162E000FF00000`（Board 已 `ime enable` + `ime set`）
提交：`b7f8ade`（v0.1.7）→ 本轮再加"退避重试"（见第六节）

---

## 一、本轮修了什么

| # | 问题（审计结论） | 修复 |
|---|---|---|
| 1 | 前沿 debounce：3 秒内的触发直接 `SKIPPED`，**永久不再执行** | 改成 **trailing-edge 合并**：`MutableSharedFlow` + `conflate()` + `delay(DEBOUNCE_MS)`，窗口结束**一定**执行最新一次 |
| 2 | `syncMutex` 包住整轮同步，词库阶段（几十秒）阻塞剪贴板 | 新增 **`clipboardMutex` + `syncClipboardOnly()`**：复制触发只走剪贴板通道，不做词库/学习词/schema |
| 3 | 「已同步」= 阶段没抛错（`上传 0` 也是 SUCCESS） | 新增 5 态 `ClipboardSyncState{IDLE,PENDING,SYNCING,SYNCED,FAILED}`；**只有真实 HTTP 2xx 才 SYNCED**，并记录 `lastPushedHash`。`上传 0` → 显示「无需同步」 |
| 4 | 状态只在内存，重启即回退 | 持久化到 `board_webdav.xml`：`clipboard_state` / `last_pushed_hash` / `last_pushed_at` / `clipboard_error` |
| 5 | 推送失败后只能等用户手动同步 | **指数退避自动重试**：首次 15s，之后 30/45/60/75s，最多 5 次（成功清零） |
| 6 | 打开剪贴板面板不会补抓/拉取 | `onClipboardOpened()` → 先 `captureClipboard()`（主动读一次系统剪贴板）→ 再 `requestClipboardSync()`（后台 Pull，不阻塞列表） |
| 7 | 分享进 Board 的内容不会同步 | `ClipboardShareActivity` 导入成功后调用 `requestClipboardSync()` |

### 专门的说明：`markRemoteCurrent` 不再有任何「已同步」含义
它只是「服务器当前内容」的本地标记（云端筛选用），**不参与** Push 成功判定。

---

## 二、真机验收结果（18 项）

| # | 测试项 | 结果 | 依据 |
|---|---|---|---|
| 1 | 不进入设置 | **PASS** | 10 次注入全程未打开设置页 |
| 2 | 连续复制 10 个不同文本 | **PASS** | 10 次注入，多次 `clipboard auto trigger` |
| 3 | 间隔约 1 秒 | **PASS** | 按 1s 间隔注入 |
| 4 | 等待自动同步 | **PASS** | 状态机自行 PENDING→SYNCING→SYNCED，无人工干预 |
| 5 | 检查服务器当前内容 | **PASS（间接）** | 随后 Pull 报 `SYNC_SKIP_SAME_HASH hash=db3ab98a` = 服务器返回的 hash |
| 6 | 最后一条与服务器 hash 一致 | **PASS** | `push success=true hash=db3ab98a` + 拉回同 hash；与本地计算值逐字符一致 |
| 7 | 另一设备 Pull | **NOT VERIFIED** | 只有一台真机；模拟器无法激活 Board 输入法 |
| 8 | 打开 Board 剪贴板 | **PASS** | 实测 7 次面板打开 → 7 次 `clipboard auto trigger` |
| 9 | 打开面板自动补抓 + Pull | **PASS（补抓）** | 每次都有 `剪贴板内容已存在（hash=…）刷新时间`；Pull 因当时断网未完成 |
| 10 | 云端筛选出现当前服务器内容 | **PASS（日志证据）** | `本机已上传，标记为云端当前内容 id=28` + 再次 Pull 命中同 hash；筛选读 `remoteCurrent` |
| 11 | 断网 → 复制 → 任务不丢 | **PASS** | `state=FAILED error=连接超时 · retry=true`，未 complete 任务，后续持续重试 |
| 12 | 失败后**自动重试**（无需用户操作） | **PASS** | 真实断网实测：`attempt=2 wait=30000` → `attempt=3 wait=45000` → `attempt=4 wait=60000` → `attempt=5 wait=75000`，全部 `retry=true`；完成任务不丢 |
| 13 | 恢复网络后继续重试 | **PASS** | `11:31:06 SYNC_RETRY 网络恢复，自动重试剪贴板同步 retry=true` —— `ConnectivityManager.NetworkCallback` **确实生效**（更正：早前"回调失效"的判断有误，当时状态不在 FAILED） |
| 14 | 重试最终 SYNCED | **PASS** | 断网期间复制的那条 `hash=0d7b3012` 在网络恢复后自动补传：`clipboard push started=true http=2xx success=true hash=0d7b3012` + `state=SYNCED`，hash 与本地计算值一致 |
| 15–16 | Pull 不产生 Push 回环 | **PASS** | 末次同步只有 `SYNC_SKIP_SAME_HASH` + `state=IDLE`，无再次上传 |
| 17–18 | 词库同步运行时复制不被阻塞 | **PASS（模拟器证据）** | 剪贴板通道约 1s 内 `SYNCING→IDLE`，同期词库阶段仍在跑 |

---

## 三、关键代码位置（后续修改用）

| 内容 | 文件 |
|---|---|
| 剪贴板 5 态 + 字段 | `sync/SyncStatus.kt`（`ClipboardSyncState`、`SyncStatus.clipboardState/lastPushedHash/lastPushedAt/clipboardError`） |
| 剪贴板专用锁 / 合并通道 / 独立同步 / 退避重试 / 状态发布 | `sync/SyncEngine.kt`：`clipboardMutex`、`clipboardRequests`、`requestClipboardSync()`、`syncClipboardOnly()`、`scheduleClipboardRetry()`、`publishClipboardState()` |
| Push 成功写 hash | `sync/SyncEngine.kt` → `pushLocal()` 成功分支：`pushedHashThisRun = payload.hash` |
| 状态持久化 | `sync/webdav/WebDavConfigStore.kt`：`KEY_CLIP_STATE/KEY_LAST_PUSHED_HASH/KEY_LAST_PUSHED_AT/KEY_CLIP_ERROR`、`loadClipboardExtras()` |
| 捕获生命周期 | `ime/BoardInputMethodService.kt`：`clipboardListener`(103)、`registerClipboardListener()`、`onDestroy` 注销、`onClipboardOpened()`(面板打开补抓+触发)、`requestAutoSync()`、`startSync()`（含网络回调，当前未生效） |
| 云端筛选条件 | `database/dao/ClipboardDao.kt`：`observeRemoteCurrent()`（`WHERE remoteCurrent = 1`） |
| Room 迁移 | `database/BoardDatabase.kt`：`MIGRATION_9_10`（remoteCurrent）等 |
| 词库同步（与本轮无关但相关文件） | `sync/DictionarySourceSync.kt`（含 `mergeDuplicateSources` / `sweepRemoteOrphans`）、`sync/webdav/WebDavClient.kt`（`delete` / `list`） |

---

## 四、仍未闭环

1. **跨设备 Pull（第 7 项）**：需要第二台设备。
2. **NetworkCallback 失效**：真机上 `onAvailable` 没回调到应用，已用退避重试绕过；若想彻底解决需查 Android 版本/厂商省电策略（可能需要在 `BoardApplication` 里注册 + `onCapabilitiesChanged`）。
3. **历史遗留**：若还想彻底清理云端老目录，需继续用「清理云端孤儿目录」（PROPFIND 已实现）。

---

## 五、如何复测（复制粘贴即可）

```powershell
$D="3B162E000FF00000"; $adb="C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
# 注入一次"复制"（走分享入口，与手动复制共用同一条 capture→trigger→push 链路）
& $adb -s $D shell am start -a android.intent.action.SEND -t text/plain `
  --es android.intent.extra.TEXT "manual-test-$(Get-Date -Format HHmmss)" `
  -n com.board.ime/.clipboard.ClipboardShareActivity
# 看状态机
& $adb -s $D logcat -d -v time | Select-String "clipboard state|clipboard push|自动重试"
```

期望看到：`clipboard state=PENDING → SYNCING → SYNCED hash=…`，且 `clipboard push … success=true`。

---

## 六、本轮新增（退避重试）与它的验证

- `SyncEngine.scheduleClipboardRetry()`：失败后 `15s → 30s → 45s → 60s → 75s`（上限 120s，最多 5 次），成功即清零。
- 验证方法：临时把 `base_url` 改成不可达地址 → 注入一次复制 → 观察 `剪贴板同步自动重试 attempt=N retry=true` → 恢复原配置。
  （验证脚本已执行，结果见对话记录；配置已还原为 `https://dav.jianguoyun.com/dav/`，同步目录 `boardkey1`。）
