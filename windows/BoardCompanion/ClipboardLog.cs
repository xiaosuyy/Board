using System;
using System.Collections.Generic;
using System.Web.Script.Serialization;

namespace BoardCompanion
{
    /// <summary>增量 Clipboard History 的一条事件（B1，只做 Text）</summary>
    public sealed class ClipboardLogEvent
    {
        public long seq { get; set; }
        public string itemId { get; set; }
        public string hash { get; set; }
        public string type { get; set; }
        public long createdAt { get; set; }
        public long size { get; set; }
        /// <summary>≤256 KiB 的 Text 正文**内联**；其余类型/超长一律不产生 event（text 为 null）</summary>
        public string text { get; set; }

        public ClipboardLogEvent()
        {
            itemId = string.Empty;
            hash = string.Empty;
            type = ClipboardLog.TYPE_TEXT;
            text = null;
        }
    }

    /// <summary>一台设备一份的增量历史日志（`/clipboard-log/&lt;deviceId&gt;.json`）</summary>
    public sealed class ClipboardLogFile
    {
        public int schema { get; set; }
        public string deviceId { get; set; }
        public string deviceType { get; set; }
        public long updatedAt { get; set; }
        /// <summary>已被裁剪掉的最小 seq（消费方据此判断“是否漏了历史”）</summary>
        public long minSeq { get; set; }
        public List<ClipboardLogEvent> events { get; set; }

        public ClipboardLogFile()
        {
            schema = 1;
            deviceId = string.Empty;
            deviceType = string.Empty;
            events = new List<ClipboardLogEvent>();
        }
    }

    /// <summary>
    /// 增量 Clipboard History 的**纯逻辑**（B1）。
    ///
    /// 与 `SyncClipboard.json` 的 latest-state **完全并存、互不影响**：
    /// - latest-state 决定“当前剪贴板是什么”；
    /// - 本日志决定“最近产生过哪些不同的 Text”，供对端补齐 History。
    ///
    /// 与 Cloud Library **严格隔离**（不同目录、不同 TTL、不同删除语义）。
    /// </summary>
    public static class ClipboardLog
    {
        public const string FOLDER = "clipboard-log";
        public const string TYPE_TEXT = "Text";

        /// <summary>每设备日志最多保留的事件数</summary>
        public const int MAX_EVENTS = 200;
        /// <summary>每设备日志的保留时长（7 天，远大于 Clipboard 的 1h）</summary>
        public const long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;

        public static string PathFor(string deviceId)
        {
            return FOLDER + "/" + SanitizeToken(deviceId) + ".json";
        }

        /// <summary>设备 id 安全化（只留字母数字与 `-`）</summary>
        public static string SanitizeToken(string value)
        {
            if (string.IsNullOrEmpty(value)) return "device";
            var builder = new System.Text.StringBuilder();
            foreach (var c in value)
            {
                var ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                         || (c >= '0' && c <= '9') || c == '-';
                if (ok && builder.Length < 64) builder.Append(c);
            }
            return builder.Length == 0 ? "device" : builder.ToString();
        }

        /// <summary>
        /// **是否产生 history event**：只做 Text，且 ≤ 256 KiB（与内联上限一致）。
        /// Image / File / 超长 Text 一律**不产生**（B1 范围；File 永不自动上传）。
        /// </summary>
        public static bool ShouldRecord(string type, long size)
        {
            return string.Equals(type, TYPE_TEXT, StringComparison.Ordinal)
                   && size > 0 && size <= ClipboardPayload.INLINE_TEXT_MAX_BYTES;
        }

        /// <summary>`itemId = deviceId:seq`（全局唯一）</summary>
        public static string ItemIdFor(string deviceId, long seq)
        {
            return SanitizeToken(deviceId) + ":" + seq;
        }

