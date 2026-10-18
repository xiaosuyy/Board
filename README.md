# Board

简约、现代的跨设备输入法。**当前进度：Android 端可用（英文 + librime 中文拼音 + 数字 + 符号 + 56dp 顶部动态面板 + 悬浮键盘）**。

> 定位：参考 Gboard 的使用逻辑（分层键盘、候选栏、空格键显示语言、双击 Shift 锁定、长按退格连删），
> 但布局、绘制、配色、代码全部自己实现，不使用 Gboard 的任何 UI、代码或品牌元素。
>
> 中文输入 **真正接入 librime**，不是自己写的拼音引擎，也不是占位实现。

> 🎨 视觉规范见 [`DESIGN.md`](DESIGN.md)（Google Stitch / `google-labs-code/design.md`
> 的 DESIGN.md 格式：YAML 令牌 + 8 段说明）。**改 UI 前先读它，改令牌后同步它。**

---

## 1. 顶部动态面板（56dp 固定高度，四种状态互斥）

TopPanel 就是键盘上方**很薄的一条功能区域**：`kb_top_panel_height = 56dp`，
TOOLBAR / CANDIDATES / MORE / CLIPBOARD 四种状态共用同一个高度、同一个位置，
切换时整个输入法窗口**高度完全不变**（实测四种状态下都是 `1006–1117px = 112px = 56dp`，
下方内容恒定从 `1120px` 开始）。

```
没有 composing   -> TOOLBAR
有   composing   -> CANDIDATES
点工具栏入口     -> MORE（在同一个 TopPanel 内替换工具栏）
点剪贴板入口     -> CLIPBOARD（内容区占用键盘位置，TopPanel 不变）
```

### 不显示任何冗余状态

| 信息 | 由谁体现 |
|------|----------|
| 中文 / 英文 | 空格键上的文字 |
| 数字 / 符号 | 当前键盘布局 |
| Shift | Shift 按键的激活底色 |
| 输入进度 | 面板形态本身 |

### TOOLBAR

```
[更多]                        [收起键盘]  [剪贴板]
```

- 图标 20dp（功能行高 44dp 的 45%），严格垂直居中，不顶上下边缘
- 全部使用**同一套 VectorDrawable**（`res/drawable/ic_board_*.xml`，24×24 viewport、
  统一线宽 1.7–2.0、统一圆头圆角），运行时 `setTint` 着色 → 线宽 / 圆角 / 视觉重量一致
- 最右是「收起键盘」（向下箭头 + 横线，无文字），点它调用 `requestHideSelf(0)`
  收起输入法 —— 不模拟返回键、不 finish Activity，输入内容与连接都保留

### MORE

```
[⌃]  主题   翻译   语音   设置   常用语   左移   右移   剪贴板  →
```

- **就是工具栏本身**：在同一块 56dp 区域里替换掉 TOOLBAR，不新开页面、不扩大窗口、不改变键盘高度
- 一行紧凑入口（图标 18dp + 文字 10sp，整块 64dp×56dp 垂直居中），横向可滑动
- 左侧固定的收起按钮与剪贴板标题栏的返回位置一致
- 未实现的功能（翻译 / 语音 / 常用语）弱化显示，点击明确提示尚未开发

### CANDIDATES（中英文同构，只有一行）

```
中文：  [nihao] │ 你好 │ 你还 │ 你 │ 尼 │ 泥 │ 逆 …
英文：  [hel]   │ help │ hello …
```

- **没有单独的 composing 行**：正在输入的拼音 / 单词就是这一行的第一项（小 pill，内容自适应宽度）
- 英文模式下第一个候选本身就是输入的单词，因此不会重复出现 pill —— 中英文共用同一套布局
- 候选 / pill 固定 32dp 紧凑高度，无论面板多高，选中背景都只是一小块圆角（不会形成大面积色块）
- 第一个候选为「当前选中」，用少量强调色；整行横向可滑动，滑动不影响键盘布局
- 没有候选且没有引擎状态时不显示任何文案

### CLIPBOARD

```
←   剪贴板                  ☁   🔍   ✎        ← 占用同一个 56dp TopPanel
─────────────────────────────────────────
固定                                        ← 分区：固定 / 收藏 / 最近
┌─────────────┐ ┌─────────────┐
│ 内容…    📌  │ │ 内容…    ★  │            ← 内容区与键盘等高、可滚动
│ 本机         │ │ 云端 · Windows│           ← 很轻的来源小字
└─────────────┘ └─────────────┘
```

- 标题 / 返回 / 云端筛选 / 搜索 / 编辑都在原来的 TopPanel 内，**没有更高的标题栏**
- 编辑是铅笔矢量图标（编辑中变对勾），搜索是放大镜，尺寸与工具栏一致
- 内容区与键盘同背景、同圆角、同字体、同间距，跟随深色模式；不使用巨大卡片
- 点条目 → 直接插进输入框；长按 → 底部动作条（收藏 / 固定 / 全文 / 删除）
- 🔍 搜索：同一个 TopPanel 原地变搜索框，详细规则见「剪贴板」一节
- ☁ 云端：只看「其他设备同步过来」的内容（来源字段见「剪贴板」一节），
  再次点击或按返回都恢复全部；激活时云朵变强调色

## 2. 候选词字号（设置 → 外观 → 候选词字号）

`小 16sp / 标准 19sp / 大 22sp / 特大 25sp`，默认 **标准**。

- 修改后**立即生效**，不需要重启输入法（实测：IME 进程 pid 不变，候选立即变大）
- 同时按比例调整拼音字号（11/13/14/15sp）与候选左右内边距（12/14/16/18dp）
- **字号变大不会撑高 TopPanel**（实测「特大」下面板仍是 112px）：
  固定 32dp 的紧凑 chip + 文本垂直居中 + 无额外竖向 padding 来适配

## 3. 用户学习与词库

### 用户学习（librime userdb，纯本地）

