# 验证记录（Validation）

## A. 自动验证（可重复执行）

| 项 | 命令 | 结果 |
|---|---|---|
| Android 单元测试 | `.\gradlew.bat --offline :android:app:testDebugUnitTest` | **210 PASS / 0 FAIL / 0 ERROR** |
| 空白/行尾检查 | `git diff --check` | **PASS（exit 0）** |
| Debug APK 构建 | `.\gradlew.bat --offline :android:app:assembleDebug` | **BUILD SUCCESSFUL** |

测试分布：

| 测试类 | 用例数 | 覆盖重点 |
|---|---|---|
| `cloudlibrary.CloudLibraryCodecTest` | 21 | index/devices 编解码、坏 JSON 安全、union/`max(uploadedAt)`、TTL 边界、10 MiB 边界、路径净化、**type 大小写不敏感**、来源文案、MIME |
| `cloudlibrary.CloudLibraryRemoteTest` | 27 | devices/index/binary 的 GET/PUT/DELETE 语义、404 幂等、**index 失败不回滚 binary**、超限拒绝、路径穿越拒绝、不碰 `/file/` 与 `SyncClipboard.json` |
| `cloudlibrary.CloudLibraryRepositoryTest` | 28 | refresh 三态、下载路径、上传链（binary→index→自注册）、prune 规则、隐藏/deviceId 映射、`fetchBytes` 无副作用 |
| `cloudlibrary.LibraryUiModelTest` | 25 | 混合列表、`uploadedAt` DESC + 稳定排序、hidden 过滤、缩略图增量、**metadata-only 可见**、断网/union 语义、与 Clipboard 隔离 |
| 其余既有 | 109 | clipboard / sync / dictionary / voice / translation 等 |

> Windows 侧另有 `windows/BoardCompanion/build.ps1` 自带的 191 项断言（B1 阶段），本阶段 Windows 代码未改动。

## B. 真机 / 人工验证（已确认通过）

由用户在真机（OPPO PLZ110 / Android 16）实际确认：

| # | 项 | 结果 |
|---|---|---|
| 1 | Clipboard 纯文字（无图片/文件板块） | PASS |
| 2 | Clipboard 无横向滑动切页 | PASS |
| 3 | 云朵图标进入 Library | PASS |
| 4 | 图片 + 文件统一混合列表 | PASS |
| 5 | `uploadedAt` DESC 排序 | PASS |
| 6 | 图片预览 | PASS |
| 7 | 图片下载 | PASS |
| 8 | 文件下载 | PASS |
| 9 | `×` 本机隐藏 | PASS |
| 10 | 隐藏状态持久化 | PASS |
| 11 | 云端数据不受隐藏影响 | PASS |
| 12 | 本机历史离线可见 | PASS |
| 13 | metadata-only 条目可见 | PASS |
| 14 | 新内容增量出现 | PASS |
| 15 | 已有内容不重复下载 | PASS |

补充的 adb 侧取证（与人工结论一致）：

- 图片下载：`/sdcard/Pictures/Board/222222238640.jpg` = **122,404 B**，与云端 index `size:122404` 一致
- 文件下载：`/sdcard/Download/Board/我每周获得一个新人设 (1).txt` = **1,175,405 B**，与云端 `size:1175405` 一致；MediaStore `relative_path=Download/Board/`
- 本机缓存：`files/library-cache/` = `index.json` + 3 个正文文件（122,404 / 81,569 / 1,175,405 B）
- 隐藏语义：隐藏后 `library/e9e2d5a4….txt` 云端仍 **HTTP 200 / 1,793,869 B**，云端 index 条目仍在

## C. 当前仍受自动化环境限制的项目

以下**不是功能缺陷**，只是 adb 无法可靠代做（需要真机人工操作），因此**未在本归档中标记为 PASS**：

| 项 | 原因 |
|---|---|
| SAF 主动上传（图片 / 文件） | 需要人工操作系统文件选择器 |
| 本机同 hash 重新上传解除 hidden | 需要走完整 SAF 上传流程（代码路径：`UploadResult.Success` → `onUnhide`） |
| 其他设备同 hash 上传**不**解除本机 hidden | 需要 Windows 侧配合重传同一 hash（逻辑上不会解除：只有本机上传成功才 `onUnhide`） |
| Activity recreate / 旋转 | 需要人工旋转或触发重建 |

## D. 曾经的一次误判（记录以免重犯）

- 现象：断网重进 Library 后，本机 `index.json` 有 4 条，UI 只显示 3 条
- 一度怀疑「metadata-only（`contentCached=false`）条目被过滤」
- **实际原因**：该条目的 hash（`ca247479…`，其云端 binary 已 404）**已被用户用 `×` 隐藏**（`hidden_library_hashes` 里确实有它）
- **结论**：不存在 `contentCached` 过滤——全量 grep 确认该字段**只被写入/序列化，从未参与过滤**
- 仍做了防御性加固（逐行 fallback 行 + 占位图标 + 更明确的失败文案），并补了 6 项 metadata-only 回归测试

## E. 验证方式说明

- 所有自动化测试为**纯 JVM**（含 fake WebDAV），**不接触真实服务器、不污染生产 WebDAV**
- 真机验证期间**未创建/未删除**任何真实 Library 数据（唯一的“删除”是本机隐藏）
- 隐私相关：未见剪贴板正文落盘、未产生 plaintext queue
