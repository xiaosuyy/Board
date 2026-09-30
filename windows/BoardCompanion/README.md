# Board Windows Companion

Board 的 Windows 端（v2.0）。第一阶段只做**纯文本剪贴板**在
`Windows ↔ WebDAV ↔ Android Board` 之间的同步。

## 技术栈（刻意保持最小）

| 项 | 选择 |
|---|---|
| 语言 | **C#**（.NET Framework 4.8 语法） |
| UI | **WinForms**（后续托盘/设置用） |
| 编译器 | **Windows 系统自带的 `csc.exe`**（`%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe`） |
| JSON | `System.Web.Extensions` 的 `JavaScriptSerializer`（**零 NuGet**） |
| 凭据 | **Windows DPAPI**（`ProtectedData`，CurrentUser） |
| 剪贴板通知 | Win32 `AddClipboardFormatListener`（事件驱动，**不轮询**） |
| 网络 | `HttpWebRequest` + Basic Auth（HTTPS 优先） |

**不安装**：.NET SDK / NuGet / Node / Rust / Go / 其他大型运行时。

## 当前状态：Phase 1-A（已完成）

- `ClipboardHasher` —— 与 Android `clipboard/ClipboardHasher.kt` **完全一致**的
  `SHA-256(UTF-8) 小写十六进制`，不做 trim / 换行转换 / Unicode 规范化；
- `ClipboardPayload` + `SyncClipboardProvider` —— `SyncClipboard.json` 的编解码，
  字段与 Android `SyncClipboardMapper` 逐字对应，**未知字段忽略**，
  `Image/File/Group` 安全拒绝，损坏 JSON 返回明确状态且**不打印正文**；
- `WebDavUrlPolicy` —— HTTPS 默认放行；HTTP 默认拒绝；只有用户明确开启
  **且** `Dns.GetHostAddresses` 解析出的**所有**地址都属于 本机/回环/link-local/
  RFC1918/IPv6 ULA 才放行；公网 HTTP 拒绝；解析失败拒绝；**绝不静默升级成 https**；
- `WebDavClient` —— GET / PUT / MKCOL，**HTTP 2xx 都算成功**（Android 实测 PUT 会返回 201/204），
  Basic Auth 只走 Authorization 头，不拼进 URL；
- `CredentialStore` —— 普通配置落 `%APPDATA%\BoardCompanion\config.json`；
  **密码只进 DPAPI**，绝不写 JSON / 日志；`deviceId` 首次生成 UUID 并持久化；
- `Tests/` —— 轻量自研断言（**不引入 xUnit/NUnit/MSTest**），退出码 0=PASS。

### 隐私硬规则（从第一阶段开始生效）

> **Board Windows 不保存剪贴板正文。**

正文只允许出现在：内存变量、必要的网络请求 body。禁止写入
文件 / JSON / SQLite / 注册表 / dump / 日志。日志只允许
`chars= / hashChanged= / status= / elapsedMs=` 这类不含内容的字段。
（本阶段尚未实现剪贴板监听，因此还没有任何正文进入程序。）

## 尚未实现（后续阶段）

- `ClipboardMonitor.cs`（Win32 剪贴板通知 + 抑制回环）
- `ClipboardSyncEngine.cs`（去抖 / SKIP_SAME_HASH / push / pull / 暂停）
- `TrayController.cs`（托盘菜单）
- `SettingsForm.cs`（WebDAV URL / 账号 / 密码 / 设备名 / 自动同步 / 测试连接）
- 图片 / 文件 / 超过 256 KiB 的文本（第一阶段明确不做，不截断、只拒绝）

## 构建与运行

```powershell
# 编译 + 跑单测（失败时退出码非 0）
powershell -ExecutionPolicy Bypass -File windows\BoardCompanion\build.ps1
```

产物：

```
windows/BoardCompanion/build/BoardCompanion.exe
windows/BoardCompanion/build/BoardCompanion.Tests.exe
```

直接运行 `BoardCompanion.exe`：加载配置（首次生成 `deviceId`）→ 打印状态 → 正常退出。

## deviceId 说明

- 首次运行生成随机 UUID（形如 `win-xxxxxxxxxxxxxxxx`），写入 `config.json`，之后**不再变化**；
- **不使用** MAC 地址 / CPU 序列号 / 硬盘序列号 / Microsoft 账号 / Windows 用户名；
- **卸载重装会重新生成**（配置目录被删除时）——此时云端会多出一个"设备"，但同步内容不受影响。

## 与 Android 的协议关系

Android 端协议已冻结（`SyncClipboard.json` 的字段、hash 算法、`remoteCurrent` 语义）。
Windows **适配** Android，不修改 Android。若发现 Windows 无法完整兼容的字段，先报告再议。
