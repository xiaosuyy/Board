using System;
using System.Drawing;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// 设置窗口：WebDAV 地址 / 账号 / 密码（默认隐藏，可切换显示）/ 设备名称 / 自动同步 /
    /// 允许未加密 HTTP（附风险说明）。按钮：保存、测试连接、立即同步、隐私说明。
    ///
    /// 密码只经 DPAPI 保存，窗口关闭后不保留。
    /// </summary>
    public sealed class SettingsForm : Form
    {
        private readonly CredentialStore store = new CredentialStore();
        private readonly ClipboardSyncEngine engine;
        private readonly Action onSaved;

        private readonly TextBox urlBox = new TextBox();
        private readonly TextBox userBox = new TextBox();
        private readonly TextBox passBox = new TextBox();
        private readonly TextBox dirBox = new TextBox();
        private readonly Label pathPreview = new Label();
        private readonly TextBox nameBox = new TextBox();
        private readonly CheckBox autoBox = new CheckBox();
        private readonly CheckBox clearBox = new CheckBox();
        private readonly CheckBox autoStartBox = new CheckBox();
        private readonly Label statusLabel = new Label();
        private bool passwordVisible;

        public SettingsForm(ClipboardSyncEngine engine, Action onSaved)
        {
            this.engine = engine;
            this.onSaved = onSaved;

            Text = "Board Companion 设置";
            Width = 520;
            Height = 480;
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;

            var panel = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 3,
                Padding = new Padding(14),
                AutoScroll = true,
            };
            panel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 110));
            panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            panel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 60));

            AddRow(panel, "WebDAV 地址", urlBox);
            AddRow(panel, "账号", userBox);

            passBox.UseSystemPasswordChar = true;
            AddRow(panel, "密码", passBox);
            var eye = new Button { Text = "显示", Width = 52 };
            eye.Click += (s, e) =>
            {
                passwordVisible = !passwordVisible;
                passBox.UseSystemPasswordChar = !passwordVisible;
                eye.Text = passwordVisible ? "隐藏" : "显示";
            };
            panel.Controls.Add(eye, 2, panel.RowCount - 1);

            AddRow(panel, "同步目录", dirBox);

            pathPreview.AutoSize = true;
            pathPreview.ForeColor = Color.DimGray;
            pathPreview.MaximumSize = new Size(460, 0);
            panel.Controls.Add(new Label { Text = "实际路径", AutoSize = true, ForeColor = Color.DimGray, Anchor = AnchorStyles.Left }, 0, panel.RowCount);
            panel.Controls.Add(pathPreview, 1, panel.RowCount);
            panel.RowCount++;
            urlBox.TextChanged += (s, e) => UpdatePathPreview();
            dirBox.TextChanged += (s, e) => UpdatePathPreview();

            AddRow(panel, "设备名称", nameBox);

            autoBox.Text = "自动同步（启动时同步一次，剪贴板变化时同步）";
            autoBox.AutoSize = true;
            panel.Controls.Add(autoBox, 1, panel.RowCount);
            panel.RowCount++;

            clearBox.Text = "允许未加密的 HTTP（仅限可信局域网）";
            clearBox.AutoSize = true;
            panel.Controls.Add(clearBox, 1, panel.RowCount);
            panel.RowCount++;

            var risk = new Label
            {
                Text = "HTTP 未加密，仅建议用于可信的局域网环境（例如自建 NAS）。" +
                       "默认只允许 https://；公网 HTTP 地址会被直接拒绝。",
                AutoSize = true,
                MaximumSize = new Size(460, 0),
                ForeColor = Color.DimGray,
            };
            panel.Controls.Add(risk, 1, panel.RowCount);
            panel.RowCount++;

            autoStartBox.Text = "开机启动（当前用户 · 登录后在托盘后台运行）";
            autoStartBox.AutoSize = true;
            autoStartBox.Checked = AutoStart.IsEnabled();
            panel.Controls.Add(autoStartBox, 1, panel.RowCount);
            panel.RowCount++;

            var buttons = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill };
            buttons.Controls.Add(MakeButton("保存", (s, e) => Save()));
            buttons.Controls.Add(MakeButton("测试连接", (s, e) => TestConnection()));
            buttons.Controls.Add(MakeButton("立即同步", (s, e) => { Save(); engine.SyncNow(); }));
            buttons.Controls.Add(MakeButton("隐私说明", (s, e) => MessageBox.Show(this,
                "• 同步需要联网，剪贴板正文会发送给你自己配置的 WebDAV 服务器。\n" +
                "• Board Windows 不在本地保存剪贴板正文：不写文件、不写注册表、不写日志。\n" +
                "• 密码使用 Windows DPAPI（当前用户）加密保存，不写明文。\n" +
                "• 服务端的数据处理与留存由你自己的服务器决定。",
                "隐私说明")));
            buttons.Controls.Add(MakeButton("高级 · 日志", (s, e) =>
            {
                using (var log = new LogForm()) log.ShowDialog(this);
            }));
            panel.Controls.Add(buttons, 1, panel.RowCount);
            panel.RowCount++;

            statusLabel.AutoSize = true;
            statusLabel.ForeColor = Color.DimGray;
            panel.Controls.Add(statusLabel, 1, panel.RowCount);

            Controls.Add(panel);

            var config = store.Load();
            urlBox.Text = config.baseUrl;
            userBox.Text = config.username;
            dirBox.Text = config.syncDir;
            nameBox.Text = config.deviceName;
            autoBox.Checked = config.autoSync;
            clearBox.Checked = config.allowCleartextHttp;
            passBox.Text = store.LoadPassword();
            UpdatePathPreview();
        }

        /// <summary>实时显示最终请求路径，填错一眼可见（这是避免"两端各写各的"最有效的办法）</summary>
        private void UpdatePathPreview()
        {
            var url = urlBox.Text.Trim().TrimEnd('/');
            if (url.Length == 0)
            {
                pathPreview.Text = "（先填地址）";
                return;
            }
            var relative = ClipboardSyncEngine.ManifestPathFor(urlBox.Text, dirBox.Text);
            pathPreview.Text = url + "/" + relative;
        }

        private static void AddRow(TableLayoutPanel panel, string label, Control field)
        {
            panel.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left }, 0, panel.RowCount);
            field.Dock = DockStyle.Fill;
            panel.Controls.Add(field, 1, panel.RowCount);
            panel.RowCount++;
        }

        private static Button MakeButton(string text, EventHandler onClick)
        {
            var button = new Button { Text = text, AutoSize = true, Margin = new Padding(4) };
            button.Click += onClick;
            return button;
        }

        private void Save()
        {
            var config = store.Load();
            config.baseUrl = urlBox.Text.Trim();
            config.username = userBox.Text.Trim();
            config.syncDir = dirBox.Text.Trim();
            config.deviceName = nameBox.Text.Trim();
            config.autoSync = autoBox.Checked;
            config.allowCleartextHttp = clearBox.Checked;

            var problem = WebDavUrlPolicy.Evaluate(config.baseUrl, config.allowCleartextHttp);
            if (!string.IsNullOrEmpty(config.baseUrl) && problem != null)
            {
                statusLabel.Text = "地址被拒绝：" + problem;
                MessageBox.Show(this, problem, "传输安全");
                return;
            }

            store.Save(config, passBox.Text);
            engine.Apply(config, passBox.Text);
            // 开机自启：只在用户显式勾选/取消时改注册表（HKCU Run，无需管理员）
            AutoStart.SetEnabled(autoStartBox.Checked);
            statusLabel.Text = "已保存";
            if (onSaved != null) onSaved();
        }

        private void TestConnection()
        {
            Save();
            statusLabel.Text = "正在测试连接…";
            var config = store.Load();
            var client = new WebDavClient(config.baseUrl, config.username, store.LoadPassword());
            // 与同步引擎走**同一个**路径：<地址>/<同步目录>/SyncClipboard.json
            var manifest = ClipboardSyncEngine.ManifestPathFor(config.baseUrl, config.syncDir);
            var response = client.Get(manifest);
            if (response.IsOk)
            {
                statusLabel.Text = "连接成功（HTTP " + response.StatusCode + "）";
            }
            else if (response.NotFound)
            {
                statusLabel.Text = "连上了，但云端没有 SyncClipboard.json。" +
                                   "若是新服务器，第一次同步会创建；若目录不存在也会是这个结果——" +
                                   "请对照上方「实际路径」，确认地址（根）和同步目录两端填法一致";
            }
            else
            {
                statusLabel.Text = "连接失败：" + (response.Error ?? "未知");
            }
        }
    }
}
