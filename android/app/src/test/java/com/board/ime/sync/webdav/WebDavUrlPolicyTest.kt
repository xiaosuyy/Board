package com.board.ime.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 传输安全策略的判定必须**可复现**：HTTPS 放行；HTTP 需明确开启且仅限可信局域网；
 * 公网 HTTP 一律拒绝；绝不静默把 http 升级成 https。
 */
class WebDavUrlPolicyTest {

    private fun blocked(url: String, allow: Boolean = true): String? =
        (WebDavUrlPolicy.evaluate(url, allow) as? WebDavUrlPolicy.Verdict.Blocked)?.message

    @Test
    fun httpsIsAlwaysAllowed() {
        assertEquals(
            WebDavUrlPolicy.Verdict.Https,
            WebDavUrlPolicy.evaluate("https://dav.example.com/dav", allowCleartextHttp = false),
        )
        assertEquals(
            WebDavUrlPolicy.Verdict.Https,
            WebDavUrlPolicy.evaluate("HTTPS://dav.example.com/dav", allowCleartextHttp = true),
        )
    }

    @Test
    fun badSchemeIsRejected() {
        assertEquals(WebDavUrlPolicy.MESSAGE_SCHEME, blocked("htpps://dav.example.com"))
        assertEquals(WebDavUrlPolicy.MESSAGE_SCHEME, blocked("dav.example.com"))
    }

    @Test
    fun httpIsRejectedUntilUserOptsIn() {
        assertEquals(
            WebDavUrlPolicy.MESSAGE_HTTP_NOT_ENABLED,
            blocked("http://127.0.0.1:8080/dav", allow = false),
        )
        assertEquals(
            WebDavUrlPolicy.MESSAGE_HTTP_NOT_ENABLED,
            blocked("http://192.168.1.10/dav", allow = false),
        )
    }

    @Test
    fun loopbackLinkLocalAndPrivateRangesAreAllowedWhenOptedIn() {
        val allowed = listOf(
            "http://127.0.0.1:8080/dav",
            "http://localhost/dav",
            "http://192.168.1.10/dav",
            "http://10.0.0.5/dav",
            "http://172.16.31.7:5005/dav",
            "http://169.254.10.10/dav",
            "http://[::1]:8080/dav",
            "http://[fd00::1]/dav",
            "http://[fe80::1]/dav",
        )
        allowed.forEach { url ->
            assertEquals(
                "应放行：$url",
                WebDavUrlPolicy.Verdict.LocalHttpAllowed,
                WebDavUrlPolicy.evaluate(url, allowCleartextHttp = true),
            )
        }
    }

    @Test
    fun publicHttpIsRejectedEvenWhenOptedIn() {
        listOf(
            "http://8.8.8.8/dav",
            "http://1.1.1.1/dav",
            "http://[2001:4860:4860::8888]/dav",
        ).forEach { url ->
            assertEquals("应拒绝：$url", WebDavUrlPolicy.MESSAGE_HTTP_PUBLIC, blocked(url, allow = true))
        }
    }

    @Test
    fun httpIsNeverSilentlyUpgradedToHttps() {
        // 放行时仍然是明文 HTTP（Verdict.LocalHttpAllowed），不做静默升级
        assertEquals(
            WebDavUrlPolicy.Verdict.LocalHttpAllowed,
            WebDavUrlPolicy.evaluate("http://192.168.1.10/dav", allowCleartextHttp = true),
        )
        // 未开启时是明确的拒绝，而不是偷偷改成 https 去连
        assertEquals(
            WebDavUrlPolicy.MESSAGE_HTTP_NOT_ENABLED,
            blocked("http://192.168.1.10/dav", allow = false),
        )
    }
}