学习完全交给 librime 自己的用户词典机制，**没有**在 UI 层按点击次数排序：

- 选词 → librime 的 `Memory` 订阅 context 的 commit 通知 → 写 `<user_data_dir>/luna_pinyin.userdb/`（leveldb）
- 再次输入同一编码时，librime 按用户词频重新排序（实测反复选第 3 个候选后，它会前移到第 2 位）
- 数据只在应用私有目录，不联网、不上传；重启输入法 / 重装 APK 后仍在
- 两种方案（`luna_pinyin`、`luna_pinyin_simp`）共用同一份学习记录，
  因为 schema 的 `translator/dictionary` 都是 `luna_pinyin` → userdb 名也是 `luna_pinyin`

读写用户词典走 librime 的 levers 模块（`RimeLeversApi.export_user_dict / import_user_dict`）。
leveldb 只允许一个句柄，所以导出 / 导入 / 清空都先销毁会话，做完再重建：

| 操作 | 实现 |
|------|------|
| 统计 / 展示学习词 | `export_user_dict` → 解析 `词条 \t 编码 \t 次数` → 镜像成 `LEARNED` 词条 |
| 导出学习词 | 同一次导出结果，可写成 TXT / CSV / JSON / Rime 词库 |
| 导入学习词 | 写回 `import_user_dict`，所以「清空 → 再导入」能真正恢复候选排序 |
| 清空学习记录 | 关会话 → 删除 `luna_pinyin.userdb/` → 重开会话（二次确认） |

> 导出文件的列顺序是 `词条 \t 编码 \t 次数`：librime 的 `TableDb` 写出时会
> `row[0].swap(row[1])`，导入解析必须按同一顺序，否则词条与编码会弄反。

### 我的词库（`dictionary_entry` 表）

一份表承载四类词（`WordType`），支持新增 / 编辑 / 删除 / 按类型筛选：

| 类型 | 说明 | 对输入的影响 |
|------|------|--------------|
| 学习词 LEARNED | librime userdb 的只读镜像，进页面时自动同步 | 由 librime 决定 |
| 自定义词 CUSTOM | 用户添加，同步进 Rime 的 `custom_phrase` | 是（优先级高于普通词库） |
| 固定词 PINNED | 同自定义词，另外不会被批量清理语义带走 | 是 |
| 屏蔽词 BLOCKED | librime 没有屏蔽接口，由 Board 在候选层过滤 | 从候选中屏蔽（不改 librime 数据） |

自定义短语写 `<user_data_dir>/custom_phrase.txt`（Rime 标准机制）。
librime 的这条词典是**按输入串前缀匹配**的（键为 `编码 \t 词条`），
所以写入前要删掉编码里的空格：编辑框里填 `bo de`，文件里落成 `bode`，输入 `bode` 才会命中。

屏蔽词过滤时**必须同时记录原始下标**（`RimeEngine.rimeIndexCache`），
否则被过滤掉一个词之后，点候选会选到相邻的错词。

### 导入 / 导出

- 格式：TXT / CSV / JSON / Rime 词库（见 `DictionaryFormat`），导入前先校验格式
- 导入：按词条去重、**不覆盖已有词条**，完成后报告「成功 / 跳过重复 / 无法解析」三个数量
- 导出范围：全部词条 / 用户词（自定义 + 固定 + 屏蔽）/ 学习词
- 文件读写走系统文件选择器（`OpenDocument` / `CreateDocument`），不申请存储权限

### 设置 → 词库

显示 `词条 N · 学习词 N · 自定义词 N`，提供 **管理我的词库 / 导入 / 导出 / 清空学习记录**。
数据库版本 v1 → v2（`dictionary_entry` 增加 `type` 列 + 索引），用显式 `Migration` 升级，不清库。

## 4. 剪贴板

面板结构与 TopPanel 严格分离：**TopPanel 永远是 56dp**，剪贴板只是把它下面那块区域换成自己的内容。

### 状态与高度

| 状态 | TopPanel | 下面那块区域 |
|------|----------|--------------|
| 普通剪贴板 | 56dp | 内容区（高度 = 键盘高度，键盘隐藏）→ 总高度与键盘状态完全一致 |
| 剪贴板搜索 | 56dp | 结果区（高度上限 146dp）+ 键盘（可以打字） |

实测（深色，900×1600 @ density 2.0）：面板 `1006–1117 = 112px = **56dp**`，分隔线 1dp，
内容区从 1120 开始 —— 与 TOOLBAR / CANDIDATES / MORE 完全一致。
搜索状态下面板同样是 `714–825 = 112px`，只是下面多了一块有上限的结果区
（这块是搜索专用的临时高度，退出搜索立刻恢复；TopPanel 本身从头到尾没变）。

### 数据（Room，纯本地）

`clipboard_item`：

| 字段 | 说明 |
|------|------|
| `content` / `preview` | 完整内容 / 列表用摘要（压掉换行与连续空白 + 截断 160 字符） |
| `contentType` | `TEXT` / `LINK` / `IMAGE` / `FILE` / `UNKNOWN`（后三种本轮只占位） |
| `contentHash` | SHA-256，用于去重，也能直接当 SyncClipboard 的 `hash` |
| `isPinned` / `isFavorite` | 固定 / 收藏；两者都不会被普通清空带走 |
| `isDeleted` / `deletedAt` | 软删除 + 时间戳，方便以后做同步冲突合并 |
| `sourceDeviceId` | null 表示本机产生 |
| `createdAt` / `updatedAt` | 首次记录时间 / **最近一次复制或使用**的时间 |

**去重**：同一段文本反复复制不会产生新记录，只刷新「最近使用时间」；
之前删掉过的内容再复制一次会复活。抓取用 `Mutex` 串行化 —— 系统会在很短时间内
回调多次，不加锁会出现「两次都查无此 hash，于是插了两条」（实测踩到过）。