        /// <summary>
        /// 崩溃窗口防御（seq 复用）。
        ///
        /// 场景：seq=N 已成功 PUT 到远端，但在持久化 seq 之前进程崩溃；
        /// 重启后本地水位落后，于是**又分配了 N** ⇒ itemId 冲突。
        ///
        /// 处理：若远端已存在同 itemId 但内容 hash **不同**，说明这个 seq 被复用了，
        /// 给它换一个**全新** seq。绝不丢弃内容、绝不覆盖远端已发布的事件。
        ///
        /// @return 被重新分配的条数
        /// </summary>
        public static int ReassignReusedSequences(
            IList<ClipboardLogEvent> batch,
            ClipboardLogFile remote,
            string deviceId,
            ClipboardLogSequence sequence)
        {
            if (batch == null || batch.Count == 0) return 0;

            var remoteHashById = new Dictionary<string, string>();
            if (remote != null && remote.events != null)
            {
                foreach (var item in remote.events)
                {
                    if (item == null || string.IsNullOrEmpty(item.itemId)) continue;
                    if (!remoteHashById.ContainsKey(item.itemId)) remoteHashById[item.itemId] = item.hash;
                }
            }

            var reassigned = 0;
            foreach (var item in batch)
            {
                if (item == null || string.IsNullOrEmpty(item.itemId)) continue;
                string remoteHash;
                if (!remoteHashById.TryGetValue(item.itemId, out remoteHash)) continue;
                if (remoteHash == item.hash) continue;   // 同 id 同内容 = 早已发布过，调用方会跳过它
                var fresh = sequence.Next();
                item.seq = fresh;
                item.itemId = ItemIdFor(deviceId, fresh);
                reassigned++;
            }
            return reassigned;
        }

        public static ClipboardLogFile Parse(string json)
        {
            if (string.IsNullOrEmpty(json)) return null;
            try
            {
                return new JavaScriptSerializer().Deserialize<ClipboardLogFile>(json);
            }
            catch (Exception)
            {
                return null;   // 坏 JSON ⇒ 本轮跳过（调用方不得推进水位）
            }
        }

        public static string Serialize(ClipboardLogFile file)
        {
            if (file == null) return "{}";
            return new JavaScriptSerializer().Serialize(file);
        }

        /// <summary>只取 `seq &gt; lastSeenSeq` 的事件，按 seq 升序（消费方逐个处理）</summary>
        public static List<ClipboardLogEvent> CandidatesFor(long lastSeenSeq, ClipboardLogFile file)
        {
            var result = new List<ClipboardLogEvent>();
            if (file == null || file.events == null) return result;
            foreach (var item in file.events)
            {
                if (item == null) continue;
                if (item.seq > lastSeenSeq) result.Add(item);
            }
            result.Sort((a, b) => a.seq.CompareTo(b.seq));
            return result;
        }

        /// <summary>
        /// 水位推进：**必须先收集成功处理过的 seq，全部成功后才一次性推进**；
        /// 中途失败（allSucceeded=false）⇒ 保持原水位，下次重来。
        /// </summary>
        public static long AdvanceWatermark(long current, IList<long> processedSeqs, bool allSucceeded)
        {
            if (!allSucceeded || processedSeqs == null || processedSeqs.Count == 0) return current;
            long max = current;
            for (int i = 0; i < processedSeqs.Count; i++)
            {
                if (processedSeqs[i] > max) max = processedSeqs[i];
            }
            return max;
        }

        /// <summary>是否检测到“日志缺档”（`minSeq` 已经超过我下一次该读的位置）</summary>
        public static bool GapDetected(long lastSeenSeq, ClipboardLogFile file)
        {
            if (file == null) return false;
            return file.minSeq > lastSeenSeq + 1;
        }

        /// <summary>
        /// 只裁剪**本设备自己的**日志：先按保留时长，再按最大条数；
        /// 更新 `minSeq` 且**不重排 seq**（保持原有 seq 值）。
        /// </summary>
        public static ClipboardLogFile Compact(ClipboardLogFile file, long now)
        {
            if (file == null) return null;
            var kept = new List<ClipboardLogEvent>();
            if (file.events != null)
            {
                foreach (var item in file.events)
                {
                    if (item == null) continue;
                    if (now - item.createdAt > RETENTION_MS) continue;
                    kept.Add(item);
                }
            }
            kept.Sort((a, b) => a.seq.CompareTo(b.seq));
            if (kept.Count > MAX_EVENTS) kept.RemoveRange(0, kept.Count - MAX_EVENTS);
            file.events = kept;
            if (kept.Count > 0) file.minSeq = kept[0].seq;
            return file;
        }
    }

    /// <summary>
    /// 设备本地 seq 分配器：**线程安全、严格单调、绝不回收**。
    /// （即使 PUT 失败也不回收 —— 否则 itemId 复用会被对端水位跳过。）
    /// </summary>
    public sealed class ClipboardLogSequence
    {
        private readonly object gate = new object();
        private long current;

        public ClipboardLogSequence(long start)
        {
            current = start;
        }

        public long Next()
        {
            lock (gate)
            {
                return ++current;
            }
        }

        public long Current
        {
            get { lock (gate) { return current; } }
        }
    }
}
