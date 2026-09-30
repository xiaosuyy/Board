using System;
using System.Net;
using System.Text;

namespace BoardCompanion
{
    public enum WebDavMethod
    {
        Get = 0,
        Put = 1,
        MkCol = 2,
    }

    public sealed class WebDavResult
    {
        public bool IsOk { get; set; }
        public int StatusCode { get; set; }
        public string Body { get; set; }
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

        public WebDavResult MkCol(string path)
        {
            return Execute(WebDavMethod.MkCol, path, null, null);
        }

        private WebDavResult Execute(WebDavMethod method, string path, string body, string contentType)
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

            request.Method = method == WebDavMethod.Get ? "GET" : (method == WebDavMethod.Put ? "PUT" : "MKCOL");
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
                var bytes = Encoding.UTF8.GetBytes(body ?? string.Empty);
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
                    if (method == WebDavMethod.Get)
                    {
                        using (var reader = new System.IO.StreamReader(response.GetResponseStream(), Encoding.UTF8))
                        {
                            text = reader.ReadToEnd();
                        }
                    }
                    // 2xx = 成功（与 Android 一致，不只认 200）
                    bool ok = status >= 200 && status < 300;
                    return ok
                        ? WebDavResult.Ok(status, text)
                        : WebDavResult.Fail(status, "HTTP " + status, false);
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
                return WebDavResult.Fail(0, "网络不可用", false);
            }
            catch (Exception)
            {
                return WebDavResult.Fail(0, "请求失败", false);
            }
        }
    }
}
