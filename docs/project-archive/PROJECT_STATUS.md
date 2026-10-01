# 项目最终状态（Phase 2）

## 1. 当前稳定版本

| 项 | 值 |
|---|---|
| 稳定基线 commit | `dad502c64ce5595b0441f6a4bfa43e2d8b415122` |
| commit message | `feat: complete cloud library phase 2` |
| 上一个基线 commit | `3b8b7c1` — `feat(v2.0 b1): add incremental text clipboard history` |
| 分支 | `master` |
| tag | `v0.1.0-mobile` / `v1.0.0` / `v1.1.0`（本次**未新增 tag**） |
| remote | **无**（未 push，从未 push） |

## 2. 当前 APK

```
路径   : android\app\build\outputs\apk\debug\app-debug.apk
大小   : 24,467,092 B
SHA256 : 81fe6f77093fd883656dbba2b878958668a1e8aeea444b49a1a2e6719be7d804
```

## 3. 当前工作区状态

```
git status --short  →（空）干净
git diff --check    → exit 0（PASS）
```

## 4. 已自动验证

```
Android tests      : 210 PASS / 0 FAIL / 0 ERROR
git diff --check   : PASS
Debug APK build    : BUILD SUCCESSFUL
```

测试分布（`test/java/com/board/ime/`）：

| 测试类 | 用例数 |
|---|---|
| `cloudlibrary.CloudLibraryCodecTest` | 21 |
| `cloudlibrary.CloudLibraryRemoteTest` | 27 |
| `cloudlibrary.CloudLibraryRepositoryTest` | 28 |
| `cloudlibrary.LibraryUiModelTest` | 25 |
| 其余既有测试（clipboard / sync / dictionary / voice / translation …） | 109 |
| **合计** | **210** |

## 5. 已人工验证（真机）

由用户在真机（OPPO PLZ110 / Android 16）上实际确认通过：

1. Clipboard 只显示文字
2. Clipboard 无左右滑分类
3. 云朵图标进入 Library
4. Library 图片 + 文件统一混合列表
5. `uploadedAt` DESC 排序
6. 图片预览
7. 图片下载
8. 文件下载
9. `×` 本机隐藏
10. hidden 持久化
11. hidden 不影响云端
12. 本机 Library cache 保留
13. 断网仍显示历史内容
14. metadata-only 条目仍显示
15. 新云端内容增量加入
16. 旧内容复用本地 cache（不重复下载）

## 6. 已完成（功能面）

- **Clipboard**：UI 只展示文字；底层图片/文件同步能力**完整保留**
- **云端资料库（Android）**：装配 / 混合列表 / 下载 / SAF 上传 / 24h prune / 本机隐藏 / 本机持久缓存 / 离线可见 / 增量刷新
- **B1 增量剪贴板历史**（已封板于 `3b8b7c1`）：`/clipboard-log/<deviceId>.json`、seq 先持久化后 PUT、crash-window 重分配保护、lastSeenSeq、minSeq/compaction、hash 去重、与 latest-state 隔离
- **Cloud Library（Windows，1-H 已封板于 `1aaf13b`）**：`/library/`、24h TTL、多设备 `max(uploadedAt)` 保护、真 DELETE、非阻塞 UI
- **隐私文档**：`docs/PRIVACY_AND_CN_COMPATIBILITY.md` 已含 B1 与 Cloud Library 行为说明

## 7. 当前**没有**继续处理的问题（不代表缺陷，是已接受的现状）

- 部分交互无法由当前自动化环境（adb）完整验证，需人工真机操作（见 `VALIDATION.md` C 节）
- 少量旧代码为降低回归风险**刻意保留**（见 `KNOWN_ISSUES.md`）
- 云端悬挂条目（index 有、binary 404）**不自动清理**——这是产品要求（"以前收到的东西都留在那里"），用户可用 `×` 本机隐藏

## 8. 暂时封存原因

- Phase 2 目标（Clipboard 纯文字 UI + 云端资料库 Android 侧 + 本机缓存/离线/增量）已达成并通过自动 + 人工验证
- 当前无已知阻塞缺陷
- 按项目节奏阶段性收口，避免在未规划的情况下继续扩张范围

## 9. 封存后重新启动的起点

**以 `dad502c64ce5595b0441f6a4bfa43e2d8b415122` 为起点**，新开分支或直接在 `master` 上继续；**不要** reset / rebase / 改写该提交及其历史。
