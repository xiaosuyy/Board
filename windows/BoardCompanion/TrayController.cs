using System;
using System.Drawing;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// 托盘：左键打开极简状态面板；右键菜单提供打开/暂停恢复/立即同步/设置/退出。
    /// 不做复杂窗口。
    /// </summary>
    public sealed class TrayController : IDisposable
    {
        private readonly NotifyIcon icon;
        private readonly ClipboardSyncEngine engine;
        private readonly ToolStripMenuItem statusItem;
        private readonly ToolStripMenuItem pauseItem;

        public TrayController(ClipboardSyncEngine engine, Action openPanel, Action openSettings, Action exit)
        {
            this.engine = engine;

            statusItem = new ToolStripMenuItem("状态：未配置") { Enabled = false };
            pauseItem = new ToolStripMenuItem("暂停同步", null, (s, e) =>
            {
                engine.SetPaused(!engine.Paused);
                UpdateState();
            });

            var menu = new ContextMenuStrip();
            menu.Items.Add(new ToolStripMenuItem("Board Companion") { Enabled = false });
            menu.Items.Add(statusItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("打开 Board Companion", null, (s, e) => openPanel()));
            menu.Items.Add(pauseItem);
            menu.Items.Add(new ToolStripMenuItem("立即同步", null, (s, e) => engine.SyncNow()));
            menu.Items.Add(new ToolStripMenuItem("设置", null, (s, e) => openSettings()));
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem("退出", null, (s, e) => exit()));

            icon = new NotifyIcon
            {
                Icon = SystemIcons.Application,
                Text = "Board",
                Visible = true,
                ContextMenuStrip = menu,
            };
            // 左键单击打开状态面板（不弹主窗口以外的东西）
            icon.MouseClick += (s, e) =>
            {
                if (e.Button == MouseButtons.Left) openPanel();
            };
            icon.DoubleClick += (s, e) => openPanel();

            engine.StateChanged += (s, e) => UpdateState();
            UpdateState();
        }

        public void UpdateState()
        {
            string label;
            switch (engine.State)
            {
                case SyncState.NotConfigured: label = "未配置"; break;
                case SyncState.Syncing: label = "同步中"; break;
                case SyncState.Paused: label = "已暂停"; break;
                case SyncState.Error: label = "最近同步失败"; break;
                default: label = "已连接"; break;
            }
            statusItem.Text = "状态：" + label;
            pauseItem.Text = engine.Paused ? "恢复同步" : "暂停同步";
            icon.Text = "Board · " + label;
        }

        public void Dispose()
        {
            icon.Visible = false;
            icon.Dispose();
        }
    }
}
