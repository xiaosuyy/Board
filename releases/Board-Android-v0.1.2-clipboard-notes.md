# Board Android v0.1.2 — 超长剪贴板架构（内容分流 + 无 content 投影）

构建产物：`releases/Board-Android-v0.1.2-clipboard-large-text.apk`
测试设备：MuMu 模拟器（Android 15，900x1600 @320dpi）
数据库版本：`7 → 8 → 9` 两次真实迁移，真机/模拟器上验证通过

---

## 一、架构变化

### 1. 内容不再全放 SQLite

`clipboard_item` 新增：

| 列 | 说明 |
|---|---|
| `contentStorage` | `INLINE`（存 content 列）/ `FILE`（存文件） |
| `contentFilePath` | 文件型条目的绝对路径 |
| `contentSize` | 完整内容字节数（展示用） |
| `searchText` | **轻量搜索索引**：内容开头 2048 字 |

超长内容落到 `files/clipboard/<hash 前2位>/<sha256>.txt`：

```
files/clipboard/46/46c1a36b…b6.txt   3,359,999 B
files/clipboard/62/62f5fafb…5e.txt   1,679,999 B
files/clipboard/63/63e946d5…72.txt  16,799,999 B
```

写入是**分块**的（64K 字符一次），不会为了写文件再复制一份几十 MB 的 byte[]。

### 2. 列表 / 搜索永不读完整 content

新增 `ClipboardSummary`（id / preview / contentType / contentHash / contentSize /
contentStorage / origin / 时间 / 固定收藏），所有列表与搜索查询都用它。
**这是本轮最关键的一步**：过去一条 10MB 记录会让整个查询抛
`Row too big to fit into CursorWindow`，现在列表查询连 content 都不 SELECT。

只有「全文」和「插到输入框」才走 `ClipboardRepository.loadContent(id)`
（IO 线程读，回主线程渲染）。

### 3. 搜索

`WHERE (content LIKE %q% OR searchText LIKE %q%)`：
小文本命中 `content`，文件型大文本命中 2KB 的 `searchText` 轻量索引。
老数据（文件型、索引为空）在抓取时或 `rebuildSearchIndex()` 时只读**文件开头**补上。

### 4. 阈值选择（有实测依据，不是拍脑袋）

- 历史（全部内联）：500KB 抓取 **17ms**；1MB 起就开始逼近 CursorWindow；2MB 以上直接失败
- 现在（≥512KB 走文件）：500KB 抓取 **8ms**，1MB 13ms，10MB 93ms —— 全部成功
- 也就是说：**500KB 以上走文件既更快也更安全**；低于 256KB 时内联/文件没有可测差异

结论：**512KB**（`ClipboardRepository.DEFAULT_INLINE_MAX_BYTES`）。
它远离 CursorWindow 的 2MB 悬崖，又不至于让 100~300KB 的普通文本都变成文件。
（说明：256KB 与 512KB 只在 256~512KB 这一小段有差异，这一段实测没有可感知差别，
所以取更保守、改动更小的 512KB。设置页开发者模式里两个阈值都能手动跑。）

---

## 二、实测结果（512KB 阈值，真链路）

| 档位 | UTF-8 字节 | 存放 | 流式 SHA-256 | 抓取 | 列表 | 搜索 | 全文 | 删除 | 系统剪贴板 |
|---|---|---|---|---|---|---|---|---|---|
| 100 KB | 167,999 | INLINE | 2 ms | 6 ms | ✓ | ✓ | ✓ | ✓ | ✓ |
| 500 KB | 839,999 | FILE | 5 ms | 8 ms | ✓ | ✓ | ✓ | ✓ | ✓ |
| 1 MB | 1,679,999 | FILE | 9 ms | 13 ms | ✓ | ✓ | ✓ | ✓ | ✗ 系统限制 |
| 2 MB | 3,359,999 | FILE | 19 ms | 20 ms | ✓ | ✓ | ✓ | ✓ | ✗ 系统限制 |
| 5 MB | 8,399,999 | FILE | 42 ms | 52 ms | ✓ | ✓ | ✓ | ✓ | ✗ 系统限制 |
| 10 MB | 16,799,999 | FILE | 91 ms | 93 ms | ✓ | ✓ | ✓ | ✓ | ✗ 系统限制 |

- **Board 侧全链路（抓取 / 入库 / 列表 / 搜索 / 全文 / 删除）在 10MB 全部通过**
- 系统剪贴板失败的原因每次都明确记录下来，例如：

```
写入系统剪贴板被系统拒绝 chars=10000000：
RuntimeException: android.os.TransactionTooLargeException: data parcel size 16800236 bytes
```

  这是 **Android 的 Binder 限制**（`ClipboardManager` 进程间事务缓冲约 1MB），
  **不是 Board 的问题，Board 没有、也不会去改它**。

### 重启 / 删除

- 重启（force-stop + 重新打开）：11 条记录照常列出，**没有** `Row too big`、没有异常
- 删除：软删除后立刻从列表消失；文件保留到 `purgeDeleted()` 才清理（软删除可以复活）
- 孤儿文件会在 `purgeDeleted()` 里按「数据库引用的路径集合」回收

---

## 三、新入口

| 入口 | 说明 |
|---|---|
| **分享给 Board** | 注册 `ACTION_SEND` + `text/plain`（`ClipboardShareActivity`）。**优先用 `content://` URI**（`EXTRA_STREAM` / `data`）从流里读，只有对方只给字符串时才退回 `EXTRA_TEXT` |
| **导入文本文件** | 设置 → 剪贴板 → 导入文本文件（SAF），几十 MB 的纯文本也能进 Board |

实测分享：`分享导入（EXTRA_TEXT）：25 字` → 库里 `19|share-to-board-test-12345|INLINE|25` ✓
（URI 分支已实现，本轮只用 EXTRA_TEXT 路径做了实测。）

---

## 四、超长文本同步

- 小文本（≤ 256KB）：SyncClipboard 的 `text` 字段内联
- 大文本：正文 PUT 到 `/file/{sha256}.txt`，清单里 `hasData=true` + `dataName`
- 上传前按 id 取内容（可能在文件里），**不会**为了发一条记录把整份历史读进内存
- 导出历史（`historyToJson`）只用列表投影，不读任何完整内容

---

## 五、数据库迁移

```
v8：clipboard_item 加 contentStorage / contentFilePath / contentSize
     （contentSize 用 SQL `length(content)` 回填）
v9：加 searchText，并用 `substr(content, 1, 2048)` 回填
```

两次都是 ADD COLUMN，老数据一条不丢；实测 `PRAGMA user_version = 9`，
三个新列与默认值都正确，没有任何 `Migration didn't properly handle`。

---

## 六、结论：Board 自己的限制在哪

| 层 | 真实限制 | 说明 |
|---|---|---|
| Android 系统剪贴板 | **≈1.68 MB**（`TransactionTooLargeException`） | 系统 Binder 限制，**不去改、也改不了** |
| Board 存储 | **没有单行上限了** | 超长内容走文件；10MB 已验证可存 / 可列 / 可搜 / 可看全文 |
| Board 列表 / 搜索 | 与内容大小**无关** | 查询不读 content，只读投影 + 2KB 索引 |
| InputConnection 上屏 | 按 50,000 字符分块提交 | 目标 App 自己可能仍有上限，属目标 App 限制 |

**不宣称能突破 Android ClipboardManager 的 Binder 限制**：普通复制仍然受系统约束，
超过系统范围时 Board 记录真实失败原因，并把内容交给「分享给 Board / 导入文本文件」入口管理。
