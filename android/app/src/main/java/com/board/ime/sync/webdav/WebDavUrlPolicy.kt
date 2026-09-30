package com.board.ime.sync.webdav

import java.net.Inet6Address
import java.net.InetAddress

/**
 * WebDAV 地址的**传输安全策略**。
 *
 * 隐私基线要求：HTTPS 优先；不允许无提示地使用明文 HTTP。
 * 但部分用户自建的 NAS 只提供 HTTP，所以 **不删除** HTTP 能力，而是：
 *
 * 1. 默认只放行 `https://`；
 * 2. `http://` 必须由用户在设置里**明确开启**（默认关）；
 * 3. 即使用户开启了，也只放行**可信局域网/本机**地址；
 * 4. 公网 HTTP 一律拒绝（避免用户把明文 WebDAV 用到公网）；
 * 5. 绝不把 HTTP 静默升级成 HTTPS（会连不上自建 NAS，也会掩盖真实风险）。
 *
 * 地址判定使用**真实的地址解析**（[InetAddress]），
 * 不做“字符串里有 192.168 就算内网”这种不可靠判断。
 *
 * 只在发起请求前拦截，不修改 WebDAV 协议本身。
 */
object WebDavUrlPolicy {

    sealed interface Verdict {
        /** 全程 TLS，放行 */
        data object Https : Verdict

        /** 用户已明确允许，且地址属于可信局域网/本机 */
        data object LocalHttpAllowed : Verdict

        /** 拒绝，并给出给人看的原因 */
        data class Blocked(val message: String) : Verdict
    }

    const val MESSAGE_SCHEME = "WebDAV 地址必须以 http:// 或 https:// 开头"

    const val MESSAGE_HTTP_NOT_ENABLED =
        "HTTP 未加密，仅建议用于可信的局域网环境。需要先在下方明确开启「允许未加密 HTTP」"

    const val MESSAGE_HTTP_PUBLIC =
        "公网地址不允许使用未加密的 HTTP，请改用 https://"

    const val MESSAGE_HTTP_UNRESOLVED =
        "无法确认该地址属于本机或局域网，出于安全考虑不允许使用 HTTP；请改用 https:// 或直接填写局域网 IP"

    /**
     * @param allowCleartextHttp 用户是否已在设置里**明确**允许明文 HTTP
     */
    fun evaluate(rawUrl: String, allowCleartextHttp: Boolean): Verdict {
        val url = rawUrl.trim()
        val lower = url.lowercase()
        if (lower.startsWith("https://")) return Verdict.Https
        if (!lower.startsWith("http://")) return Verdict.Blocked(MESSAGE_SCHEME)

        if (!allowCleartextHttp) return Verdict.Blocked(MESSAGE_HTTP_NOT_ENABLED)

        val host = extractHost(url)
        if (host.isBlank()) return Verdict.Blocked(MESSAGE_SCHEME)

        // 解析失败 = 无法确认是内网 → 按“不可信”处理（宁可拒绝，也不放行公网明文）
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (addresses.isNullOrEmpty()) return Verdict.Blocked(MESSAGE_HTTP_UNRESOLVED)

        // 只要有任意一个解析结果是公网地址，就整体拒绝（防 DNS 轮询混入公网）
        return if (addresses.all { isPrivateOrLocal(it) }) {
            Verdict.LocalHttpAllowed
        } else {
            Verdict.Blocked(MESSAGE_HTTP_PUBLIC)
        }
    }

    /** `host` 是否指向本机 / 可信局域网（回环、link-local、RFC1918 私网、IPv6 ULA） */
    fun isPrivateOrLocalHost(host: String): Boolean {
        val name = host.trim().trimStart('[').trimEnd(']')
        if (name.isEmpty()) return false
        if (name.equals("localhost", ignoreCase = true)) return true
        val addresses = runCatching { InetAddress.getAllByName(name) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.all { isPrivateOrLocal(it) }
    }

    private fun isPrivateOrLocal(address: InetAddress): Boolean {
        if (address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isAnyLocalAddress
        ) {
            return true
        }
        val bytes = address.address
        if (address is Inet6Address && bytes.size == 16) {
            // fc00::/7 Unique Local Address（Java 的 isSiteLocalAddress 不覆盖这一段）
            if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
        }
        return false
    }

    /** 从 URL 里取主机名；支持 `[::1]:8080` 这类 IPv6 字面量 */
    fun extractHost(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val authority = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        if (authority.startsWith("[")) {
            return authority.substringAfter('[').substringBefore(']')
        }
        return authority.substringBefore(':')
    }
}
