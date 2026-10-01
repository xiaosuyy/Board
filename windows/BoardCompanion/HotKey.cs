using System;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// 全局快捷键：Windows 原生 `RegisterHotKey` + 现有 WinForms 消息循环。
    ///
    /// 只做一件事：把系统发来的 `WM_HOTKEY` 变成**一次回调**。
    /// **本类不含任何同步逻辑** —— 回调由调用方接到现有的「立即同步」入口
    /// （`ClipboardSyncEngine.SyncNow()`，与托盘菜单 / 状态面板是同一个 handler），
    /// 因此 paused / autoSync 的语义完全一致，没有为快捷键另立一套。
    ///
    /// - 托盘后台运行即生效，**不需要打开任何窗口**；
    /// - **注册失败不影响启动**：返回 false 由调用方记一行日志，绝不弹窗；
    /// - 退出时注销；同一组合被别的程序占用时注册会失败（安全降级）。
    /// </summary>
    public sealed class HotKey : NativeWindow, IDisposable
    {
        private const int WM_HOTKEY = 0x0312;

        /// <summary>修饰键：Alt</summary>
        public const int MOD_ALT = 0x0001;
        /// <summary>修饰键：Ctrl</summary>
        public const int MOD_CONTROL = 0x0002;
        /// <summary>虚拟键：S</summary>
        public const int VK_S = 0x53;

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool RegisterHotKey(IntPtr hWnd, int id, int fsModifiers, int vk);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        private readonly int id;
        private readonly Action action;
        private bool registered;

        public HotKey(int id, Action action)
        {
            this.id = id;
            this.action = action;
            CreateHandle(new CreateParams());
        }

        /// <summary>默认快捷键：**Ctrl + Alt + S**</summary>
        public bool RegisterDefault()
        {
            return Register(MOD_CONTROL | MOD_ALT, VK_S);
        }

        /// <summary>注册；失败返回 false（不抛），不影响调用方继续运行</summary>
        public bool Register(int modifiers, int virtualKey)
        {
            if (registered) return true;
            try
            {
                registered = RegisterHotKey(Handle, id, modifiers, virtualKey);
            }
            catch (Exception)
            {
                registered = false;
            }
            return registered;
        }

        /// <summary>注销（幂等）；退出时必须调用</summary>
        public void Unregister()
        {
            if (!registered) return;
            registered = false;
            try
            {
                UnregisterHotKey(Handle, id);
            }
            catch (Exception)
            {
                // 退出路径不抛
            }
        }

        /// <summary>
        /// 触发一次命令。与**收到 `WM_HOTKEY` 时的行为完全一致**（同一个 action），
        /// 供自动化测试与手动触发使用 —— 测试因此不需要真实按下按键。
        /// </summary>
        public void Trigger()
        {
            var handler = action;
            if (handler != null) handler();
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_HOTKEY && m.WParam.ToInt32() == id)
            {
                Trigger();
            }
            base.WndProc(ref m);
        }

        public void Dispose()
        {
            Unregister();
            DestroyHandle();
        }
    }
}
