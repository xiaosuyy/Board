using System;
using System.Collections.Generic;
using System.Web.Script.Serialization;

namespace BoardCompanion
{
    /// <summary>
    /// `SyncClipboard.json` 的编解码（**逐字兼容 Android 当前实现**）。
    ///
    /// 形状（字段名与 Android `SyncClipboardMapper` 一致）：
    /// <code>
    /// {
    ///   "type": "Text",
    ///   "hash": "...",
    ///   "text": "...",
    ///   "hasData": false,
    ///   "dataName": "",
    ///   "size": 12,
    ///   "device": "..."      // 可选（Board 扩展；Android 只读不写）
    /// }
    /// </code>
    ///
    /// 依赖：**只用系统自带的 System.Web.Extensions.dll（JavaScriptSerializer）**，
    /// 不引入任何 NuGet 包。解析时先落到字典，因此**未知字段会被自动忽略**。
    ///
    /// 隐私：本类**不写任何日志**；错误只以状态码返回，绝不把 JSON 正文带出去。
    /// </summary>
    public static class SyncClipboardProvider
    {
        public const string MANIFEST_NAME = "SyncClipboard.json";

        public static string Serialize(ClipboardPayload payload)
        {
            if (payload == null) throw new ArgumentNullException("payload");
            var map = new Dictionary<string, object>();
            map["type"] = payload.type ?? ClipboardPayload.TYPE_TEXT;
            map["hash"] = payload.hash ?? string.Empty;
            map["text"] = payload.text;
            map["hasData"] = payload.hasData;
            map["dataName"] = payload.dataName ?? string.Empty;
            map["size"] = payload.size;
            if (!string.IsNullOrEmpty(payload.device))
            {
                map["device"] = payload.device;
            }
            var serializer = new JavaScriptSerializer();
            return serializer.Serialize(map);
        }

        public static PayloadParseResult Deserialize(string json)
        {
            if (string.IsNullOrEmpty(json)) return PayloadParseResult.Fail(PayloadParseStatus.InvalidJson);

            Dictionary<string, object> map;
            try
            {
                map = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(json);
            }
            catch (Exception)
            {
                // 只回状态，不记录正文
                return PayloadParseResult.Fail(PayloadParseStatus.InvalidJson);
            }
            if (map == null) return PayloadParseResult.Fail(PayloadParseStatus.InvalidJson);

            object rawType;
            if (!map.TryGetValue("type", out rawType) || rawType == null)
            {
                return PayloadParseResult.Fail(PayloadParseStatus.MissingType);
            }

            var payload = new ClipboardPayload();
            payload.type = Convert.ToString(rawType);

            object rawHash;
            if (map.TryGetValue("hash", out rawHash) && rawHash != null)
            {
                payload.hash = Convert.ToString(rawHash);
            }
            if (string.IsNullOrEmpty(payload.hash))
            {
                return PayloadParseResult.Fail(PayloadParseStatus.MissingHash);
            }

            object rawText;
            if (map.TryGetValue("text", out rawText) && rawText != null)
            {
                payload.text = Convert.ToString(rawText);
            }

            object rawHasData;
            if (map.TryGetValue("hasData", out rawHasData) && rawHasData != null)
            {
                payload.hasData = Convert.ToBoolean(rawHasData);
            }

            object rawDataName;
            if (map.TryGetValue("dataName", out rawDataName) && rawDataName != null)
            {
                payload.dataName = Convert.ToString(rawDataName);
            }

            object rawSize;
            if (map.TryGetValue("size", out rawSize) && rawSize != null)
            {
                payload.size = Convert.ToInt64(rawSize);
            }

            object rawDevice;
            if (map.TryGetValue("device", out rawDevice) && rawDevice != null)
            {
                payload.device = Convert.ToString(rawDevice);
            }
            // 其余未知字段：忽略（不做任何处理，也不报警）

            if (!payload.IsSupported)
            {
                return PayloadParseResult.Fail(PayloadParseStatus.UnsupportedType);
            }
            return PayloadParseResult.Ok(payload);
        }
    }
}
