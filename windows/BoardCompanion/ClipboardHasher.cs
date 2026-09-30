using System;
using System.Security.Cryptography;
using System.Text;

namespace BoardCompanion
{
    /// <summary>
    /// 内容哈希。
    ///
    /// **必须与 Android 端 `clipboard/ClipboardHasher.kt` 完全一致**：
    ///     hash = SHA-256( content 的 UTF-8 字节 ) 的小写十六进制（无分隔符）
    ///
    /// 明确**不做**：trim / CRLF↔LF 转换 / Unicode 规范化 / 大小写转换。
    /// Windows 侧只做一件事：把剪贴板的 UTF-16 字符串按 UTF-8 编码后哈希。
    /// （UTF-16 → UTF-8 是标准转换，与 Android 的 `String.toByteArray(UTF_8)` 结果一致。）
    /// </summary>
    public static class ClipboardHasher
    {
        public static string Hash(string content)
        {
            using (var sha = new SHA256Managed())
            {
                byte[] digest = sha.ComputeHash(Encoding.UTF8.GetBytes(content ?? string.Empty));
                var sb = new StringBuilder(digest.Length * 2);
                foreach (byte b in digest)
                {
                    sb.Append(b.ToString("x2")); // 小写十六进制，与 Android 的 "%02x" 一致
                }
                return sb.ToString();
            }
        }

        /// <summary>
        /// 任意字节的 SHA-256（小写十六进制）。
        ///
        /// 图片用它：hash = SHA-256(**PNG 字节**)，与 Android 的
        /// `ClipboardHasher.hashBytes` 形状一致（但两端对同一图片编出的 PNG 字节可能不同，
        /// 因此**不做跨平台图片 hash 去重** —— 见 Phase 1-E 说明）。
        /// </summary>
        public static string HashBytes(byte[] bytes)
        {
            using (var sha = new SHA256Managed())
            {
                byte[] digest = sha.ComputeHash(bytes ?? new byte[0]);
                var sb = new StringBuilder(digest.Length * 2);
                foreach (byte b in digest)
                {
                    sb.Append(b.ToString("x2"));
                }
                return sb.ToString();
            }
        }

        /// <summary>
        /// UTF-8 编码后的字节数。语义与 Android 的 `ClipboardHasher.utf8Length` 一致。
        /// </summary>
        public static long Utf8Length(string content)
        {
            if (string.IsNullOrEmpty(content)) return 0L;
            long bytes = 0L;
            for (int i = 0; i < content.Length; i++)
            {
                int code = content[i];
                if (code < 0x80)
                {
                    bytes += 1;
                }
                else if (code < 0x800)
                {
                    bytes += 2;
                }
                else if (char.IsHighSurrogate(content[i]) && i + 1 < content.Length)
                {
                    bytes += 4;
                    i++;
                }
                else
                {
                    bytes += 3;
                }
            }
            return bytes;
        }
    }
}
