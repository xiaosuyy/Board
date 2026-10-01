# 后续事项（NEXT STEPS）

> **以下内容仅作为未来候选事项，本次不实施。**
> 项目当前处于阶段性封存状态，重新启动时请先读 `README.md` 与 `PROJECT_STATUS.md`。

---

## 1. 补齐验证（优先级最高，成本最低）

- SAF 主动上传：图片 / 普通文件各一次（真机人工）
- 本机同 hash 重新上传 → 解除 `hiddenLibraryHashes`（真机人工）
- 其他设备（Windows）重传同 hash → 确认本机 hidden **不**被解除
- Activity recreate / 旋转 → 缓存、hidden、排序、缩略图恢复
- `>10 MiB` 文件走 SAF → 确认被拒绝且不写 binary/index

## 2. Library 体验细节候选

- 空态/加载态文案再打磨（区分「云端没有」与「本机全被隐藏」）
- 隐藏项的恢复入口（例如设置页「已隐藏的资料」列表，可一键取消隐藏）
- 下载进度反馈（大文件）/ 下载完成的系统通知
- 文件类型图标细化（PDF / ZIP / TXT 等按扩展名区分）
- 缩略图失败时的重试入口

## 3. 自动化覆盖增强

- 用 **Robolectric**（或 androidTest）覆盖 `AndroidLibraryUploadSource` / `AndroidLibraryFileSink` 的 API 行为（有界读取、`IS_PENDING`、半成品清理）
- 为 Library 的「请求次数」加**测试专用**计数（仅测试态），量化「旧内容不重下」
- UI 层可考虑抽出更小的可测组件，减少对 adb/人工的依赖

## 4. 性能 / 缓存策略候选

- 首轮自动取回正文的**流量控制**（例如仅对图片取缩略图、文件按需取）
- 本机 `library-cache/` 的容量上限与淘汰策略（当前无上限）
- 缩略图在页面间的复用（当前仅页面生命周期内存缓存）

## 5. 协议 / 一致性候选

- `devices.json` 并发写的收敛策略（无锁读改写 → 可加版本号或重试）
- 两端「index 失败是否回滚 binary」语义统一的可行性评估（当前有意不同）
- 悬挂条目（index 有 / binary 404）的**只读**检测（仅提示，不自动删除）

## 6. 后续新功能候选（未评估）

- Library 的搜索 / 过滤
- Library 的分组（按设备 / 按时间）
- 上传进度与断点续传（大文件）
- Windows 端 Cloud Library 与 Android 端的进一步对齐

---

## 明确的非目标（本轮及近期都不要做）

- 不要把 Clipboard 的图片/文件**自动**转入 Library
- 不要为 Library 新增数据库表
- 不要改变 `/library/` schema、24h TTL、prune 规则
- 不要改变 `SyncClipboard.json` 字段语义与 `/file/` 协议
- 不要删除为降低回归风险而保留的旧代码（见 `KNOWN_ISSUES.md` §3）
