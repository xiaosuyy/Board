using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

namespace BoardCompanion
{
    public sealed class BoardWindowsConfig
    {
        public string baseUrl { get; set; }
        public string username { get; set; }
        public string syncDir { get; set; }
        public string deviceName { get; set; }
        public bool autoSync { get; set; }
        public bool allowCleartextHttp { get; set; }
        public string deviceId { get; set; }

        /// <summary>
        /// **本地上传范围**（Phase 1-H）：只控制 Windows 本地剪贴板的**发送/上传方向**。
        /// - [SCOPE_TEXT]（默认）：只自动上传文本；图片不上传（接收照旧）
        /// - [SCOPE_TEXT_IMAGE]：文本 + 图片都自动上传（恢复 Phase 1-E 行为）
        ///
        /// 文件**永远不自动上传**（走 Cloud Library 的显式动作）。
        /// **接收方向（Text/Image/File 的 Pull）不受本设置影响。**
        /// </summary>
        public string clipboardSyncScope { get; set; }

        public const string SCOPE_TEXT = "text";
        public const string SCOPE_TEXT_IMAGE = "text-image";

        /// <summary>
        /// 上一次同步过的内容 hash（**只存 hash，绝不存正文**）。
        ///
        /// 有了它，重启/手动同步时才能区分「剪贴板没变过（不要覆盖云端）」
        /// 与「确实有新内容要推」。与 Android 用 contentHash 判定的语义一致。
        /// </summary>
        public string lastHash { get; set; }

        public BoardWindowsConfig()
        {
            baseUrl = string.Empty;
            username = string.Empty;
            syncDir = "board";
            deviceName = string.Empty;
            autoSync = true;
            allowCleartextHttp = false;
            deviceId = null;
            lastHash = null;
            clipboardSyncScope = SCOPE_TEXT;
        }

        /// <summary>上传范围是否包含图片（默认仅文字；**只影响上传**）</summary>
        public bool uploadsImages
        {
            get
            {
                return string.Equals(clipboardSyncScope, SCOPE_TEXT_IMAGE, StringComparison.OrdinalIgnoreCase);
            }
        }
    }

    /// <summary>
    /// 配置与凭据存储。
    ///
    /// - **普通配置**（URL / 账号 / 目录 / 设备名 / deviceId / 开关）→
    ///   `%APPDATA%\BoardCompanion\config.json`（明文可读，但**绝不含密码**）；
    /// - **密码** → Windows **DPAPI**（`ProtectedData`，CurrentUser 范围）加密后单独存一个文件，
    ///   不自造加密算法、不 base64 冒充加密；
    /// - `deviceId`：首次生成 **UUID** 并持久化，之后不再变化（卸载重装会重新生成 —— 见 README）；
    ///   不使用 MAC / CPU / 硬盘序列号 / Microsoft Account / Windows 用户名。
    ///
    /// 隐私：本类**不保存任何剪贴板正文**。
    /// </summary>
    public sealed class CredentialStore
    {
        private const string PasswordEntropy = "BoardCompanion.WebDav.Password.v1";

        private readonly string directory;

        public CredentialStore()
        {
            directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "BoardCompanion");
        }

        private string ConfigPath { get { return Path.Combine(directory, "config.json"); } }
        private string SecretPath { get { return Path.Combine(directory, "webdav.secret"); } }

        public BoardWindowsConfig Load()
        {
            BoardWindowsConfig config = null;
            try
            {
                if (File.Exists(ConfigPath))
                {
                    var json = File.ReadAllText(ConfigPath, Encoding.UTF8);
                    config = new JavaScriptSerializer().Deserialize<BoardWindowsConfig>(json);
                }
            }
            catch (Exception)
            {
                config = null;
            }
            if (config == null) config = new BoardWindowsConfig();

            var id = config.deviceId;
            if (string.IsNullOrEmpty(id))
            {
                id = GenerateDeviceId();
                config.deviceId = id;
                Save(config, LoadPassword());   // 首次生成后落盘
            }
            return config;
        }

        public void Save(BoardWindowsConfig config, string password)
        {
            if (config == null) return;
            try
            {
                Directory.CreateDirectory(directory);
                if (string.IsNullOrEmpty(config.deviceId)) config.deviceId = GenerateDeviceId();
                var json = new JavaScriptSerializer().Serialize(config);
                File.WriteAllText(ConfigPath, json, new UTF8Encoding(false));
            }
            catch (Exception)
            {
                // 配置写入失败不影响运行；不记录正文
            }
            SavePassword(password);
        }

        /// <summary>密码 → DPAPI（当前用户）。空密码会清掉保存的凭据。</summary>
        public void SavePassword(string password)
        {
            try
            {
                Directory.CreateDirectory(directory);
                if (string.IsNullOrEmpty(password))
                {
                    if (File.Exists(SecretPath)) File.Delete(SecretPath);
                    return;
                }
                var entropy = Encoding.UTF8.GetBytes(PasswordEntropy);
                var enciphered = ProtectedData.Protect(
                    Encoding.UTF8.GetBytes(password), entropy, DataProtectionScope.CurrentUser);
                File.WriteAllText(SecretPath, Convert.ToBase64String(enciphered), new UTF8Encoding(false));
            }
            catch (Exception)
            {
                // 失败就当作没有保存密码，绝不退化成明文
            }
        }

        public string LoadPassword()
        {
            try
            {
                if (!File.Exists(SecretPath)) return string.Empty;
                var text = File.ReadAllText(SecretPath, Encoding.UTF8);
                var enciphered = Convert.FromBase64String(text);
                var entropy = Encoding.UTF8.GetBytes(PasswordEntropy);
                var plain = ProtectedData.Unprotect(enciphered, entropy, DataProtectionScope.CurrentUser);
                return Encoding.UTF8.GetString(plain);
            }
            catch (Exception)
            {
                return string.Empty;
            }
        }

        /// <summary>派生自随机 UUID 的稳定设备标识（不含任何用户身份信息）</summary>
        public static string GenerateDeviceId()
        {
            return "win-" + Guid.NewGuid().ToString("N").Substring(0, 16);
        }
    }
}
