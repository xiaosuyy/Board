using System;
using System.Collections.Generic;
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
            TestHotKey();
            TestPhase1H();
            TestCloudLibrary();

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

            // Phase 1-H：File 带数据时**支持**（单文件，二进制走 /file/）
            var file = SyncClipboardProvider.Deserialize(
                "{\"type\":\"File\",\"hash\":\"abc\",\"text\":null,\"hasData\":true," +
                "\"dataName\":\"abc.zip\",\"size\":9}");
            Check("accept-file-with-data",
                file.Status == PayloadParseStatus.Ok && file.Payload.hasData &&
                file.Payload.dataName == "abc.zip" && file.Payload.text == null,
                "status=" + file.Status);

            // Group，以及**没有数据**的 File：依旧安全拒绝，绝不当作 Text
            var group = SyncClipboardProvider.Deserialize(
                "{\"type\":\"Group\",\"hash\":\"abc\",\"hasData\":true,\"dataName\":\"f.png\"}");
            Check("reject-Group",
                group.Status == PayloadParseStatus.UnsupportedType && group.Payload == null,
                "status=" + group.Status);
            var noDataFile = SyncClipboardProvider.Deserialize(
                "{\"type\":\"File\",\"hash\":\"abc\",\"hasData\":false,\"dataName\":\"\"}");
            Check("reject-file-without-data",
                noDataFile.Status == PayloadParseStatus.UnsupportedType && noDataFile.Payload == null,
                "status=" + noDataFile.Status);
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

        // ------------------------------------------------------------ 单文件（Phase 1-H）

        /// <summary>
        /// 单文件：统一上限、dataName 形状与**扩展名净化**、MIME 派生、清单形状、
        /// 以及「受控临时缓存」的**当前引用保护**。
        /// </summary>
        private static void TestPhase1H()
        {
            Console.WriteLine("[file]");

            Check("file-limit-const",
                ClipboardPayload.MAX_FILE_BYTES == 10 * 1024 * 1024,
                "必须与 Android 一致 = 10 MiB");
            var maxFile = ClipboardPayload.MAX_FILE_BYTES;
            Check("file-limit-10mib-1", ClipboardFileCache.WithinLimit(maxFile - 1L), "-1 应通过");
            Check("file-limit-10mib", ClipboardFileCache.WithinLimit(maxFile), "= 10MiB 应通过");
            Check("file-limit-10mib+1", !ClipboardFileCache.WithinLimit(maxFile + 1L), "+1 应拒绝");
            Check("file-limit-zero", !ClipboardFileCache.WithinLimit(0L), "0 应拒绝");

            // dataName = <hash><.ext>；扩展名必须被**净化**（去掉路径分隔符 / 控制字符）
            Check("file-data-name",
                ClipboardSyncEngine.FileDataNameFor("abc", ".pdf") == "abc.pdf" &&
                ClipboardSyncEngine.FileDataNameFor("abc", "") == "abc" &&
                ClipboardSyncEngine.FileDataNameFor("", ".txt") == "board.txt",
                "dataName = <hash><.ext>");
            Check("file-data-name-sanitised",
                ClipboardSyncEngine.FileDataNameFor("abc", "..\\..\\evil").IndexOf('\\') < 0 &&
                ClipboardSyncEngine.FileDataNameFor("abc", "/etc/passwd").IndexOf('/') < 0,
                "扩展名不得带路径分隔符：" + ClipboardSyncEngine.FileDataNameFor("abc", "..\\..\\evil"));

            Check("file-mime",
                ClipboardSyncEngine.MimeFor("a.pdf") == "application/pdf" &&
                ClipboardSyncEngine.MimeFor("a.unknownext") == "application/octet-stream" &&
                ClipboardSyncEngine.MimeFor("a") == "application/octet-stream",
                "MIME 按扩展名派生，取不到用 octet-stream");

            // 清单形状（与 Android 的 toFilePayload 一致；正文绝不进 JSON）
            var fileHash = ClipboardHasher.HashBytes(new byte[] { 1, 2, 3, 4 });
            var payload = new ClipboardPayload
            {
                type = ClipboardPayload.TYPE_FILE,
                hash = fileHash,
                text = null,
                hasData = true,
                dataName = ClipboardSyncEngine.FileDataNameFor(fileHash, ".zip"),
                size = 4,
                device = "win-test",
            };
            var json = SyncClipboardProvider.Serialize(payload);
            var back = SyncClipboardProvider.Deserialize(json);
            Check("file-manifest-roundtrip",
                back.Status == PayloadParseStatus.Ok && back.Payload.hasData &&
                back.Payload.text == null && back.Payload.dataName == fileHash + ".zip" &&
                back.Payload.size == 4,
                "文件清单应为 type=File/hasData=true/text=null/dataName=<hash><.ext>");
            Check("file-manifest-has-no-body", json.Length < 512, "清单里不得含文件正文");

            // 受控临时缓存：**当前剪贴板引用的那个永不删**
            var dir = ClipboardFileCache.Directory;
            var current = System.IO.Path.Combine(dir, "p1h-current.bin");
            var stale = System.IO.Path.Combine(dir, "p1h-stale.bin");
            try
            {
                System.IO.Directory.CreateDirectory(dir);
                System.IO.File.WriteAllBytes(current, new byte[] { 9 });
                System.IO.File.WriteAllBytes(stale, new byte[] { 9 });
                var old = DateTime.UtcNow - TimeSpan.FromHours(3);
                System.IO.File.SetLastWriteTimeUtc(current, old);
                System.IO.File.SetLastWriteTimeUtc(stale, old);

                var removed = ClipboardFileCache.Cleanup(current, DateTime.UtcNow.Ticks, ClipboardFileCache.TtlMs);
                Check("file-cache-protects-current",
                    System.IO.File.Exists(current),
                    "当前剪贴板引用的临时文件必须保留");
                Check("file-cache-removes-stale",
                    !System.IO.File.Exists(stale) && removed >= 1,
                    "无引用且过期的临时文件应被清理（removed=" + removed + "）");
            }
            catch (Exception ex)
            {
                Check("file-cache-protects-current", false, ex.GetType().Name);
                Check("file-cache-removes-stale", false, ex.GetType().Name);
            }
            finally
            {
                try { System.IO.File.Delete(current); } catch (Exception) { }
                try { System.IO.File.Delete(stale); } catch (Exception) { }
            }
        }

        // ------------------------------------------------------------ Cloud Library（Phase 1-H）

        /// <summary>假远端：让上传/清理/DELETE 的全部流程**离线可测**，不打任何网络。</summary>
        private sealed class FakeLibraryRemote : ICloudLibraryRemote
        {
            public readonly Dictionary<string, string> Indexes = new Dictionary<string, string>();
            public readonly Dictionary<string, byte[]> Binaries = new Dictionary<string, byte[]>();
            public readonly List<string> Deleted = new List<string>();
            public bool FailPutBytes;
            public bool FailPutIndex;
            public LibraryDeleteResult DeleteResult = LibraryDeleteResult.Deleted;

            public List<string> ReadAllIndexJson()
            {
                return new List<string>(Indexes.Values);
            }

            public string ReadIndexJson(string deviceId)
            {
                string found;
                return Indexes.TryGetValue(deviceId, out found) ? found : null;
            }

            public byte[] GetBytes(string binaryName)
            {
                byte[] found;
                return Binaries.TryGetValue(binaryName, out found) ? found : null;
            }

            public bool PutBytes(string binaryName, byte[] bytes, string contentType)
            {
                if (FailPutBytes) return false;
                Binaries[binaryName] = bytes;
                return true;
            }

            public bool PutIndexJson(string deviceId, string json)
            {
                if (FailPutIndex) return false;
                Indexes[deviceId] = json;
                return true;
            }

            public LibraryDeleteResult DeleteBinary(string binaryName)
            {
                Deleted.Add(binaryName);
                if (DeleteResult == LibraryDeleteResult.Failed) return LibraryDeleteResult.Failed;
                Binaries.Remove(binaryName);
                return DeleteResult;
            }
        }

        private static void TestCloudLibrary()
        {
            Console.WriteLine("[library]");

            // ---- scope：默认值 / 持久化 / 闸门语义 ----
            var textOnly = new BoardWindowsConfig();
            var both = new BoardWindowsConfig { clipboardSyncScope = BoardWindowsConfig.SCOPE_TEXT_IMAGE };
            Check("scope-default-text",
                textOnly.clipboardSyncScope == BoardWindowsConfig.SCOPE_TEXT && !textOnly.uploadsImages,
                "默认必须是 text");
            var roundtripCfg = new System.Web.Script.Serialization.JavaScriptSerializer()
                .Deserialize<BoardWindowsConfig>(
                    new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(both));
            Check("scope-persist", roundtripCfg != null && roundtripCfg.uploadsImages,
                "scope=text-image 必须能持久化并读回");
            Check("scope-text-blocks-image-upload",
                !ClipboardSyncEngine.ShouldUploadImage(textOnly), "text 时图片不得上传");
            Check("scope-text-image-allows-upload",
                ClipboardSyncEngine.ShouldUploadImage(both), "text-image 时图片应可上传");
            Check("files-never-auto-upload",
                !ClipboardSyncEngine.ShouldAutoUploadClipboardFile(), "剪贴板文件永不自动上传");
            Check("pull-not-gated-by-scope",
                ClipboardSyncEngine.ShouldApplyRemoteImage(textOnly), "接收方向不受 scope 限制");

            // ---- 索引：round-trip / 坏 JSON / union / 去重 / max(uploadedAt) ----
            const long t0 = 1000000000000L;
            var idxA = new CloudLibraryIndex { deviceId = "win-a", deviceType = "Windows" };
            idxA.items.Add(new CloudLibraryItem { hash = "aaa", type = "image", name = "a.png", size = 10, uploadedAt = t0, deviceId = "win-a" });
            var idxB = new CloudLibraryIndex { deviceId = "win-b", deviceType = "Android" };
            idxB.items.Add(new CloudLibraryItem { hash = "aaa", type = "image", name = "a.png", size = 10, uploadedAt = t0 + 8000, deviceId = "win-b" });
            idxB.items.Add(new CloudLibraryItem { hash = "bbb", type = "file", name = "b.zip", size = 20, uploadedAt = t0, deviceId = "win-b" });
            var jsonA = CloudLibrary.SerializeIndex(idxA);
            var jsonB = CloudLibrary.SerializeIndex(idxB);

            var parsed = CloudLibrary.ParseIndex(jsonA);
            Check("index-roundtrip",
                parsed != null && parsed.items.Count == 1 && parsed.items[0].hash == "aaa" &&
                parsed.deviceType == "Windows",
                "索引 JSON 必须能往返");
            Check("index-broken-json-safe", CloudLibrary.ParseIndex("{oops") == null,
                "坏 JSON 必须安全返回 null");

            var merged = CloudLibrary.MergeIndexes(new[] { jsonA, jsonB });
            Check("union-dedup", merged.Count == 2, "同 hash 去重后应为 2 条，实际 " + merged.Count);
            var aaa = merged.Find(x => x.hash == "aaa");
            Check("union-max-uploadedAt",
                aaa != null && aaa.uploadedAt == t0 + 8000,
                "同 hash 取 max(uploadedAt)");

            // ---- TTL 边界 ----
            Check("ttl-24h-1", !CloudLibrary.IsExpired(t0 + CloudLibrary.TTL_MS - 1, t0), "24h-1 未过期");
            Check("ttl-24h", CloudLibrary.IsExpired(t0 + CloudLibrary.TTL_MS, t0), "= 24h 应过期");
            Check("ttl-24h+1", CloudLibrary.IsExpired(t0 + CloudLibrary.TTL_MS + 1, t0), "24h+1 应过期");

            // ---- 上限 / 名称 / 来源 / 路径 ----
            Check("library-limit-10mib-1", CloudLibrary.WithinLimit(CloudLibrary.MAX_LIBRARY_BYTES - 1L), "-1 通过");
            Check("library-limit-10mib", CloudLibrary.WithinLimit(CloudLibrary.MAX_LIBRARY_BYTES), "= 通过");
            Check("library-limit-10mib+1", !CloudLibrary.WithinLimit(CloudLibrary.MAX_LIBRARY_BYTES + 1L), "+1 拒绝");
            Check("library-filename-sanitised",
                CloudLibrary.SanitizeFileName("C:\\a\\..\\b.txt") == "b.txt" &&
                CloudLibrary.SanitizeFileName("/etc/passwd").IndexOf('/') < 0,
                "文件名必须净化（无路径分隔符）");
            Check("library-origin-local",
                CloudLibrary.OriginText("win-a", "win-a", "Windows") == "本地", "本机上传 = 本地");
            Check("library-origin-cloud",
                CloudLibrary.OriginText("win-b", "win-a", "Android") == "云端 · Android", "他机 = 云端 · 设备");
            Check("library-image-download-path",
                CloudLibrary.ImageDownloadPath("a.png").EndsWith("Board\\a.png"), "图片下载到 Pictures\\Board");
            Check("library-file-download-dir",
                CloudLibrary.FileDownloadDirectory().EndsWith("Downloads\\Board"), "文件默认目录 Downloads\\Board");

            // ---- 拖拽：单文件接受 / 多文件拒绝 / 文件夹拒绝 ----
            var dropDir = Path.Combine(Path.GetTempPath(), "p1h-drop-dir");
            var dropFile = Path.Combine(Path.GetTempPath(), "p1h-drop.txt");
            try
            {
                Directory.CreateDirectory(dropDir);
                File.WriteAllText(dropFile, "x");
                Check("drop-single-accepted",
                    CloudLibrary.DecideDrop(new[] { dropFile }) == CloudLibrary.DropDecision.Accept, "单文件应接受");
                Check("drop-multiple-rejected",
                    CloudLibrary.DecideDrop(new[] { dropFile, dropFile }) == CloudLibrary.DropDecision.RejectMultiple,
                    "多文件必须拒绝");
                Check("drop-folder-rejected",
                    CloudLibrary.DecideDrop(new[] { dropDir }) == CloudLibrary.DropDecision.RejectFolder,
                    "文件夹必须拒绝");
            }
            finally
            {
                try { File.Delete(dropFile); } catch (Exception) { }
                try { Directory.Delete(dropDir, true); } catch (Exception) { }
            }

            // ---- 上传 / 下载 / 清理（假远端） ----
            var bytes = new byte[] { 1, 2, 3, 4, 5 };
            var fake = new FakeLibraryRemote();
            var service = new CloudLibraryService(fake, "win-a", "Windows");

            Check("upload-ok", service.Upload("a.png", bytes, "image"), "上传应成功");
            Check("upload-creates-binary", fake.Binaries.Count == 1, "应写入 /library/ 二进制");
            var afterFirst = CloudLibrary.MergeIndexes(fake.ReadAllIndexJson());
            Check("upload-writes-index", afterFirst.Count == 1 && afterFirst[0].uploadedAt > 0, "应写入索引");

            var firstAt = afterFirst[0].uploadedAt;
            System.Threading.Thread.Sleep(5);
            Check("reupload-ok", service.Upload("a.png", bytes, "image"), "重复上传同 hash 应成功");
            var afterSecond = CloudLibrary.MergeIndexes(fake.ReadAllIndexJson());
            Check("reupload-refreshes-ttl",
                afterSecond.Count == 1 && afterSecond[0].uploadedAt > firstAt,
                "重新上传必须刷新 uploadedAt");

            var indexBeforeDownload = fake.Indexes["win-a"];
            var downloaded = service.Download(afterSecond[0]);
            Check("download-returns-bytes", downloaded != null && downloaded.Length == 5, "下载应返回原字节");
            Check("download-does-not-refresh", fake.Indexes["win-a"] == indexBeforeDownload, "下载不得刷新 TTL");

            var fresh = afterSecond[0].uploadedAt + 1000;
            Check("prune-keeps-fresh", service.Prune(fresh) == 0 && fake.Deleted.Count == 0,
                "未过期时不得 DELETE");

            var expired = afterSecond[0].uploadedAt + CloudLibrary.TTL_MS + 1000;
            var removed = service.Prune(expired);
            Check("prune-deletes-expired", removed == 1 && fake.Deleted.Count == 1, "全过期应 DELETE 一次");
            Check("prune-removes-index-entry",
                CloudLibrary.MergeIndexes(fake.ReadAllIndexJson()).Count == 0, "删除后应移除索引条目");

            // 17. 只要**任一设备**未过期 → 不 DELETE
            var fakeB = new FakeLibraryRemote();
            var serviceA = new CloudLibraryService(fakeB, "win-a", "Windows");
            serviceA.Upload("x.zip", bytes, "file");
            var mine = CloudLibrary.MergeIndexes(fakeB.ReadAllIndexJson())[0];
            var other = new CloudLibraryIndex { deviceId = "win-b", deviceType = "Windows" };
            other.items.Add(new CloudLibraryItem { hash = mine.hash, type = "file", name = "x.zip", size = 5, uploadedAt = mine.uploadedAt + CloudLibrary.TTL_MS + 500000, deviceId = "win-b" });
            fakeB.Indexes["win-b"] = CloudLibrary.SerializeIndex(other);
            Check("prune-keeps-when-other-device-fresh",
                serviceA.Prune(mine.uploadedAt + CloudLibrary.TTL_MS + 1000) == 0 && fakeB.Deleted.Count == 0,
                "任一设备未过期时禁止 DELETE");

            // 18. DELETE 404 → 视为成功（并移除索引条目）
            var fake404 = new FakeLibraryRemote();
            var service404 = new CloudLibraryService(fake404, "win-a", "Windows");
            service404.Upload("y.zip", bytes, "file");
            var yAt = CloudLibrary.MergeIndexes(fake404.ReadAllIndexJson())[0].uploadedAt;
            fake404.DeleteResult = LibraryDeleteResult.AlreadyGone;
            Check("prune-404-treated-as-success",
                service404.Prune(yAt + CloudLibrary.TTL_MS + 1) == 1, "404 必须视为成功");

            // 19. DELETE 失败 → 保留索引条目
            var fakeFail = new FakeLibraryRemote();
            var serviceFail = new CloudLibraryService(fakeFail, "win-a", "Windows");
            serviceFail.Upload("z.zip", bytes, "file");
            var zAt = CloudLibrary.MergeIndexes(fakeFail.ReadAllIndexJson())[0].uploadedAt;
            fakeFail.DeleteResult = LibraryDeleteResult.Failed;
            Check("prune-failure-keeps-index",
                serviceFail.Prune(zAt + CloudLibrary.TTL_MS + 1) == 0 &&
                CloudLibrary.MergeIndexes(fakeFail.ReadAllIndexJson()).Count == 1,
                "DELETE 失败必须保留索引条目");

            // 20. binary PUT 失败 → 不写索引
            var fakeNoBin = new FakeLibraryRemote { FailPutBytes = true };
            var serviceNoBin = new CloudLibraryService(fakeNoBin, "win-a", "Windows");
            Check("upload-binary-failure-no-index",
                !serviceNoBin.Upload("q.zip", bytes, "file") && fakeNoBin.Indexes.Count == 0,
                "二进制失败时不得写索引");

            // 21. index PUT 失败 → 尽力回滚 binary
            var fakeNoIdx = new FakeLibraryRemote { FailPutIndex = true };
            var serviceNoIdx = new CloudLibraryService(fakeNoIdx, "win-a", "Windows");
            Check("upload-index-failure-rolls-back",
                !serviceNoIdx.Upload("w.zip", bytes, "file") && fakeNoIdx.Deleted.Count == 1,
                "索引失败时必须尝试回滚二进制");
        }

        // ------------------------------------------------------------ 全局快捷键（Phase 1-G）

        /// <summary>
        /// 全局快捷键：注册 / 命令路径 / 注销 / 冲突与失败的安全性。
        ///
        /// 真实按键（WM_HOTKEY）需要消息泵，这里用与之一致的 `Trigger()` 走**同一条命令路径**；
        /// 注册本身是真实的 `RegisterHotKey` 调用（桌面会话下应成功）。
        /// </summary>
        private static void TestHotKey()
        {
            Console.WriteLine("[hotkey]");

            // 若 Board 已经在运行，它本就占用了 Ctrl+Alt+S —— 这是正常占用，不是缺陷。
            var boardRunning = Process.GetProcessesByName("BoardCompanion").Length > 0;

            var fired = 0;
            var first = new HotKey(0x4A01, () => fired++);
            var ok = first.RegisterDefault();
            Check("hotkey-registers", ok || boardRunning,
                boardRunning ? "已有 Board 实例占用该组合（视为通过）" : "RegisterHotKey(Ctrl+Alt+S) 应成功");

            // 命令路径：与收到 WM_HOTKEY 完全一致
            first.Trigger();
            Check("hotkey-invokes-command", fired == 1, "Trigger 应恰好调用一次命令，实际 " + fired);

            // 同一组合被占用：第二个必须失败且**不抛**（确定性：first 仍持有 Ctrl+Alt+S）
            var second = new HotKey(0x4A02, () => { });
            bool conflictSafe;
            try { conflictSafe = !second.RegisterDefault(); }
            catch (Exception) { conflictSafe = false; }
            Check("hotkey-conflict-safe", conflictSafe, "同组合重复注册应返回 false 而不是抛异常");
            second.Dispose();

            // 注册失败**不影响启动**：失败返回 false、不抛，之后 Unregister/Dispose 依旧安全
            var bad = new HotKey(0x4A04, () => { });
            bool failureSafe;
            try
            {
                var failed = !bad.Register(HotKey.MOD_CONTROL | HotKey.MOD_ALT, HotKey.VK_S);
                bad.Unregister();
                bad.Dispose();
                failureSafe = failed;
            }
            catch (Exception) { failureSafe = false; }
            Check("hotkey-failure-does-not-throw", failureSafe, "注册失败应返回 false 且不抛异常");
            if (fired != 1) Check("hotkey-failed-object-does-not-fire", false, "失败的注册不应触发命令");

            // 注销幂等
            bool unregisterSafe;
            try { first.Unregister(); first.Unregister(); unregisterSafe = true; }
            catch (Exception) { unregisterSafe = false; }
            Check("hotkey-unregister-idempotent", unregisterSafe, "重复 Unregister 不应抛异常");
            first.Dispose();

            // 注销后应能重新注册同一组合（证明确实释放了）
            var third = new HotKey(0x4A03, () => { });
            var again = third.RegisterDefault();
            Check("hotkey-reregister-after-unregister", again || boardRunning,
                boardRunning ? "已有 Board 实例占用该组合（视为通过）" : "注销后应能重新注册同一组合");
            third.Dispose();
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
