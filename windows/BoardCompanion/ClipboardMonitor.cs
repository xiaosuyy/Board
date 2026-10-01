using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// Windows 剪贴板监听（D2 + D3）。
    ///
    /// **事件驱动**：注册 `AddClipboardFormatListener` 并接收 `WM_CLIPBOARDUPDATE`。
    ///
    /// D2 —— 通知级去重：一次真实变化会产生多条通知（实测 3~4 条）。
    /// 用 `GetClipboardSequenceNumber()` 判定「同一次变化」，只捕获第一条；
    /// 下一次真实变化序号 +1 ⇒ 即使 hash 相同也照样再捕获一次。
    ///
    /// D3 —— **专用监听线程**：消息泵跑在一条独立的 STA 线程上（不是 UI 线程），
    /// `WM_CLIPBOARDUPDATE` 一到就**立刻**同步读取剪贴板快照（最小同步工作），
    /// 然后把快照封送回 UI 线程。UI 线程忙不忙都不再影响"读到的是哪一次内容"。
    ///
    /// ⚠️ 系统级限制（不可绕过）：Windows 剪贴板 API 只暴露**当前**内容。
    /// 如果两次写入之间的间隔短于本进程真正完成读取的时间，先写的那次
    /// **已经不存在了**，任何实现都读不回来。见 README / 验收报告中的压力实测。
    ///
    /// 处理纯文本（`CF_UNICODETEXT`）与图片（Phase 1-E：`ContainsImage`）。
    /// 其它格式（文件列表等）调用方直接忽略（不 Push、不动云端）。
    /// </summary>
    public sealed class ClipboardMonitor : IDisposable
    {
        private const int WM_CLIPBOARDUPDATE = 0x031D;
        private const int WM_APP_QUIT = 0x8000 + 1;

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool AddClipboardFormatListener(IntPtr hwnd);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint GetClipboardSequenceNumber();

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool PostMessage(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam);

        public event EventHandler<ClipboardSnapshot> ClipboardChanged;

        private readonly ClipboardNotificationGate gate = new ClipboardNotificationGate();

        /// <summary>UI 线程上的封送目标（隐藏控件）。为 null 时退化为直接回调。</summary>
        private Control marshaller;

        private Thread thread;
        private ListenerWindow window;
        private volatile bool running;

        public ClipboardMonitor()
        {
        }

        public void Start()
        {
            if (running) return;
            running = true;

            // 在调用线程（UI 线程）上建一个隐藏控件用于封送：
            // 引擎里的去抖是 WinForms Timer，只能在 UI 线程上操作。
            try
            {
                marshaller = new Control();
                var handle = marshaller.Handle;   // 强制创建句柄
                GC.KeepAlive(handle);
            }
            catch (Exception)
            {
                marshaller = null;
            }

            thread = new Thread(ThreadMain);
            thread.IsBackground = true;
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
        }

        public void Stop()
        {
            if (!running) return;
            running = false;
            try
            {
                var w = window;
                if (w != null) w.PostQuit();
            }
            catch (Exception)
            {
                // 退出路径不抛
            }
        }

        private void ThreadMain()
        {
            try
            {
                Application.OleRequired();          // 本线程需要 OLE（Clipboard 读写）
                window = new ListenerWindow(this);
                if (!AddClipboardFormatListener(window.Handle))
                {
                    Log.Warn("clipboard listener register failed");
                }
                Application.Run();                  // 本线程专属消息泵
            }
            catch (Exception)
            {
                Log.Warn("clipboard listener thread stopped");
            }
        }

        /// <summary>在**监听线程**上执行：最小同步读取快照 → 封送</summary>
        private void HandleUpdate()
        {
            uint sequence = 0;
            try { sequence = GetClipboardSequenceNumber(); }
            catch (Exception) { }

            // D2：同一次剪贴板变化的重复通知，只捕获一次
            if (!gate.ShouldCapture(sequence)) return;

            ClipboardSnapshot snapshot;
            try
            {
                snapshot = CaptureSnapshot(sequence);
            }
            catch (Exception)
            {
                return;
            }
            if (snapshot == null) return;

            // D2 补充：一次复制可能产生**两个序号**（Empty + Set），
            // 用「极短时间窗 + 内容相同」把它并成一次；内容不同（A→B）不受影响。
            if (!gate.ShouldCaptureContent(snapshot.Text, Environment.TickCount)) return;

            var handler = ClipboardChanged;
            if (handler == null) return;

            var target = marshaller;
            if (target != null && target.IsHandleCreated)
            {
                try
                {
                    target.BeginInvoke((Action)delegate { handler(this, snapshot); });
                    return;
                }
                catch (Exception)
                {
                    // 封送失败就退回直接回调
                }
            }
            try { handler(this, snapshot); }
            catch (Exception) { }
        }

        /// <summary>
        /// **单次剪贴板访问**（D3 优化）：只取一次 `DataObject`，
        /// 之后的格式判断与读取全部在它上面完成 —— 不再让
        /// `ContainsImage` / `ReadSingleFile` / `ReadText` 各自 `Open→Close` 一遍。
        ///
        /// 语义与优化前**完全一致**：
        /// - `DataFormats.Bitmap` 命中 ⇒ 图片（与 `Clipboard.ContainsImage()` 等价）
        /// - `FileDrop` 且恰好一个存在的文件 ⇒ 文件
        /// - `UnicodeText` / `Text` ⇒ 文本
        ///
        /// 只做最小同步工作：图片的 PNG 编码、网络、历史发布等全部留给后续后台流程。
        /// </summary>
        private static ClipboardSnapshot CaptureSnapshot(uint sequence)
        {
            var snapshot = new ClipboardSnapshot { Sequence = sequence };
            Application.OleRequired();

            System.Windows.Forms.IDataObject data;
            try
            {
                data = Clipboard.GetDataObject();   // 唯一一次剪贴板访问
            }
            catch (Exception)
            {
                return snapshot;
            }
            if (data == null) return snapshot;

            try
            {
                if (data.GetDataPresent(DataFormats.Bitmap))
                {
                    snapshot.HasImage = true;
                    return snapshot;
                }

                if (data.GetDataPresent(DataFormats.FileDrop))
                {
                    var paths = data.GetData(DataFormats.FileDrop) as string[];
                    if (paths != null && paths.Length == 1 && !string.IsNullOrEmpty(paths[0]))
                    {
                        var path = paths[0];
                        if (!System.IO.Directory.Exists(path) && System.IO.File.Exists(path))
                        {
                            snapshot.HasFile = true;
                            return snapshot;
                        }
                    }
                }

                if (data.GetDataPresent(DataFormats.UnicodeText))
                {
                    snapshot.Text = data.GetData(DataFormats.UnicodeText) as string;
                }
                if (snapshot.Text == null && data.GetDataPresent(DataFormats.Text))
                {
                    snapshot.Text = data.GetData(DataFormats.Text) as string;
                }
            }
            catch (Exception)
            {
                // 读取失败按“没有可用内容”处理（与优化前返回 null 的语义一致）
            }
            return snapshot;
        }

        private sealed class ListenerWindow : NativeWindow
        {
            private readonly ClipboardMonitor owner;

            public ListenerWindow(ClipboardMonitor owner)
            {
                this.owner = owner;
                CreateHandle(new CreateParams());
            }

            public void PostQuit()
            {
                PostMessage(Handle, WM_APP_QUIT, IntPtr.Zero, IntPtr.Zero);
            }

            protected override void WndProc(ref Message m)
            {
                if (m.Msg == WM_APP_QUIT)
                {
                    RemoveClipboardFormatListener(Handle);
                    Application.ExitThread();
                    return;
                }
                if (m.Msg == WM_CLIPBOARDUPDATE)
                {
                    owner.HandleUpdate();
                }
                base.WndProc(ref m);
            }
        }

        /// <summary>读纯文本；不是文本 / 被占用 → null</summary>
        public static string ReadText()
        {
            try
            {
                // WinForms 的 Clipboard 需要 OLE 初始化（控制台/无消息循环的进程尤其如此）
                Application.OleRequired();
                if (!Clipboard.ContainsText()) return null;
                return Clipboard.GetText();
            }
            catch (Exception)
            {
                return null;
            }
        }

        /// <summary>写纯文本；失败返回 false（不抛）</summary>
        public static bool WriteText(string text)
        {
            try
            {
                Application.OleRequired();
                Clipboard.SetText(text ?? string.Empty);
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>
        /// 剪贴板里若是**单个文件**（CF_HDROP），返回它的完整路径；否则 null。
        ///
        /// 本阶段只做单文件：**多文件 / 文件夹 / 不存在**一律返回 null（安全忽略，不崩溃）。
        /// </summary>
        public static string ReadSingleFile()
        {
            try
            {
                Application.OleRequired();
                var data = Clipboard.GetDataObject();
                if (data == null || !data.GetDataPresent(DataFormats.FileDrop)) return null;
                var paths = data.GetData(DataFormats.FileDrop) as string[];
                if (paths == null || paths.Length != 1) return null;
                var path = paths[0];
                if (string.IsNullOrEmpty(path)) return null;
                if (System.IO.Directory.Exists(path)) return null;
                if (!System.IO.File.Exists(path)) return null;
                return path;
            }
            catch (Exception)
            {
                return null;
            }
        }

        /// <summary>剪贴板里是否**有**文件（用于区分“没有文件”和“读不到路径”）</summary>
        public static bool ClipboardHasFile()
        {
            try
            {
                Application.OleRequired();
                var data = Clipboard.GetDataObject();
                return data != null && data.GetDataPresent(DataFormats.FileDrop);
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>当前剪贴板引用的文件路径（生命周期保护用）；无 / 多文件 → null</summary>
        public static string CurrentFilePath()
        {
            try
            {
                Application.OleRequired();
                var data = Clipboard.GetDataObject();
                if (data == null || !data.GetDataPresent(DataFormats.FileDrop)) return null;
                var paths = data.GetData(DataFormats.FileDrop) as string[];
                if (paths == null || paths.Length != 1) return null;
                return paths[0];
            }
            catch (Exception)
            {
                return null;
            }
        }

        /// <summary>把单个文件放进剪贴板（CF_HDROP）；失败返回 false（不抛）</summary>
        public static bool WriteSingleFile(string path)
        {
            try
            {
                Application.OleRequired();
                if (string.IsNullOrEmpty(path) || !System.IO.File.Exists(path)) return false;
                var list = new System.Collections.Specialized.StringCollection();
                list.Add(path);
                Clipboard.SetFileDropList(list);
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>剪贴板里是否有图片（CF_BITMAP / CF_DIB 等，WinForms 会一并识别）</summary>
        public static bool ContainsImage()
        {
            try
            {
                Application.OleRequired();
                return Clipboard.ContainsImage();
            }
            catch (Exception)
            {
                return false;
            }
        }

        /// <summary>
        /// 读出剪贴板图片并**统一编码为 PNG**（内存，不落盘）；没有 / 失败返回 null。
        /// 同一张图重复读出的 PNG 字节稳定 —— 回环抑制依赖这一点。
        /// </summary>
        public static byte[] ReadImagePng()
        {
            try
            {
                Application.OleRequired();
                if (!Clipboard.ContainsImage()) return null;
                using (var image = Clipboard.GetImage())
                {
                    if (image == null) return null;
                    return ClipboardImage.EncodePng(image);
                }
            }
            catch (Exception)
            {
                return null;
            }
        }

        /// <summary>把 PNG 字节写入剪贴板为图片；失败返回 false（不抛）</summary>
        public static bool WriteImagePng(byte[] png)
        {
            try
            {
                Application.OleRequired();
                var bitmap = ClipboardImage.DecodePng(png);
                if (bitmap == null) return false;
                Clipboard.SetImage(bitmap);
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }

        public void Dispose()
        {
            Stop();
            var m = marshaller;
            if (m != null)
            {
                try { m.Dispose(); } catch (Exception) { }
            }
            var t = thread;
            if (t != null)
            {
                try { t.Join(1500); } catch (Exception) { }
            }
        }
    }
}
