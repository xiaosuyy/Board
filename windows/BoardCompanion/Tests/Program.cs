using System;
using System.Diagnostics;
using System.IO;
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
            TestLifecycle();
            TestPhase1D();
            TestPhase1E();

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

            // Phase 1-E：Image 带数据时**支持**（二进制走 /file/，清单里不含图片）
            var image = SyncClipboardProvider.Deserialize(
                "{\"type\":\"Image\",\"hash\":\"abc\",\"text\":null,\"hasData\":true," +
                "\"dataName\":\"abc.png\",\"size\":5}");
            Check("accept-image-with-data",
                image.Status == PayloadParseStatus.Ok && image.Payload.hasData &&
                image.Payload.dataName == "abc.png" && image.Payload.text == null,
                "status=" + image.Status);

            // File / Group，以及**没有数据**的 Image：依旧安全拒绝，绝不当作 Text
            foreach (var type in new[] { "File", "Group" })
            {
                var r = SyncClipboardProvider.Deserialize(
                    "{\"type\":\"" + type + "\",\"hash\":\"abc\",\"hasData\":true,\"dataName\":\"f.png\"}");
                Check("reject-" + type,
                    r.Status == PayloadParseStatus.UnsupportedType && r.Payload == null,
                    "status=" + r.Status);
            }
            var noDataImage = SyncClipboardProvider.Deserialize(
                "{\"type\":\"Image\",\"hash\":\"abc\",\"hasData\":false,\"dataName\":\"\"}");
            Check("reject-image-without-data",
                noDataImage.Status == PayloadParseStatus.UnsupportedType && noDataImage.Payload == null,
                "status=" + noDataImage.Status);

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

        // ------------------------------------------------------------ 图片（Phase 1-E）

        /// <summary>
        /// 图片相关：统一常量、10 MiB 边界、PNG 编解码的**确定性**与透明通道、
        /// 图片清单形状。**不触碰系统剪贴板**（避免污染用户剪贴板，真机项另记 NOT VERIFIED）。
        /// </summary>
        private static void TestPhase1E()
        {
            Console.WriteLine("[image]");

            Check("image-limit-const",
                ClipboardPayload.MAX_IMAGE_BYTES == 10 * 1024 * 1024,
                "必须与 Android 一致 = 10 MiB");

            // ---- 10 MiB 边界：10MiB-1 / 10MiB / 10MiB+1 ----
            Check("image-limit-10mib-1",
                ClipboardImage.WithinLimit(ClipboardPayload.MAX_IMAGE_BYTES - 1L), "-1 应通过");
            Check("image-limit-10mib",
                ClipboardImage.WithinLimit(ClipboardPayload.MAX_IMAGE_BYTES), "= 10MiB 应通过");
            Check("image-limit-10mib+1",
                !ClipboardImage.WithinLimit(ClipboardPayload.MAX_IMAGE_BYTES + 1L), "+1 应拒绝");
            Check("image-limit-zero", !ClipboardImage.WithinLimit(0L), "0 应拒绝");

            // ---- dataName ----
            Check("image-data-name",
                ClipboardImage.DataName("abc") == "abc.png" &&
                ClipboardImage.DataName("") == "board.png",
                "dataName = <hash>.png");

            // ---- PNG 编码稳定性（不碰剪贴板）----
            using (var bitmap = new System.Drawing.Bitmap(8, 8))
            {
                using (var graphics = System.Drawing.Graphics.FromImage(bitmap))
                {
                    graphics.Clear(System.Drawing.Color.Crimson);
                }
                var png1 = ClipboardImage.EncodePng(bitmap);
                var png2 = ClipboardImage.EncodePng(bitmap);
                Check("png-encode-stable",
                    ByteEquals(png1, png2) && png1.Length > 0,
                    "同一 Bitmap 反复编码应得到相同字节");
                Check("png-hash-stable",
                    ClipboardHasher.HashBytes(png1) == ClipboardHasher.HashBytes(png2) &&
                    ClipboardHasher.HashBytes(png1).Length == 64,
                    "PNG hash 应稳定且为 64 位小写十六进制");

                var decoded = ClipboardImage.DecodePng(png1);
                Check("png-decode", decoded != null && decoded.Width == 8 && decoded.Height == 8,
                    "解码后尺寸应保持 8x8");
                if (decoded != null)
                {
                    var reencoded = ClipboardImage.EncodePng(decoded);
                    Check("png-reencode-stable", ByteEquals(png1, reencoded),
                        "编码→解码→编码应幂等（回环抑制依赖它）");
                    decoded.Dispose();
                }
            }

            // ---- 透明 PNG：保留 alpha ----
            using (var bitmap = new System.Drawing.Bitmap(
                4, 4, System.Drawing.Imaging.PixelFormat.Format32bppArgb))
            {
                bitmap.SetPixel(0, 0, System.Drawing.Color.FromArgb(0, 255, 0, 0));
                bitmap.SetPixel(1, 0, System.Drawing.Color.FromArgb(128, 0, 255, 0));
                bitmap.SetPixel(2, 0, System.Drawing.Color.FromArgb(255, 0, 0, 255));
                var png = ClipboardImage.EncodePng(bitmap);
                var back = ClipboardImage.DecodePng(png);
                Check("png-transparency",
                    back != null && back.GetPixel(0, 0).A == 0 &&
                    back.GetPixel(1, 0).A == 128 && back.GetPixel(2, 0).A == 255,
                    "透明通道必须保留");
                if (back != null) back.Dispose();
            }

            Check("png-decode-garbage",
                ClipboardImage.DecodePng(new byte[] { 1, 2, 3, 4 }) == null,
                "坏数据应返回 null 而不是抛异常");

            // ---- 图片清单形状（与 Android 的 toImagePayload 一致）----
            var hash = ClipboardHasher.HashBytes(new byte[] { 9, 9, 9 });
            var payload = new ClipboardPayload
            {
                type = ClipboardPayload.TYPE_IMAGE,
                hash = hash,
                text = null,
                hasData = true,
                dataName = ClipboardImage.DataName(hash),
                size = 3,
                device = "win-test",
            };
            var json = SyncClipboardProvider.Serialize(payload);
            var back2 = SyncClipboardProvider.Deserialize(json);
            Check("image-manifest-roundtrip",
                back2.Status == PayloadParseStatus.Ok && back2.Payload.hasData &&
                back2.Payload.text == null && back2.Payload.dataName == hash + ".png" &&
                back2.Payload.size == 3,
                "图片清单应为 type=Image/hasData=true/text=null/dataName=<hash>.png");
            Check("image-manifest-has-no-body",
                json.Length < 512, "清单里不得含图片二进制，实际长度 " + json.Length);
        }

        private static bool ByteEquals(byte[] a, byte[] b)
        {
            if (a == null || b == null || a.Length != b.Length) return false;
            for (int i = 0; i < a.Length; i++)
            {
                if (a[i] != b[i]) return false;
            }
            return true;
        }

        // ------------------------------------------------------------ 生命周期 / UI

        /// <summary>
        /// Phase 1-C 的生命周期检查（能自动化的部分）：
        /// - 主程序必须是 GUI 子系统（= 启动没有控制台黑窗口）；
        /// - 主程序能在"没有界面"的情况下后台存活（托盘服务持续运行）。
        ///
        /// 需要人点托盘的那些（关闭面板不退出、点退出才结束）无法在这里自动化，
        /// 按规则记为 NOT VERIFIED，需人工确认。
        /// </summary>
        private static void TestLifecycle()
        {
            Console.WriteLine("[lifecycle]");

            var dir = Path.GetDirectoryName(typeof(Program).Assembly.Location);
            var exe = Path.Combine(dir, "BoardCompanion.exe");
            Check("main-exe-exists", File.Exists(exe), "找不到 " + exe);
            if (!File.Exists(exe)) return;

            // PE OptionalHeader.Subsystem：2=Windows GUI，3=Console
            int subsystem = PeSubsystem(exe);
            Check("no-console-window(pe-subsystem=" + subsystem + ")", subsystem == 2,
                "期望 2(GUI)，实际 " + subsystem);

            // 后台存活：启动后数秒仍在运行（没有主窗口也不退出）
            var before = Process.GetProcessesByName("BoardCompanion").Length;
            Process started = null;
            try
            {
                started = Process.Start(exe);
                System.Threading.Thread.Sleep(4000);
                var alive = started != null && !started.HasExited;
                if (!alive && before > 0)
                {
                    // 已有实例在跑：新进程按单实例策略立刻退出，这也是预期行为
                    Check("background-stays-alive", true, "已有实例在运行（单实例策略）");
                }
                else
                {
                    Check("background-stays-alive", alive, "进程启动后 4 秒内退出了");
                }
            }
            catch (Exception ex)
            {
                Check("background-stays-alive", false, ex.GetType().Name);
            }
            finally
            {
                // 只结束"本次测试启动的"那个进程，绝不动用户已经在用的实例
                if (started != null && !started.HasExited)
                {
                    try { started.Kill(); } catch (Exception) { }
                }
            }
        }

        /// <summary>读取 PE 的 Subsystem 字段（2=GUI，3=Console）</summary>
        private static int PeSubsystem(string exePath)
        {
            try
            {
                var bytes = File.ReadAllBytes(exePath);
                int peOffset = BitConverter.ToInt32(bytes, 0x3C);
                int optional = peOffset + 4 + 20; // Signature + FileHeader
                return BitConverter.ToUInt16(bytes, optional + 68);
            }
            catch (Exception)
            {
                return -1;
            }
        }

        // ------------------------------------------------------------ Phase 1-D

        /// <summary>
        /// Phase 1-D：开机自启、超长文本边界、失败后不留正文副本、退出只清理一次。
        ///
        /// 超长文本用**纯逻辑 + 编解码**验证边界与内容完整性（不入网络）；
        /// 真实 5 MiB 往返需要联网环境，按规则另记 NOT VERIFIED。
        /// </summary>
        private static void TestPhase1D()
        {
            Console.WriteLine("[phase1d]");

            // ---- 开机自启（用 scratch 注册表键，绝不碰真正的 Run 键）----
            const string scratch = @"Software\BoardCompanion\AutoStartTest";
            try
            {
                try { Microsoft.Win32.Registry.CurrentUser.DeleteSubKeyTree(scratch, false); }
                catch (Exception) { }
                Check("autostart-default-off", !AutoStart.IsEnabled(scratch), "scratch 键初始应为未启用");
                Check("autostart-enable", AutoStart.SetEnabled(true, scratch) && AutoStart.IsEnabled(scratch),
                    "开启后应读到已启用");
                Check("autostart-disable", AutoStart.SetEnabled(false, scratch) && !AutoStart.IsEnabled(scratch),
                    "关闭后应读到未启用");
                Check("autostart-command-quoted", AutoStart.CommandLine.StartsWith("\"", StringComparison.Ordinal),
                    "路径需要带引号以防空格");
            }
            finally
            {
                try { Microsoft.Win32.Registry.CurrentUser.DeleteSubKeyTree(scratch, false); }
                catch (Exception) { }
            }
            Check("autostart-scratch-cleaned",
                !AutoStart.IsEnabled(scratch), "测试键应已清理");
            // 真正的 Run 键：只允许"读"，测试绝不改写
            Check("autostart-runkey-readable",
                AutoStart.IsEnabled() == AutoStart.IsEnabled(), "读取 Run 键不应抛异常");

            // ---- 256 KiB 边界（按 UTF-8 字节数）----
            const int limit = ClipboardPayload.INLINE_TEXT_MAX_BYTES; // 256 * 1024
            var atLimit = new string('a', limit);            // 恰好 256 KiB
            var overLimit = new string('a', limit + 1);      // 256 KiB + 1
            Check("boundary-256k-inline", !ClipboardSyncEngine.UseFileMode(atLimit),
                "= 256KiB 应继续内联");
            Check("boundary-256k+1-file", ClipboardSyncEngine.UseFileMode(overLimit),
                "256KiB+1 应走 /file/");
            Check("boundary-utf8-bytes",
                ClipboardHasher.Utf8Length(atLimit) == limit &&
                ClipboardHasher.Utf8Length(overLimit) == limit + 1,
                "边界必须按 UTF-8 字节数判定");

            // ---- 1 MiB / 5 MiB（多字节内容：中文 + Emoji + 换行）----
            var oneMiB = BuildLargeText(1 * 1024 * 1024);
            var fiveMiB = BuildLargeText(5 * 1024 * 1024);
            Check("boundary-1mib-file", ClipboardSyncEngine.UseFileMode(oneMiB), "1 MiB 应走 /file/");
            Check("boundary-5mib-file", ClipboardSyncEngine.UseFileMode(fiveMiB), "5 MiB 应走 /file/");
            Check("large-utf8-length",
                ClipboardHasher.Utf8Length(oneMiB) >= 1024 * 1024 &&
                ClipboardHasher.Utf8Length(fiveMiB) >= 5 * 1024 * 1024,
                "多字节内容按 UTF-8 计算不低于目标字节数");
            Check("large-hash-stable",
                ClipboardHasher.Hash(fiveMiB) == ClipboardHasher.Hash(fiveMiB) &&
                ClipboardHasher.Hash(fiveMiB).Length == 64,
                "5 MiB 文本 hash 稳定且形状正确");
            Check("large-mixed-content-intact",
                fiveMiB.Contains("你好") && fiveMiB.Contains("👋") && fiveMiB.Contains("\n"),
                "中文 / Emoji / 换行必须原样保留");

            // ---- 超长文本的清单形状（与 Android 的 toFilePayload 一致）----
            var hash = ClipboardHasher.Hash(fiveMiB);
            var payload = new ClipboardPayload
            {
                type = ClipboardPayload.TYPE_TEXT,
                hash = hash,
                text = null,
                hasData = true,
                dataName = ClipboardSyncEngine.FileDataName(hash),
                size = ClipboardHasher.Utf8Length(fiveMiB),
                device = "win-test",
            };
            var json = SyncClipboardProvider.Serialize(payload);
            var back = SyncClipboardProvider.Deserialize(json);
            Check("large-payload-roundtrip",
                back.Status == PayloadParseStatus.Ok && back.Payload.hasData &&
                back.Payload.text == null &&
                back.Payload.dataName == hash + ".txt" &&
                back.Payload.size == payload.size,
                "清单应为 hasData=true + dataName=<hash>.txt + text=null");
            Check("large-manifest-has-no-body",
                json.Length < 1024,
                "超长正文不得进 JSON（清单应很短），实际 " + json.Length);

            // ---- 正文不落盘 / 无临时文件（失败后也不留）----
            var dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BoardCompanion");
            long biggest = 0;
            string logPath = Path.Combine(dir, "companion.log");
            if (Directory.Exists(dir))
            {
                foreach (var file in Directory.GetFiles(dir))
                {
                    // companion.log 是**只含元数据**的运行日志，会随运行时长增长，
                    // 不能用“体积”判断它是否含正文 —— 单独按**内容**校验（见下一条断言）。
                    if (string.Equals(
                            Path.GetFileName(file), "companion.log", StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    var length = new FileInfo(file).Length;
                    if (length > biggest) biggest = length;
                }
            }
            Check("no-content-on-disk(largest=" + biggest + "B)",
                biggest < 64 * 1024,
                "配置目录里不应出现正文副本或临时文件（运行日志单独校验）");

            // 运行日志**同样**不得出现剪贴板正文：只允许 hash / 大小 / 状态 / 耗时等元数据。
            // 比“体积阈值”更准确 —— 日志再长也不该包含任何正文片段。
            long logBytes = 0;
            var logHasBody = false;
            if (File.Exists(logPath))
            {
                logBytes = new FileInfo(logPath).Length;
                var logText = File.ReadAllText(logPath, Encoding.UTF8);
                foreach (var marker in new[] { "BOARDNEW7F3A9K", "你好", "👋", "Hello 世界", "第一行", "😀" })
                {
                    if (logText.Contains(marker)) { logHasBody = true; break; }
                }
            }
            Check("log-has-no-body(bytes=" + logBytes + ")",
                !logHasBody,
                "运行日志只能记 hash / 大小 / 状态，绝不能出现剪贴板正文");

            // ---- 路径规则（与 Android 完全一致）----
            Check("path-root-plus-dir",
                ClipboardSyncEngine.ManifestPathFor("https://h/dav/", "board") == "board/SyncClipboard.json",
                "地址=根 + 目录 board → board/SyncClipboard.json");
            Check("path-url-already-has-dir",
                ClipboardSyncEngine.ManifestPathFor("https://h/dav/board/", "board") == "SyncClipboard.json",
                "地址已含 board → 不得再拼一层");
            Check("path-empty-dir",
                ClipboardSyncEngine.ManifestPathFor("https://h/dav/board/", "") == "SyncClipboard.json",
                "目录留空 → 地址即根");
            Check("path-file-endpoint",
                ClipboardSyncEngine.RelativePathFor("https://h/dav/", "board", "file/abc.txt") == "board/file/abc.txt",
                "超长文本的 /file/ 也必须带上同一目录");

            // ---- 退出只清理/打印一次 ----
            Check("cleanup-first-run", BoardCompanion.Program.Cleanup(), "第一次应真正清理");
            Check("cleanup-second-run-noop", !BoardCompanion.Program.Cleanup(), "第二次应直接返回 false（不再打印 exited）");
        }

        /// <summary>
        /// 构造一个含中文 / Emoji / 换行的指定字节量级文本。
        ///
        /// **O(n)**：块字节数只算一次，循环里用计数累加，最后只做一次 ToString()。
        /// （旧写法每次循环都 `sb.ToString()` 再量长度，是 O(n²)，5 MiB 时让测试跑了 4 分多钟。）
        /// 生成的内容与旧实现**逐字节相同**。
        /// </summary>
        private static string BuildLargeText(int targetBytes)
        {
            const string chunk = "你好👋世界\n";
            var chunkBytes = (int)ClipboardHasher.Utf8Length(chunk);
            var builder = new StringBuilder(targetBytes + chunk.Length);
            long bytes = 0;
            while (bytes < targetBytes)
            {
                builder.Append(chunk);
                bytes += chunkBytes;
            }
            return builder.ToString();
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
