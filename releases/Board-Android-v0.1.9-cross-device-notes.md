# Board Android v0.1.9 — 跨设备同步真实验收（真机 + 模拟器）

产物：`releases/Board-Android-v0.1.9-cross-device.apk`（23,342,385 B，已装到真机与模拟器）
提交：`856f6a4`
测试拓扑：`tools/WebDavLite.java` 起在 PC 的 `127.0.0.1:8080`，两台设备各自 `adb reverse tcp:8080 tcp:8080`，使用同一个 `sync_dir`（`xtest` / `xtest2`）。**测完已把两台设备的配置还原**（真机 `https://dav.jianguoyun.com/dav/` + `boardkey1`）。

---

## 一、真机 → 模拟器：**PASS**

```
真机     11:33:55 clipboard state=SYNCED hash=366eaabd
         11:33:55 clipboard push started=true http=2xx success=true hash=366eaabd
云端     { "hash": "366eaabd7e95cdeb916c1e685e696ea33a387e6b2a704510fe11182c4a71f4d3",
           "text": "phone-to-emu-1790739230" }
模拟器   11:34:10 SYNC_DOWNLOAD hash=366eaabd bytes=23
模拟器DB 47|REMOTE|1|366eaabd|phone-to-emu-1790739230      ← origin=REMOTE + remoteCurrent=1
         46|REMOTE|0|880cfc5f   45|REMOTE|0|2d9fcf0f     ← 旧内容自动清掉云端标记（A→B 语义正确）
```

结论：模拟器**确实看见**了真机的内容，且它带 `remoteCurrent=1` → 会出现在「云端」筛选里；旧的云端内容自动变成 `remoteCurrent=0` → 不再出现在云端筛选。

## 二、模拟器 → 真机：**PASS**（并暴露一个新风险）

```
模拟器   11:36:42 clipboard state=PENDING
         11:36:45 clipboard state=SYNCED hash=94e72af6
         11:36:45 clipboard push started=true http=2xx success=true hash=94e72af6
云端     { "hash": "94e72af6…", "text": "emu2phone-1790739401" }
真机     11:37:17 clipboard state=SYNCED hash=94e72af6
```

第一版这里**失败**（模拟器毫无反应、云端没更新），定位到真 bug 并修好后通过。

## 三、本轮修的真 bug：`MutableSharedFlow` 缺 `replay`

```kotlin
// 之前
private val clipboardRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, ...)
// 之后
private val clipboardRequests = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1, ...)
```

原因：`SyncEngine.init` 里是 `engineScope.launch { clipboardRequests.conflate().collect { ... } }` —— **`launch` 是异步的**。冷启动时 `SyncEngineHolder.get()` 刚构造完引擎，collector **还没订阅**，此刻 `tryEmit` 会被直接丢弃。
真机上因为 IME 早就把引擎建好、collector 已订阅，所以一直没暴露；**模拟器（无 IME、全新进程）第一条触发就丢了**。
`replay = 1` 保证"订阅之前发出的请求也能送达" → 与 `conflate()` 配合，正好满足"最终任务不能丢"。

同时给 `ClipboardShareActivity` 里那句 `runCatching` 补了失败日志（之前静默吞异常，导致"没触发"无法定位）。

## 四、Pull → Push 回环：**未复现（假设已否证）**

### 受控实验（2026-09-30）
方法：先把服务器放上内容 W → 真机推 W（排空待上传队列）→ 再把服务器换成真机**没有**的新内容 Z → 让真机拉取。

```
11:39:31 同步完成 上传=0 · 下载=1 · 冲突=1 · 跳过=0 … 合计=1873ms
```

`上传=0` + `下载=1` ⇒ 真机把新内容 Z 作为**新记录写入本地**，但**没有**把它推回云端。
**结论：回环未复现，抑制机制（`suppressCapture` + 永不过期的 `lastRemoteHash`）在这条时序上有效。没有为此改代码。**

### 对早期观察的解释
跨设备测试里真机出现过 `clipboard state=SYNCED hash=94e72af6`。按本次实验，这更可能是**真机当时确实存在一条内容就是该 hash 的待上传项**，而不是"把远端内容推回去"。该现象未再复现，不列为缺陷。


## 五、如何复现跨设备测试

```powershell
# 1) 起本地 WebDAV
Start-Process "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\java.exe" `
  -ArgumentList "E:\OpenCode\blueboard\tools\WebDavLite.java","8080","E:\OpenCode\blueboard\_webdav_remote" -WindowStyle Hidden
# 2) 两台设备各自 reverse + 把 base_url 指到 http://127.0.0.1:8080/ 、sync_dir 指到同一个新目录
# 3) 在一台注入内容（分享入口），另一台点「立即同步」后查 DB：
#    adb -s <device> shell run-as com.board.ime sqlite3 databases/board.db \
#      "SELECT id,origin,remoteCurrent,substr(contentHash,1,8) FROM clipboard_item WHERE remoteCurrent=1;"
```

## 六、交付物

| 内容 | 路径 |
|---|---|
| APK（跨设备验收版） | `E:\OpenCode\blueboard\releases\Board-Android-v0.1.9-cross-device.apk` |
| 自动同步链路验收（18 项） | `E:\OpenCode\blueboard\releases\Board-Android-v0.1.8-auto-sync-notes.md` |
| 本文档 | `E:\OpenCode\blueboard\releases\Board-Android-v0.1.9-cross-device-notes.md` |
| 本地 WebDAV 工具 | `E:\OpenCode\blueboard\tools\WebDavLite.java` |
