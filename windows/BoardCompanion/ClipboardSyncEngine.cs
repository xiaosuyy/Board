using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// 极简日志。**允许**：chars / hashChanged / status / elapsedMs / suppress 等不含内容的字段。
    /// **禁止**：剪贴板正文、完整 JSON、密码、Authorization。
    /// 同时写到控制台与 `%APPDATA%\BoardCompanion\companion.log`（只含允许字段）。
    /// </summary>
    public static class Log
    {
        private static readonly object Gate = new object();
        private static readonly string Path = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "BoardCompanion", "companion.log");

        public static void Info(string message) { Write("INFO ", message); }
        public static void Warn(string message) { Write("WARN ", message); }

        private static void Write(string level, string message)
        {
            var line = DateTime.Now.ToString("HH:mm:ss") + " " + level + " " + message;
            lock (Gate)
            {
                Console.WriteLine(line);
                try
                {
                    Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path));
                    File.AppendAllText(Path, line + Environment.NewLine, new UTF8Encoding(false));
                }
                catch (Exception)
                {
                    // 日志失败绝不影响功能
                }
            }
        }
    }

    public enum SyncState
    {
        NotConfigured = 0,
        Idle = 1,
        Syncing = 2,
        Paused = 3,
        Error = 4,
    }

    /// <summary>
    /// 剪贴板同步引擎（事件驱动 + 去抖 + hash 去重 + 防回环）。
    ///
    /// 流程（与 Android 端语义一致）：
    ///   外部复制 → WM_CLIPBOARDUPDATE → 去抖 1s → 读文本 → SHA-256
    ///   → 与已同步 hash 相同则 SKIP_SAME_HASH（不发请求）
    ///   → 否则 Push 云端（任意 2xx 成功）→ 再 Pull 一次做收敛
    ///
    /// 防回环：自己写回剪贴板前登记 expectedHash，并把 localHash 更新为它；
    /// 随后到达的 WM_CLIPBOARDUPDATE 因 hash 相同直接 SKIP（日志打印 suppress_loop）。
    ///
    /// 隐私：正文只存在于内存与请求 body；引擎**只保存 hash**，从不落盘正文。
    /// 线程：网络在后台 Task 上跑，剪贴板与 Timer 在 UI 线程。
    /// </summary>
    public sealed class ClipboardSyncEngine : IDisposable
    {
        private const int DebounceMs = 1000;

        private readonly object gate = new object();
        private readonly System.Windows.Forms.Timer debounce;

        private BoardWindowsConfig config;
        private string password;
        private WebDavClient client;

        /// <summary>最后一次"已知同步过"的 hash（允许保存；不是正文）</summary>
        private string localHash = string.Empty;

        /// <summary>自己写回剪贴板时登记的期望 hash（只在内存）</summary>
        private string expectedHash;

        /// <summary>待推送的正文（**只在内存**；Push 失败时留到下次重试）</summary>
        private string pendingText;

        private bool busy;

        public event EventHandler StateChanged;

        public SyncState State { get; private set; }
        public bool Paused { get; private set; }
        public string LastError { get; private set; }

        public ClipboardSyncEngine(BoardWindowsConfig config, string password)
        {
            State = SyncState.NotConfigured;
            debounce = new System.Windows.Forms.Timer { Interval = DebounceMs };
            debounce.Tick += (s, e) =>
            {
                debounce.Stop();
                OnDebounceElapsed();
            };
            Apply(config, password);
        }

        public void Apply(BoardWindowsConfig newConfig, string newPassword)
        {
            lock (gate)
            {
                config = newConfig;
                password = newPassword;
                client = (config != null && !string.IsNullOrEmpty(config.baseUrl))
                    ? new WebDavClient(config.baseUrl, config.username, password)
                    : null;
                // 关键：沿用上次同步过的 hash，避免重启/手动同步时把云端覆盖成自己的旧剪贴板
                localHash = (config == null || config.lastHash == null) ? string.Empty : config.lastHash;
                State = client == null ? SyncState.NotConfigured : SyncState.Idle;
            }
            RaiseStateChanged();
        }

        /// <summary>把"已同步 hash"落盘（只存 hash，不存正文）</summary>
        private void PersistHash()
        {
            try
            {
                if (config == null) return;
                if (config.lastHash == localHash) return;
                config.lastHash = localHash;
                var store = new CredentialStore();
                var current = store.Load();
                current.lastHash = localHash;
                store.Save(current, store.LoadPassword());
            }
            catch (Exception)
            {
                // 落盘失败不影响同步
            }
        }

        public string DeviceId
        {
            get { return config == null ? string.Empty : config.deviceId; }
        }

        public void SetPaused(bool paused)
        {
            Paused = paused;
            if (!paused) localHash = string.Empty; // 恢复后允许重新同步一次
            lock (gate) { State = paused ? SyncState.Paused : (client == null ? SyncState.NotConfigured : SyncState.Idle); }
            Log.Info("paused=" + paused);
            RaiseStateChanged();
        }

        /// <summary>WM_CLIPBOARDUPDATE 回调（UI 线程）</summary>
        public void OnClipboardChanged()
        {
            if (Paused) return;
            var text = ClipboardMonitor.ReadText();
            if (text == null)
            {
                Log.Info("clipboard changed (not text, ignored)");
                return;
            }
            var hash = ClipboardHasher.Hash(text);
            if (hash == expectedHash)
            {
                Log.Info("clipboard changed chars=" + text.Length + " suppress_loop");
                expectedHash = null;
                return;
            }
            if (hash == localHash)
            {
                Log.Info("clipboard changed chars=" + text.Length + " hashChanged=false skip");
                return;
            }
            // 去抖：短时间连续复制只处理最后稳定值
            debounce.Stop();
            debounce.Start();
        }

        private void OnDebounceElapsed()
        {
            var text = ClipboardMonitor.ReadText();
            if (text == null) return;
            var hash = ClipboardHasher.Hash(text);
            if (hash == localHash || hash == expectedHash)
            {
                Log.Info("debounce settle chars=" + text.Length + " hashChanged=false skip");
                return;
            }
            Log.Info("clipboard changed chars=" + text.Length + " hashChanged=true");
            pendingText = text;
            RunSta(() => SyncInternal(false));
        }

        /// <summary>
        /// 在**专用 STA 线程**上执行同步。
        ///
        /// 关键：Windows 剪贴板（`Clipboard.GetText/SetText`）要求 STA。
        /// 线程池线程是 MTA，在那里读写剪贴板会直接失败（实测 read=none / write failed）。
        /// </summary>
        private static Task RunSta(Action action)
        {
            var tcs = new TaskCompletionSource<object>();
            var thread = new Thread(() =>
            {
                try
                {
                    action();
                    tcs.SetResult(null);
                }
                catch (Exception ex)
                {
                    tcs.SetException(ex);
                }
            });
            thread.IsBackground = true;
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            return tcs.Task;
        }

        /// <summary>启动时 / 托盘「立即同步」：先推本地变更，再拉云端收敛</summary>
        public void SyncNow()
        {
            var ignored = SyncNowAsync();
        }

        /// <summary>同上，但返回可等待的 Task（供无界面模式判断结果）</summary>
        public Task SyncNowAsync()
        {
            return RunSta(() => SyncInternal(true));
        }

        private void SyncInternal(bool readClipboardFirst)
        {
            lock (gate)
            {
                if (client == null)
                {
                    State = SyncState.NotConfigured;
                    RaiseStateChanged();
                    return;
                }
                if (busy || Paused) return;
                busy = true;
                State = SyncState.Syncing;
            }
            RaiseStateChanged();

            var started = Environment.TickCount;
            try
            {
                if (readClipboardFirst)
                {
                    var current = ClipboardMonitor.ReadText();
                    Log.Info("read clipboard chars=" + (current == null ? "none" : current.Length.ToString()));
                    if (current != null)
                    {
                        var currentHash = ClipboardHasher.Hash(current);
                        if (currentHash != localHash) pendingText = current;
                    }
                }

                // ---- 1) Push（只在本地确有变更时）----
                if (pendingText != null)
                {
                    var text = pendingText;
                    var hash = ClipboardHasher.Hash(text);
                    if (hash == localHash)
                    {
                        pendingText = null;
                    }
                    else
                    {
                        var size = ClipboardHasher.Utf8Length(text);
                        var fileMode = size > ClipboardPayload.INLINE_TEXT_MAX_BYTES;
                        ClipboardPayload payload;
                        var uploadOk = true;
                        var uploadStatus = 0;

                        if (fileMode)
                        {
                            // 超长：正文走 /file/{hash}.txt，清单里只留元信息（与 Android 的
                            // toFilePayload 完全一致）。正文**只在内存与请求体里**，不落盘。
                            var dataName = FileDataName(hash);
                            var filePut = client.PutText(
                                FilePath(dataName),
                                text,
                                "text/plain; charset=utf-8");
                            if (!filePut.IsOk)
                            {
                                LastError = filePut.Error;
                                Log.Warn("push file failed status=" + filePut.StatusCode +
                                         " size=" + size + " kept_pending=true");
                                lock (gate) { State = SyncState.Error; }
                                uploadOk = false;
                            }
                            uploadStatus = filePut.StatusCode;
                            payload = new ClipboardPayload
                            {
                                type = ClipboardPayload.TYPE_TEXT,
                                hash = hash,
                                text = null,
                                hasData = true,
                                dataName = dataName,
                                size = size,
                                device = DeviceId,
                            };
                        }
                        else
                        {
                            payload = new ClipboardPayload
                            {
                                type = ClipboardPayload.TYPE_TEXT,
                                hash = hash,
                                text = text,
                                hasData = false,
                                dataName = string.Empty,
                                size = size,
                                device = DeviceId,
                            };
                        }

                        if (uploadOk)
                        {
                            var put = client.PutText(
                                ManifestPath(),
                                SyncClipboardProvider.Serialize(payload),
                                "application/json; charset=utf-8");
                            uploadStatus = put.StatusCode;
                            if (put.IsOk)
                            {
                                localHash = hash;
                                pendingText = null;
                                PersistHash();
                                Log.Info("push status=" + put.StatusCode + " chars=" + text.Length +
                                         " file=" + fileMode + " bytes=" + size);
                            }
                            else
                            {
                                LastError = put.Error;
                                Log.Warn("push failed status=" + put.StatusCode + " kept_pending=true");
                                lock (gate) { State = SyncState.Error; }
                            }
                        }
                    }
                }
                else
                {
                    Log.Info("push skipped no_pending");
                }

                // ---- 2) Pull：把云端当前项收敛到本机 ----
                var get = client.Get(ManifestPath());
                if (get.IsOk)
                {
                    var parsed = SyncClipboardProvider.Deserialize(get.Body ?? string.Empty);
                    if (parsed.Status == PayloadParseStatus.Ok)
                    {
                        var remote = parsed.Payload;
                        var text = remote.text ?? string.Empty;
                        if (remote.hasData)
                        {
                            // 超长：正文在 /file/{dataName}，先取回来（仍然只在内存）
                            if (string.IsNullOrEmpty(remote.dataName))
                            {
                                Log.Warn("pull skipped hasData without dataName");
                                text = string.Empty;
                            }
                            else
                            {
                                var fileGet = client.Get(FilePath(remote.dataName));
                                if (fileGet.IsOk)
                                {
                                    text = fileGet.Body ?? string.Empty;
                                    Log.Info("pull file status=" + fileGet.StatusCode + " bytes=" + remote.size);
                                }
                                else
                                {
                                    Log.Warn("pull file failed status=" + fileGet.StatusCode);
                                    text = string.Empty;
                                }
                            }
                        }

                        if (text.Length == 0)
                        {
                            Log.Info("pull status=" + get.StatusCode + " empty skip");
                        }
                        else if (remote.hash == localHash)
                        {
                            Log.Info("pull status=" + get.StatusCode + " hashChanged=false skip");
                        }
                        else
                        {
                            expectedHash = remote.hash;
                            if (ClipboardMonitor.WriteText(text))
                            {
                                localHash = remote.hash;
                                PersistHash();
                                Log.Info("pull status=" + get.StatusCode + " chars=" + text.Length +
                                         " applied=true file=" + remote.hasData);
                            }
                            else
                            {
                                Log.Warn("pull write-clipboard failed");
                            }
                        }
                    }
                    else if (parsed.Status == PayloadParseStatus.UnsupportedType)
                    {
                        Log.Info("pull ignored type=" + (parsed.Payload == null ? "unknown" : parsed.Payload.type));
                    }
                    else
                    {
                        Log.Warn("pull payload invalid status=" + parsed.Status);
                    }
                }
                else if (get.NotFound)
                {
                    Log.Info("pull status=404 (cloud empty)");
                }
                else
                {
                    LastError = get.Error;
                    Log.Warn("pull failed status=" + get.StatusCode);
                    lock (gate) { State = SyncState.Error; }
                }

                lock (gate)
                {
                    if (State != SyncState.Error) State = Paused ? SyncState.Paused : SyncState.Idle;
                }
            }
            finally
            {
                lock (gate) { busy = false; }
                Log.Info("sync done elapsedMs=" + (Environment.TickCount - started));
                RaiseStateChanged();
            }
        }

        /// <summary>
        /// 清单路径 = `&lt;baseUrl&gt;/&lt;syncDir&gt;/SyncClipboard.json`（**与 Android 完全同一规则**）。
        ///
        /// 两条安全网：
        /// - 目录留空 → 直接用 `&lt;baseUrl&gt;/SyncClipboard.json`（地址即根）；
        /// - 地址结尾**已经是该目录** → 不再重复拼（否则会变成 /dav/board/board/…，实测踩过）。
        /// </summary>
        public static string ManifestPathFor(string baseUrl, string syncDir)
        {
            return RelativePathFor(baseUrl, syncDir, SyncClipboardProvider.MANIFEST_NAME);
        }

        /// <summary>按 Android 的规则拼出相对路径（`&lt;dir&gt;/name`，或地址已含目录时直接 `name`）</summary>
        public static string RelativePathFor(string baseUrl, string syncDir, string name)
        {
            var dir = (syncDir ?? string.Empty).Trim().Trim('/');
            if (dir.Length == 0) return name;
            var baseTrimmed = (baseUrl ?? string.Empty).Trim().TrimEnd('/');
            if (baseTrimmed.EndsWith("/" + dir, StringComparison.OrdinalIgnoreCase)) return name;
            return dir + "/" + name;
        }

        /// <summary>`/file/{dataName}`（同样遵循"地址即根 / 目录单独填"两条规则）</summary>
        private string FilePath(string dataName)
        {
            var file = "file/" + (dataName ?? string.Empty).Trim('/');
            var dir = config == null ? null : config.syncDir;
            var baseUrl = config == null ? null : config.baseUrl;
            return RelativePathFor(baseUrl, dir, file);
        }

        /// <summary>清单路径：与 Android 一致 = `&lt;syncDir&gt;/SyncClipboard.json`</summary>
        private string ManifestPath()
        {
            return ManifestPathFor(config == null ? null : config.baseUrl,
                                   config == null ? null : config.syncDir);
        }

        /// <summary>
        /// 超长文本是否该走 `/file/`（与 Android 的 INLINE_TEXT_MAX_BYTES 完全一致）。
        ///
        /// 判定用 **UTF-8 字节数**，不是字符数 —— 中文一个字 3 字节，Emoji 4 字节。
        /// 边界语义：`&lt;= 256 KiB` 内联；`&gt; 256 KiB` 走文件。用户无感，没有手动选项。
        /// </summary>
        public static bool UseFileMode(string text)
        {
            return ClipboardHasher.Utf8Length(text) > ClipboardPayload.INLINE_TEXT_MAX_BYTES;
        }

        /// <summary>与 Android 的 SyncClipboardMapper.dataNameFor() 一致</summary>
        public static string FileDataName(string hash)
        {
            return (string.IsNullOrEmpty(hash) ? "board" : hash) + ".txt";
        }

        private void RaiseStateChanged()
        {
            var handler = StateChanged;
            if (handler != null) handler(this, EventArgs.Empty);
        }

        public void Dispose()
        {
            debounce.Dispose();
        }
    }
}
