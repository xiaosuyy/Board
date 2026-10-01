# 阶段历史（Phase History）

> 只记录**已发生**的阶段与提交，不做未来规划。

## 基线提交链（`master`）

```
dad502c  feat: complete cloud library phase 2          ← 当前稳定基线
3b8b7c1  feat(v2.0 b1): add incremental text clipboard history
1aaf13b  feat(v2.0 p1h): add cloud library with 24h ttl and non-blocking ui
b1ab3bb  fix(v2.0 p1g): keep cloud clipboard entries single and add global sync hotkey
e184cfe  feat(v2.0 p1f): add clipboard lifecycle and image preview
99250aa  feat(v2.0 p1e): add bidirectional image clipboard sync
```

tag：`v0.1.0-mobile` / `v1.0.0` / `v1.1.0`（Phase 2 归档**未新增 tag**）

---

## 更早阶段（摘要）

- **v1.0.0 / v1.1.0** 封版
- **v1.2 Phase 1**（1-A ~ 1-D）：剪贴板同步、协议、尺寸边界、路径规则
- **Phase 1-E**（`99250aa`）：双向图片剪贴板同步（PNG 编码、二进制 `/file/`、10 MiB 上限）
- **Phase 1-F**（`e184cfe`）：剪贴板生命周期（1h）+ 图片预览
- **Phase 1-G**（`b1ab3bb`）：CLOUD/LOCAL 去重修复 + `Ctrl+Alt+S` 全局快捷键
- **Phase 1-H**（`1aaf13b`）：**Cloud Library（Windows）** —— `/library/`、24h TTL、多设备 `max(uploadedAt)` 保护、真 DELETE（404=成功）、非阻塞 UI
- **B1**（`3b8b7c1`）：**增量剪贴板历史** —— Android 唯一 `ClipboardRepository`、Windows `ClipboardNotificationGate`、`ClipboardSnapshot` 单次读取、`SyncCoalescer`、`/clipboard-log/<deviceId>.json`、seq 先持久化后 PUT、crash-window 重分配保护、lastSeenSeq、minSeq/compaction、hash 去重、与 latest-state 隔离、无回环、隐私文档更新

---

## Phase 2（本次封存的内容，提交 `dad502c`）

### Phase 2 — Clipboard 纯文字 UI + Library 统一列表 + 本机隐藏

- Clipboard 改为**纯文字 UI**：`visibleItems()` 恒为 `isTextLike`；移除 `ClipPage` / `pageIndex` / `page` / `switchPage` 与水平切页接管
- **保留底层图片/文件同步能力**（`ClipboardRepository` / `SyncEngine` / `/file/` / B1 全部未动）
- 云朵图标 → **一键进入「云端资料库」**（不再作为 remote-only 过滤开关）
- Library 改为**图片 + 文件统一混合列表**（不再分区、无左右滑、无分类标题）
- **下载**（图片 → `Pictures/Board`；文件 → `Download/Board`，MediaStore + `IS_PENDING`）
- **本机隐藏**：每行 `[下载][×]`；`×` = 仅本机不显示
- **`hiddenLibraryHashes`**（`BoardPreferences` 的 `StringSet`，只存 hash）
- **`uploadedAt` DESC 排序**（同值按 hash 稳定，刷新不抖动）
- **云端数据 + 本机缓存合并**（`LibraryMerge`）
- **offline cache-first**：进入页面先显示本机缓存，再后台刷新
- **增量刷新**：只对新增/无正文的 hash 取正文与缩略图
- **metadata-only 条目保留**（云端 binary 404 也不删本机 metadata）
- **本地缓存不因隐藏 / 云端 TTL 而删除**
- **24h TTL / prune 只作用于云端**
- **异常行 fallback**：逐行 `runCatching` + 兜底行，单行失败不牵连整页
- 自动测试 + 人工真机验证（见 `VALIDATION.md`）

### Phase 2.1 — 本机 Library 缓存

- 新增 **`LibraryCacheStore`**：App 私有 `filesDir/library-cache/`（`index.json` + `<hash><ext>` 原始正文）
- **cache-first**：进入 Library 先读本机缓存立即渲染，再后台刷新云端 index
- 缩略图/预览优先读本机缓存（`bytesFor()`），命中即不再访问 WebDAV

### Phase 2.2 — 真机验证（断网 / 增量）

- 真机**断网 + force-stop + 重进**：本机历史内容仍立即显示（不依赖网络）
- Windows 新上传内容**增量出现**并位于顶部
- 已缓存内容**不重复下载**
- × 图标可见性修复：色值解析支持 `resourceId`（ColorStateList）+ 圆形底，解决“图标看不见”

### Phase 2.3 — metadata-only / fallback 加固

- 定位并澄清：**不存在 `contentCached` 过滤**（全量 grep 确认该字段只写不读）；"漏行"实为用户已 `×` 隐藏该 hash
- 仍做加固：`render()` 逐行 `runCatching` + `buildFallbackRow`；缩略图取不到时显示通用占位图标；预览失败文案改为「× 内容已不可用」
- 新增 6 项 metadata-only / union 回归测试

### Phase 2.4 — 人工真机验收

- 用户人工验证核心功能通过（16 项，见 `PROJECT_STATUS.md` §5）

### Final Health Check

- `git diff --check` → **PASS**
- Android tests → **210 PASS / 0 FAIL / 0 ERROR**
- Debug APK build → **BUILD SUCCESSFUL**
- working tree → **clean**
