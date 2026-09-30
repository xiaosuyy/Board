using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;

namespace BoardCompanion
{
    /// <summary>
    /// 剪贴板图片的编解码（**只做内存 ↔ 字节**，不落盘）。
    ///
    /// 统一用 PNG，保留透明通道，不做 JPEG/WebP 压缩，不改画质。
    /// 依赖的是 .NET Framework 自带的 `System.Drawing`（in-box），**零 NuGet**。
    ///
    /// 确定性：同一张 Bitmap 反复编码得到**相同字节**（PNG 无时间戳等易变字段），
    /// 因此“写入剪贴板后读回再编码”得到的 hash 是稳定的 —— 这正是回环抑制所依赖的。
    /// </summary>
    public static class ClipboardImage
    {
        /// <summary>图片在 `/file/` 下的文件名（与 Android 的 `dataNameForImage` 一致）</summary>
        public static string DataName(string hash)
        {
            return (string.IsNullOrEmpty(hash) ? "board" : hash) + ".png";
        }

        /// <summary>大小是否在统一上限内（原始输入与最终 PNG 都要过这一关）</summary>
        public static bool WithinLimit(long bytes)
        {
            return bytes > 0 && bytes <= ClipboardPayload.MAX_IMAGE_BYTES;
        }

        /// <summary>图片 → PNG 字节</summary>
        public static byte[] EncodePng(Image image)
        {
            if (image == null) return null;
            using (var stream = new MemoryStream())
            {
                image.Save(stream, ImageFormat.Png);
                return stream.ToArray();
            }
        }

        /// <summary>PNG 字节 → Bitmap（失败返回 null，不抛）</summary>
        public static Bitmap DecodePng(byte[] png)
        {
            if (png == null || png.Length == 0) return null;
            try
            {
                using (var stream = new MemoryStream(png, writable: false))
                using (var image = Image.FromStream(stream))
                {
                    // 复制一份，避免依赖已释放的流
                    return new Bitmap(image);
                }
            }
            catch (Exception)
            {
                return null;
            }
        }
    }
}
