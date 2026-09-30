using System;
using System.Net;
using System.Net.Sockets;

namespace BoardCompanion
{
    /// <summary>
    /// WebDAV 地址的传输安全策略 —— **与 Android 的 `sync/webdav/WebDavUrlPolicy.kt` 同一套规则**，
    /// 不在 Windows 重新发明安全规则。
    ///
    /// - https:// 一律放行；
    /// - http:// 必须用户**明确开启**，且地址**解析出的所有地址**都属于本机/回环/link-local/
    ///   RFC1918 私网/IPv6 ULA 才放行；
    /// - 公网 HTTP 一律拒绝；
    /// - 解析失败 → 拒绝（宁可拒绝，也不放行明文）；
    /// - **绝不**把 http 静默升级成 https。
    ///
    /// 判定一律使用 `Dns.GetHostAddresses` + `IPAddress` 的真实解析，
    /// **禁止** `host.Contains("192.168")` 这类字符串判断。
    /// </summary>
    public static class WebDavUrlPolicy
    {
        public const string MESSAGE_SCHEME =
            "WebDAV 地址必须以 http:// 或 https:// 开头";

        public const string MESSAGE_HTTP_NOT_ENABLED =
            "HTTP 未加密，仅建议用于可信的局域网环境。需要先明确开启「允许未加密 HTTP」";

        public const string MESSAGE_HTTP_PUBLIC =
            "公网地址不允许使用未加密的 HTTP，请改用 https://";

        public const string MESSAGE_HTTP_UNRESOLVED =
            "无法确认该地址属于本机或局域网，出于安全考虑不允许使用 HTTP；请改用 https:// 或直接填写局域网 IP";

        public static bool IsHttps(string rawUrl)
        {
            return !string.IsNullOrEmpty(rawUrl) &&
                rawUrl.Trim().StartsWith("https://", StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>返回 null 表示放行；否则返回给人看的原因（不含 URL 之外的隐私信息）。</summary>
        public static string Evaluate(string rawUrl, bool allowCleartextHttp)
        {
            var url = (rawUrl ?? string.Empty).Trim();
            if (url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return null;
            if (!url.StartsWith("http://", StringComparison.OrdinalIgnoreCase)) return MESSAGE_SCHEME;
            if (!allowCleartextHttp) return MESSAGE_HTTP_NOT_ENABLED;

            var host = ExtractHost(url);
            if (string.IsNullOrEmpty(host)) return MESSAGE_SCHEME;

            IPAddress[] addresses;
            try
            {
                addresses = Dns.GetHostAddresses(host);
            }
            catch (Exception)
            {
                return MESSAGE_HTTP_UNRESOLVED;
            }
            if (addresses == null || addresses.Length == 0) return MESSAGE_HTTP_UNRESOLVED;

            foreach (var address in addresses)
            {
                if (!IsPrivateOrLocal(address)) return MESSAGE_HTTP_PUBLIC;
            }
            return null;
        }

        /// <summary>本机 / 回环 / link-local / RFC1918 私网 / IPv6 ULA</summary>
        public static bool IsPrivateOrLocal(IPAddress address)
        {
            if (address == null) return false;
            if (IPAddress.IsLoopback(address)) return true;
            if (address.AddressFamily == AddressFamily.InterNetworkV6)
            {
                if (address.IsIPv6LinkLocal) return true;   // fe80::/10
                var v6 = address.GetAddressBytes();
                if ((v6[0] & 0xFE) == 0xFC) return true;    // fc00::/7 Unique Local Address
                return false;
            }
            var b = address.GetAddressBytes();
            if (b.Length != 4) return false;
            if (b[0] == 10) return true;                                  // 10.0.0.0/8
            if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true;      // 172.16.0.0/12
            if (b[0] == 192 && b[1] == 168) return true;                   // 192.168.0.0/16
            if (b[0] == 169 && b[1] == 254) return true;                   // 169.254.0.0/16 link-local
            if (b[0] == 127) return true;                                  // 127.0.0.0/8
            return false;
        }

        /// <summary>从 URL 取主机名；支持 `[::1]:8080` 这类 IPv6 字面量。</summary>
        public static string ExtractHost(string url)
        {
            var text = (url ?? string.Empty).Trim();
            int schemeIndex = text.IndexOf("://", StringComparison.Ordinal);
            var afterScheme = schemeIndex >= 0 ? text.Substring(schemeIndex + 3) : text;

            int slash = afterScheme.IndexOfAny(new[] { '/', '?', '#' });
            var authority = slash >= 0 ? afterScheme.Substring(0, slash) : afterScheme;

            int at = authority.LastIndexOf('@');
            if (at >= 0) authority = authority.Substring(at + 1);

            if (authority.StartsWith("[", StringComparison.Ordinal))
            {
                int end = authority.IndexOf(']');
                return end > 0 ? authority.Substring(1, end - 1) : string.Empty;
            }
            int colon = authority.IndexOf(':');
            return colon >= 0 ? authority.Substring(0, colon) : authority;
        }
    }
}
