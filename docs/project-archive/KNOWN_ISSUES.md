# 已知问题与限制（Known Issues）

> 只记录**真实存在**的问题与限制，不为了凑内容制造条目。

## 1. 自动化环境限制（非缺陷）

| 项 | 说明 |
|---|---|
| 部分交互无法自动验证 | adb 无法可靠操作 **IME 面板** 与 **SAF 系统文件选择器**，因此：SAF 上传（图片/文件）、本机同 hash 重传解除 hidden、Activity recreate 等需要**真机人工**验证（见 `VALIDATION.md` C 节） |
| 合成手势与真实手指不同 | `adb shell input swipe` 在很短时长（≤250ms）时不触发 Library 左右滑（现已无左右滑需求，但同类现象在其它手势上仍存在）；真实手指事件更密，行为正常 |

## 2. 产品上**刻意接受**的行为（不是缺陷）

| 项 | 说明 |
|---|---|
| 云端悬挂条目（index 有、binary 404） | 本机**保留 metadata**，不自动删除。原因：产品要求「以前收到的东西都留在那里」。用户可用 `×` 本机隐藏 |
| 本机隐藏不清理缓存 | `×` 只影响本机显示；本机 cache、云端 binary、云端 index 都不动 |
| 云端 TTL 不清理本机历史 | 24h prune 只作用于云端；本机已收到的内容继续显示 |
| 本机主动重传同 hash 会**解除**隐藏 | 视为用户主动重新获得该资料 |
| 其他设备重传同 hash **不**解除本机隐藏 | 隐藏是本机 UI 状态（只有本机 `upload()` 成功才触发 `onUnhide`） |

## 3. 为降低回归风险**刻意保留**的旧代码（**不要顺手清理**）

| 位置 | 内容 | 保留原因 |
|---|---|---|
| `ui/panel/TopPanelController.kt` | `private fun toggleRemoteFilter()`（已无调用方） | 与 `remoteOnly` 状态机、`setRemoteOnly` 仍同源；删除有潜在宿主依赖风险 |
| `ui/panel/ClipboardPanel.kt` | 字段 `gestureDownX`、`touchSlop`、`switchThresholdPx`（已无使用） | 仅无用字段，删除属纯清理，收益低、回归风险非零 |
| `ui/panel/ClipboardPanel.kt` | IMAGE/FILE 行渲染分支（现不可达，因为 `visibleItems()` 只回文字） | 公开回调（`onLoadThumbnail`/`onSaveImage`/`onLoadContent`）仍属宿主 API；删除渲染管线会牵连 `CardBinder`/预览，风险大于收益 |
| `ui/panel/ClipboardHeader.kt` | `Slot.CLOUD` → `onToggleRemoteFilter` 命名 | 沿用既有命名，避免牵动 Header 绘制与命中测试 |

> **原则**：宁可保留不用的代码，也不为「代码更干净」破坏现有能力。

## 4. 已知的技术性注意点

| 项 | 说明 |
|---|---|
| Library 首次进入会自动取正文 | 新 hash 会被 `fetchBytes` 并写入本机缓存（这是「本地优先 + 离线可见」的前提）；若云端资料很大，首轮会有相应流量 |
| `Download/` 而非 `Downloads/` | Android 的 `Environment.DIRECTORY_DOWNLOADS` = `Download`（单数）；文件落盘路径为 `/sdcard/Download/Board/` |
| MediaStore 同名去重 | 重复下载同名文件会出现 `xxx (1).txt` 后缀，属系统行为 |
| API < 29 | `AndroidLibraryFileSink` 在 API<29 直接返回 `null`（不申请存储权限）⇒ 落盘失败；资料库其余功能不受影响 |
| `devices.json` 并发写 | 与 Windows 侧同为读改写并集，**存在竞态**（未加锁）；失败不破坏本地状态，下次上传会重新自注册 |
| 多设备同时 prune | 未做并发保护；`partial` 时直接 skip，避免误删 |
| Windows 侧 index 失败回滚 | Windows 1-H 语义为「index 失败 → 尽力 DELETE binary」；Android 侧为「不 DELETE」（安全优先）。**两端此处有意不同** |

## 5. 未验证的边界（诚实记录）

- 真实崩溃（kill 进程）下的 B1 seq crash-window 复现（仅单测 + 顺序论证）
- B1 离线补偿 / 真实网络失败恢复的真机端到端
- `>10 MiB` SAF 上传的真机拒绝链路
- Android ↔ Windows 双向 Library 互见的真机完整链路（Windows 端已封板）
- Activity recreate / 旋转后的缓存与 hidden 恢复
