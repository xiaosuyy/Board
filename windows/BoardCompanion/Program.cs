using System;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// Board Windows Companion（v2.0，Phase 1-C：后台托盘化）。
    ///
    /// 编译为 **WinExe**：启动**不出现控制台黑窗口**；默认**后台运行**，只在托盘可见；
    /// 需要时才弹出极简状态面板（托盘左键 / 菜单）。关闭面板只是隐藏，**不退出**。
    /// 只有托盘/面板里的「退出」才真正结束进程。
    ///
    /// 日志：写 `%APPDATA%\BoardCompanion\companion.log`（不含剪贴板正文），
    /// 界面入口在 设置 → 高级 → 日志。
    ///
    /// 自动化入口（沿用 Phase 1-B）：
    ///   --sync-once   读配置 → 做一次同步 → 退出（0=成功，1=失败）
    /// </summary>
    public static class Program
    {
        private const string MutexName = "BoardCompanion.SingleInstance";

        /// <summary>后台拉取间隔：60 秒（低频）。绝不使用秒级轮询。</summary>
        private const int BackgroundPullMs = 60000;

        private static System.Windows.Forms.Timer backgroundPull;

        private static ClipboardMonitor monitor;
        private static TrayController tray;
        private static ClipboardSyncEngine engine;
        private static StatusForm statusForm;
        private static SettingsForm settingsForm;
        private static DateTime? lastSyncAt;

        [STAThread]
        private static int Main(string[] args)
        {
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
                    // 已有实例：这里没有控制台，也不再弹窗打扰；直接退出
                    return 0;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                var store = new CredentialStore();
                var config = store.Load();
                engine = new ClipboardSyncEngine(config, store.LoadPassword());
                engine.StateChanged += (s, e) =>
                {
                    if (engine.State == SyncState.Idle) lastSyncAt = DateTime.Now;
                };

                monitor = new ClipboardMonitor();
                monitor.ClipboardChanged += (s, e) => engine.OnClipboardChanged();
                monitor.Start();

                Action openSettings = () =>
                {
                    if (settingsForm == null || settingsForm.IsDisposed)
                    {
                        settingsForm = new SettingsForm(engine, () => { if (tray != null) tray.UpdateState(); });
                    }
                    if (!settingsForm.Visible) settingsForm.Show();
                    settingsForm.Activate();
                };

                Action openPanel = () =>
                {
                    if (statusForm == null || statusForm.IsDisposed)
                    {
                        statusForm = new StatusForm(engine, () => lastSyncAt, openSettings);
                        statusForm.OnExitRequested = ExitApplication;
                    }
                    if (!statusForm.Visible) statusForm.Show();
                    statusForm.Activate();
                    statusForm.Refresh2();
                };

                tray = new TrayController(engine, openPanel, openSettings, ExitApplication);

                // 低频后台拉取：让 PC 在"没人点同步"时也能收到其它端的内容。
                // 60 秒一次（**不做秒级轮询**）；本机剪贴板没变时这一趟只会 GET + 必要时写回，
                // 不会产生多余 PUT。暂停时整体跳过。
                backgroundPull = new System.Windows.Forms.Timer { Interval = BackgroundPullMs };
                backgroundPull.Tick += (s, e) =>
                {
                    if (config.autoSync && !engine.Paused) engine.SyncNow();
                };
                if (config.autoSync) backgroundPull.Start();

                Log.Info("started deviceId=" + engine.DeviceId +
                         " configured=" + (!string.IsNullOrEmpty(config.baseUrl)) + " mode=winexe-tray" +
                         " backgroundPull=" + config.autoSync);

                if (config.autoSync) engine.SyncNow();

                // 无主窗体：消息循环照常运行（托盘 + 剪贴板通知都要它）
                Application.Run();

                Cleanup();
                return 0;
            }
        }

        private static void ExitApplication()
        {
            Cleanup();
            Application.Exit();
        }

        private static bool cleaned;

        /// <summary>
        /// 收尾。**幂等**：只有第一次真正清理并打印 `exited`，
        /// 之后的调用直接返回 false（修掉退出时打印两次的问题）。
        /// </summary>
        public static bool Cleanup()
        {
            if (cleaned) return false;
            cleaned = true;
            try
            {
                if (backgroundPull != null) { backgroundPull.Stop(); backgroundPull.Dispose(); backgroundPull = null; }
                if (monitor != null) { monitor.Stop(); monitor.Dispose(); monitor = null; }
                if (engine != null) { engine.Dispose(); engine = null; }
                if (tray != null) { tray.Dispose(); tray = null; }
                Log.Info("exited");
            }
            catch (Exception)
            {
                // 退出路径不抛
            }
            return true;
        }

        /// <summary>无界面：做一次同步后退出（供自动化/自检使用）</summary>
        private static int RunSyncOnce()
        {
            var store = new CredentialStore();
            var config = store.Load();
            if (string.IsNullOrEmpty(config.baseUrl))
            {
                Log.Warn("sync-once aborted: not configured");
                return 1;
            }
            var problem = WebDavUrlPolicy.Evaluate(config.baseUrl, config.allowCleartextHttp);
            if (problem != null)
            {
                Log.Warn("sync-once aborted: url policy rejected");
                return 1;
            }

            var localEngine = new ClipboardSyncEngine(config, store.LoadPassword());
            var task = localEngine.SyncNowAsync();
            var finished = task.Wait(30000);
            Thread.Sleep(300);
            Log.Info("sync-once finished=" + finished + " state=" + localEngine.State);
            var ok = finished && localEngine.State != SyncState.Error && localEngine.State != SyncState.NotConfigured;
            localEngine.Dispose();
            return ok ? 0 : 1;
        }
    }
}
