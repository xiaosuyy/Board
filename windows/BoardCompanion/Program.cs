using System;
using System.IO;
using System.Text;

namespace BoardCompanion
{
    /// <summary>
    /// Board Windows Companion（v2.0 Phase 1-A 骨架）。
    ///
    /// 本阶段**只**包含：SyncClipboard 协议层、Hash、WebDAV 基础层、URL 安全策略、DPAPI 凭据。
    /// **尚未**实现：ClipboardMonitor / 自动同步 Engine / 托盘 / 设置界面。
    ///
    /// 现在运行的行为：加载配置（首次会生成并持久化 deviceId）→ 打印状态 → 正常退出。
    /// 这样即使只有骨架，也能"启动后正常退出"。
    ///
    /// 隐私：本程序**不保存任何剪贴板正文**；日志只打印长度/状态/耗时。
    /// </summary>
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            Console.OutputEncoding = Encoding.UTF8;
            Console.WriteLine("Board Windows Companion · v2.0 Phase 1-A");

            BoardWindowsConfig config;
            string password;
            try
            {
                var store = new CredentialStore();
                config = store.Load();
                password = store.LoadPassword();
            }
            catch (Exception)
            {
                Console.WriteLine("配置加载失败");
                return 1;
            }

            var configured = !string.IsNullOrEmpty(config.baseUrl);
            Console.WriteLine("deviceId=" + config.deviceId);
            Console.WriteLine("WebDAV=" + (configured ? "已配置" : "未配置"));
            Console.WriteLine("密码=" + (string.IsNullOrEmpty(password) ? "未保存" : "已保存（DPAPI）"));

            if (configured)
            {
                var problem = WebDavUrlPolicy.Evaluate(config.baseUrl, config.allowCleartextHttp);
                Console.WriteLine(problem == null
                    ? "传输安全=通过（" + (WebDavUrlPolicy.IsHttps(config.baseUrl) ? "HTTPS" : "HTTP·已明确允许") + "）"
                    : "传输安全=拒绝：" + problem);
            }

            Console.WriteLine("尚未实现：剪贴板监听 / 自动同步 / 托盘 / 设置（Phase 1-B）");
            return 0;
        }
    }
}
