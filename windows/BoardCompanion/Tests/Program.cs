using System;
using System.Net;
using System.Text;
using BoardCompanion;

namespace BoardCompanionTests
{
    /// <summary>
    /// 轻量单元测试（**不引入 xUnit / NUnit / MSTest / NuGet**）。
    ///
    /// 用法：`BoardCompanion.Tests.exe` → 退出码 0 = 全部 PASS，非 0 = 有 FAIL。
    /// 输出只含用例名与原因，绝不含剪贴板正文。
    /// </summary>
    internal static class Program
    {
        private static int passed;
        private static int failed;

        private static void Check(string name, bool condition, string detail)
        {
            if (condition)
            {
                passed++;
                Console.WriteLine("  PASS  " + name);
            }
            else
            {
                failed++;
                Console.WriteLine("  FAIL  " + name + "  " + detail);
            }
        }

        private static int Main()
        {
            Console.OutputEncoding = Encoding.UTF8;
            Console.WriteLine("Board Companion Tests");

            TestHash();
            TestJson();
            TestUrlPolicy();

            Console.WriteLine();
            Console.WriteLine("PASS " + passed);
            Console.WriteLine("FAIL " + failed);
            return failed == 0 ? 0 : 1;
        }

        // ------------------------------------------------------------ Hash

        private static void TestHash()
        {
            Console.WriteLine("[hash]");

            // 与 Android 真机日志实测值对照：
            // 日志（MuMu）：SYNC_UPLOAD hash=2ab0a6bd bytes=14，对应文本 "BOARDNEW7F3A9K"（14 字节 ASCII）
            // → Android 端 ClipboardHasher 的实际输出前 8 位必须是 2ab0a6bd
            var androidSample = ClipboardHasher.Hash("BOARDNEW7F3A9K");
            Check("android-reference-prefix",
                androidSample.StartsWith("2ab0a6bd", StringComparison.Ordinal),
                "expected 2ab0a6bd…, got " + androidSample.Substring(0, 8));
            Check("android-reference-bytes",
                ClipboardHasher.Utf8Length("BOARDNEW7F3A9K") == 14L,
                "bytes=" + ClipboardHasher.Utf8Length("BOARDNEW7F3A9K"));

            // 长度与形状：SHA-256 = 64 位小写十六进制
            foreach (var sample in new[] { "hello", "你好", "Hello 世界", "第一行\n第二行", "😀", "你好 👋 🌏", "" })
            {
                var hash = ClipboardHasher.Hash(sample);
                Check("sha256-shape(len=" + sample.Length + ")",
                    IsLowerHex(hash) && hash.Length == 64,
                    "hash-len=" + hash.Length);
            }

            // 明确"不规范化"：CRLF 与 LF 必须是**不同**的 hash（Android 同样不做转换）
            Check("no-newline-normalisation",
                ClipboardHasher.Hash("a\r\nb") != ClipboardHasher.Hash("a\nb"),
                "CRLF/LF 必须产生不同 hash");

            // 不做 trim
            Check("no-trim",
                ClipboardHasher.Hash(" x ") != ClipboardHasher.Hash("x"),
                "trim 前后必须不同");

            // UTF-8 字节数
            Check("utf8-length-chinese", ClipboardHasher.Utf8Length("你好") == 6L, "你好=6");
            Check("utf8-length-emoji", ClipboardHasher.Utf8Length("😀") == 4L, "😀=4");
            Check("utf8-length-mixed", ClipboardHasher.Utf8Length("a你😀") == 1 + 3 + 4, "a你😀=8");

            // 稳定性：同样输入必然同样输出
            Check("stable",
                ClipboardHasher.Hash("Hello 世界") == ClipboardHasher.Hash("Hello 世界"),
                "same input → same hash");
        }

        private static bool IsLowerHex(string text)
        {
            if (string.IsNullOrEmpty(text)) return false;
            foreach (var c in text)
            {
                var ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                if (!ok) return false;
            }
            return true;
        }

        // ------------------------------------------------------------ JSON

