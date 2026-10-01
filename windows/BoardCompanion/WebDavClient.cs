using System;
using System.IO;
using System.Net;
using System.Text;

namespace BoardCompanion
{
    public enum WebDavMethod
    {
        Get = 0,
        Put = 1,
        MkCol = 2,
        Delete = 3,
    }

    public sealed class WebDavResult
    {
        public bool IsOk { get; set; }
        public int StatusCode { get; set; }
        public string Body { get; set; }
        /// <summary>二进制读取（图片）时的原始字节；文本读取时为 null</summary>
        public byte[] BodyBytes { get; set; }
        public string Error { get; set; }
        public bool NotFound { get; set; }

        public static WebDavResult Ok(int status, string body)
        {
            return new WebDavResult { IsOk = true, StatusCode = status, Body = body };
        }

        public static WebDavResult Fail(int status, string error, bool notFound)
        {
            return new WebDavResult
            {
                IsOk = false,
                StatusCode = status,
                Error = error,
                NotFound = notFound,
            };
        }
    }

    /// <summary>
    /// 极简 WebDAV 客户端：只用 **GET / PUT / MKCOL**（第一阶段够用）。
    ///
    /// 与 Android 保持一致的关键点：
    /// - **HTTP 2xx 都算成功**（Android 实测 PUT 会得到 201 / 204）；
    /// - Basic Auth 走 **Authorization 头**，绝不把账号密码拼进 URL；
    /// - 任何日志都不含密码 / Authorization / 正文。
    /// </summary>
    public sealed class WebDavClient
    {
        /// <summary>
        /// 显式启用 TLS 1.2 / 1.3。
        ///
        /// .NET Framework 在部分机器/注册表配置下仍会用旧协议协商，表现为
        /// "TLS 握手失败"（服务器拒绝旧协议）。这里只**加强**协议版本，
        /// **绝不**放宽证书校验（保持默认的 ServerCertificateValidationCallback）。
        /// </summary>
        static WebDavClient()
        {
            try
            {
                // 0x0C00 = Tls12，0x3000 = Tls13（用数值，避免依赖枚举在各版本是否可用）
                const SecurityProtocolType modern = (SecurityProtocolType)(0x0C00 | 0x3000);
                ServicePointManager.SecurityProtocol |= modern;
            }
            catch (Exception)
            {
                // 不支持就保持系统默认
            }
        }

        private readonly string baseUrl;
        private readonly string username;
        private readonly string password;

        public WebDavClient(string baseUrl, string username, string password)
        {
            this.baseUrl = (baseUrl ?? string.Empty).TrimEnd('/');
            this.username = username ?? string.Empty;
            this.password = password ?? string.Empty;
        }

        public WebDavResult Get(string path)
        {
            return Execute(WebDavMethod.Get, path, null, null);
        }

        public WebDavResult PutText(string path, string text, string contentType)
        {
            return Execute(WebDavMethod.Put, path, text ?? string.Empty, contentType);
        }

        /// <summary>GET 二进制（图片 / 文件）。**绝不经 UTF-8 字符串中转**。</summary>
        public WebDavResult GetBytes(string path)
        {
            return Execute(WebDavMethod.Get, path, null, null, null, readBinary: true);
        }

        /// <summary>PUT 二进制（图片 / 文件）。**绝不经 UTF-8 字符串中转**。</summary>
        public WebDavResult PutBytes(string path, byte[] bytes, string contentType)
        {
            return Execute(WebDavMethod.Put, path, null, contentType, bytes ?? new byte[0], readBinary: false);
        }

        public WebDavResult MkCol(string path)
        {
            return Execute(WebDavMethod.MkCol, path, null, null);
        }

        /// <summary>
        /// DELETE（Phase 1-H：Cloud Library 真正释放云盘空间时使用）。
        /// **语义**：200/204 = 成功；**404 视为成功**（已经被别人删掉，幂等）；
        /// 401/403/405/5xx/网络错误 = 失败（调用方必须保留索引条目，绝不假装清理成功）。
        /// 只新增方法，**不影响**任何现有调用与协议。
        /// </summary>
        public WebDavResult Delete(string path)
        {
            return Execute(WebDavMethod.Delete, path, null, null);
        }

