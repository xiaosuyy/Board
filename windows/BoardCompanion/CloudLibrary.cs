using System;
using System.Collections.Generic;
using System.Web.Script.Serialization;

namespace BoardCompanion
{
    /// <summary>Cloud Library 的一条资料（存在 `library/index-&lt;deviceId&gt;.json` 里）</summary>
    public sealed class CloudLibraryItem
    {
        public string hash { get; set; }
        /// <summary>`image` 或 `file`</summary>
        public string type { get; set; }
        /// <summary>原始文件名（仅用于展示；写入前已净化）</summary>
        public string name { get; set; }
        public long size { get; set; }
        /// <summary>**本次主动上传**的时间（epoch ms）。浏览/刷新/下载**都不修改它**。</summary>
        public long uploadedAt { get; set; }
        /// <summary>上传设备（用于来源判定，不一定与 index 的 deviceId 相同）</summary>
        public string deviceId { get; set; }

        public CloudLibraryItem()
        {
            hash = string.Empty;
            type = "file";
            name = string.Empty;
            size = 0;
            uploadedAt = 0;
            deviceId = string.Empty;
        }
    }

    /// <summary>一台设备一份的索引文件内容</summary>
    public sealed class CloudLibraryIndex
    {
        public int schema { get; set; }
        public string deviceId { get; set; }
        public string deviceType { get; set; }
        public long updatedAt { get; set; }
        public List<CloudLibraryItem> items { get; set; }

        public CloudLibraryIndex()
        {
            schema = 1;
            deviceId = string.Empty;
            deviceType = string.Empty;
            items = new List<CloudLibraryItem>();
        }
    }

    public enum LibraryDeleteResult
    {
        Deleted = 0,
        AlreadyGone = 1,
        Failed = 2,
    }

    /// <summary>Cloud Library 的远端抽象：生产实现走 WebDAV；测试注入假实现（**离线可测**）</summary>
    public interface ICloudLibraryRemote
    {
        /// <summary>读取**所有**设备的 index JSON 文本（读不到的跳过）</summary>
        List<string> ReadAllIndexJson();
        /// <summary>读取指定设备自己的 index JSON；不存在返回 null</summary>
        string ReadIndexJson(string deviceId);
        byte[] GetBytes(string binaryName);
        bool PutBytes(string binaryName, byte[] bytes, string contentType);
        bool PutIndexJson(string deviceId, string json);
        LibraryDeleteResult DeleteBinary(string binaryName);
    }

    /// <summary>
    /// Cloud Library 的**纯逻辑 + 流程**（Phase 1-H）。
    ///
    /// 与 Clipboard **完全隔离**：这里只碰 `library/`，绝不碰 `/file/` 与 `SyncClipboard.json`。
    ///
    /// 生命周期：用户主动上传 → `uploadedAt = now` → **24h** 后过期；
    /// 浏览 / 刷新 / 预览 / 下载**都不续期**；只有再次主动上传同 hash 才刷新。
    /// </summary>
    public static class CloudLibrary
    {
        public const int MAX_LIBRARY_BYTES = 10 * 1024 * 1024;
        public const long TTL_MS = 24L * 60L * 60L * 1000L;
        public const string FOLDER = "library";
        public const string INDEX_PREFIX = "index-";

        public static bool IsExpired(long now, long uploadedAt)
        {
            return now - uploadedAt >= TTL_MS;
        }

        public static bool WithinLimit(long bytes)
        {
            return bytes > 0 && bytes <= MAX_LIBRARY_BYTES;
        }

        /// <summary>`library/index-&lt;deviceId&gt;.json`（deviceId 已净化，防止路径穿越）</summary>
        public static string IndexPathFor(string deviceId)
        {
            return FOLDER + "/" + INDEX_PREFIX + SanitizeToken(deviceId) + ".json";
        }

        /// <summary>`library/&lt;hash&gt;&lt;.ext&gt;`（扩展名已净化）</summary>
        public static string BinaryPathFor(string hash, string extension)
        {
            return FOLDER + "/" + (string.IsNullOrEmpty(hash) ? "board" : hash)
                   + ClipboardFileCache.NormalizeExtension(extension);
        }

        /// <summary>deviceId 只保留字母数字与 `-`（索引文件名安全）</summary>
        /// <summary>日志用：设备 id 前缀（不记完整 token）</summary>
        public static string ShortDevice(string deviceId)
        {
            if (string.IsNullOrEmpty(deviceId)) return "-";
            return deviceId.Length <= 8 ? deviceId : deviceId.Substring(0, 8);
        }

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

