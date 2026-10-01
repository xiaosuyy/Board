using System;

namespace BoardCompanion
{
    /// <summary>
    /// 通知级去重（D2）。
    ///
    /// **只用** Windows 的剪贴板序号 `GetClipboardSequenceNumber()` 判断
    /// 「这是不是同一次剪贴板变化」：
    /// - 一次真实变化会产生多条 `WM_CLIPBOARDUPDATE`（实测一次复制 3~4 条），
    ///   它们的序号相同 ⇒ 只捕获第一条；
    /// - 下一次真实变化序号 +1 ⇒ **即使内容 hash 完全相同也会再捕获一次**。
    ///
    /// 关键：hash 仍然是 **History identity**，绝不参与通知级去重。
    /// 序号本身**不写入任何日志**。
    /// </summary>
    public sealed class ClipboardNotificationGate
    {
        private readonly object gate = new object();
        private uint last;
        private bool seen;

        /// <returns>true = 这是一次**新的**剪贴板变化，应当捕获</returns>
        public bool ShouldCapture(uint sequence)
        {
            // 取不到序号（0）时不参与去重 —— 宁可多捕获，不可漏
            if (sequence == 0) return true;
            lock (gate)
            {
                if (seen && sequence == last) return false;
                seen = true;
                last = sequence;
                return true;
            }
        }

        public uint LastSequence
        {
            get { lock (gate) { return last; } }
        }

        /// <summary>
        /// 同一次复制的**内容级**合并窗口（实测驱动）。
        ///
        /// 实测：一次 `Clipboard.SetText` 会产生**两个不同的序号**
        /// （`EmptyClipboard` 一次、`SetClipboardData` 一次），所以只靠序号
        /// 仍然会得到 2 条通知。这里补一层**时间窗 + 内容相同**的合并：
        /// - 窗口内内容相同 ⇒ 视为同一次变化（只捕获一次）；
        /// - 内容不同（A→B）⇒ 仍是两次（D3 不受影响）；
        /// - 超过窗口再复制同样内容 ⇒ **照样产生新 event**
        ///   （所以这不是"相同 hash 不再产生 event"）。
        /// </summary>
        public const long BurstMs = 120;

        private const int CompareChars = 4096;

        private string lastContent;
        private long lastContentMs;
        private bool seenContent;

        public bool ShouldCaptureContent(string content, long nowMs)
        {
            if (content == null) return true;   // 非文本一律照常
            var compare = content.Length > CompareChars ? content.Substring(0, CompareChars) : content;
            lock (gate)
            {
                if (seenContent && nowMs - lastContentMs <= BurstMs &&
                    string.Equals(lastContent, compare, StringComparison.Ordinal))
                {
                    lastContentMs = nowMs;   // 滑动窗口：整串重复通知都并成一次
                    return false;
                }
                seenContent = true;
                lastContent = compare;
                lastContentMs = nowMs;
                return true;
            }
        }
    }

    /// <summary>
    /// 一次通知里**同步捕获**到的剪贴板快照。
    ///
    /// 正文只在内存（不落盘）；D3 的意义在于：文本是在通知到达的**那一瞬间**
    /// 读到的，而不是等排到 UI 线程后再去读"当前剪贴板"。
    /// </summary>
    public sealed class ClipboardSnapshot
    {
        public uint Sequence { get; set; }
        public bool HasImage { get; set; }
        public bool HasFile { get; set; }
        public string Text { get; set; }
    }
}