        private WebDavResult Execute(
            WebDavMethod method,
            string path,
            string body,
            string contentType,
            byte[] binaryBody = null,
            bool readBinary = false)
        {
            var url = baseUrl + "/" + (path ?? string.Empty).TrimStart('/');
            HttpWebRequest request;
            try
            {
                request = (HttpWebRequest)WebRequest.Create(url);
            }
            catch (Exception)
            {
                return WebDavResult.Fail(0, "地址不合法", false);
            }

            request.Method =
                method == WebDavMethod.Get ? "GET" :
                method == WebDavMethod.Put ? "PUT" :
                method == WebDavMethod.Delete ? "DELETE" : "MKCOL";
            request.Timeout = 20000;
            request.ReadWriteTimeout = 20000;
            request.AllowAutoRedirect = true;
            request.UserAgent = "BoardCompanion/0.1";

            if (!string.IsNullOrEmpty(username) || !string.IsNullOrEmpty(password))
            {
                var raw = username + ":" + password;
                request.Headers["Authorization"] =
                    "Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes(raw));
            }

            if (method == WebDavMethod.Put)
            {
                var bytes = binaryBody ?? Encoding.UTF8.GetBytes(body ?? string.Empty);
                request.ContentType = string.IsNullOrEmpty(contentType)
                    ? "application/json; charset=utf-8"
                    : contentType;
                request.ContentLength = bytes.Length;
                try
                {
                    using (var stream = request.GetRequestStream())
                    {
                        stream.Write(bytes, 0, bytes.Length);
                    }
                }
                catch (Exception)
                {
                    return WebDavResult.Fail(0, "上传失败", false);
                }
            }
            else if (method == WebDavMethod.MkCol)
            {
                request.ContentLength = 0;
            }

            try
            {
                using (var response = (HttpWebResponse)request.GetResponse())
                {
                    int status = (int)response.StatusCode;
                    string text = string.Empty;
                    byte[] bytes = null;
                    if (method == WebDavMethod.Get)
                    {
                        if (readBinary)
                        {
                            // 有界读取：最多多读 1 字节即可判定“超过 10 MiB”
                            bytes = ReadAll(response, ClipboardPayload.MAX_IMAGE_BYTES + 1);
                        }
                        else
                        {
                            using (var reader = new System.IO.StreamReader(response.GetResponseStream(), Encoding.UTF8))
                            {
                                text = reader.ReadToEnd();
                            }
                        }
                    }
                    // 2xx = 成功（与 Android 一致，不只认 200）
                    bool ok = status >= 200 && status < 300;
                    if (!ok) return WebDavResult.Fail(status, "HTTP " + status, false);
                    var result = WebDavResult.Ok(status, text);
                    result.BodyBytes = bytes;
                    return result;
                }
            }
            catch (WebException ex)
            {
                var response = ex.Response as HttpWebResponse;
                if (response != null)
                {
                    int status = (int)response.StatusCode;
                    response.Close();
                    bool notFound = status == 404;
                    return WebDavResult.Fail(status, "HTTP " + status, notFound);
                }
                if (ex.Status == WebExceptionStatus.SecureChannelFailure ||
                    ex.Status == WebExceptionStatus.TrustFailure)
                {
                    // 只记类别与底层异常类型/消息（不含 URL 与凭据），便于对症
                    var inner = ex.InnerException;
                    var detail = inner == null ? string.Empty : inner.GetType().Name;
                    if (inner != null && !string.IsNullOrEmpty(inner.Message))
                    {
                        var message = inner.Message;
                        detail += ": " + (message.Length > 200 ? message.Substring(0, 200) : message);
                    }
                    Log.Warn("webdav tls failure status=" + ex.Status + " " + detail);
                }
                return WebDavResult.Fail(0, Describe(ex.Status), false);
            }
            catch (Exception)
            {
                return WebDavResult.Fail(0, "请求失败", false);
            }
        }

        /// <summary>
        /// 把响应体读成字节。**有界**：最多读 [maxBytes]（≤0 表示不限），
        /// 因此即使服务器返回超大文件也不会把内存打爆（超限时调用方按“超过 10 MiB”拒绝）。
        /// </summary>
        private static byte[] ReadAll(HttpWebResponse response, int maxBytes)
        {
            int limit = maxBytes > 0 ? maxBytes : int.MaxValue;
            using (var input = response.GetResponseStream())
            using (var output = new MemoryStream())
            {
                var buffer = new byte[64 * 1024];
                while (true)
                {
                    int read = input.Read(buffer, 0, buffer.Length);
                    if (read <= 0) break;
                    output.Write(buffer, 0, read);
                    if (output.Length > limit) break;
                }
                return output.ToArray();
            }
        }

        /// <summary>
        /// 把底层网络异常翻译成**能对症**的中文提示。
        ///
        /// 以前所有非 HTTP 失败都写"网络不可用"，用户根本无法判断是域名写错了、
        /// 证书不受信任、还是端口没开 —— 这里按 WebExceptionStatus 区分。
        /// 只输出故障类别，不含 URL query / 凭据。
        /// </summary>
        public static string Describe(WebExceptionStatus status)
        {
            switch (status)
            {
                case WebExceptionStatus.NameResolutionFailure:
                case WebExceptionStatus.ProxyNameResolutionFailure:
                    return "域名解析失败（地址写错，或本机 DNS 解析不了这个域名）";
                case WebExceptionStatus.TrustFailure:
                    return "HTTPS 证书校验失败（服务器证书不受信任；自签名证书需要导入信任）";
                case WebExceptionStatus.SecureChannelFailure:
                    return "TLS 握手失败（服务器可能只支持较新的 TLS 版本）";
                case WebExceptionStatus.Timeout:
                    return "连接超时（服务器没有响应）";
                case WebExceptionStatus.ConnectFailure:
                    return "无法连接（端口未开放 / 服务器不可达 / 被当前网络拦截）";
                case WebExceptionStatus.ReceiveFailure:
                case WebExceptionStatus.SendFailure:
                    return "传输中断（连接被中途断开）";
                default:
                    return "网络层失败（" + status + "）";
            }
        }
    }
}