        private static void TestJson()
        {
            Console.WriteLine("[json]");

            var payload = new ClipboardPayload
            {
                type = ClipboardPayload.TYPE_TEXT,
                hash = ClipboardHasher.Hash("hello"),
                text = "hello",
                hasData = false,
                dataName = string.Empty,
                size = 5,
            };
            var json = SyncClipboardProvider.Serialize(payload);
            Check("serialize-has-fields",
                json.Contains("\"type\":\"Text\"") && json.Contains("\"hash\"") &&
                json.Contains("\"text\":\"hello\"") && json.Contains("\"hasData\":false") &&
                json.Contains("\"size\":5"),
                "json=" + json);

            var round = SyncClipboardProvider.Deserialize(json);
            Check("roundtrip",
                round.Status == PayloadParseStatus.Ok && round.Payload.text == "hello" &&
                round.Payload.hash == payload.hash && round.Payload.hasData == false,
                "status=" + round.Status);

            // 带 device
            payload.device = "win-0123456789abcdef";
            var withDevice = SyncClipboardProvider.Deserialize(SyncClipboardProvider.Serialize(payload));
            Check("device-roundtrip",
                withDevice.Status == PayloadParseStatus.Ok &&
                withDevice.Payload.device == "win-0123456789abcdef",
                "device 应能往返");

            // 未知字段必须被忽略
            var unknown = "{\"type\":\"Text\",\"hash\":\"abc\",\"text\":\"x\",\"hasData\":false," +
                          "\"dataName\":\"\",\"size\":1,\"futureField\":{\"a\":1},\"device\":\"zz\"}";
            var unknownResult = SyncClipboardProvider.Deserialize(unknown);
            Check("ignore-unknown-fields",
                unknownResult.Status == PayloadParseStatus.Ok && unknownResult.Payload.text == "x",
                "status=" + unknownResult.Status);

            // Image / File / Group：安全拒绝，不当作 Text
            foreach (var type in new[] { "Image", "File", "Group" })
            {
                var r = SyncClipboardProvider.Deserialize(
                    "{\"type\":\"" + type + "\",\"hash\":\"abc\",\"hasData\":true,\"dataName\":\"f.png\"}");
                Check("reject-" + type,
                    r.Status == PayloadParseStatus.UnsupportedType && r.Payload == null,
                    "status=" + r.Status);
            }

            // 损坏 JSON
            Check("broken-json",
                SyncClipboardProvider.Deserialize("{not json").Status == PayloadParseStatus.InvalidJson,
                "应返回 InvalidJson");

            // 缺 type / 缺 hash
            Check("missing-type",
                SyncClipboardProvider.Deserialize("{\"hash\":\"abc\"}").Status == PayloadParseStatus.MissingType,
                "应返回 MissingType");
            Check("missing-hash",
                SyncClipboardProvider.Deserialize("{\"type\":\"Text\"}").Status == PayloadParseStatus.MissingHash,
                "应返回 MissingHash");

            // 缺 text（但 hash 合法）：允许解析，text 为空 —— Android 侧同理
            var noText = SyncClipboardProvider.Deserialize("{\"type\":\"Text\",\"hash\":\"abc\"}");
            Check("missing-text-is-tolerated",
                noText.Status == PayloadParseStatus.Ok && string.IsNullOrEmpty(noText.Payload.text),
                "status=" + noText.Status);

            // 空字符串
            Check("empty-json",
                SyncClipboardProvider.Deserialize("").Status == PayloadParseStatus.InvalidJson,
                "空串应 InvalidJson");
        }

        // ------------------------------------------------------------ URL policy

        private static void TestUrlPolicy()
        {
            Console.WriteLine("[url-policy]");

            Check("https-allowed",
                WebDavUrlPolicy.Evaluate("https://dav.example.com/dav", false) == null,
                "https 应放行");

            Check("http-public-blocked-by-default",
                WebDavUrlPolicy.Evaluate("http://8.8.8.8/dav", false) == WebDavUrlPolicy.MESSAGE_HTTP_NOT_ENABLED,
                "未开启时 http 应拒绝");

            Check("http-public-blocked-even-when-enabled",
                WebDavUrlPolicy.Evaluate("http://8.8.8.8/dav", true) == WebDavUrlPolicy.MESSAGE_HTTP_PUBLIC,
                "公网 http 即使开启也必须拒绝");

            Check("http-loopback-allowed-when-enabled",
                WebDavUrlPolicy.Evaluate("http://127.0.0.1:8080/dav", true) == null,
                "回环 http 在开启后应放行");

            Check("http-ipv6-loopback-allowed",
                WebDavUrlPolicy.Evaluate("http://[::1]:8080/dav", true) == null,
                "IPv6 回环应放行");

            Check("http-rfc1918-allowed",
                WebDavUrlPolicy.Evaluate("http://192.168.1.10/dav", true) == null &&
                WebDavUrlPolicy.Evaluate("http://10.0.0.5/dav", true) == null &&
                WebDavUrlPolicy.Evaluate("http://172.16.31.7:5005/dav", true) == null,
                "RFC1918 三段都应放行");

            Check("bad-scheme",
                WebDavUrlPolicy.Evaluate("htpps://dav.example.com", true) == WebDavUrlPolicy.MESSAGE_SCHEME,
                "错 scheme 应返回 MESSAGE_SCHEME");

            // 不能用字符串匹配：192.168.1.10.evil.com 这类"看着像内网"的公网主机名
            Check("no-string-matching",
                WebDavUrlPolicy.Evaluate("http://8.8.8.8@evil.com/dav", true) != null,
                "带内网字样/混淆的地址必须按真实解析判定");

            // 真实地址判定（直接测 IPAddress，不经 DNS）
            Check("private-ip-detection",
                WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("10.1.2.3")) &&
                WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("172.20.0.1")) &&
                WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("192.168.0.1")) &&
                WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("169.254.1.1")) &&
                WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("127.0.0.1")) &&
                WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("::1")) &&
                WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("fe80::1")) &&
                WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("fd12:3456::1")),
                "内网/回环/link-local/ULA 都应判为可信");

            Check("public-ip-detection",
                !WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("8.8.8.8")) &&
                !WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("1.1.1.1")) &&
                !WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("2001:4860:4860::8888")) &&
                !WebDavUrlPolicy.IsPrivateOrLocal(IPAddress.Parse("172.32.0.1")),
                "公网地址（含 172.32 边界）不得判为可信");

            // host 提取
            Check("extract-host",
                WebDavUrlPolicy.ExtractHost("http://user:pass@nas.local:5005/dav") == "nas.local" &&
                WebDavUrlPolicy.ExtractHost("http://[::1]:8080/dav") == "::1",
                "host 提取（含 userinfo/IPv6）");
        }
    }
}
