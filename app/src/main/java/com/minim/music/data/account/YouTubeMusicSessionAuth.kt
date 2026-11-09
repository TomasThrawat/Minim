package com.minim.music.data.account

import java.security.MessageDigest

/** Cookie parsing and the WEB_REMIX SAPISIDHASH signature used by YouTube Music sessions. */
internal object YouTubeMusicSessionAuth {
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private val AUTH_COOKIE_NAMES = listOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

    fun parseCookieString(cookie: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        cookie.split(';').forEach { rawPair ->
            val split = rawPair.indexOf('=')
            if (split <= 0) return@forEach
            val name = rawPair.substring(0, split).trim()
            val value = rawPair.substring(split + 1).trim()
            if (name.isNotBlank() &&
                name.all { it.isLetterOrDigit() || it == '_' || it == '-' } &&
                value.none { it == '\r' || it == '\n' }
            ) {
                values[name] = value
            }
        }
        return values
    }

    fun normalizeCookieString(cookie: String): String =
        parseCookieString(cookie).entries.joinToString("; ") { (name, value) ->
            name + "=" + value
        }

    fun hasAuthenticationCookie(cookie: String): Boolean {
        val values = parseCookieString(cookie)
        return AUTH_COOKIE_NAMES.any { !values[it].isNullOrBlank() }
    }

    fun authorizationHeader(
        cookie: String,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L,
        origin: String = MUSIC_ORIGIN,
    ): String? {
        val cookies = parseCookieString(cookie)
        val sapisid = AUTH_COOKIE_NAMES.firstNotNullOfOrNull { cookies[it]?.takeIf(String::isNotBlank) }
            ?: return null
        val digestInput = timestampSeconds.toString() + " " + sapisid + " " + origin
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(digestInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "SAPISIDHASH " + timestampSeconds + "_" + digest
    }

    fun accountKey(dataSyncId: String, cookie: String): String {
        val cookies = parseCookieString(cookie)
        val stableIdentity = dataSyncId.takeIf { it.isNotBlank() }
            ?: AUTH_COOKIE_NAMES.firstNotNullOfOrNull { cookies[it]?.takeIf(String::isNotBlank) }
            ?: throw IllegalArgumentException("A stable YouTube Music session identity is missing")
        return MessageDigest.getInstance("SHA-256")
            .digest(stableIdentity.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
