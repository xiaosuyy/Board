# v0.2.0 关键修复：远端剪贴板内容被静默丢弃（"另一端看不见"的根因）

## 根因
`SyncEngine.pullRemote()` 里有一段按时间判断的冲突逻辑：

```kotlin
val localLatest = clipboardDao.latestSummary()
if (localLatest != null) {
    val remoteTime = response.lastModified   // 服务器 Last-Modified（秒级、可能跨时区）
    val localTime  = localLatest.updatedAt   // 本机时钟 = 用户"刚刚"复制的时间
    if (remoteTime != null && localTime > remoteTime) {
        return PullOutcome.Applied(0, 1, 0)  // 保留本地，远端内容被丢弃
    }
}
```

`localTime` 几乎永远大于 `remoteTime`（设备时钟不同步 + 用户刚在本机复制过），
于是**远端内容被无条件丢弃，连一条本地记录都不会建** → 剪贴板面板里永远看不到另一台设备的内容。

## 修复
删除整段时间比较逻辑：SyncClipboard 只有一条"当前剪贴板"，且同步顺序是"先 push 再 pull"，
pull 拿到的一定是最新状态 → **无条件写入本地**（不覆盖、不删除任何本地条目）。提交 `7ccdf5c`。

## 用户亲自确认的验收（display 层）
1. 在"另一台设备"（本地 WebDAV）放入一条真机从未有过的内容 `BROWSER-NEW-115748`（hash `19bada42…`）
2. 真机上**只打开 Board 剪贴板面板**（不点任何同步按钮）
3. **用户确认：面板里看到了 `BROWSER-NEW-115748`** ✓

对应日志：

```
clipboard auto trigger source=AFTER_CLIPBOARD_CHANGE     ← 打开面板触发同步
HTTP GET SyncClipboard.json http=200                     ← 拉取
SYNC_DOWNLOAD hash=19bada42 …                            ← 写入本地（面板显示的就是它）
云端当前内容 → hash=19bada42（已重置旧标记）               ← 标为「云端」
```

另外，"打开面板触发同步"这条链路在更早一次用户操作中也验证过：
`11:57:10 auto trigger` → `GET SyncClipboard.json 200` → `SKIP_SAME_HASH hash=19c4a013` → `云端当前内容 → 19c4a013`。

## 交付
- APK：`releases/Board-Android-v0.2.0-pull-fix.apk`
- 提交：`7ccdf5c`