数据库 v2 → v3 用**重建表**的方式迁移（`pinned` → `isPinned`，新增 `isFavorite` /
`contentHash` / `isDeleted` + 索引）：minSdk 26 的 SQLite 不支持 `RENAME COLUMN`。
旧数据全部保留，启动时会补算 `contentHash` 并把历史里的重复内容合并成一条
（固定 / 收藏标记会带到保留的那条上）。

### 来源：本机 / 云端

`clipboard_item` 里区分来源的字段：

| 字段 | 说明 |
|------|------|
| `origin` | `LOCAL` / `REMOTE`。判断依据是**内容由哪台设备产生**，不是「有没有同步成功」 |
| `originDeviceId` / `originType` | 远端设备标识 / 名称。SyncClipboard 协议本身没有设备字段，读到可选的 `device` 扩展就显示成「云端 · Windows」，否则只显示「云端」 |
| `receivedAt` | 远端内容落到本机的时间 |

- **本机复制、随后上传到云端的内容仍然算 `LOCAL`**（不是按同步状态判断）
- 只有从其他设备同步下来的才是 `REMOTE`，卡片上只用一行很轻的小字标注（`云端 · Windows` / `本机`）
- 顶部 ☁ 按钮 = 只看云端（`CLIPBOARD_REMOTE`），再点一次或按返回恢复全部（`CLIPBOARD_ALL`）
- 数据库 v3 → v4 用 `ADD COLUMN` 迁移（`origin` 默认 `LOCAL`），旧数据全部算本机，内容不丢

### 交互

| 操作 | 行为 |
|------|------|
| 点击卡片 | 通过 `InputConnection` 直接插入当前输入框，并算一次「最近使用」 |
| 长按卡片 | 底部出现动作条：收藏 / 固定 / 全文 / 删除 / 取消（不弹 Dialog、不新开 Activity） |
| 全文 | 完整内容（可选中），长文本只在内容区里滚动，不撑爆列表 |
| ✎ 编辑 | 每张卡片出现 ✕ 删除，列表底部出现「清空全部历史」 |
| 🔍 搜索 | TopPanel 原地变成搜索框；键盘照常可用，**按键进搜索框，不污染输入框** |
| 清空全部历史 | 二次确认；默认**保留**固定和收藏，要一起删必须显式勾选（文案里写明条数） |
| ← / 系统返回键 | 先退动作条 / 全文 / 清空确认 → 再退搜索 → 最后才关剪贴板 |

分区顺序：固定 → 收藏 → 最近（同组内按最近使用时间倒序）。

### 敏感内容

`ClipboardSensitivityDetector` 接口 + 默认启发式实现（不联网、不上模型）：
纯数字 4–8 位，或含「验证码 / 密码 / otp / password」等关键词且带数字 → `SENSITIVE`。
策略映射：`SENSITIVE → DO_NOT_SAVE`（**不入库**）、`NORMAL → ALLOW_SYNC`、
`UNKNOWN → LOCAL_ONLY`。命中 `DO_NOT_SAVE` 时会提示
「疑似验证码 / 密码，按策略不写入剪贴板历史」。

### 与 SyncClipboard 的兼容层

`SyncClipboardMapper` 只做字段映射，**不发任何网络请求**：

```json
{ "type": "Text", "hash": "…", "text": "…", "hasData": false, "dataName": "", "size": 10 }
```

Board 自己的 `isPinned` / `isFavorite` / 敏感策略不外泄到协议里；
协议里缺失或多出来的字段在 `fromPayload` 补默认值。接真正的同步之前，
Board 的表结构不会被第三方协议绑死。

## 5. 手机端体验：性能 / 动画 / 设计令牌

### 性能（本轮重点，都是实测过的）

| 位置 | 改动 | 效果 |
|------|------|------|
| `CandidateBar` | 子 View 只在 `init` 建一次，之后每次候选更新**只改文本 / 颜色 / 可见性** | 连续快速输入不再 per-keystroke `removeAllViews` + 重建 ~19 个 View |
| `ClipboardPanel` | 结构 + 内容双指纹：数据没变直接返回；只有内容变了在原 View 上更新 | Room 每条事件不再重建整个网格 |
| `KeyboardView.onDraw` | 3 个 `Path` / 1 个 `RectF` 改成复用字段；`resolvedMetrics()` 结果缓存 | 绘制阶段零分配，少一次 GC 抖动源 |
| `PanelIconPainter` | 按「图标 + 颜色」缓存 Drawable | 不再每帧 `setTint` 触发 drawable 失效 |
| `TopPanelController.applyState` | 只在状态 / 布局模式真的变了时才动 View | 每次按键不再白跑一遍 `setVisibility` |
| 滚动 / 搜索 / 同步 | 全部在 `Dispatchers.IO`：Room 查询、WebDAV、Rime 部署都不在 UI 线程 | UI 线程只做绘制与状态分发 |

实测（`adb shell dumpsys gfxinfo com.board.ime`）：

| 场景 | 帧数 | Janky | 50th / 90th / 99th |
|------|------|-------|--------------------|
| 快速连打英文 60 次（2.2s） | 34 | **0 (0.00%)** | 5ms / 13ms / 21ms |
| 快速连打拼音 60 字母 + 10 空格 | 50 | **0 (0.00%)** | 5ms / 24ms / 48ms |
| 工具栏 ↔ 剪贴板 ↔ 更多 连续切换 ×12 | 112 | **0 (0.00%)** | 5ms / 7ms / 12ms |
| 剪贴板列表快速上下滚动 ×30 | 22 | **0 (0.00%)** | 5ms / 5ms / 6ms |

四组场景都没有 FATAL / ANR。**没有测到的就不写**：真机（非模拟器）、长时间压力、
低端机帧率都不在本轮验证范围内。

### 过渡动画（克制版）

