# Board Android v0.1.1 — 大型词库文件同步 + 剪贴板超长文本（联调记录）

构建产物：`releases/Board-Android-v0.1.1-dict-sync-debug.apk`
测试设备：MuMu 模拟器（`127.0.0.1:7555`，Android 15，900x1600 @320dpi）
本地 WebDAV：`tools/WebDavLite.java`（`http://127.0.0.1:8080/`，配 `adb reverse tcp:8080 tcp:8080`）

---

## 一、大型导入词库的文件同步（A → WebDAV → B）

云端布局（独立目录，不占用用户词同步文件）：

```
<syncDir>/dictionary/sources/index.json
<syncDir>/dictionary/sources/<sourceId>/manifest.json
<syncDir>/dictionary/sources/<sourceId>/dictionary.dict
```

`sourceId = sha256(词库名).take(16)`，两台设备对同一个词库得到同一个目录名。

### 实测（600,000 条 / 19,444,580 B）

| 阶段 | 结果 |
|---|---|
| 从设备上传 | `PUT dictionary.dict http=201 **193ms**`（19.4MB） |
| 清单写入 | `PUT manifest.json 2ms` + `PUT index.json 2ms` |
| 词库阶段总耗时 | **255ms** |
| 云端文件校验 | sha256 = `74c88041…2f00`，**与源文件逐字节一致** |
| 另一侧下载 | `GET dictionary.dict 200 **20ms**`，sha256 校验通过 |
| 落盘 | 原子替换；`Rime 词典配置已生成`（600,010 行 .dict.yaml） |
| 重复同步 | `SYNC_SKIP_SAME_HASH 云端一致，跳过上传`（不再重传） |

### 安全性验证（重点）

**篡改远端内容（大小不变、100 字节被改坏）后重新同步：**

```
SYNC_CONFLICT 词库 SHA-256 校验失败（期望 74c88041 实际 ef6d0b34），保留原文件
词库阶段完成 上传 0 · 下载 0 · 失败 1 耗时=287ms
```

- 下载内容被丢弃，**没有**生成任何半成品文件
- `dictionary_source` 行数 = 0，本地可用词库未被触碰
- 词库通道显示 `FAILED` 并给出原因；剪贴板通道仍在 63ms 内正常结束
- 全程总耗时 502ms，**没有卡在「同步中」**

### 仍然存在的差距（不隐瞒）

- 600,000 条 > 自动部署上限（120,000），所以**只生成 Rime 词典配置、没有自动 deploy**，
  `ready` 保持 0，界面显示「未接入 Rime」。需要手动点「部署到 Rime」触发。
- 即使 deploy 成功，Rime 也只完成了「词典被加载」；要让候选真正出现，
  还需要 schema 级别的 translator/dependencies 挂接（本轮未做，避免破坏现有可用方案）。

---

## 二、剪贴板超长文本（真机链路实测量）

| 档位 | UTF-8 字节 | 流式 SHA-256 | 抓取入库 | 全文读回 | 搜索 | 系统剪贴板写入 |
|---|---|---|---|---|---|---|
| 10 KB | 16,799 | 0 ms | 8 ms | ✓ 逐字一致 | ✓ | ✓ |
| 100 KB | 167,999 | 2 ms | 6 ms | ✓ 逐字一致 | ✓ | ✓ |
| 500 KB | 839,999 | 5 ms | 17 ms | ✓ 逐字一致 | ✓ | ✓ |
| 1 MB | 1,679,999 | 10 ms | 12 ms | ✗ 拒绝入库 | — | ✗ `TransactionTooLargeException: data parcel size 1680236 bytes` |
| 2 MB | 3,359,999 | 17 ms | 18 ms | ✗ 拒绝入库 | — | ✗ `TransactionTooLargeException: 3360236 bytes` |
| 5 MB | 8,399,999 | 49 ms | 44 ms | ✗ 拒绝入库 | — | ✗ `TransactionTooLargeException: 8400236 bytes` |
| 10 MB | 16,799,999 | 93 ms | 88 ms | ✗ 拒绝入库 | — | ✗ `TransactionTooLargeException: 16800236 bytes` |

- 全部档位**没有卡顿 / 没有崩溃**，哈希与入库都在后台线程（UI 不参与）。
- 摘要固定 161 字符（160 + 省略号），**完整内容按原样保存**，不是截断后的摘要。

### Board 自身的真实限制在哪里

