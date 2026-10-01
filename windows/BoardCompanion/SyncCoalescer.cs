using System;

namespace BoardCompanion
{
    /// <summary>
    /// 同步执行者的「单执行者 + 合并补跑」闸门（D4）。
    ///
    /// 语义：
    /// - [TryEnter]：空闲 ⇒ 取得执行权；**忙 ⇒ 不启动第二个执行者**，
    ///   只把 `pending` 置位并返回 false（请求不丢）。
    /// - [ShouldRepeat]：一次执行结束后调用；若期间来过请求 ⇒ 返回 true
    ///   并清掉标志（**合并**成一次补跑）。
    /// - [Exit]：释放执行权。
    ///
    /// 目的：快速连续复制时 latest-state 不再因为 busy 无条件等 60 秒 tick。
    /// 不新增常驻线程 —— 补跑就在**当前**这次调用的线程里继续跑。
    /// </summary>
    public sealed class SyncCoalescer
    {
        private readonly object gate = new object();
        private bool busy;
        private bool pending;

        public bool TryEnter()
        {
            lock (gate)
            {
                if (busy)
                {
                    pending = true;
                    return false;
                }
                busy = true;
                return true;
            }
        }

        public bool ShouldRepeat()
        {
            lock (gate)
            {
                if (!pending) return false;
                pending = false;
                return true;
            }
        }

        public void Exit()
        {
            lock (gate) { busy = false; }
        }

        public bool IsBusy
        {
            get { lock (gate) { return busy; } }
        }

        public bool HasPending
        {
            get { lock (gate) { return pending; } }
        }
    }
}