- 四种状态（TOOLBAR / CANDIDATES / CLIPBOARD / MORE）互相切换时，
  只给 **TopPanel 里刚进来的那块内容**做动画：`alpha 0→1` + **4dp** 轻微上移，**150ms**
- **键盘主体完全不参与动画**，所以不会上下跳动；面板高度始终 56dp
- 状态没变（例如候选内容更新）不会触发动画
- 尊重系统「移除动画」：`ValueAnimator.areAnimatorsEnabled()` 为 false 时直接跳过

### 设计令牌（`dimens.xml` 单一来源）

只有 **三档圆角**：

| 令牌 | 值 | 用在哪 |
|------|----|--------|
| `kb_radius_small` | 6dp | 候选 chip、拼音 pill、分隔 pill、按下反馈小块、动作按钮 |
| `kb_radius_medium` | 10dp | 键帽、剪贴板卡片、搜索框 |
| `kb_radius_large` | 14dp | 悬浮键盘卡片、设置页卡片 |

配套统一：间距 `kb_space_xs/s/m/l/xl`（2/4/8/12/16）、
图标 `kb_icon_small/medium/large`（14/18/20）、
文字 `kb_text_xs/s/m/l`（10/12/13/15sp）。
颜色仍是黑 / 白 / 灰为主，蓝色只用于强调（选中候选、云端标记、激活的图标）。

## 6. 同步（SyncClipboard 协议 + 用户自己的 WebDAV）

### 数据流

```
剪贴板 UI ──► ClipboardRepository（Room）
                    │ 本机复制 → 写 sync_operation 队列
                    ▼
              SyncEngine ──► SyncClipboardMapper ──► WebDavClient（OkHttp）
                                                     GET / PUT /SyncClipboard.json
                                                     MKCOL 同步目录
                                                     GET / PUT /file/{dataName}
```

UI **从不**直接调用 WebDAV；协议字段转换只在 `SyncClipboardMapper` 里做，
Board 自己的表结构没有被第三方协议绑死。

### 协议兼容程度（以 SyncClipboard 当前文档为准）

| 方法 / 路径 | 状态 |
|---|---|
| `GET  /SyncClipboard.json` | ✅ 已实测（下载 + 合并进本地） |
| `PUT  /SyncClipboard.json` | ✅ 已实测（上传当前剪贴板） |
| `MKCOL`（逐级建同步目录） | ✅ 已实测（405/409 视为已存在） |
| `GET  /file/{dataName}` | ⚠️ 客户端已实现，本轮不调用（只有 Image / File 才需要） |
| `PUT  /file/{dataName}` | ⚠️ 同上 |

字段 `type` / `hash` / `text` / `hasData` / `dataName` / `size` ✅。
`type = Text` 才处理；`Image` / `File` / `Group` 一律 `SYNC_IGNORED_TYPE`
**安全忽略并写日志**，不伪装成已同步。远端 `hash` 与内容算出来的 hash 不一致 → 拒绝写入。

### 同步语义与冲突

SyncClipboard 的模型是「服务器上只有一份当前剪贴板」，所以推送 = 把本地最新内容 PUT 上去，
拉取 = GET 下来按 hash 比对：

| 情况 | 行为 |
|---|---|
| hash 相同 | `SYNC_SKIP_SAME_HASH`，什么都不做（不会产生重复） |
| 本地没有 | 作为**新记录**写入本地，并写到系统剪贴板 |
| 远端没有（404） | 只上传，不算错误 |
| 双方不同 | 用 WebDAV 的 `Last-Modified` 与本地 `updatedAt` 比时间：远端更新 → 下载成新记录；本地更新 → 上传本地。两种都写 `SYNC_CONFLICT` |
| 远端 hash 与内容不符 | 拒绝写入（防静默覆盖） |

**任何情况下都不删除、不覆盖本地已有记录**；同步失败的任务保留在 `sync_operation` 里等下次重试。

### 回环防护（WebDAV → 本地剪贴板 → 回调 → 上传）

远端内容写进系统剪贴板后，系统会回调 `OnPrimaryClipChangedListener`，可能再次上传导致回环。
两层防护：
1. 写入前登记**抑制窗口**（15s，按内容 hash）
2. 记住**最近一条远端 hash**：只要内容还是它就不产生上传任务（不随时间失效）

实测日志：`SYNC_SUPPRESS_LOOP hash=… 命中抑制窗口`，之后服务器没有再收到该内容的 PUT。

### 可靠性与错误分类

| 分类 | 行为 |
|---|---|
| 网络不可用 / 超时 / 5xx / 409 | 可重试：任务标 FAILED + attemptCount，本地数据保留，WorkManager 退避重试 |
| 401 / 403 | `SYNC_AUTH_ERROR`（重试无意义，明确提示认证失败） |
| 404 | 远端还没有清单，视为正常 |
| JSON 解析失败 / 未知字段 | 安全忽略并写日志 |
| hash 不一致 | 拒绝静默覆盖 |

### 自动同步（明确不是实时推送）

- 设置页打开时：一次去抖同步
- 输入法服务启动时：一次去抖同步
- 复制新内容后：去抖上传
- WorkManager 周期任务（30 分钟、要求网络）：兜底重试

### 设置页

设置 → **同步（WebDAV / SyncClipboard）**：地址 / 用户名 / 密码 / 同步目录 +
测试连接 / 保存配置 / 立即同步 / 自动同步开关，并显示
**未配置 / 已连接 / 同步中 / 同步成功 / 同步失败**、最近同步时间与最近同步日志。

键盘顶部区域**没有任何同步状态文字**，保持原来的 56dp 简洁设计。

密码存在 `EncryptedSharedPreferences`（Android Keystore + AES256）；Keystore 不可用时
**不会默默降级成明文**，而是在设置页明确提示「密码只保留在本次会话内存中」。

## 7. 设置页结构（首页只放入口）

