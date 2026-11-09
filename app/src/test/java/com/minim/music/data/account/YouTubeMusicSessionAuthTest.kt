package com.minim.music.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicSessionAuthTest {
    @Test
    fun parsesCookieValuesWithoutTruncatingEqualsSigns() {
        val parsed = YouTubeMusicSessionAuth.parseCookieString(
            "SAPISID=secret==value; VISITOR_INFO1_LIVE=visitor; empty=; malformed; bad name=x",
        )
        assertEquals("secret==value", parsed["SAPISID"])
        assertEquals("visitor", parsed["VISITOR_INFO1_LIVE"])
        assertEquals("", parsed["empty"])
        assertFalse(parsed.containsKey("bad name"))
    }

    @Test
    fun createsTheWebSessionAuthorizationHeader() {
        val header = YouTubeMusicSessionAuth.authorizationHeader(
            cookie = "SAPISID=abc",
            timestampSeconds = 123456L,
        )
        assertEquals(
            "SAPISIDHASH 123456_e43f9a4b24a73b3308640356ae7dff6f03471b42",
            header,
        )
    }

    @Test
    fun acceptsSecureSapisidCookieVariants() {
        assertNotNull(
            YouTubeMusicSessionAuth.authorizationHeader(
                cookie = "__Secure-3PAPISID=secure-cookie",
                timestampSeconds = 123456L,
            ),
        )
    }

    @Test
    fun rejectsCookiesWithoutAnAuthenticationCookie() {
        assertFalse(YouTubeMusicSessionAuth.hasAuthenticationCookie("VISITOR_INFO1_LIVE=value"))
        assertNull(YouTubeMusicSessionAuth.authorizationHeader("VISITOR_INFO1_LIVE=value"))
    }

    @Test
    fun accountKeyIsStableForTheSameChannel() {
        val first = YouTubeMusicSessionAuth.accountKey("channel-id", "SAPISID=first")
        val second = YouTubeMusicSessionAuth.accountKey("channel-id", "SAPISID=second")
        assertEquals(first, second)
    }
}