        /// <summary>文件名净化：去掉目录部分与控制字符（只用于展示/落盘命名）</summary>
        public static string SanitizeFileName(string name)
        {
            if (string.IsNullOrEmpty(name)) return "board";
            var cut = name.LastIndexOfAny(new[] { '\\', '/' });
            var baseName = cut >= 0 ? name.Substring(cut + 1) : name;
            var builder = new System.Text.StringBuilder();
            foreach (var c in baseName)
            {
                if (c < 32 || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|')
                {
                    continue;
                }
                if (builder.Length < 128) builder.Append(c);
            }
            return builder.Length == 0 ? "board" : builder.ToString();
        }

        /// <summary>
        /// 来源文案：**本机主动上传 = 本地；其它设备 = 云端 · &lt;设备类型&gt;**。
        /// 不新增 UPLOADED/SYNCED 之类的第三套状态。
        /// </summary>
        public static string OriginText(string itemDeviceId, string myDeviceId, string deviceType)
        {
            if (!string.IsNullOrEmpty(itemDeviceId) && itemDeviceId == myDeviceId) return "本地";
            var device = string.IsNullOrEmpty(deviceType) ? "云端" : "云端 · " + deviceType;
            return device;
        }

        /// <summary>图片下载目标：`Pictures\Board\&lt;name&gt;`</summary>
        public static string ImageDownloadPath(string name)
        {
            var pictures = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
            return System.IO.Path.Combine(pictures, "Board", SanitizeFileName(name));
        }

        /// <summary>文件下载默认目录：`Downloads\Board`</summary>
        public static string FileDownloadDirectory()
        {
            return System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Board");
        }

        /// <summary>拖拽判定：**一次只允许一个文件**；多文件与文件夹都拒绝（不偷偷逐个传）</summary>
        public enum DropDecision
        {
            Accept = 0,
            RejectMultiple = 1,
            RejectFolder = 2,
            Ignore = 3,
        }

        public static DropDecision DecideDrop(string[] paths)
        {
            if (paths == null || paths.Length == 0) return DropDecision.Ignore;
            if (paths.Length > 1) return DropDecision.RejectMultiple;
            var path = paths[0];
            if (string.IsNullOrEmpty(path)) return DropDecision.Ignore;
            if (System.IO.Directory.Exists(path)) return DropDecision.RejectFolder;
            if (!System.IO.File.Exists(path)) return DropDecision.Ignore;
            return DropDecision.Accept;
        }

        public static CloudLibraryIndex ParseIndex(string json)
        {
            if (string.IsNullOrEmpty(json)) return null;
            try
            {
                return new JavaScriptSerializer().Deserialize<CloudLibraryIndex>(json);
            }
            catch (Exception)
            {
                return null;   // 坏 JSON 安全忽略
            }
        }

        public static string SerializeIndex(CloudLibraryIndex index)
        {
            if (index == null) return "{}";
            return new JavaScriptSerializer().Serialize(index);
        }

        /// <summary>
        /// 多设备索引合并：**按 hash 去重，`uploadedAt` 新的胜**（= 从最近一次主动上传算 TTL）。
        /// </summary>
        public static List<CloudLibraryItem> MergeIndexes(IEnumerable<string> indexJsons)
        {
            var map = new Dictionary<string, CloudLibraryItem>(StringComparer.OrdinalIgnoreCase);
            if (indexJsons != null)
            {
                foreach (var json in indexJsons)
                {
                    var index = ParseIndex(json);
                    if (index == null || index.items == null) continue;
                    foreach (var item in index.items)
                    {
                        if (item == null || string.IsNullOrEmpty(item.hash)) continue;
                        CloudLibraryItem existing;
                        if (!map.TryGetValue(item.hash, out existing) || item.uploadedAt > existing.uploadedAt)
                        {
                            map[item.hash] = item;
                        }
                    }
                }
            }
            var merged = new List<CloudLibraryItem>(map.Values);
            merged.Sort((a, b) => b.uploadedAt.CompareTo(a.uploadedAt));
            return merged;
        }

        /// <summary>
        /// 可删除判定（**关键安全规则**）：只有该 hash 在**所有设备**的合并结果里都已过期才可删。
        /// 因为合并取的是 `max(uploadedAt)` —— 任一设备未过期 ⇒ 合并值未过期 ⇒ 不出现在结果里。
        /// </summary>
        public static List<CloudLibraryItem> DeletableItems(long now, IEnumerable<CloudLibraryItem> merged)
        {
            var result = new List<CloudLibraryItem>();
            if (merged == null) return result;
            foreach (var item in merged)
            {
                if (item != null && !string.IsNullOrEmpty(item.hash) && IsExpired(now, item.uploadedAt))
                {
                    result.Add(item);
                }
            }
            return result;
        }
    }

    /// <summary>
    /// Cloud Library 的读写流程（上传 / 清理 / 下载）。
    ///
    /// **一致性顺序**（由测试固化）：
    /// - 上传：`PUT binary` 成功 → `PUT index`；index 失败 → **尽力 DELETE 刚上传的 binary**（不留孤儿）
    /// - 清理：读全部 index → 判定 → **再次读全部 index 再判定** → `DELETE binary` → 删除自己 index 条目；
    ///         DELETE 失败 → **保留自己的 index 条目**，等下次 prune
    /// </summary>
    public sealed class CloudLibraryService
    {
        private readonly ICloudLibraryRemote remote;
        private readonly string deviceId;
        private readonly string deviceType;