```
Board 设置
状态        ● Board 输入法正常运行  ● Rime luna_pinyin_simp
            ● 同步 已连接 / 未配置   ● 数据库 正常 · 词条 N · 剪贴板 N
输入      > 中文输入、输入引擎
外观      > 主题、候选词字号
键盘      > 布局、按键音效与震动
剪贴板    > 历史、敏感内容、清理
词库      > 我的词库、学习词、导入导出
同步      > WebDAV、同步内容
隐私      > 敏感剪贴板、同步数据范围
关于      > 输入法状态、版本
```

- 首页**不放**日志、HTTP 状态码、错误堆栈、Debug 信息、账号 / 服务器地址
- 详细设置全部进二级页面（`SettingsSectionActivity`，一个 Activity 按分类生成内容）
- **日志默认隐藏**：连续点「版本号」5 次才开启开发者模式，开启后首页与「关于」里才出现「日志详情」
- **同步隐私**：配置阶段才显示地址 / 用户名 / 密码 / 目录；配置成功后首页只显示「已连接」，
  账号信息只在「设置 → 同步」里可见，密码永远打码
- 敏感内容策略可在「隐私 / 剪贴板」里关掉（关掉后敏感内容仍保存，但只留在本机、不参与同步）

## 8. 词库 / 学习词 / 常用语的同步模型

### 三种实体，一种记录

`SyncWordRecord`（`sync/SyncWordRecords.kt`）同时承载：

| 实体 | `type` | 关键字段 |
|------|--------|----------|
| 学习词 | `LEARNED` | `word` / `code`(拼音) / `frequency` / `createdAt` / `updatedAt` / `sourceDeviceId` |
| 常用语 | `PHRASE` | `word` / `code` / `enabled` / `deleted` |
| 自定义 / 固定 / 屏蔽词 | `CUSTOM` / `PINNED` / `BLOCKED` | 同上 |

**每种实体一个文件**（不塞进一个巨大 JSON）：
`board_dictionary.json` / `board_learned.json` / `board_phrases.json`；
剪贴板继续用官方 `SyncClipboard.json`。

### 合并，不互相覆盖

`SyncWordMerge`（键 = `词条 + 编码`，所以**同词不同拼音算两条**）：

| 情况 | 结果 |
|------|------|
| 只有一边有 | 采用它（包括墓碑） |
| 都是活的 | `frequency` 取**最大值**，其余字段听 `updatedAt` 较新的一侧 |
| 一侧是墓碑且不比另一侧旧 | **删除生效**，另一端不会把它恢复 |
| 学习词 | 只要有一端还活着就保留（学习是积累出来的证据），词频取最大 |

写回服务器用的是「拉取 → 合并 → 写回并集」，所以两台设备不会互相清空对方的数据。

### 学习词真的会进候选

`LearnedWordBridge` 把合并后的学习词写成 librime 用户词典文本再 `import_user_dict`，
而 librime 的 `UserDbImporter::Put` 用的是 `commits = max(旧, 新)` ——
天然是合并语义、重复导入幂等。**实测**：另一台设备（模拟）给 `拟好/nihao` 记 500 次，
同步后本机输入 `nihao`，`拟好` 从不在第一页变成第 2 个候选；`你好` 的词频取 max(6, 3) = 6。

自定义词 / 固定词 / 常用语写进 Rime 的 `custom_phrase`，同样实测：同步过来的 `博德/bo de`
输入 `bode` 直接排在第一位。

### 删除（tombstone）

`dictionary_entry` 增加 `isDeleted` / `deletedAt`；删除是软删除，墓碑会参与同步。
实测：把某词标成 `deleted=true` 同步后本机删掉；另一台设备再用更旧的时间戳把它传回来，
本机**没有恢复**（`写回=0`，`custom_phrase.txt` 里也没有它）。

### 同步内容开关

「设置 → 同步 → 同步内容」逐项开关：剪贴板 / 用户词库 / 学习词 / 常用语 / 自定义词 / 主题 / 键盘设置。
只有勾选的内容会进入同步范围；账号密码、日志、调试信息、无关设备信息永远不同步。

## 9. 键盘（对齐微信输入法布局）

分层键盘，每行 10 个宽度单位、自适应屏宽。**五套布局全部是 4 行**，
因此切换任意面板（字母 / 数字 / 数字九宫格 / 符号 / 更多符号）键盘高度都不变。
三套主层按微信输入法重做：

