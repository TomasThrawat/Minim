package com.minim.music.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicWebConfigParserTest {
    @Test
    fun readsInnertubeConfigurationFromTheMusicHomepage() {
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"api-key","INNERTUBE_CLIENT_VERSION":"1.2.3"});</script>"""
        val config = YouTubeMusicWebConfigParser.parse(html)
        assertEquals("api-key", config?.apiKey)
        assertEquals("1.2.3", config?.clientVersion)
    }

    @Test
    fun acceptsContextClientVersionWhenPrimaryVersionIsAbsent() {
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"api-key","INNERTUBE_CONTEXT_CLIENT_VERSION":"2.3.4"});</script>"""
        val config = YouTubeMusicWebConfigParser.parse(html)
        assertEquals("api-key", config?.apiKey)
        assertEquals("2.3.4", config?.clientVersion)
    }

    @Test
    fun doesNotRequireVisitorDataThatComesFromTheSignedInWebViewSession() {
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"api-key","INNERTUBE_CLIENT_VERSION":"1.2.3"});</script>"""
        assertEquals("api-key", YouTubeMusicWebConfigParser.parse(html)?.apiKey)
    }

    @Test
    fun rejectsHomepageWithoutRequiredClientConfiguration() {
        assertNull(YouTubeMusicWebConfigParser.parse("<html>no ytcfg here</html>"))
        assertNull(YouTubeMusicWebConfigParser.parse("""{"INNERTUBE_API_KEY":"api-key"}"""))
    }
}
