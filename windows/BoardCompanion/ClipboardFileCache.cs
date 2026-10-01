using System;
using System.IO;
using System.Text;

namespace BoardCompanion
{
    /// <summary>
    /// Windows 接收远端文件时的**受控临时文件**（Phase 1-H）。
    ///
    /// 为什么必须有：Windows 的文件剪贴板是 `CF_HDROP`，**只能放真实路径**，
    /// 没法像 Android 那样放一个内存/URI 引用 —— 所以要让用户能在别的程序里粘贴，
    /// 就必须落一个临时文件。它**不是永久缓存**。
    ///
    /// 生命周期（与 Android 图片同款规则）：
    /// - **当前剪贴板正在引用的那个文件，永不删**（同步完成 / 下次启动 / 退出都不删）；
    /// - 剪贴板换成别的内容后，旧文件变成**清理候选**；
    /// - 只有「无引用 **且** 已超过 [TtlMs]」才真正删除；
    /// - 退出时也只清这类，绝不删当前引用的那个。
    /// </summary>
    public static class ClipboardFileCache
    {
        /// <summary>缓存目录：%TEMP%\BoardCompanion\clipboard-files\</summary>
        public static string Directory
        {
            get { return Path.Combine(Path.GetTempPath(), "BoardCompanion", "clipboard-files"); }
        }

        /// <summary>无引用文件的保留时长（1 小时，与历史生命周期一致）</summary>
        public const long TtlMs = 60L * 60L * 1000L;

        /// <summary>上限判定（与两端统一的 10 MiB 一致）</summary>
        public static bool WithinLimit(long bytes)
        {
            return bytes > 0 && bytes <= ClipboardPayload.MAX_FILE_BYTES;
        }

        /// <summary>把接收到的字节写成缓存文件；返回路径，失败返回 null</summary>
        public static string Write(string hash, string extension, byte[] bytes)
        {
            try
            {
                System.IO.Directory.CreateDirectory(Directory);
                var name = (string.IsNullOrEmpty(hash) ? "board" : hash) + NormalizeExtension(extension);
                var path = Path.Combine(Directory, name);
                File.WriteAllBytes(path, bytes ?? new byte[0]);
                return path;
            }
            catch (Exception)
            {
                return null;
            }
        }

        /// <summary>
        /// 扩展名安全化：只保留字母数字（最多 8 位），统一小写并带前导点。
        /// 传进来的可能是任意字符串（含路径分隔符 / 控制字符），一律过滤。
        /// </summary>
        public static string NormalizeExtension(string extension)
        {
            if (string.IsNullOrEmpty(extension)) return string.Empty;
            var start = extension[0] == '.' ? 1 : 0;
            var builder = new StringBuilder();
            for (int i = start; i < extension.Length && builder.Length < 8; i++)
            {
                char c = extension[i];
                var ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
                if (ok) builder.Append(c);
            }
            return builder.Length == 0 ? string.Empty : "." + builder.ToString().ToLowerInvariant();
        }

        /// <summary>
        /// 清理**无引用且已过期**的缓存文件。
        /// [protectedPath] 是**当前剪贴板正在引用**的文件，**永不删**。
        /// </summary>
        public static int Cleanup(string protectedPath, long nowTicks, long ttlMs)
        {
            var removed = 0;
            try
            {
                if (!System.IO.Directory.Exists(Directory)) return 0;
                foreach (var file in System.IO.Directory.GetFiles(Directory))
                {
                    if (!string.IsNullOrEmpty(protectedPath) &&
                        string.Equals(file, protectedPath, StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    var ageMs = (nowTicks - File.GetLastWriteTimeUtc(file).Ticks) / TimeSpan.TicksPerMillisecond;
                    if (ageMs < ttlMs) continue;
                    try
                    {
                        File.Delete(file);
                        removed++;
                    }
                    catch (Exception)
                    {
                        // 被占用等 → 下次再清
                    }
                }
            }
            catch (Exception)
            {
                // 清理失败绝不影响功能
            }
            return removed;
        }
    }
}