```
字母层（4 行）
Q W E R T Y U I O P
 A S D F G H J K L
⇧  Z X C V B N M  ⌫
123   ，。   空格   中/英   ↵

数字层 123（4 行；微信顶部那行 1234567890 按需求去掉）
#  %  &  +  ……  《  》  ｜  ✕
-  /  :  ~  (  )  …  @  “  ”
符号  。  ,  、  ?  !  .  ⌫
←   12/34   空格   换行   ↵

数字层 34（九宫格数字小键盘，点 12/34 切换；4 行）
@  1  2  3  ⌫
%  4  5  6  空格
-  7  8  9  +
←  符号  0  .  ↵

符号层（4 行；微信顶部那行 # % & + …… 《 》 ｜ ✕ 按需求去掉）
_  —  ;  ｜  %  +  -  ×  ÷  =
【 】 { } 《 》 # $ & ·
123  「 」 < > ^ *  ⌫
←   中/英   符号   空格   ↵

更多符号（符号层点「符号」翻到第二页；4 行）
%  &  <  >  °  ±  ×  ÷  §  •
€  £  ¥  ¢  ©  ®  ™  ¶  ¿  ¡
符号  ^  `  |  \  /  ~  "  ⌫
←   中/英   符号   空格   ↵
```

- 顶栏仍是 Board 的动态面板（候选 / 工具栏），高度 56dp 不变；
- `中/英` = 语言切换（长按打开系统输入法选择器），`，。` = 标点（长按句号）；
- 数字层第二页（底部 `12/34` → 九宫格数字小键盘）已实现；微信该页右列的表情键按需求去掉；
- 符号层两页的**翻页键都显示「符号」**（微信是「更多 / 1/2」）；
- 顶部符号条里收起的 `✕` 仍按占位（点击提示「尚未开发」）；
- `空格` = 输入空格；**在空格键上左右滑动可移动光标**（对齐微信输入法，见下）。

键高 50dp；键盘总高 = 顶部留白 + 行数 × 键高 + 行距 + 底部留白（见第 10 节）。

### 空格键左右滑动移动光标（对齐微信输入法）

微信输入法支持在空格键上左右滑动移动光标，Board 已对齐（**不含**微信「长按空格 = 语音」的行为）。

| 项 | 值 | 依据 |
|---|---|---|
| 死区 | **50dp** | 微信实测：150px（50dp）以内不移动，按点按处理 |
| 步长 | **33dp / 字符** | 微信实测：约 100px / 字符（density 3） |
| 方向 | 左滑左移、右滑右移（完全对称） | 微信实测 |
| 反馈 | 无气泡、无额外音效 / 震动 | 微信实测 |

- 采用**绝对映射**：光标位置只由「手指相对按下点的位移」决定，往回拖会按同样步长把光标移回去；
- 越过死区后**锁定空格键**：手指滑出空格键范围也不会换键、不再触发长按，抬手也不会补打一个空格；
- 换算逻辑在 `SpaceCursorDrag`（纯 Kotlin，单测 `SpaceCursorDragTest`），触摸接线在 `KeyboardView`，
  `InputController.moveCursor(delta, steps)` 负责发 `KEYCODE_DPAD_LEFT/RIGHT`。

实测（LDPlayer 1080×1920 @480dpi，24 个等宽数字，滑动 150ms）：

| 手势 | 结果 |
|---|---|
| 点按空格（1px 位移） | 输入 **1 个空格**，光标不动 |
| 左滑 250px | 光标左移 **1** 字符 |
| 左滑 350px | 光标左移 **2** 字符 |
| 左滑 450px | 光标左移 **3** 字符 |
| 右滑 350px | 光标右移 **2** 字符 |

「键盘抬升」开（底行 y=1712..1828）与关（底行 y=1746..1895）两种高度都复测过，结果一致
（手势全程按手指位移计算，与键盘高度无关）。

微信侧原始实测（用于定参数）见 `reverse engineer/blueboard-test/WECHAT_SPACE_CURSOR.md`。

## 10. 键盘尺寸与悬浮布局

尺寸由 `KeyboardLayoutConfig`（设计意图）+ `KeyboardLayoutCalculator`（换算像素）决定：

| | 铺满（默认竖屏） | 悬浮（横屏 / 大屏 / 手动） |
|---|---|---|
| 宽度 | 屏幕宽度 | 屏宽 × 0.62（340–720dp，≤ 屏宽 92%） |
| 单键高度 | 50dp | 46dp |
| 边距 | 0 | 四周 12dp，靠右对齐 |
| 高度上限 | 屏高 62%（超出压缩键高，最低 34dp） | 同左 |
| 外观 | 无额外背景 | 圆角卡片 + 窗口透明，卡片外露出应用 |

竖屏铺满时的留白（`dimens.xml` 单一来源）：

| 令牌 | 值 | 说明 |
|---|---|---|
| `kb_keyboard_padding_vertical` | 8dp | **顶部**留白（键盘顶边 → 第一行） |
| `kb_keyboard_padding_bottom` | 30.67dp | **底部**留白（最后一行 → 屏幕底）；1080×1920 @480dpi（density 3）= **92px**，与微信输入法实测值一致；由设置里的「键盘抬升」开关控制（关闭时回退为 `kb_keyboard_padding_vertical` = 24px） |
| `kb_key_gap_vertical` | 8dp | 行间距 |

键盘总高 = 顶部留白 + 行数 × 键高 + 行距 + 底部留白。
底部留白单独取值，所以「最后一行 → 屏幕底」的距离只由它决定，改它不影响顶部与键高。

### 键盘抬升（设置里可切换）

设置 → **键盘 → 键盘位置 → 键盘抬升**：

- **开启（默认）**：底部留白 = `kb_keyboard_padding_bottom` = **92px**（与微信输入法 1:1）；
- **关闭**：底部留白 = 顶部留白 = **24px**（键盘不抬升）。

只改**底部**留白，键高 / 顶部留白 / 行距都不动；切换后**立即生效**（输入法服务监听同一个
SharedPreferences，重算尺寸并重绘，不重启输入法）。

实测（LDPlayer 1080×1920 @480dpi，字母层）：

| 键盘抬升 | 最后一行底边 | 底部留白 |
|---|---|---|
| 开 | y = 1828 | **92px** |
| 关 | y = 1896 | **24px** |

设置页可显式选择 `键盘布局：自动 / 铺满 / 悬浮`。

## 11. 视觉

- 只有 **白 / 黑 / 灰**；蓝色只用于 **当前候选 / Shift 激活 / 当前选中**
- 图标全部是矢量资源，统一线宽与圆角，无「粗黑块」感
- 无渐变、无发光、无大面积背景块、无厚重阴影、无多余状态徽章
- 四种状态共用同一个面板背景、同一套圆角 / 字体 / 内边距 / 图标风格
- 颜色集中在 `colors.xml`，尺寸集中在 `dimens.xml` + `KeyboardLayoutConfig`
- 浅色 / 深色 / 跟随系统

## 12. 架构

```
KeyboardView ──► InputController ──► InputEngine ──► RimeEngine ──► librime
  (只负责按键)      (输入逻辑/状态)      (可替换接口)      (JNI 封装)

TopPanelController ──► ToolbarView / CandidateBar / MorePanel / ClipboardHeader + ClipboardPanel
                   (只决定 TopPanel 显示哪一种状态)