        public CloudLibraryService(ICloudLibraryRemote remote, string deviceId, string deviceType)
        {
            this.remote = remote;
            this.deviceId = deviceId ?? string.Empty;
            this.deviceType = deviceType ?? string.Empty;
        }

        /// <summary>拉取所有设备索引并合并（UI 列表用）</summary>
        public List<CloudLibraryItem> LoadAll()
        {
            return CloudLibrary.MergeIndexes(remote.ReadAllIndexJson());
        }

        /// <summary>用户**主动上传**：成功返回 true（失败不写 index，绝不留半成品）</summary>
        public bool Upload(string fileName, byte[] bytes, string type)
        {
            if (bytes == null || !CloudLibrary.WithinLimit(bytes.Length)) return false;
            var hash = ClipboardHasher.HashBytes(bytes);
            var safeName = CloudLibrary.SanitizeFileName(fileName);
            var binaryPath = CloudLibrary.BinaryPathFor(hash, System.IO.Path.GetExtension(safeName));

            if (!remote.PutBytes(binaryPath, bytes, ClipboardSyncEngine.MimeFor(binaryPath)))
            {
                Log.Warn("library upload binary failed bytes=" + bytes.Length);
                return false;
            }

            var index = LoadOwnIndex();
            Upsert(index, new CloudLibraryItem
            {
                hash = hash,
                type = string.Equals(type, "image", StringComparison.OrdinalIgnoreCase) ? "image" : "file",
                name = safeName,
                size = bytes.Length,
                uploadedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                deviceId = deviceId,
            });
            index.deviceId = deviceId;
            index.deviceType = deviceType;

            if (!remote.PutIndexJson(deviceId, CloudLibrary.SerializeIndex(index)))
            {
                // index 写失败 → 尽力回滚刚发布的二进制，绝不留“无索引的垃圾文件”
                var rollback = remote.DeleteBinary(binaryPath);
                Log.Warn("library upload index failed; rollback binary result=" + rollback);
                return false;
            }
            return true;
        }

        /// <summary>
        /// 清理过期资料。返回实际删除的二进制数量。
        /// **删除前会再次读取全部 index 重新判定**（收敛并发窗口）；DELETE 失败则保留索引条目。
        /// </summary>
        public int Prune(long now)
        {
            Log.Info("library prune start deviceId=" + Short(deviceId));
            var all = LoadAll();
            var candidates = CloudLibrary.DeletableItems(now, all);
            Log.Info("library prune indexes items=" + all.Count + " candidates=" + candidates.Count);
            if (candidates.Count == 0) return 0;

            // 二次读取再判定：期间若有别的设备重新上传同 hash，就会被排除
            candidates = CloudLibrary.DeletableItems(now, LoadAll());
            Log.Info("library prune confirmed=" + candidates.Count);

            var index = LoadOwnIndex();
            var removed = 0;
            foreach (var item in candidates)
            {
                var extension = System.IO.Path.GetExtension(item.name);
                var binaryPath = CloudLibrary.BinaryPathFor(item.hash, extension);
                var result = remote.DeleteBinary(binaryPath);
                Log.Info("library prune delete hash=" + Short(item.hash) + extension + " result=" + result);
                if (result == LibraryDeleteResult.Failed)
                {
                    // 删除失败：**保留自己的 index 条目**，等下次 prune（一致性优先）
                    continue;
                }
                removed++;
                index.items.RemoveAll(x => string.Equals(x.hash, item.hash, StringComparison.OrdinalIgnoreCase)
                                           && x.deviceId == deviceId);
            }

            if (removed > 0)
            {
                remote.PutIndexJson(deviceId, CloudLibrary.SerializeIndex(index));
            }
            Log.Info("library prune done candidates=" + candidates.Count + " deleted=" + removed);
            return removed;
        }

        /// <summary>只记录 hash 前缀（不记完整 token / 路径）</summary>
        private static string Short(string value)
        {
            if (string.IsNullOrEmpty(value)) return "-";
            return value.Length <= 8 ? value : value.Substring(0, 8);
        }

        /// <summary>用户**主动下载**：**不修改任何 uploadedAt**（下载不续期）</summary>
        public byte[] Download(CloudLibraryItem item)
        {
            if (item == null || string.IsNullOrEmpty(item.hash)) return null;
            return remote.GetBytes(CloudLibrary.BinaryPathFor(item.hash, System.IO.Path.GetExtension(item.name)));
        }

        private CloudLibraryIndex LoadOwnIndex()
        {
            var json = remote.ReadIndexJson(deviceId);
            var index = CloudLibrary.ParseIndex(json);
            if (index == null) index = new CloudLibraryIndex();
            if (index.items == null) index.items = new List<CloudLibraryItem>();
            return index;
        }

        private void Upsert(CloudLibraryIndex index, CloudLibraryItem item)
        {
            index.items.RemoveAll(x => string.Equals(x.hash, item.hash, StringComparison.OrdinalIgnoreCase)
                                       && x.deviceId == deviceId);
            index.items.Add(item);
        }
    }
}
