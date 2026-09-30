using System;
using System.Drawing;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// 极简状态面板：状态 / 连接 / 最后同步时间 / 暂停状态。
    ///
    /// **关闭窗口只是隐藏**（后台继续同步）；只有托盘「退出」才真正结束进程。
    /// 视觉：黑/白/灰为主，蓝色只用于"正在同步"这一处强调，不做卡片堆叠。
    /// </summary>
    public sealed class StatusForm : Form
    {
        private static readonly Color Accent = Color.FromArgb(0x2F, 0x6F, 0xED);

        private readonly ClipboardSyncEngine engine;
        private readonly Func<DateTime?> lastSyncProvider;
        private readonly Action openSettings;

        private readonly Label stateValue = new Label();
        private readonly Label windowsValue = new Label();
        private readonly Label androidValue = new Label();
        private readonly Label lastValue = new Label();
        private readonly Label pauseValue = new Label();
        private readonly Button pauseButton = new Button();

        public StatusForm(ClipboardSyncEngine engine, Func<DateTime?> lastSyncProvider, Action openSettings)
        {
            this.engine = engine;
            this.lastSyncProvider = lastSyncProvider;
            this.openSettings = openSettings;

            Text = "Board Companion";
            Width = 372;
            Height = 268;
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = true;
            BackColor = Color.White;
            Font = new Font("Microsoft YaHei UI", 9f);

            var root = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 2,
                Padding = new Padding(14, 12, 14, 10),
            };
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 96));
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

            AddRow(root, "同步状态", stateValue);
            AddRow(root, "Windows 端", windowsValue);
            AddRow(root, "Android 端", androidValue);
            AddRow(root, "最后同步", lastValue);
            AddRow(root, "暂停", pauseValue);

            var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
            buttons.Controls.Add(MakeButton("立即同步", (s, e) => engine.SyncNow()));
            pauseButton.Click += (s, e) =>
            {
                engine.SetPaused(!engine.Paused);
                Refresh2();
            };
            buttons.Controls.Add(pauseButton);
            buttons.Controls.Add(MakeButton("设置", (s, e) => openSettings()));
            buttons.Controls.Add(MakeButton("退出", (s, e) =>
            {
                if (MessageBox.Show(this, "退出后停止同步（剪贴板内容不会被清空）。", "Board",
                        MessageBoxButtons.OKCancel) == DialogResult.OK)
                {
                    if (OnExitRequested != null) OnExitRequested();
                }
            }));
            root.Controls.Add(buttons, 1, root.RowCount);
            root.RowCount++;

            Controls.Add(root);

            engine.StateChanged += (s, e) => Refresh2();
            Refresh2();
        }

        /// <summary>托盘/此处点「退出」时由宿主处理真正的退出</summary>
        public Action OnExitRequested { get; set; }

        private static void AddRow(TableLayoutPanel panel, string label, Control value)
        {
            panel.Controls.Add(new Label
            {
                Text = label,
                AutoSize = true,
                ForeColor = Color.DimGray,
                Anchor = AnchorStyles.Left,
                Margin = new Padding(0, 4, 0, 4),
            }, 0, panel.RowCount);
            value.AutoSize = true;
            value.Anchor = AnchorStyles.Left;
            value.Margin = new Padding(0, 4, 0, 4);
            panel.Controls.Add(value, 1, panel.RowCount);
            panel.RowCount++;
        }

        private static Button MakeButton(string text, EventHandler onClick)
        {
            var button = new Button
            {
                Text = text,
                AutoSize = true,
                FlatStyle = FlatStyle.System,
                Margin = new Padding(0, 0, 6, 0),
            };
            button.Click += onClick;
            return button;
        }

        public void Refresh2()
        {
            string state;
            Color color = Color.Black;
            switch (engine.State)
            {
                case SyncState.NotConfigured: state = "未配置"; color = Color.DimGray; break;
                case SyncState.Syncing: state = "同步中…"; color = Accent; break;
                case SyncState.Paused: state = "已暂停"; color = Color.DimGray; break;
                case SyncState.Error: state = "同步失败（见日志）"; color = Color.FromArgb(0xB3, 0x26, 0x1E); break;
                default: state = "已连接"; break;
            }
            stateValue.Text = state;
            stateValue.ForeColor = color;

            var last = lastSyncProvider == null ? null : lastSyncProvider();
            windowsValue.Text = last.HasValue ? "运行中" : "运行中（尚未同步）";
            androidValue.Text = engine.State == SyncState.NotConfigured ? "未连接" : "已连接（同一 WebDAV）";
            lastValue.Text = last.HasValue ? last.Value.ToString("yyyy-MM-dd HH:mm:ss") : "—";
            pauseValue.Text = engine.Paused ? "已暂停" : "未暂停";
            pauseButton.Text = engine.Paused ? "恢复同步" : "暂停同步";
        }

        /// <summary>关闭 = 隐藏，后台继续同步</summary>
        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
                return;
            }
            base.OnFormClosing(e);
        }
    }
}
