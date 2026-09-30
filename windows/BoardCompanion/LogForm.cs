using System;
using System.Drawing;
using System.IO;
using System.Text;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// 日志查看（设置 → 高级 → 日志）。
    ///
    /// 只读显示 `companion.log` 的最后若干行。日志本身**不含剪贴板正文**
    /// （只有 chars/status/elapsedMs 这类字段），这里也不做任何编辑/保存功能。
    /// </summary>
    public sealed class LogForm : Form
    {
        private const int MaxLines = 200;

        private readonly TextBox box = new TextBox();

        public LogForm()
        {
            Text = "Board Companion · 日志";
            Width = 720;
            Height = 420;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.White;
            Font = new Font("Consolas", 9f);
            MinimizeBox = false;

            box.Multiline = true;
            box.ReadOnly = true;
            box.ScrollBars = ScrollBars.Both;
            box.WordWrap = false;
            box.Dock = DockStyle.Fill;
            box.BackColor = Color.White;
            box.ForeColor = Color.FromArgb(0x22, 0x22, 0x22);
            box.BorderStyle = BorderStyle.None;

            var hint = new Label
            {
                Dock = DockStyle.Bottom,
                Height = 26,
                Text = "  日志不包含剪贴板正文；仅记录长度、状态与耗时。",
                ForeColor = Color.DimGray,
                TextAlign = ContentAlignment.MiddleLeft,
            };

            var buttons = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 36, FlowDirection = FlowDirection.RightToLeft };
            var refresh = new Button { Text = "刷新", AutoSize = true, Margin = new Padding(6) };
            refresh.Click += (s, e) => Reload();
            var close = new Button { Text = "关闭", AutoSize = true, Margin = new Padding(6) };
            close.Click += (s, e) => Close();
            buttons.Controls.Add(refresh);
            buttons.Controls.Add(close);

            Controls.Add(box);
            Controls.Add(hint);
            Controls.Add(buttons);
            Reload();
        }

        private void Reload()
        {
            try
            {
                var path = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "BoardCompanion", "companion.log");
                if (!File.Exists(path))
                {
                    box.Text = "（还没有日志）";
                    return;
                }
                var lines = File.ReadAllLines(path, Encoding.UTF8);
                var start = Math.Max(0, lines.Length - MaxLines);
                var sb = new StringBuilder();
                for (var i = start; i < lines.Length; i++) sb.AppendLine(lines[i]);
                box.Text = sb.ToString();
                box.SelectionStart = box.TextLength;
                box.ScrollToCaret();
            }
            catch (Exception)
            {
                box.Text = "（读取日志失败）";
            }
        }
    }
}
