using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// Board Windows Companion（v2.0）。
    ///
    /// 启动顺序：单实例 → 读凭据 → 剪贴板监听 → 同步引擎 → 托盘 → 消息循环。
    /// 自动同步开启时，启动后做一次同步（Push 本地未同步的变更 + Pull 云端收敛）。
    ///
    /// 命令行（用于自动化验证，不需要点托盘）：
    ///   --sync-once    读配置 → 做一次同步 → 退出（0=成功，1=失败）
    ///
    /// 隐私：不落盘剪贴板正文；日志只含长度/状态/耗时。
    /// </summary>
    internal static class Program
    {
        private const string MutexName = "BoardCompanion.SingleInstance";

        [STAThread]
        private static int Main(string[] args)
        {
            Console.OutputEncoding = Encoding.UTF8;

            var syncOnce = false;
            foreach (var arg in args)
            {
                if (string.Equals(arg, "--sync-once", StringComparison.OrdinalIgnoreCase)) syncOnce = true;
            }

            if (syncOnce) return RunSyncOnce();

            bool isFirst;
            using (var mutex = new Mutex(true, MutexName, out isFirst))
            {
                if (!isFirst)
                {
                    MessageBox.Show("Board Companion 已在运行（见系统托盘）。", "Board");
                    return 0;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                var store = new CredentialStore();
                var config = store.Load();
                var engine = new ClipboardSyncEngine(config, store.LoadPassword());

                var monitor = new ClipboardMonitor();
                monitor.ClipboardChanged += (s, e) => engine.OnClipboardChanged();
                monitor.Start();

                TrayController tray = null;
                SettingsForm form = null;

                Action openSettings = () =>
                {
                    if (form == null || form.IsDisposed || !form.Visible)
                    {
                        form = new SettingsForm(engine, () => tray.UpdateState());
                        form.Show();
                    }
                    else
                    {
                        form.Activate();
                    }
                };

                tray = new TrayController(engine, openSettings, () =>
                {
                    monitor.Stop();
                    monitor.Dispose();
                    tray.Dispose();
                    Application.Exit();
                });

                Log.Info("started deviceId=" + engine.DeviceId +
                         " configured=" + (!string.IsNullOrEmpty(config.baseUrl)));

                if (config.autoSync) engine.SyncNow();

                Application.Run();

                engine.Dispose();
                tray.Dispose();
                return 0;
            }
        }

        /// <summary>无界面：做一次同步后退出（供自动化/自检使用）</summary>
        private static int RunSyncOnce()
        {
            var store = new CredentialStore();
            var config = store.Load();
            if (string.IsNullOrEmpty(config.baseUrl))
            {
                Console.WriteLine("未配置 WebDAV 地址");
                return 1;
            }
            var problem = WebDavUrlPolicy.Evaluate(config.baseUrl, config.allowCleartextHttp);
            if (problem != null)
            {
                Console.WriteLine("传输安全=拒绝：" + problem);
                return 1;
            }

            var engine = new ClipboardSyncEngine(config, store.LoadPassword());
            var task = engine.SyncNowAsync();
            var finished = task.Wait(30000);
            Thread.Sleep(300); // 让日志落盘
            Log.Info("sync-once finished=" + finished + " state=" + engine.State);

            var ok = finished && engine.State != SyncState.Error && engine.State != SyncState.NotConfigured;
            Console.WriteLine("sync-once finished=" + finished + " state=" + engine.State + " ok=" + ok);
            engine.Dispose();
            return ok ? 0 : 1;
        }
    }
}
