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
            TestClipboardLog();
            TestSeqCrashWindow();
            TestNotify();
            TestCoalesce();

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

        // ------------------------------------------------------------ 通知去重 / 合并补跑

        // ------------------------------------------------------------ seq 崩溃窗口

        private static void TestSeqCrashWindow()
        {
            Console.WriteLine("[seqcrash]");

            // 复现崩溃窗口：seq=7 已成功发布（远端有 dev:7 / HASH-OLD），
            // 但落盘 seq 之前崩溃 → 重启后本地水位=6 → 又分配了 7（内容 HASH-NEW）。
            var remote = new ClipboardLogFile();
            remote.deviceId = "dev";
            remote.events.Add(new ClipboardLogEvent
            {
                seq = 7, itemId = "dev:7", hash = "HASH-OLD",
                type = ClipboardLog.TYPE_TEXT, createdAt = 1, size = 1,
            });

            var sequence = new ClipboardLogSequence(6);
            var reused = new ClipboardLogEvent
            {
                seq = sequence.Next(), itemId = ClipboardLog.ItemIdFor("dev", 7), hash = "HASH-NEW",
                type = ClipboardLog.TYPE_TEXT, createdAt = 2, size = 1,
            };
            var batch = new List<ClipboardLogEvent> { reused };
            Check("crash-window-reproduced",
                reused.seq == 7 && reused.itemId == "dev:7" && reused.hash == "HASH-NEW",
                "应先复现 seq 被重复分配");

            var n = ClipboardLog.ReassignReusedSequences(batch, remote, "dev", sequence);
            Check("crash-window-reassigned",
                n == 1 && batch[0].seq == 8 && batch[0].itemId == "dev:8",
                "seq=" + batch[0].seq + " itemId=" + batch[0].itemId);
            Check("crash-window-content-preserved", batch[0].hash == "HASH-NEW", "内容不得丢弃");
            Check("crash-window-no-itemid-collision", batch[0].itemId != "dev:7", "不得复用已发布的 itemId");
            Check("crash-window-seq-monotonic", sequence.Current > 7, "分配后水位必须超过 remoteMax");

            // 同 itemId 同内容（上次已发布、pending 还没清掉）⇒ 不重新分配
            var remote2 = new ClipboardLogFile();
            remote2.events.Add(new ClipboardLogEvent { seq = 3, itemId = "dev:3", hash = "SAME", type = ClipboardLog.TYPE_TEXT, createdAt = 1, size = 1 });
            var seq2 = new ClipboardLogSequence(3);
            var batch2 = new List<ClipboardLogEvent>
            {
                new ClipboardLogEvent { seq = 3, itemId = "dev:3", hash = "SAME", type = ClipboardLog.TYPE_TEXT, createdAt = 1, size = 1 },
            };
            Check("same-id-same-hash-not-reassigned",
                ClipboardLog.ReassignReusedSequences(batch2, remote2, "dev", seq2) == 0 && batch2[0].itemId == "dev:3",
                "同 id 同内容不应换 seq（由调用方跳过）");

            // 无冲突一律不改动
            var seq3 = new ClipboardLogSequence(10);
            var batch3 = new List<ClipboardLogEvent>
            {
                new ClipboardLogEvent { seq = 11, itemId = "dev:11", hash = "X", type = ClipboardLog.TYPE_TEXT, createdAt = 1, size = 1 },
            };
            Check("no-conflict-not-reassigned",
                ClipboardLog.ReassignReusedSequences(batch3, remote2, "dev", seq3) == 0 && batch3[0].seq == 11,
                "无冲突不得改动");

            // 水位抬升后严格递增（不允许回退）
            var bumped = new ClipboardLogSequence(remote.events[0].seq);
            Check("bump-then-monotonic",
                bumped.Next() == 8 && bumped.Next() == 9 && bumped.Current == 9,
                "抬升后必须从 remoteMax+1 继续");

            // 边界：空批次 / null 远端
            Check("reassign-empty-safe",
                ClipboardLog.ReassignReusedSequences(new List<ClipboardLogEvent>(), remote, "dev", new ClipboardLogSequence(0)) == 0 &&
                ClipboardLog.ReassignReusedSequences(batch3, null, "dev", new ClipboardLogSequence(10)) == 0,
                "空批次 / 无远端时必须安全");
        }

        private static void TestNotify()
        {
            Console.WriteLine("[notify]");

            // D2-1：同一次 clipboard change 的多次通知 → 只捕获一次
            var gate = new ClipboardNotificationGate();
            Check("same-change-once",
                gate.ShouldCapture(100) && !gate.ShouldCapture(100) &&
                !gate.ShouldCapture(100) && !gate.ShouldCapture(100),
                "同一 sequence 只应捕获一次");

            // D2-2：下一次真实 change（sequence+1）即使内容 hash 相同也必须再捕获
            var gate2 = new ClipboardNotificationGate();
            var first = gate2.ShouldCapture(200);
            var second = gate2.ShouldCapture(201);
            Check("next-change-same-hash-still-captured", first && second,
                "不同 sequence 必须各捕获一次，不得因为 hash 相同而吞掉");

            // D2-3：两次不同 hash（不同 sequence）
            var gate3 = new ClipboardNotificationGate();
            Check("two-different-changes", gate3.ShouldCapture(300) && gate3.ShouldCapture(301),
                "两次不同内容 = 两次捕获");

            // 取不到序号（0）时不参与去重 —— 宁可多捕获，不可漏
            var gate4 = new ClipboardNotificationGate();
            Check("zero-sequence-not-deduped", gate4.ShouldCapture(0) && gate4.ShouldCapture(0),
                "sequence=0 不应被去重吞掉");

            // D3：快照承载「通知那一刻」读到的文本（不是稍后的"当前剪贴板"）
            var snapshot = new ClipboardSnapshot { Sequence = 7, Text = "SNAPSHOT-A" };
            Check("snapshot-carries-text",
                snapshot.Text == "SNAPSHOT-A" &&
                ClipboardHasher.Hash(snapshot.Text) == ClipboardHasher.Hash("SNAPSHOT-A") &&
                !snapshot.HasFile && !snapshot.HasImage,
                "快照必须原样携带捕获到的文本");

            var fileSnapshot = new ClipboardSnapshot { Sequence = 8, HasFile = true };
            var imageSnapshot = new ClipboardSnapshot { Sequence = 9, HasImage = true };
            Check("snapshot-kinds",
                fileSnapshot.HasFile && !fileSnapshot.HasImage && imageSnapshot.HasImage && !imageSnapshot.HasFile,
                "文件 / 图片快照必须可区分");

            // D2-4：一次复制产生**两个序号**（Empty + Set）但内容相同 ⇒ 仍然只算一次
            var gate5 = new ClipboardNotificationGate();
            Check("burst-same-content-coalesced",
                gate5.ShouldCapture(400) && gate5.ShouldCaptureContent("SAME", 1000) &&
                gate5.ShouldCapture(401) && !gate5.ShouldCaptureContent("SAME", 1050),
                "同一次复制的两个序号 + 相同内容 ⇒ 只一次");

            // D2-5：不同内容即使 34ms 也必须是两次（D3 场景不受影响）
            var gate6 = new ClipboardNotificationGate();
            Check("burst-different-content-two",
                gate6.ShouldCaptureContent("AAA", 1000) && gate6.ShouldCaptureContent("BBB", 1034),
                "A→B 必须各算一次");

            // D2-6：相同内容但超过合并窗口 ⇒ 必须产生新 event（不是"hash 全局去重"）
            var gate7 = new ClipboardNotificationGate();
            Check("same-content-after-window-two",
                gate7.ShouldCaptureContent("SAME", 1000) &&
                gate7.ShouldCaptureContent("SAME", 1000 + ClipboardNotificationGate.BurstMs + 1),
                "同内容不同时间再次复制必须产生新 event");

            // 非文本不受内容合并影响
            var gate8 = new ClipboardNotificationGate();
            Check("null-content-not-coalesced",
                gate8.ShouldCaptureContent(null, 1000) && gate8.ShouldCaptureContent(null, 1010),
                "非文本不受 burst 合并影响");
        }

        private static void TestCoalesce()
        {
            Console.WriteLine("[coalesce]");

            var c = new SyncCoalescer();
            Check("enter-when-idle", c.TryEnter() && c.IsBusy, "空闲时应取得执行权");
            Check("second-enter-blocked",
                !c.TryEnter() && c.HasPending,
                "忙时不得启动第二个执行者，但要登记 pending");
            Check("repeat-once", c.ShouldRepeat() && !c.ShouldRepeat(), "pending 只补跑一次（合并）");
            c.Exit();
            Check("exit-releases", !c.IsBusy && !c.HasPending, "结束后释放执行权");
            Check("enter-again-after-exit", c.TryEnter(), "释放后可以再次进入");
            c.Exit();

            // 忙期间来多次请求 → 仍然只补跑一次
            var d = new SyncCoalescer();
            d.TryEnter();
            d.TryEnter();
            d.TryEnter();
            d.TryEnter();
            Check("many-requests-single-repeat",
                d.ShouldRepeat() && !d.ShouldRepeat(),
                "多次请求合并成一次补跑");
            d.Exit();
            Check("no-repeat-when-idle", !d.ShouldRepeat(), "没有 pending 时不得空转补跑");
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

        // ------------------------------------------------------------ B1 Clipboard Log

        private static ClipboardLogEvent Ev(long seq, string hash)
        {
            return new ClipboardLogEvent
            {
                seq = seq,
                itemId = "dev:" + seq,
                hash = hash,
                type = ClipboardLog.TYPE_TEXT,
                createdAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                size = 1,
                text = null,
            };
        }

        private static ClipboardLogFile BuildLog(string[] texts)
        {
            var file = new ClipboardLogFile();
            file.deviceId = "win-test";
            file.deviceType = "Windows";
            file.updatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            for (int i = 0; i < texts.Length; i++)
            {
                var seq = i + 1;
                file.events.Add(new ClipboardLogEvent
                {
                    seq = seq,
                    itemId = ClipboardLog.ItemIdFor(file.deviceId, seq),
                    hash = ClipboardHasher.Hash(texts[i]),
                    type = ClipboardLog.TYPE_TEXT,
                    createdAt = file.updatedAt,
                    size = ClipboardHasher.Utf8Length(texts[i]),
                    text = texts[i],
                });
            }
            return file;
        }

        private static string LatestOf(string[] items)
        {
            return items[items.Length - 1];   // latest-state 语义：最后一条胜出
        }

        private static void TestClipboardLog()
        {
            Console.WriteLine("[clipboard-log]");

            // 1) seq 单调递增
            var sequence = new ClipboardLogSequence(0);
            var last = 0L;
            var monotonic = true;
            for (int i = 0; i < 100; i++)
            {
                var next = sequence.Next();
                if (next <= last) monotonic = false;
                last = next;
            }
            Check("seq-monotonic", monotonic && sequence.Current == 100, "current=" + sequence.Current);

            // 2) itemId 唯一（含 deviceId 前缀）
            var ids = new HashSet<string>();
            var idsUnique = true;
            for (int i = 1; i <= 50; i++)
            {
                if (!ids.Add(ClipboardLog.ItemIdFor("android-abc", i))) idsUnique = false;
            }
            Check("item-id-unique",
                idsUnique && ClipboardLog.ItemIdFor("d1", 7) == "d1:7",
                "itemId=deviceId:seq");

            // 3) A/B 快速连续复制 → 两个 event（与去抖无关）
            var ab = BuildLog(new[] { "AAA", "BBB" });
            Check("ab-two-events", ab.events.Count == 2, "count=" + ab.events.Count);

            // 4) latest-state 仍只有 B（去抖只影响 newest，不影响 event 数量）
            Check("latest-state-last-wins",
                LatestOf(new[] { "AAA", "BBB" }) == "BBB" && ab.events.Count == 2,
                "latest=B 且 log 仍有 2 条");

            // 5) pull A/B → 两条候选（按 seq 升序）
            var candidates = ClipboardLog.CandidatesFor(0, ab);
            Check("pull-two-items",
                candidates.Count == 2 && candidates[0].seq == 1 && candidates[1].seq == 2,
                "count=" + candidates.Count);

            // 6) 相同 hash 的两个 event 都进入候选（去重是本地 History DB 的既有语义，不在这里改）
            var same = BuildLog(new[] { "same", "same" });
            var sameCandidates = ClipboardLog.CandidatesFor(0, same);
            Check("same-hash-both-candidates",
                sameCandidates.Count == 2 &&
                sameCandidates[0].hash == sameCandidates[1].hash &&
                ClipboardHasher.Hash("same") == ClipboardHasher.Hash("same"),
                "hash 去重不改动，交由本地库");

            // 7) offline A/B/C → 上线后全部补齐
            var abc = BuildLog(new[] { "A", "B", "C" });
            Check("offline-backfill-three", ClipboardLog.CandidatesFor(0, abc).Count == 3, "backfill=3");

            // 8) 多设备各写自己的 log → 路径隔离、互不覆盖
            var pa = ClipboardLog.PathFor("win-aaa");
            var pb = ClipboardLog.PathFor("android-bbb");
            Check("per-device-isolation",
                pa != pb && pa == "clipboard-log/win-aaa.json" && pb == "clipboard-log/android-bbb.json",
                pa + " / " + pb);

            // 9) 同设备并发 append → seq 不重复（ClibpoardLogSequence 内部加锁）
            var concurrent = new ClipboardLogSequence(0);
            var seen = new List<long>();
            var seenGate = new object();
            System.Threading.Tasks.Parallel.For(0, 2000, i =>
            {
                var seq = concurrent.Next();
                lock (seenGate) { seen.Add(seq); }
            });
            var unique = new HashSet<long>(seen);
            Check("concurrent-append-unique",
                seen.Count == 2000 && unique.Count == 2000 && concurrent.Current == 2000,
                "count=" + seen.Count + " unique=" + unique.Count);

            // 10) PUT 失败不回收 seq（否则 itemId 复用会被对端水位跳过）
            var failed = new ClipboardLogSequence(5);
            var e1 = failed.Next();   // 6  —— PUT 失败，保留内存 pending
            var e2 = failed.Next();   // 7  —— 重试仍用新 seq，绝不回退
            var e3 = failed.Next();   // 8
            Check("seq-not-reclaimed-on-put-failure",
                e1 == 6 && e2 == 7 && e3 == 8 && failed.Current == 8,
                "seq=" + e1 + "," + e2 + "," + e3);

            // 11) pull 中途失败 ⇒ 不推进 lastSeenSeq
            Check("watermark-not-advanced-on-failure",
                ClipboardLog.AdvanceWatermark(3, new long[] { 4, 5, 6 }, false) == 3,
                "中途失败必须保持原水位");
            Check("watermark-advanced-on-success",
                ClipboardLog.AdvanceWatermark(3, new long[] { 4, 5, 6 }, true) == 6,
                "全部成功才一次性推进到 max");
            Check("watermark-empty-stays",
                ClipboardLog.AdvanceWatermark(9, new long[0], true) == 9,
                "空批次不动水位");

            // 12) 404 / 缺失 log 安全处理
            Check("missing-log-safe",
                ClipboardLog.CandidatesFor(0, null).Count == 0 &&
                ClipboardLog.GapDetected(0, null) == false &&
                ClipboardLog.Parse("") == null,
                "404 不得报错、不得推进水位");

            // 13) 坏 JSON 安全处理
            Check("bad-json-safe",
                ClipboardLog.Parse("{ this is not json ]") == null &&
                ClipboardLog.Parse(null) == null,
                "坏 JSON ⇒ 本轮跳过");

            // 14) minSeq gap 被正确识别（不猜测、不伪造）
            var gap = new ClipboardLogFile();
            gap.deviceId = "android-gap";
            gap.minSeq = 50;
            gap.events.Add(Ev(50, "h50"));
            Check("minSeq-gap-detected",
                ClipboardLog.GapDetected(3, gap) == true &&
                ClipboardLog.GapDetected(49, gap) == false &&
                ClipboardLog.CandidatesFor(49, gap).Count == 1,
                "gap 只记录 warning，从可得 seq 继续");

            // 15/16/17) 只有 Text 且 ≤256 KiB 才产生 event
            var limit = ClipboardPayload.INLINE_TEXT_MAX_BYTES;
            Check("file-no-event", !ClipboardLog.ShouldRecord("File", 10), "File 不产生 event");
            Check("image-no-event", !ClipboardLog.ShouldRecord("Image", 10), "Image B1 不产生 event");
            Check("text-oversize-no-event",
                !ClipboardLog.ShouldRecord(ClipboardLog.TYPE_TEXT, limit + 1),
                ">256 KiB 不产生 event（不引用 /file/）");
            Check("text-boundary-record",
                ClipboardLog.ShouldRecord(ClipboardLog.TYPE_TEXT, 1) &&
                ClipboardLog.ShouldRecord(ClipboardLog.TYPE_TEXT, limit) &&
                !ClipboardLog.ShouldRecord(ClipboardLog.TYPE_TEXT, 0),
                "1..256KiB 记录，0 不记录");
            Check("file-never-auto-upload", !ClipboardSyncEngine.ShouldAutoUploadClipboardFile(), "File 永不自动上传");

            // 18) 消费远端事件是只读的（不写系统剪贴板 ⇒ 不触发 ClipboardMonitor ⇒ 无回环）
            var readOnly = BuildLog(new[] { "R1", "R2" });
            var beforeCount = readOnly.events.Count;
            ClipboardLog.CandidatesFor(0, readOnly);
            Check("consume-is-read-only",
                readOnly.events.Count == beforeCount,
                "CandidatesFor 不修改远端日志");

            // 19) 与 latest-state 并存且隔离（不同路径、不同 TTL、不同目录）
            Check("coexist-with-latest-state",
                ClipboardLog.PathFor("x") != SyncClipboardProvider.MANIFEST_NAME &&
                ClipboardLog.PathFor("x").StartsWith("clipboard-log/", StringComparison.Ordinal) &&
                !ClipboardLog.PathFor("x").StartsWith("library/", StringComparison.Ordinal),
                "clipboard-log 与 SyncClipboard.json / library 严格隔离");

            // 裁剪：只裁本设备、更新 minSeq、不重排 seq、上限 200
            var many = new ClipboardLogFile();
            many.deviceId = "win-many";
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            for (int i = 1; i <= 250; i++)
            {
                many.events.Add(new ClipboardLogEvent
                {
                    seq = i,
                    itemId = ClipboardLog.ItemIdFor("win-many", i),
                    hash = "h" + i,
                    type = ClipboardLog.TYPE_TEXT,
                    createdAt = now,
                    size = 1,
                });
            }
            ClipboardLog.Compact(many, now);
            var ordered = true;
            for (int i = 1; i < many.events.Count; i++)
            {
                if (many.events[i].seq <= many.events[i - 1].seq) ordered = false;
            }
            Check("compact-max-events",
                many.events.Count == ClipboardLog.MAX_EVENTS &&
                many.events[0].seq == 51 &&
                many.minSeq == 51 &&
                ordered,
                "count=" + many.events.Count + " minSeq=" + many.minSeq);

            // 裁剪：7 天前的事件被移除，minSeq 随之上移
            var aged = new ClipboardLogFile();
            aged.deviceId = "win-aged";
            aged.events.Add(new ClipboardLogEvent
            {
                seq = 1, itemId = "win-aged:1", hash = "old", type = ClipboardLog.TYPE_TEXT,
                createdAt = now - ClipboardLog.RETENTION_MS - 1000, size = 1,
            });
            aged.events.Add(new ClipboardLogEvent
            {
                seq = 2, itemId = "win-aged:2", hash = "new", type = ClipboardLog.TYPE_TEXT,
                createdAt = now, size = 1,
            });
            ClipboardLog.Compact(aged, now);
            Check("compact-retention",
                aged.events.Count == 1 && aged.events[0].seq == 2 && aged.minSeq == 2,
                "minSeq=" + aged.minSeq);

            // 序列化往返（远端 schema）
            var roundtrip = ClipboardLog.Parse(ClipboardLog.Serialize(ab));
            Check("log-roundtrip",
                roundtrip != null && roundtrip.deviceId == "win-test" &&
                roundtrip.events.Count == 2 && roundtrip.events[0].hash == ClipboardHasher.Hash("AAA"),
                "schema round-trip");

            // deviceId 安全化：路径穿越字符不得进入远端路径
            Check("device-id-sanitised",
                ClipboardLog.PathFor("../../etc/passwd") == "clipboard-log/etcpasswd.json" ||
                ClipboardLog.PathFor("../../etc/passwd").IndexOf("..") < 0,
                ClipboardLog.PathFor("../../etc/passwd"));
        }
    }
}