1. **单条内容 ≤ 1.5 MB（UTF-8 字节）** —— 由 Android SQLite 的 `CursorWindow`（约 2MB）决定：
   单行超过它，`SELECT` 会抛 `Row too big to fit into CursorWindow`。
   超过时 Board **明确拒绝入库**（返回 `SKIPPED_TOO_LARGE` + 日志说明），
   既不截断用户内容，也不留下一条读不出来的记录。
2. 这个限制不是凭空设的：实测**一条 10MB 记录会把整个剪贴板列表 / 搜索查询一起搞崩**
   （`Row too big to fit into CursorWindow`），所以必须挡住。

### 真正的端到端上限来自系统，不是 Board

Android 的 `ClipboardManager.setPrimaryClip` 在 **约 1.68 MB（≈100 万字符）** 处就抛
`TransactionTooLargeException`（Binder 事务缓冲 1MB 且进程级共享）。
也就是说日常「复制」根本到不了 Board 的 1.5MB 上限；**1MB 档位是系统先拒绝，不是 Board。**

### 还没测到的部分（明确说明）

- **InputConnection 上屏**：Board 已实现分块提交（`COMMIT_CHUNK_CHARS = 50_000`，
  并捕获 `TransactionTooLargeException`），自测入口也做好了，但需要在「设置 → 键盘 → 试用」
  里保持输入连接后点击按钮，本轮未完成逐档实测。
  代码层面已保证：单次提交远小于 1MB，超长文本按 50,000 字符分块。

---

## 三、本轮真机联调发现并修掉的 4 个 Bug

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 数据库迁移失败、之后每次查询都报错但 App 不崩 | 迁移里建了 `sourceId` 索引，但实体 `@Index` 没声明 → Room 判定 `Migration didn't properly handle` 并回滚 | 实体补 `Index(value = ["sourceId"])` |
| 2 | 迁移失败后数据库「半坏」，恢复机制没生效 | Room 懒加载：`openWithRecovery()` 里 `build()` 不会真正打开数据库，异常在之后的查询才抛 | 打开后主动 `openHelper.writableDatabase` 触发校验，失败才走删除重建 |
| 3 | 剪贴板通道永久显示「同步中」 | 目录准备阶段失败时，`fail()` 原样保留了 `SYNCING` 的通道状态 | 所有终态都走 `settledIfRunning()`；进程被杀后重启也会把 `SYNCING` 归为 `FAILED（上次同步中断）` |
| 4 | 设置页被一条错误信息撑爆 | Room 迁移异常带整张表结构 dump（几千字）被存进 `lastError` 并显示 | 错误信息只保留首行且截断到 160 字符；日志页单条截断 300 字符 |

另外顺手修掉：地址写错（`htpps://`）时 OkHttp 抛 `IllegalArgumentException`，
现在保存配置 / 同步前就给出「地址要以 http:// 或 https:// 开头」。

---

## 四、同步分阶段实测

```
SYNC_DONE 开始同步 触发=MANUAL dir=Board
SYNC_DONE HTTP MKCOL Board http=201 3ms
SYNC_DONE 剪贴板阶段完成 上传=0 下载=0 冲突=0 跳过=1 耗时=46ms
SYNC_DONE 词库阶段完成 上传 1 · 下载 0 上传字节=19444580 耗时=255ms
SYNC_DONE 同步完成 … 导入词库=已同步 耗时[目录=11 剪贴板=68 导入词库=257 词库学习词=140 合计=476]ms
```

- 剪贴板阶段**先结束自己的状态**（UI 上先显示「剪贴板 ✓ 已同步」），
  19.4MB 的词库上传不会让它显示「同步中」。
- 词库下载期间 UI 显示 `同步中 37% · <词库名>`，进度最多每 400ms 刷新一次。
- 「同步内容」里「导入词库（大型文件）」是独立开关，默认开启但不阻塞剪贴板。

---

## 五、文件清单

- `tools/WebDavLite.java` — 本地最小 WebDAV（支持 MKCOL/PUT/GET/HEAD），联调用
  （注意：`.NET HttpListener` 会拒绝 `MKCOL` 返回 400，所以用 Java 写）
- `tools/webdav-lite.ps1` — 同上，PowerShell 版（受限，仅 PUT/GET 可用）
- `releases/Board-Android-v0.1.1-dict-sync-debug.apk` — 本轮构建产物
