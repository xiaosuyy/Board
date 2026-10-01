using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// Windows 剪贴板监听。
    ///
    /// **事件驱动**：注册 `AddClipboardFormatListener` 并接收 `WM_CLIPBOARDUPDATE`，
    /// 只在系统剪贴板**真正发生变化**时才回调。**不做任何轮询 / Timer 检查**。
    ///
    /// 处理纯文本（`CF_UNICODETEXT`）与图片（Phase 1-E：`ContainsImage`，统一编码为 PNG）。
    /// 其它格式（文件列表等）调用方直接忽略（不 Push、不动云端）。
    ///
    /// Clipboard 所有权：读取与写入都是 `Open → 操作 → Close`（WinForms 的
    /// `Clipboard.GetText/SetText` 内部即如此），异常也不会把剪贴板锁死。
    /// </summary>
    public sealed class ClipboardMonitor : NativeWindow, IDisposable
    {
        private const int WM_CLIPBOARDUPDATE = 0x031D;

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool AddClipboardFormatListener(IntPtr hwnd);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

        public event EventHandler ClipboardChanged;

        private bool listening;

        public ClipboardMonitor()
        {
            CreateHandle(new CreateParams());
        }

        public void Start()
        {
            if (listening) return;
            listening = AddClipboardFormatListener(Handle);
            if (!listening)
            {
                Log.Warn("clipboard listener register failed");
            }
        }

        public void Stop()
        {
            if (!listening) return;
            RemoveClipboardFormatListener(Handle);
            listening = false;
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_CLIPBOARDUPDATE)
            {
                var handler = ClipboardChanged;
                if (handler != null) handler(this, EventArgs.Empty);
            }
            base.WndProc(ref m);
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
            DestroyHandle();
        }
    }
}
