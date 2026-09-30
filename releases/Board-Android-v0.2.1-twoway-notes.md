# v0.2.1 第三处关键修复：打开剪贴板面板会"覆盖云端"，导致永远拉不到对方内容

## 现象（用户实测）
一端复制 → 另一端打开剪贴板面板 → **看不见**刚复制的内容。

## 根因（真机 + 模拟器日志证据）
打开剪贴板面板时，`onClipboardOpened()` 会「顺手读一次系统剪贴板」补抓；
而 `capture()` 在"内容已存在"（UPDATED）时仍会 `enqueueClipboardPush()` →
**于是每次打开面板都会把自己的内容重新上传，在 pull 之前就把云端覆盖成自己的**：

```
12:12:18 SYNC_UPLOAD hash=d719588a -> SYNCED     推送自己的内容
12:12:38 clipboard auto trigger (打开面板)
12:12:41 SYNC_UPLOAD hash=d719588a -> SYNCED     ★ 打开面板又推了一遍自己的
```

## 修复
给 `capture()` 增加 `enqueuePush: Boolean = true`；打开面板的补抓走
`captureClipboard(pushToCloud = false)` → **只入库、不产生上传**。
只有"用户真的复制"（ClipboardManager 监听回调）才会上传。提交见 git。

## 修复后的双向实测（本地 WebDAV，两台设备）
```
MuMu  复制 MUMU-URL-121340
真机  同步 → SYNCED hash=ca429106
真机  复制 WECHAT-121340
MuMu  同步 → SYNC_DOWNLOAD hash=e7e91cb0 bytes=13

MuMu 剪贴板列表：
  61|REMOTE|1|WECHAT-121340              ← 真机复制的内容，已同步并标为「云端」
  60|LOCAL|0|MUMU-URL-121340             ← MuMu 自己复制的
  59|LOCAL|0|https://github.com/cwuom    ← 用户在浏览器复制的网址
  58|REMOTE|0|【网易】您好…                ← 之前从真机同步过来的
```

结论：**两端都持有对方复制过的内容** —— 目标达成。

## 三处关键修复汇总（"另一端看不见"的完整链条）
1. `pullRemote` 用本地时间 vs 服务器时间判断冲突 → 远端几乎永远被判"更旧"而丢弃（`7ccdf5c`）
2. `MutableSharedFlow` 缺 `replay` → 冷启动第一条触发被丢（`856f6a4`）
3. 打开面板补抓会重新上传 → 覆盖云端，导致拉不到对方内容（本次）