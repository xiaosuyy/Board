using System;

namespace BoardCompanion
{
    /// <summary>
    /// SyncClipboard 协议的一条记录（字段名与 Android 的
    /// `clipboard/SyncClipboardMapper.kt` 的 `SyncClipboardPayload` 逐字对应）。
    ///
    /// 第一阶段 Windows 只实现 **TEXT 内联**：
    ///   type = "Text", hasData = false, text = 正文
    /// 超过 256 KiB 的文本**不做**（不偷偷截断、也不只哈希一部分）。
    /// </summary>
    public sealed class ClipboardPayload
    {
        public const string TYPE_TEXT = "Text";
        public const string TYPE_IMAGE = "Image";
        public const string TYPE_FILE = "File";
        public const string TYPE_GROUP = "Group";

        /// <summary>与 Android 完全一致的内联上限（超出走 /file/，Windows 第一阶段不实现）</summary>
        public const int INLINE_TEXT_MAX_BYTES = 256 * 1024;

        public string type { get; set; }
        public string hash { get; set; }
        public string text { get; set; }
        public bool hasData { get; set; }
        public string dataName { get; set; }
        public long size { get; set; }

        /// <summary>
        /// Board 侧扩展：Windows 写入自己的稳定 deviceId，方便 Android 显示「云端 · Windows」。
        /// Android 只读不写，所以这是向前兼容字段。
        /// </summary>
        public string device { get; set; }

        public ClipboardPayload()
        {
            type = TYPE_TEXT;
            hash = string.Empty;
            text = null;
            hasData = false;
            dataName = string.Empty;
            size = 0L;
            device = null;
        }

        /// <summary>只有 Text 才处理；Image / File / Group 一律安全忽略（不报错、不上传）</summary>
        public bool IsSupported
        {
            get { return string.Equals(type, TYPE_TEXT, StringComparison.Ordinal); }
        }
    }

    /// <summary>解析结果。任何一种"数据不对"都只返回明确原因，绝不带上正文。</summary>
    public enum PayloadParseStatus
    {
        Ok = 0,
        InvalidJson = 1,
        MissingType = 2,
        MissingHash = 3,
        UnsupportedType = 4,
        TextTooLarge = 5,
    }

    public sealed class PayloadParseResult
    {
        public PayloadParseStatus Status { get; private set; }
        public ClipboardPayload Payload { get; private set; }

        private PayloadParseResult(PayloadParseStatus status, ClipboardPayload payload)
        {
            Status = status;
            Payload = payload;
        }

        public static PayloadParseResult Ok(ClipboardPayload payload)
        {
            return new PayloadParseResult(PayloadParseStatus.Ok, payload);
        }

        public static PayloadParseResult Fail(PayloadParseStatus status)
        {
            return new PayloadParseResult(status, null);
        }
    }
}
