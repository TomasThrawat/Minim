package com.minim.music.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicWebConfigParserTest {
    @Test
    fun readsInnertubeConfigurationFromTheMusicHomepage() {
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"api-key","INNERTUBE_CLIENT_VERSION":"1.2.3","VISITOR_DATA":"visitor","SESSION_INDEX":"0","DATASYNC_ID":"page||extra"});</script>"""
        val config = YouTubeMusicWebConfigParser.parse(html)
        assertEquals("api-key", config?.apiKey)
        assertEquals("1.2.3", config?.clientVersion)
        assertEquals("visitor", config?.visitorData)
        assertEquals("0", config?.authUser)
        assertEquals("page", config?.dataSyncId)
    }

    @Test
    fun rejectsHomepageWithoutRequiredClientConfiguration() {
        assertNull(YouTubeMusicWebConfigParser.parse("<html>no ytcfg here</html>"))
    }
}