```

- `KeyboardView` 里没有任何面板相关 if/else；`TopPanelController` 只做状态推导与事件分发
- 图标绘制统一走 `PanelIconPainter`（VectorDrawable + 运行时着色），单个图标按钮走 `PanelIconSlot`

## 13. 目录结构

```
blueboard/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/libs.versions.toml
├── tools/fetch_rime.ps1           下载 librime 预编译库 + 标准 Rime 数据
├── THIRD_PARTY.md                 第三方组件与许可证
├── third_party/rime/<abi>/        预编译静态库（gitignore，脚本下载）
└── android/app/src/main/
    ├── cpp/                       CMakeLists.txt + board_rime.cpp（JNI）
    ├── assets/rime/               标准 Rime 数据（配置 / 词库 / OpenCC 数据）
    ├── res/drawable/ic_board_*.xml 15 个统一风格的矢量图标
    ├── res/                       颜色 / 尺寸 / 主题 / 布局
    └── java/com/board/ime/
        ├── ime/          BoardInputMethodService、InputController
        ├── engine/       InputEngine、BasicEngine、RimeEngine、InputMode…
        │   └── rime/     RimeNative(JNI)、RimeRuntime、RimeDataInstaller、RimeKey
        ├── keyboard/     KeyboardLayouts、KeyboardLayoutConfig、KeyboardLayoutCalculator…
        ├── ui/           KeyboardView、KeyboardTheme
        │   └── panel/    TopPanelState、TopPanelController、ToolbarView、CandidateBar、
        │                 MorePanel、ClipboardHeader、ClipboardPanel、
        │                 PanelIconPainter、PanelIconSlot
        ├── clipboard/ dictionary/ database/ sync/
        │   ├── clipboard/ ClipboardRepository、ClipboardContentType、
        │   │             ClipboardSensitivityDetector、SyncClipboardMapper、ClipboardHasher
        │   ├── dictionary/ WordType、DictionaryCodec（4 种格式）、DictionaryRepository
        │   └── sync/     SyncEngine、SyncLog、SyncStatus、SyncWorker/SyncScheduler、
        │       └── webdav/ WebDavClient（OkHttp 实现）、WebDavConfig、WebDavConfigStore
        └── settings/     BoardSettingsActivity、DictionaryActivity、BoardPreferences、
                          ThemeMode、EngineKind、LayoutMode、CandidateTextSize
```

## 14. Rime 数据

```
android/app/src/main/assets/rime/
├── default.yaml / default.custom.yaml / key_bindings.yaml / punctuation.yaml / symbols.yaml
├── luna_pinyin.schema.yaml / luna_pinyin_simp.schema.yaml / luna_pinyin.dict.yaml / pinyin.yaml
├── luna_pinyin_simp.custom.yaml                # page_size=50 + 模糊音/纠错 + 前缀补全（对齐微信本地行为）
├── stroke.schema.yaml / stroke.dict.yaml       # luna_pinyin 声明的 dependencies
├── essay.txt / LICENSE (LGPL-3.0) / opencc/
```

运行时释放到 `filesDir/rime/shared/`，部署产物在 `filesDir/rime/user/build/`。
`squirrel.yaml` 是 macOS 前端配置，Android 上不读取，因此未打包。

用户数据（`filesDir/rime/user/`，**只增不删，重新释放 assets 时保留**）：

```
filesDir/rime/user/
├── luna_pinyin.userdb/     学习记录（leveldb，选词与词频）
├── custom_phrase.txt       自定义词 / 固定词（Rime 标准机制，`词条<TAB>编码`）
├── user.yaml / installation.yaml
└── build/                  部署产物
```

assets 更新时会重新释放 `shared/`，但**不动** `user/`，所以学习记录不会因为升级而丢失。

## 15. 环境与构建

| 项 | 值 |
|----|----|
| Gradle / AGP | 9.4.1 / 9.2.1 |
| Kotlin / KSP | 2.4.10 / 2.3.10 |
| JDK | 17 |
| NDK / CMake | 27.0.12077973 / 3.22.1 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| ABI | arm64-v8a、armeabi-v7a、x86_64 |

```bash
powershell -ExecutionPolicy Bypass -File tools/fetch_rime.ps1   # 首次需要
./gradlew clean
./gradlew :android:app:assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.board.ime/.settings.BoardSettingsActivity
```

## 16. 已知限制

- **横屏旋转无法在本机模拟器上验证**：该 ROM 忽略 `settings put system user_rotation`，
  `am get-config` 始终返回 `port`；悬浮布局本身通过「键盘布局 = 悬浮」在竖屏下验证过。
- **IME 窗口不能自由拖动**（系统决定位置）→ 只做稳定悬浮 + 靠右对齐，**没有**做拖动。
- MORE 里的 翻译 / 语音 / 常用语 只有入口，点击会提示尚未开发。
- 候选取 librime 当前页（`page_size = 50`，可横向滑动；点候选栏右侧 `∨` 可**全部展开**为
  4 列网格，单字 / 长词都直接点得到，`∧` 或返回键收起；对齐微信候选栏的展开面板），
  已开启模糊音（平翘舌 / n-l / h-f / 前后鼻音）、拼写与按键纠错、前缀补全（对齐微信本地候选，
  不含云端）；模糊音以 `derive` 形式追加，权重低于精确匹配，最常用的词仍排在前面。
  **用户词优先**：自定义词 / 固定词 / 常用语（Rime `custom_phrase`）的 `initial_quality` 提到
  基础词频（essay.txt，最大约 4.8e6）之上，输入其编码时排在最前（对齐微信「用户词优先」）。
  **现代常用词补充词库**：`board_common.dict.yaml`（essay.txt 高频 2–4 字词，脚本自动注音）
  作为额外词源，补上基础 `luna_pinyin`（繁体 / 文言词典）缺的现代常用词（如 微信）。
  **英文候选**改为按**词频**排序的 10k 词表（`assets/english_words.txt`）：前缀补全「常用词优先」
  （`hel` → help / held / helpful / hello…），不再按词长排序；候选上限 8 → 50（与中文候选栏一致，
  横向可滑动，对齐微信英文「一页 + 展开更多」的量级）。
  另补充常用**语气词 / 叠词的口语编码**（`嗯=e`、`嗯嗯=e e`、`哦=o`…），
  对齐微信：输入 `ee` → 首选「嗯嗯」。
  **逐字选词**（对齐微信）：输入 `xupengcheng` 时，选中只覆盖前几个音节的候选（如「许」）
  会**保留未选完的拼音**（`pengcheng`）继续给候选，可继续「鹏 → 程」，而不是一次性把整串提交掉。
  实现见 `RimeEngine.selectAndDrain`：librime 对这类前缀候选的 `select_candidate` 返回 `true`
  但什么都不做，Board 兜底时用 `nativeGetPreedit` 取音节切分，提交候选文本后把剩余音节重新喂回 librime。
- **剪贴板搜索时整个输入法会变高**（面板 56dp + 结果区 ≤146dp + 键盘）：
  这是为了让「能打字」和「看得见结果」同时成立；TopPanel 本身始终 56dp，
  退出搜索立刻恢复原来的总高度。剪贴板普通状态与键盘状态高度完全一致。
- 剪贴板没有置顶/拖拽排序、没有批量选择、没有「最近删除」恢复入口（删除是软删除，
  数据还在库里，只是列表不显示）。
- 敏感内容判定是**启发式**：4–8 位纯数字会被当成验证码而不入库（可能误判），
  复杂检测（模型 / 按来源判断）留给以后。
- 剪贴板 `IMAGE` / `FILE` 类型只有枚举占位，没有真正的图片 / 文件内容与同步。
- **同步只做纯文本**：远端 `type = Image / File / Group` 时安全忽略并写
  `SYNC_IGNORED_TYPE` 日志，不会假装同步成功；`/file/{dataName}` 的客户端方法已实现但没有调用。
- **同步不是实时推送**：只有「打开设置页 / 输入法启动 / 复制后去抖 / WorkManager 30 分钟周期」
  这四种触发。真正实时（长连接 / 局域网）留给后续阶段。
- 同步模型是 SyncClipboard 的「服务器上只有**一份当前剪贴板**」，不是同步整个历史；
  本地历史不会因为同步被裁剪，但服务器上只保留最新一条。
- **没有删除同步**：在 Board 里删除一条记录不会去删除远端；远端删除也不会删除本地。
- 冲突判断依赖 WebDAV 的 `Last-Modified`；服务器不返回该头时，行为是「保留本地 + 把远端
  作为新记录写入本地」，不会覆盖本地。
- 测试用的是**本机最小 WebDAV 服务器**（`tools/test_webdav.ps1`，HttpListener 实现，
  支持 GET/PUT/MKCOL/PROPFIND/DELETE + Basic 认证），不是 Nextcloud / 坚果云等商业 WebDAV；
  逐条实测记录见本轮报告。
- 明文 `http://` 的 WebDAV 也允许（`usesCleartextTraffic=true`），是否用 https 由用户填写的地址决定。
- **来源标记依赖可选的 `device` 扩展**：SyncClipboard 官方协议没有设备字段，
  所以从别的客户端同步过来时多数情况只能显示「云端」，不会显示具体设备名。
