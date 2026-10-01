using System;
using System.Collections.Generic;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace BoardCompanion
{
    /// <summary>
    /// 「云端资料」独立窗口（Phase 1-H）。
    ///
    /// **线程模型（本轮重点）**：
    /// - **所有** WebDAV 网络 I/O、文件 I/O、图片下载与**解码**都在后台线程（`Task.Run`）执行；
    /// - `Render()` **只做 UI**，**绝不发起任何网络请求**；
    /// - 缩略图**按需异步加载**：UI 先出占位（无图），后台下载+解码，完成后 `BeginInvoke` 回填对应行；
    /// - 同 hash 的缩略图复用**窗口生命周期内的内存缓存**（`thumbnailCache`），
    ///   **不落盘、不持久化**，`FormClosed` 时统一 `Dispose` 并清空。
    ///
    /// 与 Clipboard **完全分离**：只展示/管理 `library/`。
    /// </summary>
    public sealed class CloudLibraryForm : Form
    {
        private readonly CloudLibraryService service;
        private readonly string myDeviceId;
        private readonly string myDeviceType;

        private readonly Button tabImages = new Button();
        private readonly Button tabFiles = new Button();
        private readonly Button refreshButton = new Button();
        private readonly Button addButton = new Button();
        private readonly Button downloadButton = new Button();
        private readonly ListView list = new ListView();
        private readonly Label message = new Label();
        private readonly System.Windows.Forms.Timer messageTimer = new System.Windows.Forms.Timer();
        private readonly ImageList thumbnails = new ImageList();

        /// <summary>窗口生命周期内的**内存**缩略图缓存（key = hash）；FormClosed 全部 Dispose</summary>
        private readonly Dictionary<string, Image> thumbnailCache = new Dictionary<string, Image>();
        /// <summary>正在后台加载的 hash（避免重复请求）</summary>
        private readonly HashSet<string> loadingThumbnails = new HashSet<string>();
        /// <summary>hash → 列表行（异步回填缩略图用）</summary>
        private readonly Dictionary<string, ListViewItem> rows = new Dictionary<string, ListViewItem>();

        private string section = "image";
        private List<CloudLibraryItem> items = new List<CloudLibraryItem>();
        private bool refreshing;

        public CloudLibraryForm(CloudLibraryService service, string myDeviceId, string myDeviceType)
        {
            this.service = service;
            this.myDeviceId = myDeviceId;
            this.myDeviceType = myDeviceType;

            Text = "云端资料";
            Width = 760;
            Height = 520;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.White;
            AllowDrop = true;

            var top = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 40, Padding = new Padding(8) };
            Style(tabImages, "云端图片");
            Style(tabFiles, "云端文件");
            tabImages.Click += (s, e) => SwitchSection("image");
            tabFiles.Click += (s, e) => SwitchSection("file");
            Style(refreshButton, "刷新");
            refreshButton.Click += (s, e) => Reload();
            Style(addButton, "+");
            addButton.Click += (s, e) => PickAndUpload();
            top.Controls.Add(tabImages);
            top.Controls.Add(tabFiles);
            top.Controls.Add(refreshButton);
            top.Controls.Add(addButton);

            thumbnails.ImageSize = new Size(24, 24);
            thumbnails.ColorDepth = ColorDepth.Depth32Bit;
            list.View = View.Details;
            list.FullRowSelect = true;
            list.MultiSelect = false;
            list.Dock = DockStyle.Fill;
            list.SmallImageList = thumbnails;
            list.Columns.Add("名称 · 大小", 480);
            list.Columns.Add("来源", 160);

            var bottom = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 44, Padding = new Padding(8) };
            Style(downloadButton, "下载");
            downloadButton.Click += (s, e) => DownloadSelected();
            bottom.Controls.Add(downloadButton);

            message.AutoSize = true;
            message.ForeColor = Color.DimGray;
            message.Padding = new Padding(8, 0, 0, 0);
            bottom.Controls.Add(message);

            messageTimer.Interval = 1700;
            messageTimer.Tick += (s, e) => { messageTimer.Stop(); message.Text = string.Empty; };

            Controls.Add(list);
            Controls.Add(bottom);
            Controls.Add(top);

            DragEnter += OnDragEnter;
            DragDrop += OnDragDrop;
            // 窗口显示后再刷新（异步，不阻塞绘制）
            Shown += (s, e) => Reload();
            FormClosed += (s, e) => ReleaseThumbnailCache();
        }

        private static void Style(Button button, string text)
        {
            button.Text = text;
            button.AutoSize = true;
            button.Margin = new Padding(4);
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderColor = Color.Gainsboro;
        }

        private void Say(string text)
        {
            message.Text = text;
            messageTimer.Stop();
            messageTimer.Start();
        }

        /// <summary>只在 UI 线程调用；若窗口已释放则忽略</summary>
        private void BeginInvokeSafe(Action action)
        {
            if (IsDisposed) return;
            try
            {
                BeginInvoke(action);
            }
            catch (Exception)
            {
                // 窗口正在销毁
            }
        }

        /// <summary>释放内存缩略图缓存（不触碰任何磁盘文件）</summary>
        private void ReleaseThumbnailCache()
        {
            messageTimer.Stop();
            thumbnails.Images.Clear();
            lock (thumbnailCache)
            {
                foreach (var image in thumbnailCache.Values)
                {
                    try { image.Dispose(); } catch (Exception) { }
                }
                thumbnailCache.Clear();
            }
            lock (loadingThumbnails) { loadingThumbnails.Clear(); }
        }

        private void SwitchSection(string value)
        {
            section = value;
            Render();
        }

        /// <summary>刷新：prune + 读取全部 index **都在后台线程**；完成后回 UI 渲染</summary>
        private void Reload()
        {
            if (refreshing) return;
            refreshing = true;
            Say("正在刷新…");
            Task.Run(() =>
            {
                List<CloudLibraryItem> loaded;
                try
                {
                    service.Prune(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());   // 网络：后台线程
                    loaded = service.LoadAll();                                      // 网络：后台线程
                }
                catch (Exception)
                {
                    loaded = new List<CloudLibraryItem>();
                }
                return loaded;
            }).ContinueWith(task =>
            {
                BeginInvokeSafe(() =>
                {
                    refreshing = false;
                    items = task.Result ?? new List<CloudLibraryItem>();
                    Render();
                    message.Text = string.Empty;
                });
            });
        }

        /// <summary>
        /// 渲染：**只做 UI**。缩略图绝不在这里下载 —— 命中内存缓存就画，
        /// 否则投递一个后台加载任务（UI 先出占位）。
        /// </summary>
        private void Render()
        {
            list.BeginUpdate();
            list.Items.Clear();
            thumbnails.Images.Clear();
            rows.Clear();
            foreach (var item in items)
            {
                if (item == null) continue;
                var isImage = string.Equals(item.type, "image", StringComparison.OrdinalIgnoreCase);
                if (isImage != (section == "image")) continue;

                var row = new ListViewItem(item.name + " · " + SizeLabel(item.size));
                row.SubItems.Add(CloudLibrary.OriginText(item.deviceId, myDeviceId, myDeviceType));
                row.Tag = item;
                rows[item.hash] = row;

                if (isImage)
                {
                    Image cached = null;
                    lock (thumbnailCache)
                    {
                        if (!string.IsNullOrEmpty(item.hash)) thumbnailCache.TryGetValue(item.hash, out cached);
                    }
                    if (cached != null)
                    {
                        thumbnails.Images.Add(cached);
                        row.ImageIndex = thumbnails.Images.Count - 1;
                    }
                    else
                    {
                        EnsureThumbnailAsync(item);   // 只投递后台任务，绝不阻塞
                    }
                }
                list.Items.Add(row);
            }
            list.EndUpdate();
        }

        /// <summary>按需异步加载缩略图：**下载 + 解码都在后台**，完成后回填该行</summary>
        private void EnsureThumbnailAsync(CloudLibraryItem item)
        {
            if (item == null || string.IsNullOrEmpty(item.hash)) return;
            lock (thumbnailCache)
            {
                if (thumbnailCache.ContainsKey(item.hash)) return;
            }
            lock (loadingThumbnails)
            {
                if (!loadingThumbnails.Add(item.hash)) return;   // 已在加载
            }

            var capture = item;
            Task.Run(() =>
            {
                Image thumb = null;
                try
                {
                    var bytes = service.Download(capture);                        // 网络：后台线程
                    if (bytes != null)
                    {
                        using (var original = ClipboardImage.DecodePng(bytes))    // 解码：后台线程
                        {
                            if (original != null) thumb = new Bitmap(original, new Size(24, 24));
                        }
                    }
                }
                catch (Exception)
                {
                    thumb = null;
                }

                BeginInvokeSafe(() =>
                {
                    lock (loadingThumbnails) { loadingThumbnails.Remove(capture.hash); }
                    if (thumb == null) return;
                    lock (thumbnailCache) { thumbnailCache[capture.hash] = thumb; }
                    ListViewItem row;
                    if (!rows.TryGetValue(capture.hash, out row)) return;
                    if (row.ListView == null) return;
                    thumbnails.Images.Add(thumb);
                    row.ImageIndex = thumbnails.Images.Count - 1;
                });
            });
        }

        private static string SizeLabel(long bytes)
        {
            if (bytes >= 1024L * 1024L) return (bytes / 1024.0 / 1024.0).ToString("0.0") + " MB";
            if (bytes >= 1024L) return (bytes / 1024.0).ToString("0.0") + " KB";
            return bytes + " B";
        }

        private void PickAndUpload()
        {
            using (var dialog = new OpenFileDialog())
            {
                dialog.Multiselect = false;
                dialog.CheckFileExists = true;
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                UploadSingleAsync(dialog.FileName);
            }
        }

        /// <summary>上传：**读文件 + 网络 PUT 都在后台**；完成后回 UI 提示并刷新</summary>
        private void UploadSingleAsync(string path)
        {
            Say("正在上传…");
            Task.Run(() =>
            {
                string failure = null;
                try
                {
                    var bytes = System.IO.File.ReadAllBytes(path);                 // 文件 I/O：后台线程
                    if (!CloudLibrary.WithinLimit(bytes.Length))
                    {
                        failure = "× 超过 10 MiB，未上传";
                    }
                    else
                    {
                        var extension = System.IO.Path.GetExtension(path).ToLowerInvariant();
                        var type = (extension == ".png" || extension == ".jpg" || extension == ".jpeg"
                                    || extension == ".gif" || extension == ".webp" || extension == ".bmp")
                            ? "image" : "file";
                        if (!service.Upload(System.IO.Path.GetFileName(path), bytes, type))   // 网络：后台线程
                        {
                            failure = "× 上传失败";
                        }
                    }
                }
                catch (Exception)
                {
                    failure = "× 上传失败";
                }

                BeginInvokeSafe(() =>
                {
                    if (failure == null)
                    {
                        Say("✓ 已上传");
                        Reload();
                    }
                    else
                    {
                        Say(failure);
                    }
                });
            });
        }

        private void OnDragEnter(object sender, DragEventArgs e)
        {
            e.Effect = (e.Data != null && e.Data.GetDataPresent(DataFormats.FileDrop))
                ? DragDropEffects.Copy : DragDropEffects.None;
        }

        private void OnDragDrop(object sender, DragEventArgs e)
        {
            if (e.Data == null || !e.Data.GetDataPresent(DataFormats.FileDrop)) return;
            var paths = e.Data.GetData(DataFormats.FileDrop) as string[];
            var decision = CloudLibrary.DecideDrop(paths);
            if (decision == CloudLibrary.DropDecision.RejectMultiple) { Say("一次只能上传一个文件"); return; }
            if (decision == CloudLibrary.DropDecision.RejectFolder) { Say("暂不支持文件夹"); return; }
            if (decision == CloudLibrary.DropDecision.Ignore) return;
            UploadSingleAsync(paths[0]);
        }

        /// <summary>下载：文件选择框在 UI 线程；**网络下载 + 落盘都在后台**</summary>
        private void DownloadSelected()
        {
            if (list.SelectedItems.Count == 0) { Say("请先选中一条"); return; }
            var item = list.SelectedItems[0].Tag as CloudLibraryItem;
            if (item == null) return;

            var isImage = string.Equals(item.type, "image", StringComparison.OrdinalIgnoreCase);
            string target;
            if (isImage)
            {
                target = CloudLibrary.ImageDownloadPath(item.name);
            }
            else
            {
                using (var dialog = new SaveFileDialog())
                {
                    dialog.FileName = CloudLibrary.SanitizeFileName(item.name);
                    dialog.InitialDirectory = CloudLibrary.FileDownloadDirectory();
                    if (dialog.ShowDialog(this) != DialogResult.OK) return;
                    target = dialog.FileName;
                }
            }

            Say("正在下载…");
            var capture = item;
            Task.Run(() =>
            {
                var ok = false;
                try
                {
                    var bytes = service.Download(capture);                          // 网络：后台线程
                    if (bytes != null)
                    {
                        var dir = System.IO.Path.GetDirectoryName(target);
                        if (!string.IsNullOrEmpty(dir)) System.IO.Directory.CreateDirectory(dir);
                        System.IO.File.WriteAllBytes(target, bytes);                // 落盘：后台线程
                        ok = true;
                    }
                }
                catch (Exception)
                {
                    ok = false;
                }
                BeginInvokeSafe(() => Say(ok ? (isImage ? "✓ 已下载到相册" : "✓ 已下载") : "× 下载失败"));
            });
        }
    }
}