- 剪贴板筛选目前只有「全部 / 云端」两种；收藏 / 固定筛选留到以后。
- **主题 / 键盘设置只有开关和 `SyncEntityType`，没有真正的同步实现**：
  勾选后不会上传任何内容（本轮只把模型和入口准备好）。
- 词库同步用的是「一个实体一个文件 + 拉取后合并写回」的模型；
  `sync_operation` 表的 `deviceId` / `deleted` / `timestamp` 字段已就位，
  但目前只有剪贴板推送真正走队列，词库同步没有逐条任务记录。
- 学习词同步要求**本进程里 Rime 已经初始化**（打开过一次键盘）；否则这次同步会跳过并写日志。
- 删除同步只在词库类类型（自定义 / 固定 / 常用语）上实现；剪贴板条目的删除还不同步。
- 同步文件里的设备名取自 `Build.MODEL`，不是账号信息。
- **性能只在模拟器（MuMu Android 12 / x86_64 / 900×1600）上量过**：
  四组场景 Janky frames 都是 0，但真机、低端机、长时间压力、横屏悬浮布局下的帧率**没有测**。
- 状态切换动画是「进入方淡入 + 4dp 上移」的单向动画（没有做交叉淡出），
  这也是为了不动键盘、不产生叠加布局。
- `SyncClipboardMapper` 只是映射层：没有上传下载、没有 `SyncClipboard.json` 落盘入口。
- 剪贴板只同步文本，没有置顶 / 搜索 / 清空全部；编辑模式删除没有二次确认。
- **学习词镜像是快照**：需要输入法服务在本进程里起过 librime 才能重新导出；
  否则设置页显示上一次同步的数量（不会清零，但也不是实时）。
- **屏蔽词是 Board 侧过滤**，没有写进 librime 数据；换用别的 Rime 前端不会生效。
- 自定义词 / 固定词改动后会重启 Rime 会话，当前未上屏的输入会被清掉。
- 学习记录的多音节自定义短语必须写成**连写编码**（`bode`），因为 librime 按输入串前缀匹配。
- 测试设备上「候选词字号」停在「特大」（该 ROM 设置页的 ScrollView 不响应 adb 合成滑动），
  代码默认值是**标准**。
- 仍无单元测试 / 仪器测试。

**不在本阶段范围**：跨设备剪贴板、WebDAV 同步、局域网同步、Windows 端、AI、语音、GIF、账号、广告、主题商城。

第三方组件与许可证见 [THIRD_PARTY.md](THIRD_PARTY.md)。
